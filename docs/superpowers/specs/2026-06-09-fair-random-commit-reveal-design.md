# FairRandom — commit-reveal shared RNG seed

**Issue:** #311 · **Module:** `:kuilt-deal` · **Status:** design approved, ready for planning

## Goal

A two-phase commit-reveal protocol that lets N peers derive a shared random
seed without a trusted dealer. Complements `:kuilt-deal`'s SRA card-secrecy
scheme (`DealSession`): SRA conceals card identity; `FairRandom` produces fair
dice rolls, shuffle seeds, and coin-flips. Both are "fair dealing without a
trusted dealer" and share the module's `secureRandomBytes` CSPRNG.

## Placement & dependencies

- Package `us.tractat.kuilt.deal`, alongside `DealSession`.
- New dependency: `org.kotlincrypto.hash:sha2` (synchronous SHA-256 on every
  kuilt target including wasmJs — KotlinCrypto added wasmJs support in 0.5.x).
  Added to `gradle/libs.versions.toml`, wired into `kuilt-deal`'s `commonMain`.
  No new dependency on `:kuilt-crdt` or ionspin bignum.
- Reuses the existing `internal expect fun secureRandomBytes(n: Int)` (landed
  in #308) for secret generation.

## Public API

```kotlin
public class FairRandom(
    private val seam: Seam,
    private val peers: Set<PeerId>,   // must contain seam.selfId; pins the round's participants
    scope: CoroutineScope,
)

/** One commit-reveal round. Suspends through both phases. */
public suspend fun FairRandom.roll(): Seed

/** 32-byte agreed seed. */
public class Seed internal constructor(public val bytes: ByteArray) {
    public fun toLong(): Long          // first 8 bytes, big-endian
    public fun asRandom(): kotlin.random.Random
}

/** Thrown when one or more peers' reveals do not match their commitments. */
public class FairRandomAborted(public val culprits: Set<PeerId>) : Exception
```

Mirrors `DealSession`'s shape: collects `seam.incoming` in `init` via
`launchIn(scope)`, broadcasts CBOR frames.

**Single-collection caveat.** `FairRandom` collects the seam directly, so there
must be one collector per `Seam` (the `Seam.incoming` contract). Multiplexing a
`FairRandom` and a `DealSession` over one fabric is a future `RoutingSeam`
concern and is out of scope here. Documented in KDoc.

**Lock-step contract.** Rolls are a natural barrier — a peer cannot finish
round `r` until every peer has committed for round `r` — so the per-instance
round counter stays synchronised provided every participant calls `roll()` the
same number of times. One roll at a time per instance (serialized by a `Mutex`);
frames for a round that hasn't started locally are buffered.

## Protocol (round `r`)

Let `DOMAIN = "kuilt/fair-random/v1"`.

1. **Secret.** `secretᵢ = secureRandomBytes(32)`. No separate nonce — a 256-bit
   CSPRNG secret is already hiding (preimage resistance) and binding, so it acts
   as its own blinding factor. (Drops the issue sketch's explicit nonce as
   redundant.)
2. **Commit.** `commitᵢ = SHA-256(DOMAIN ‖ r ‖ peerIdᵢ ‖ secretᵢ)`. The domain
   tag, round, and peer id bind the commitment to context and prevent
   cross-round / cross-peer replay. Broadcast `Commit(r, commitᵢ)`. Wait until a
   commit from **every** peer in `peers` is buffered.
3. **Reveal.** Broadcast `Reveal(r, secretᵢ)`. Wait for every peer's reveal.
4. **Verify & derive.** For each peer, recompute `SHA-256(DOMAIN ‖ r ‖ peerId ‖
   secret)` and compare to its stored commit; mismatches collect into
   `culprits`. If `culprits` is non-empty, throw `FairRandomAborted(culprits)`.
   Otherwise:
   `seed = SHA-256(DOMAIN ‖ "/derive" ‖ r ‖ secret₁ ‖ … ‖ secretₙ)` where
   secrets are concatenated in ascending `PeerId` order. The canonical sort
   guarantees every peer derives an identical seed.

### Abort resistance (scope)

A last-mover can withhold its reveal after seeing peers' reveals to bias the
outcome. Per the issue, the mitigation is **abort = forfeit**, enforced by the
application layer:

- **Cheat** (reveal ≠ commitment): `roll()` throws `FairRandomAborted(culprits)`
  naming exactly who cheated. The app enforces forfeit.
- **Silence** (peer never reveals): `roll()` suspends; the caller wraps in
  `withTimeout` and handles `TimeoutCancellationException`. Structured
  concurrency owns liveness; the protocol owns cheat detection.

Full abort-resistance (threshold signatures / VRF) is explicitly out of scope.

```kotlin
try {
    val seed = withTimeout(5.seconds) { fr.roll() }
    val die = seed.asRandom().nextInt(1..6)
} catch (e: FairRandomAborted) {
    forfeit(e.culprits)                  // a peer's reveal didn't match
} catch (e: TimeoutCancellationException) {
    // a peer never revealed
}
```

## Wire frames

```kotlin
@Serializable
internal sealed interface FairRandomFrame {
    @Serializable data class Commit(val round: Int, val peer: PeerId, val commit: ByteArray) : FairRandomFrame
    @Serializable data class Reveal(val round: Int, val peer: PeerId, val secret: ByteArray) : FairRandomFrame
}
```

CBOR-encoded over `seam.broadcast`, decoded in the `incoming` collector.
Malformed / foreign frames are dropped without cancelling the collector (the
same defensive pattern as `DealSession`).

## Internal structure

- `incoming` collector routes each decoded frame into the per-round buffer for
  its `round`. A round in progress completes its commit-wait / reveal-wait when
  all expected peers are present (e.g. each phase backed by a
  `CompletableDeferred` resolved once the buffer is full).
- A `Mutex` serializes `roll()` calls; the instance holds a monotonic round
  counter.
- `selfId` participates fully (commits and reveals like any peer). Constructor
  validates `seam.selfId ∈ peers`. The 1-peer degenerate case derives a seed
  from the single secret.

## Testing

- **N-peer fake mesh helper** added to `:kuilt-test`:
  `public fun fakeSeamMesh(vararg ids: PeerId): List<FakeSeam>`, cross-wiring
  broadcast delivery to all peers (only `fakeSeamPair` exists today; FairRandom
  is inherently N-peer, and the helper benefits future N-peer protocols).
- Happy path: 3 peers each `roll()` → identical `Seed`; determinism holds across
  consecutive rounds; 1-peer degenerate case.
- Cheater: a peer reveals a non-matching secret → `FairRandomAborted` names
  exactly that peer.
- Liveness: a peer never reveals → `roll()` suspends; `withTimeout` cancels
  cleanly.
- One known-vector SHA-256 assertion to lock the commitment-format bytes
  (KotlinCrypto covers SHA-256 correctness itself).
- Coroutine determinism per repo convention: tests inject
  `UnconfinedTestDispatcher(testScheduler)`; `FairRandom` takes the scope from
  the caller.

## Decisions made during design (vs. the issue sketch)

- **Hash source:** KotlinCrypto `sha2` dependency (synchronous on all targets,
  no hand-rolled crypto, no async wasm bridge) over a pure-Kotlin in-tree
  implementation or expect/actual platform digests.
- **API:** reusable instance with round-tagging + a rich `Seed` result
  (`toLong()` / `bytes` / `asRandom()`) over the single-shot `Long` sketch.
- **Failure:** typed `FairRandomAborted(culprits)` for cheats + caller-owned
  `withTimeout` for liveness, over a built-in timeout parameter.
- **Nonce dropped:** a 256-bit CSPRNG secret is its own blinding factor.
- **Derivation:** SHA-256 over `PeerId`-sorted secrets (domain-separated, fixed
  output) over the issue's XOR alternative.
- **Test mesh:** reusable `fakeSeamMesh` in `:kuilt-test`.
