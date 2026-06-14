# kuilt architecture

This is the design rationale for kuilt's transport contract. The contract itself
is normative; everything else here is the reasoning that produced it.

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

### Why there is no raw Bluetooth fabric (yet)

A recurring question: should kuilt expose Bluetooth/BLE directly? Today the
answer is **no**, for reasons that are worth recording so the question doesn't
get re-litigated from scratch.

- **Bluetooth is already present, at the right altitude.** The Near meta-fabrics
  ride it transparently: Multipeer multiplexes Bluetooth + infra-WiFi +
  peer-to-peer WiFi (and hands off between them), and Android Nearby does the
  same over BLE + Bluetooth Classic + WiFi. Both present a *connection-oriented,
  multi-peer* surface that maps cleanly onto `Seam`. A raw fabric would mostly
  duplicate — worse — what they already do.
- **Raw BLE fights the contract in three places.** (1) GATT is central/peripheral
  — *asymmetric* — whereas `Seam` is peer-symmetric, so you'd need a symmetry shim
  over an inherently asymmetric link. (2) `Swatch` is an arbitrary opaque blob, but
  BLE gives ~185–512-byte writes at sub-kbps effective throughput, forcing a real
  fragmentation/reassembly protocol (GATT-notify chunking or L2CAP CoC) *inside* the
  fabric. (3) There is no common platform surface — CoreBluetooth, Android
  `BluetoothGatt`, Chrome-only Web Bluetooth (gesture-gated), and nothing standard
  on desktop JVM — so the `expect`/`actual` spread is the widest of any fabric, for
  the worst transport.
- **The one genuine gap it would close** is *cross-ecosystem* proximity: Multipeer
  is Apple-only and Nearby is Android-only, and they don't interoperate. A raw
  BLE/L2CAP fabric is the only way an iPhone and an Android phone connect with no
  AP, router, or internet — but it's a narrow niche, viable only for small,
  low-rate payloads given BLE's throughput.

This costs nothing to defer: fabrics are pluggable, so a `:kuilt-ble` module can
be added later as a normal fabric that passes `SeamConformanceSuite` via
`newLoomPair()` (plus the symmetry shim and fragmentation layer), and documented
as the lowest-throughput, highest-cost Near fabric. Nothing Bluetooth-shaped
belongs in `:kuilt-core` — the contract stays transport-agnostic, so this is only
ever a "is the niche worth a module" decision, never a core one.

## Multipath: one peer, several transports

Near and Far are not exclusive. A peer can ride **both at once** — e.g. a phone
reaching the others over a relay WebSocket *and* over a direct LAN/TCP link — and
have the two paths act as one. `CompositeLoom` (`kuilt-core`,
`us.tractat.kuilt.core.composite`) is a `Loom` that composes other `Loom`s: you
give it a list of `(PlyId, Loom)` *plies*, it weaves each, and `weave()` returns a
single `CompositeSeam` over the union.

The composite presents the ordinary `Seam` contract, so everything above it is
unchanged. What it does underneath:

- **One stable `selfId`.** Minted once and kept across plies attaching and
  detaching, so a path change is *not* an identity change.
- **Remote-peer collapse.** On each ply reaching `Woven` the peer broadcasts a
  `PlyFrame.Announce(compositeId)`; the far side maps every `(plyId, transportId)`
  back to one composite id. A remote peer that is itself multi-homed therefore
  appears **once** in `peers`, not once per path.
- **Send over all live plies; dedup + reorder on receive.** `broadcast` fans out
  over every non-torn ply, and an inbound gate keyed on `(originId, originSeq)`
  drops the duplicate copy that arrives over the second path and restores
  per-origin order.
- **Failover with no membership event.** Tear one ply and the aggregate stays
  `Woven` while a survivor carries the peer; only the *last* ply tearing drives the
  aggregate `Torn`, and a recovered ply re-announces to restore routing.

Why this matters for the layers above: because the bonding lives **below** the
`Seam`, the consensus and CRDT layers never see it. Hand the composite `Seam` to
`SeamRaftTransport` or `SeamReplicator` and Raft sees one `NodeId`, the replicator
tracks one peer, and a WebSocket→TCP failover reaches them as nothing at all — no
election, no membership churn, no full-state resync. That is the whole point of
keeping multipath at the transport layer rather than teaching each consumer about
it.

The ply set may also change while the session is live — construct `CompositeLoom`
from a `StateFlow` of the desired set to attach or detach overlays (a LAN radio
lighting up as peers come into proximity) on the fly. Capabilities deliberately
left for when a consumer needs them — application-layer gateway forwarding,
primary-ply-per-peer send — are tracked in
[`docs/ply-roadmap.md`](ply-roadmap.md).

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

- **`peers` initial state is `{ selfId }`** — the moment a `Seam` returns from
  `weave()`, `peers.value` contains exactly this peer's own identifier. Remote
  peers are added when they connect and removed when they disconnect. This makes
  `peers.value.size > 1` a reliable sentinel for "at least one remote peer is
  connected" — a pattern used by diagnostic and handshake tooling.
- **`incoming` is single-collection.** One `Flow<Swatch>` carries *all* peers'
  frames, in send order, delivered to **one** collector. Collect it once per
  `Seam`. If multiple consumers need it, wrap it with `shareIn`/`MutableSharedFlow`
  yourself — the `Seam` does not fan out. A second concurrent collector races and
  is unsupported.
- **`Swatch` is binary-only.** No text-frame variant. The wire layer never
  interprets the bytes — that is the consumer's job.
- **`sender` / `sequence` are stamped on receipt.** Senders leave them unset
  (null sender, zero sequence); the receiving `Seam` fills them in on dispatch.
- **`availability()` means present-but-not-usable-now.** A fabric scoped out by
  *target* (e.g. a Multipeer fabric on wasmJs, a WebRTC fabric on the JVM) is
  simply **absent** — the artifact isn't on that platform's classpath — not
  `Unavailable`. `Unavailable(reason)` is for a runtime capability that's missing
  *now* (e.g. Play Services absent on an AOSP build). A host composes fabrics
  with `looms.filter { it.availability() is Available }`.

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

## Consensus and leader election

`:kuilt-raft` implements a Raft consensus layer over the `Seam` transport. To
avoid spurious term inflation when a node is merely partitioned from the leader
rather than the leader being dead, elections use a two-phase approach: on an
election timeout a node runs a non-mutating *pre-vote* probe, sending a
`PreVote` request to all peers without bumping its term. A peer grants a
pre-vote only when it has not heard from a live leader recently and the
candidate's log is at least as up-to-date; term and vote state are left
unchanged. The node advances to a real election — incrementing its term and
sending `RequestVote` — only after collecting pre-vote grants from a quorum.
Complementing this, a follower that is within its leader-lease window rejects an
incoming `RequestVote` for a higher term without adopting that term, preventing a
re-joining partitioned node from deposing a healthy leader the moment it regains
connectivity.

Beyond election and replication, `:kuilt-raft` exposes two further Raft
refinements. **Linearizable reads** (`readIndex()`, §3.6/§3.7) let a leader serve
a read that reflects all committed writes *without* appending a log entry: it
records the current commit index, confirms it still holds a quorum via a
heartbeat round, and the caller waits for its state machine to apply through that
index (`awaitRead` packages the barrier). **Graceful leadership transfer**
(`transferLeadership(target)`, §3.10) hands the lease to a named peer — the leader
brings the target's log up to the commit index, sends `TimeoutNow` to trigger an
immediate election there, and stops accepting new proposals until the handoff
completes or `cancelTransfer()` aborts it.

## Session metadata convergence

`kuilt-session` and `kuilt-crdt` compose to provide live-converging session
metadata without a separate replication protocol. The three-layer chain is:

1. **`MuxSeam`** (`kuilt-core`) — N-way Seam multiplexer; multiple logical
   channels share one physical transport collection.
2. **`Room.channel(id)`** (`kuilt-session`) — a `Seam` view scoped to admitted
   members only. Its `peers` is the live admitted roster, so a replicator
   running over it never sends state to unadmitted peers.
3. **`SeamReplicator<LWWMap<PeerId, String>>`** (`kuilt-crdt`) — runs over
   `room.channel("member-metadata")` to converge display names live across
   all admitted members with no explicit `merge()` call.

```kotlin
val rep = SeamReplicator<LWWMap<PeerId, String>>(
    replica = ReplicaId(room.selfId.value),
    seam = room.channel("member-metadata"),
    initial = LWWMap.empty(),
    messageSerializer = ReplicatorMessage.serializer(
        LWWMap.serializer(PeerId.serializer(), String.serializer())
    ),
    scope = scope,
)
// rep.state is the live-converging display-name map.
```

`MemberMetadata` remains a pure value type; the replication wiring sits above it.

## JSON document CRDT (`kuilt-crdt`)

`JsonCrdt` is a recursive, convergent JSON document CRDT that composes three
primitives from the zoo:

- **`ORMap<String, JsonNode>`** — the root and every nested object, with add-wins
  key semantics.
- **`Rga<JsonNode>`** — every JSON array: position-stable, concurrent-insert-safe.
- **`MVRegister<JsonValue>`** — every JSON scalar: concurrent writes from distinct
  replicas are retained until one replica observes and supersedes them.

Cross-type conflicts (concurrent type changes at the same key — e.g. one replica
turns a scalar into an object while another replica adds items to an array there)
are resolved by a total precedence rule (`Object > Array > Leaf`). The losing
subtree is silently discarded; see `JsonNode` KDoc for the v1 rationale.

`JsonCrdt` overrides `causalDots()` to recurse through all embedded `Rga`
arrays, so `SeamReplicator`'s causal-stability GC barrier fires correctly for
nested array tombstones.

## Presence/awareness CRDT (`kuilt-crdt`)

`EphemeralMap<V>` is a presence and awareness CRDT — it models the set of replicas
currently online, each with an arbitrary value (cursor, scroll position, user name,
…). It complements the durable CRDT types (`LWWMap`, `ORMap`) with an explicitly
*ephemeral* variant intended for real-time, non-durable state.

Key design decisions:

- **Per-replica slot ownership.** Each replica writes only to its own slot; there
  is no mechanism for replica `A` to overwrite `B`'s entry. This eliminates the
  need for tombstones or add-wins logic — absence after TTL is sufficient for
  removal.
- **Local receive-time TTL.** The CRDT itself is time-free and serialisable.
  `EphemeralMapTracker` stamps a local monotonic receive time whenever an entry
  advances, and `live()` filters by `now - receiveTime < ttlMs`. Cross-peer
  wall-clock comparison is avoided.
- **Graceful departure — null + higher clock.** A replica calls `leave()` to
  publish a `null`-valued entry with an incremented clock. Peers that merge the
  departure suppress the slot from `live()` output even if a stale presence entry
  with a lower clock is also in state.
- **Tie-break at equal clocks — present beats null.** A crash-detector tombstone
  minted at `seenClock + 1` can collide with the live peer's next heartbeat if
  both increment from the same base. `EphemeralMap.piece` keeps the non-null
  (present) entry at equal clocks, so a live peer's heartbeat is never silently
  evicted.
- **Reconnect recovery via TTL only.** A restarted replica whose clock resets to
  zero will have its writes dropped until the stale high-clock entry TTL-evicts
  on each observer. Rejoin-visibility latency is bounded by `ttlMs`.

## What kuilt is *not* responsible for

The `:kuilt-core` contract deliberately stops at moving bytes between connected
peers. The next two concerns belong to the `:kuilt-session` layer (a sibling
module over `:kuilt-core`, not part of the transport contract):

- **Role assignment** (host vs. joiner) — not a `Loom`/`Seam` concern; `Room`
  carries it.
- **Membership and the full join → active → leave → rejoin lifecycle**, the
  admit/identify handshake, reconnect, resume tokens, and the host-loss terminal
  state — all `kuilt-session` (see [usage.md](usage.md#the-membership-layer-kuilt-session)).

Genuinely out of scope for kuilt at every layer:

- **Application message semantics** — what the `Swatch.payload` / `RoomFrame`
  bytes mean is the consumer's job.

## Module boundary

```
kuilt-core         the contract + InMemoryLoom + MuxSeam + SeamConformanceSuite (depends on nothing fabric-specific)
  ├── kuilt-raft        Raft consensus (election, log, snapshots, membership, reads, transfer)  → depends on kuilt-core
  │     └── kuilt-game  turn-based game facade (TurnSequencer / SpeculativeSequencer)  → depends on kuilt-raft
  ├── kuilt-crdt        delta-state CRDT zoo + SeamReplicator   → depends on kuilt-core
  │     └── kuilt-deal  fair card dealing + fair-random (SRA / commit-reveal)  → depends on kuilt-crdt + kuilt-core
  ├── kuilt-session     membership/room layer (admit, roster, roles, resume)  → depends on kuilt-core
  ├── kuilt-websocket   Ktor WebSocket fabric (Far)            → depends on kuilt-core
  ├── kuilt-multipeer   Apple Multipeer fabric (Near, iOS/macOS)  → depends on kuilt-core
  ├── kuilt-nearby      Google Nearby fabric (Android)         → depends on kuilt-core
  ├── kuilt-webrtc      WebRTC data-channel fabric (wasmJs)    → depends on kuilt-core
  └── kuilt-mdns        Bonjour discovery → WebSocket session  → depends on kuilt-core + kuilt-websocket

kuilt-bom            Gradle/Maven platform constraining every module to one aligned version (not a code module)
```

The dependency arrow only ever points *down* toward `kuilt-core`. Keeping
`kuilt-core` free of fabric imports is what lets the whole boundary be
build-enforced when consumed as a composite build or a published artifact.

## Compatibility surface

Consumers depend on kuilt via the published `us.tractat.kuilt:*` coordinates,
optionally with a presence-gated `includeBuild("../kuilt")` override for
zero-latency local iteration. The public API and Maven coordinates are therefore
a compatibility surface — keep them stable across patch versions.
