# kuilt architecture

This is the design rationale for kuilt's transport contract. It is the standalone
counterpart of fireworks-compose's
[ADR-034 — Cohered Transport Contract](https://github.com/tractat-us/fireworks-compose/blob/main/docs/adr-034-cohered-transport-contract.md)
and the
[kuilt fabric-foundation roadmap](https://github.com/tractat-us/fireworks-compose/blob/main/docs/superpowers/specs/2026-05-25-kuilt-fabric-foundation-roadmap-design.md);
those documents hold the full history. This file is the version that travels
with the library.

## The one idea

kuilt collapses every transport to **one peer-symmetric contract**. A `Seam` is
one peer's view of a multi-peer session, and every peer in a session holds an
*identical* `Seam` — there is no `ClientTransport` / `ServerTransport` split, no
asymmetric handshake baked into the interface. A fabric implements two types
(`Loom`, `Seam`) and a frame (`Swatch`) and it is done; there is never a
"which interface do I implement?" question.

A 2-peer WebSocket connection — historically modelled as client+server — is just
the **degenerate `peers.size == 2` case** of the symmetric model. This is why
the WebSocket fabric and a future N-peer Multipeer mesh can share one contract.

## Near and Far

The contract must not bake in either topology:

- **Far** — client → server relay (`kuilt-websocket`, whether the server is
  on-LAN or remote).
- **Near** — peer ↔ peer (Multipeer, WebRTC data channel, Android Nearby).

**Discovery is orthogonal** to the Near/Far cut. mDNS, Multipeer browsing, and
WebRTC signaling are *rendezvous* — they tell you who is out there and how to
reach them; they feed any fabric. An mDNS-discovered game still connects over
WebSocket (Far), so `kuilt-mdns` depends on `kuilt-websocket`, not the reverse.

## The contract

```kotlin
sealed interface Rendezvous {
    data class New(val pattern: Pattern) : Rendezvous        // host a new session
    data class Existing(val tag: Tag) : Rendezvous           // join an existing one
}

interface Loom {
    suspend fun weave(rendezvous: Rendezvous): Seam           // the ONE abstract method
    suspend fun host(pattern: Pattern): Seam = weave(Rendezvous.New(pattern))
    suspend fun join(tag: Tag): Seam = weave(Rendezvous.Existing(tag))
    fun availability(): FabricAvailability = FabricAvailability.Available
}

interface Seam {
    val selfId: PeerId
    val peers: StateFlow<Set<PeerId>>            // includes selfId
    val incoming: Flow<Swatch>                   // single-collection — see below
    suspend fun broadcast(payload: ByteArray)
    suspend fun sendTo(peer: PeerId, payload: ByteArray)
    suspend fun close(reason: CloseReason = CloseReason.Normal)
}

data class Swatch(val payload: ByteArray, val sender: PeerId? = null, val sequence: Long = 0)
```

### Rules the contract enforces

These are the load-bearing decisions. Violating them breaks consumers in ways
the type system won't catch:

- **`incoming` is single-collection.** One `Flow<Swatch>` carries *all* peers'
  frames, in send order, delivered to **one** collector. Collect it once per
  `Seam`. If multiple consumers need it, wrap it with `shareIn`/`MutableSharedFlow`
  yourself — the `Seam` does not fan out. A second concurrent collector races and
  is unsupported. (fireworks-compose
  [#1496](https://github.com/tractat-us/fireworks-compose/issues/1496).)
- **`Swatch` is binary-only.** No text-frame variant. The wire layer never
  interprets the bytes — that is the consumer's job (in fireworks, `:session-protocol`).
  The one historical text consumer (the hanab.live spectate proxy) drops to raw
  Ktor and is simply not a kuilt consumer.
- **`sender` / `sequence` are stamped on receipt.** Senders leave them unset
  (null sender, zero sequence); the receiving `Seam` fills them in on dispatch.
- **`availability()` means present-but-not-usable-now.** A fabric scoped out by
  *target* (e.g. a Multipeer fabric on wasmJs, a WebRTC fabric on the JVM) is
  simply **absent** — the artifact isn't on that platform's classpath — not
  `Unavailable`. `Unavailable(reason)` is for a runtime capability that's missing
  *now* (e.g. Play Services absent on an AOSP build). A host composes fabrics
  with `looms.filter { it.availability() is Available }`. (fireworks-compose
  [#1299](https://github.com/tractat-us/fireworks-compose/issues/1299).)

## Conformance: one suite, every fabric

`kuilt-core` ships `SeamConformanceSuite`, an abstract test class. Every fabric
proves it satisfies the contract by subclassing it and implementing `newLoomPair()`.
In-process radio fabrics return the same Loom instance twice (shared mesh); role-split
fabrics (websocket, mdns, webrtc, multipeer) return distinct host/joiner Looms wired to
reach each other.
The suite encodes the invariants as tests: `open` yields a usable `Seam` with a
non-empty `selfId`; `broadcast`/`sendTo` deliver to joined peers and stamp the
sender; `peers` reflects membership; `incoming` preserves single-collection
ordering; `close` is idempotent; `availability()` returns sensibly.

`InMemoryLoom` is the reference implementation — a no-network, channel-backed
mesh that is both the worked example of the contract and the test bedrock for
everything layered above kuilt. `InMemoryLoomConformanceTest` runs the suite
against it. Real-radio / real-network behavior is verified by separate tests that
stay `-P`-gated (e.g. `-Pmdns.multicast.tests`) so the standard build never
depends on physical network conditions.

## What kuilt is *not* responsible for

The contract deliberately stops at moving bytes between connected peers.
Out of scope here (these live in the consumer, or in a future `:kuilt-session`
layer):

- **Role assignment** (host vs. joiner tiebreak) — not a `Loom`/`Seam` concern.
- **Room multiplexing and the full join → active → leave → rejoin lifecycle**,
  reconnect, resume tokens, host-loss terminal state.
- **Application message semantics** — what the `Swatch.payload` bytes mean.

## Module boundary

```
kuilt-core         the contract + InMemoryLoom + SeamConformanceSuite (depends on nothing fabric-specific)
  ├── kuilt-websocket   Ktor WebSocket fabric (Far)            → depends on kuilt-core
  └── kuilt-mdns        Bonjour discovery → WebSocket session  → depends on kuilt-core + kuilt-websocket
```

The dependency arrow only ever points *down* toward `kuilt-core`. Keeping
`kuilt-core` free of fabric imports is what lets the whole boundary be
build-enforced when consumed as a composite build or a published artifact.

## Relationship to fireworks-compose

kuilt was carved out of fireworks-compose's `:transport-core` and shipping
transports. Downstream, fireworks consumes published `us.tractat.kuilt:*`
coordinates, with a presence-gated `includeBuild("../kuilt")` override for
zero-latency local iteration. The public API and Maven coordinates are therefore
a compatibility surface — keep them stable. Full extraction history:
[epic #1515](https://github.com/tractat-us/fireworks-compose/issues/1515).
