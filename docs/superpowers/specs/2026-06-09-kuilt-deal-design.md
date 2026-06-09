# `:kuilt-deal` — CRDT-based card deal with configurable visibility quorums

**Status:** design approved, not yet built.
**Date:** 2026-06-09.

## What this is

A new module, `:kuilt-deal`, that implements a **cryptographically fair, peer-symmetric
card deal** as an op-based CRDT. It lets N peers shuffle a deck, deal hands, and reveal
cards to arbitrary visibility quorums — with no trusted dealer — over any kuilt `Seam`.

The core insight: **commutative encryption is the CRDT merge operator**. Mental-poker
protocols (SRA, EC-ElGamal) require that encryption operations commute —
`E_A(E_B(m)) = E_B(E_A(m))` — which is exactly the commutativity property an op-based
CRDT needs. The SRA protocol, viewed through this lens, is already a distributed
commutative op; calling it a CRDT just makes the convergence guarantee explicit and
topology-independent.

The quorum model generalises beyond mental poker. By making the *visibility set* a
first-class parameter per hand, the same protocol covers:

| Game | Visibility quorum for player P's hand |
|------|--------------------------------------|
| Poker | `{P}` — only P can see their own cards |
| Hanabi | `{everyone except P}` — P cannot see their own cards |
| Generic | any subset — configurable per-deal |

## Why this fits kuilt

kuilt moves opaque frames over unreliable, partition-prone fabrics. An op-based CRDT
whose operations commute is robust to reordering and exactly-once delivery by
construction — the same properties that make CRDTs a good fit for kuilt's world
generally. `DealSession` rides directly on a `Seam`; the transport is interchangeable.

## Core abstractions

### `CommutativeScheme`

The pluggable encryption primitive. Every operation returns a proof that binds the new
ciphertext to the previous one, enabling recipients to verify the transformation was
applied exactly once and correctly.

```kotlin
interface CommutativeScheme {
    fun encrypt(plaintext: ByteArray, key: SchemeKey): Pair<ByteArray, EncryptProof>
    fun strip(ciphertext: ByteArray, key: SchemeKey): Pair<ByteArray, StripProof>
    fun verifyEncrypt(prev: ByteArray, next: ByteArray, proof: EncryptProof, pubKey: PublicKey): Boolean
    fun verifyStrip(prev: ByteArray, next: ByteArray, proof: StripProof, pubKey: PublicKey): Boolean
    fun generateKey(): SchemeKeyPair
}
```

Two implementations ship with `:kuilt-deal`:

- **`SraScheme`** — SRA (Shamir–Rivest–Adleman, 1979). Modular exponentiation over a
  safe prime: `m.modPow(e, p)`. Commutativity is exact. Implemented via
  `ionspin/kotlin-multiplatform-bignum` — pure KMP, runs on all platforms. 2048-bit
  default, 4096-bit optional.
- **`ElGamalScheme`** — EC-ElGamal re-encryption over P-256. ~8× faster than SRA at
  equivalent security. Implemented via BouncyCastle (`bcprov-jdk18on`). Compiled into
  `jvmAndAndroidMain` only; `ElGamalScheme` does not exist as a class on iOS/macOS/wasmJs.
  Cross-platform code must use `SraScheme` directly.

Both plug into the same `CommutativeScheme` interface. The default is `SraScheme`.

### `CardState` — the per-card CRDT

```kotlin
data class CardState(
    val ciphertext: ByteArray,
    val encryptedBy: GSet<PlayerId>,      // grows-only: who has applied their key
    val strippedBy: GSet<PlayerId>,       // grows-only: who has removed their key
    val visibilityQuorum: Set<PlayerId>,  // set once at deal time, immutable after
    val proofChain: List<OpProof>,        // hash-chained proofs for each operation
)
```

`encryptedBy` and `strippedBy` are `GSet` instances from `:kuilt-crdt`. Merge is
set-union on both G-Sets; the ciphertext converges to the same value regardless of
operation order, guaranteed by the commutativity of the encryption scheme
(`E_A(E_B(m)) = E_B(E_A(m))`). The proof chain is for verification only — there is
no ciphertext conflict to resolve. Phase is **derived** — computed from G-Set
membership, never stored:

| Phase | Condition |
|-------|-----------|
| `UNENCRYPTED` | `encryptedBy.isEmpty()` |
| `SHUFFLING` | `encryptedBy` non-empty, `encryptedBy ≠ allPlayers` |
| `FULLY_ENCRYPTED` | `encryptedBy == allPlayers` |
| `REVEALING` | `strippedBy` non-empty, `strippedBy ≠ allPlayers ∖ visibilityQuorum` |
| `REVEALED` | `strippedBy == allPlayers ∖ visibilityQuorum` |

### `CardOp`

```kotlin
sealed class CardOp {
    data class Encrypt(val player: PlayerId, val newCiphertext: ByteArray, val proof: EncryptProof)
    data class Strip(val player: PlayerId, val newCiphertext: ByteArray, val proof: StripProof)
    data class DepositKey(val player: PlayerId, val escrowedKey: EncryptedKey)  // optional
}
```

Validity predicates (checked before applying any op):

- `Encrypt` valid iff: `player ∉ encryptedBy` (no double-encoding).
- `Strip` valid iff: `player ∈ encryptedBy` AND `player ∉ strippedBy` AND
  `player ∉ visibilityQuorum` (only non-quorum players strip). Proof must verify
  against the current ciphertext.
- `DepositKey` valid at any time after `FULLY_ENCRYPTED`. May arrive before or after
  quorum assignment is committed. Encouraged (but not required) after commitment.

## Security properties

### Anti-double-encode

The `Encrypt` validity predicate (`player ∉ encryptedBy`) rejects duplicate encrypt
ops at the application layer. Proof verification ties the new ciphertext to the
previous one — a player cannot substitute a ciphertext encrypted with a different key
or applied to a different prior state.

### Withholding (liveness attack)

A player who never sends their `Strip` op blocks a card from reaching `REVEALED`. This
is a **liveness** failure, not a safety failure — the card value remains secret, no
player learns something they shouldn't. Defences:

1. **Game-layer timeout** — if a player has not stripped within a deadline, penalise
   them at the game layer (disconnect, penalise score).
2. **Key deposit** — players may pre-deposit their strip keys with a trusted server
   via `DepositKey`. If a player disconnects, the server releases their key and
   completes the strip on their behalf. The server holds only encrypted key material
   and cannot read card values. Since all players must be online to play anyway, the
   deposit is a recovery mechanism, not a coordination requirement.

Key deposit is **optional and timing-flexible**: deposit may happen at any point after
`FULLY_ENCRYPTED`. Depositing after quorum assignment is committed is preferred (the
server cannot leverage early key knowledge before assignments are known), but is not
enforced by the protocol — enforcement is a deployment policy.

## `DealSession` — entry point

```kotlin
class DealSession(
    seam: Seam,
    scheme: CommutativeScheme = SraScheme(),
    myKey: SchemeKeyPair,
    allPlayers: Set<PlayerId>,
    myId: PlayerId,
) {
    // Shuffle phase: encrypt every card with my key; broadcasts Encrypt ops
    suspend fun shuffle(deck: List<ByteArray>)

    // Deal phase: assign visibility quorums (local — no broadcast)
    fun assignQuorums(assignments: Map<Int, Set<PlayerId>>)

    // Reveal phase: strip all cards where I am not in the quorum; broadcasts Strip ops
    suspend fun strip()

    // After REVEALED: local decryption only, no network
    fun decrypt(cardIndex: Int): ByteArray

    // Observe the converging deck state
    val state: StateFlow<DeckState>

    // Optional: escrow my key to a trusted server
    suspend fun depositKey(escrow: KeyEscrow)
}
```

`assignQuorums` is local because the quorum assignment does not need to be agreed
via the network — each peer derives their `Strip` obligations from the same
assignment independently. The assignment may be computed by any deterministic rule
(e.g. deal position order) that all peers evaluate identically.

### Transport

`DealSession` uses the `Seam` directly — each `CardOp` is serialized to a `Swatch`
and broadcast to all peers. `SeamReplicator` (delta-state) is not used; the op-based
nature of the protocol means delivery order is irrelevant by construction. Exactly-once
delivery is enforced by the G-Set membership check before applying each incoming op.

## Module structure

```
:kuilt-deal
  commonMain  → :kuilt-core (Seam, Swatch)
  commonMain  → :kuilt-crdt (GSet)
  commonMain  dep: io.github.ionspin:kotlin-multiplatform-bignum  (SRA)
  jvmAndAndroidMain dep: org.bouncycastle:bcprov-jdk18on           (EC-ElGamal)

:kuilt-deal-test
  commonMain  → :kuilt-deal
  commonMain  → :kuilt-test
```

EC-ElGamal lives in `jvmAndAndroidMain` — the same split pattern as `:kuilt-websocket`'s
Ktor server. `ElGamalScheme` does not exist on iOS/macOS/wasmJs source sets; it is a
compile-time absence, not a runtime fallback.

A `DealConformanceSuite` in `:kuilt-conformance` is out of scope for the initial
implementation but is the natural follow-up once the protocol is stable.

## Benchmark plan

Three tiers. Acceptance thresholds are asserted in CI (Tiers 1–2); Tier 3 is manual.

### Targets

| Metric | Target |
|--------|--------|
| Shuffle (5 players, 52 cards, SRA-2048) | < 2 s |
| Per-card reveal (after strips arrive) | < 100 ms |
| Proof verification per incoming op | < 10 ms |

### Tier 1 — Op microbenchmark (no network)

Raw `encrypt()` and `strip()` cost per scheme, isolated. Simple `measureNanoTime`
loop, 1 000 iterations, median reported. Lives in `jvmTest`.

| Scheme | Key size | Expected (JVM) | Platform |
|--------|----------|----------------|----------|
| SRA | 2048-bit | ~1–3 ms / op | all |
| SRA | 4096-bit | ~8–15 ms / op | all |
| EC-ElGamal | P-256 | ~0.1–0.3 ms / op | JVM/Android |

### Tier 2 — Full protocol cycle (InMemoryLoom, zero network)

Shuffle → assignQuorums → strip → decrypt end-to-end using `InMemoryLoom`. Isolates
pure crypto + CRDT cost. Asserted against the shuffle target; failure blocks CI.

| Config | Total ops | Expected (SRA-2048) |
|--------|-----------|---------------------|
| 2 players, 10 cards | 40 modPow | ~60 ms |
| 5 players, 50 cards (Hanabi) | 500 modPow | ~750 ms |
| 10 players, 52 cards (poker) | 1 040 modPow | ~1.5 s |

### Tier 3 — Network round-trip (KtorClientLoom over loopback)

Same protocol over a real WebSocket on loopback. Measures serialization and delivery
overhead on top of Tier 2. `@Ignored` in CI; run manually.

## Paper notes

What a paper based on this work would claim, for reference if a write-up is ever pursued.

**Primary contribution:** The observation that commutative encryption is structurally
equivalent to op-CRDT commutativity. The SRA mental poker protocol, viewed through this
lens, is already a distributed commutative op; formalising it as an op-based CRDT yields
convergence guarantees, topology independence, and a clean compositional model for
free. This framing does not appear to have been stated explicitly in either the
mental-poker or CRDT literature.

**Secondary contribution:** The quorum-parametric visibility model — a single protocol
that covers poker (sole-recipient hand), Hanabi (everyone-except-holder hand), and
arbitrary subsets — expressed as a pure predicate on G-Set membership with no change to
the shuffle machinery.

**Tertiary contribution:** The authenticated op chain (each op binds to a previous
ciphertext hash) as an integrated defence against double-encode attacks, unified with
the CRDT validity predicate.

**Related work to survey:** Shamir–Rivest–Adleman (1979), Barnett–Smart verifiable
shuffles (2003), Groth–Lu shuffle arguments (2007), Kleppmann et al. authenticated
CRDTs (2022), Byzantine-fault-tolerant CRDT literature generally.

**Candidate venues:** PODC or DISC (distributed systems, focus on convergence proof);
CCS or Usenix Security (crypto focus, needs formal security reduction); arXiv
(self-publication, no peer-review overhead). A short paper (8–12 pages) targeting a
workshop (e.g. PaPoC — Principles and Practice of Consistency for Distributed Data)
would be the lowest-friction entry point.
