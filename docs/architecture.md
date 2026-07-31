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
  `Woven` while a survivor carries the peer. If every ply goes down, the
  aggregate degrades to `Weaving` — recoverable, not terminal — and a ply
  coming back re-announces to restore routing. `Torn` is reserved for the
  close *decision*; a transient all-plies-down moment never tears the
  session on its own.

Why this matters for the layers above: because the bonding lives **below** the
`Seam`, the consensus and CRDT layers never see it. Hand the composite `Seam` to
`SeamRaftTransport` or `Quilter` and Raft sees one `NodeId`, the replicator
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

### Building, bonding, and splitting a Seam

Several `kuilt-core` types sit around the `Seam` boundary, and they are easy to
confuse because they all "combine" something. The clarifying lens is **what each
consumes and produces** — they line up on the `Connection`↔`Seam` axis:

| Type | Direction | Role |
|------|-----------|------|
| `Connection` | — | The point-to-point SPI a transport implements: a duplex, message-framed link between *exactly two* peers. Not a `Seam`. |
| `identified()` → `LinkSeam` | **Connection → Seam** | One link, both identities known, presented as a 2-peer `Seam` (`broadcast == sendTo(remote)`). |
| `meshSeam()` → `Mesh` | **Connections → Seam** | *Topology builder.* N point-to-point links woven into one fully-connected N-peer `Seam`; learns each remote id via a mesh preamble (id + per-connection nonce) and dedups duplicate links from a simultaneous dial by a canonical, order-independent link nonce both ends agree on. Admits later joiners via `Mesh.addLink`. |
| `CompositeLoom` → `CompositeSeam` | **Seams → Seam** | *Transport multiplexer.* Several `Seam`s (plies) for the **same** logical session bonded into one multipath `Seam` (see [Multipath](#multipath-one-peer-several-transports)). |
| `MuxSeam` | **Seam → Seams** | *Channel splitter (byte-tagged).* One `Seam` fanned into up to 256 byte-tagged logical-channel `Seam` views over a single collection. |
| `NamedMux` | **Seam → Seams** | *Channel splitter (string-keyed).* Unbounded-namespace sibling of `MuxSeam` — frames carry a UTF-8 name prefix (1–255 bytes) instead of a 1-byte tag. Compose by nesting: a `MuxSeam` tag can carry a whole `NamedMux` subtree. |

The two that invite the most confusion are **`MeshSeam` and `CompositeSeam`**:
`MeshSeam` is a **topology builder (`Connection → Seam`)** — it *creates* a `Seam` out of
raw links; `CompositeSeam` is a **transport multiplexer (`Seam → Seam`)** — it
*consumes* finished `Seam`s and bonds them. They sit on opposite sides of the
`Seam` boundary, so they don't fight: `MeshSeam`'s only reserved frame is its
`Hello` handshake, while `CompositeSeam`'s `PlyFrame` envelope wraps whatever its
plies already carry. They also don't compose by type today (`meshSeam()` takes
`List<Connection>`, not `List<Seam>`), so "a mesh whose every link is itself multipath"
would be a *new* abstraction at the topology layer, not a change to either.

### Who gets in: principals and link admission on the hosted hub

A mesh peer's wire identity is *self-asserted* — whatever `PeerId` it claims in its
`MeshHello` preamble. On a trusted LAN that is enough; on the open internet the host
usually *knows more* (a login token, a session cookie, a client certificate) and needs
a way to hold the door with it. The hosted-hub accept path carries that knowledge in
three steps, each defaulting to today's open behaviour:

- **Extraction** — the transport accept is the only moment auth material is in hand, so
  that's where a `Principal` (an opaque, host-verified identity) is derived:
  `KtorConnectionSource(principalExtractor = …)` on the WebSocket front door, or
  `Connection.withPrincipal(…)` on any hand-built source. The principal rides the
  connection object itself — never an out-of-band `peer → principal` map that could
  desync.
- **Verification** — `meshSeam(admission = LinkAdmission { principal, remoteId -> … })`
  enforces the policy inside `Mesh.addLink`, between the `MeshHello` handshake (the
  first moment the claimed id is known) and link publication (the last moment before
  the link can contend in duplicate-link dedup or receive frames). A rejected link is
  closed and never reaches the dedup tiebreak, so a forged `MeshHello` can never
  displace a live peer's link. `hostedOverlay(admission = …)` / `gameHosted(admission = …)`
  thread the same policy from the top. A supplied policy is authoritative for *every*
  link — unattested connections reach it with `principal = null` and it decides.
- **Landing** — `Mesh` (and, by delegation, the hub's `GossipSeam` and the
  `GameSession` facade) is a `PrincipalRoster`: `attestedPrincipals` exposes the
  verified principal per admitted peer, maintained atomically with the link set.

kuilt deliberately does not hardcode a `principal == peerId` binding — how an auth
subject maps to a mesh peer id is the consumer's policy (one user may run several
devices); the design and threat model live in
[`docs/superpowers/specs/2026-07-07-hub-accept-attestation.md`](superpowers/specs/2026-07-07-hub-accept-attestation.md).

### Principals on the mux hub: the same guarantee, one seam per room

A server can also host many rooms over a *single* shared connection instead of
giving each room its own link — one socket carrying several game tables at once.
A joiner's verified identity has one extra hop to make in that shape: from the
shared connection into the one room it actually joins. kuilt closes that hop so
a mux-hosted room gives the same verified-identity guarantee as a directly
hosted one — a consumer reading a member's principal doesn't need to know or
care which hub topology is underneath.

- **Two interfaces, two jobs.** `PrincipalRoster` is the canonical
  **seam-level** carrier — `attestedPrincipals: StateFlow<Map<PeerId,
  Principal>>`, a map because a seam can hold many peers at once. `RoomHubSeam`
  (the per-room seam behind the mux hub) implements it exactly as `Mesh` does
  (above), maintaining the map in the same critical sections as its membership
  so it can never desync. `PrincipalAttested` is the **connection-level**
  marker — a single, optional `Principal`, because exactly one connection has
  exactly one remote peer. It survives as the primitive every fabric attaches
  a verified identity through (`Connection.withPrincipal`) and as the
  **degenerate 2-peer view**: the relay `Seam` returned by `Seam.withPrincipal`
  exposes one principal because it only ever has one remote peer to describe.
- **Roster-first is the read rule.** A consumer checking whether a peer is
  attested should read `(seam as? PrincipalRoster)?.attestedPrincipals` first
  and fall back to `(seam as? PrincipalAttested)?.principal` only when no
  roster exists. `SeamRoom` follows exactly this rule when it populates
  `Member.principal`, and exposes the same roster directly as
  `Room.attestedPrincipals` (empty when the underlying seam isn't a
  `PrincipalRoster` — `Member.principal` stays the primary per-member
  surface); `GameSession.attestedPrincipals` reads the roster the same way —
  so a `gameHosted` session riding a mux-hosted room reports the same
  populated map a directly hosted `Mesh` does, with no session- or
  game-layer code aware of which topology sits underneath.

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

// Swatch — immutable; no public ByteArray field. Read bytes zero-copy:
//   swatch.byteAt(i), swatch.payloadSize, swatch.decodeToString(), swatch.decode(format, deserializer)
//   swatch.toByteArray()  — explicit copy (only allocating path)
//   swatch.dropFirst(n)   — zero-copy view for mux/framing layers
class Swatch(payload: ByteArray, val sender: PeerId? = null, val sequence: Long = 0)
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
- **`Swatch` is binary-only and immutable.** No text-frame variant. The wire layer never
  interprets the bytes — that is the consumer's job. Frames are read zero-copy via
  `byteAt` / `decodeToString` / `decode`; getting a `ByteArray` out is an explicit
  `toByteArray()` copy — the name makes the allocation visible at the call site.
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

To implement a new fabric — message-based or stream-based — see
[`docs/extending-fabrics.md`](extending-fabrics.md) for a step-by-step tutorial
with copy-pasteable Track A (message RPC) and Track B (stream RPC / TCP) paths,
the cold-`Connection` pump gotcha, and a `SeamConformanceSuite` subclass template.

## Capability gaps by design

Not every fabric can promise everything. A fabric declares what it can and
can't do (its `SeamCapabilities`), and the first two gaps below are permanent
properties of *how the fabric works*, not bugs waiting on a fix — they are
recorded here so a reader hits the explanation once, rather than re-deriving
it every time a new fabric declares the same honest limitation. The third is a
different shape: an honest "not wired yet" that closes fabric by fabric.

### securesTransport — fabrics without wire encryption

Some fabrics send frames in the clear, on purpose. An in-memory/loopback
fabric never leaves the process, so there is no wire to secure; a plain
`ws://` WebSocket, an mDNS-discovered connection, or raw TCP is designed to be
wrapped in transport encryption by whoever deploys it — the same way a plain
HTTP server expects TLS termination in front of it in production, not baked
into the server itself. kuilt keeps that choice at the deployment layer
(e.g. `wss://`) instead of forcing every fabric to carry its own crypto, so a
fabric that is honestly unencrypted declares `securesTransport = false`
rather than claiming a guarantee it doesn't provide.

### meshDelivery — relay and multi-hop fabrics

Some fabrics move a frame through other peers before it reaches its
destination, instead of sending it directly. The websocket and mdns fabrics
are hub-mediated: every message passes through a relay server, even between
two peers sitting side by side. The gossip overlay deliberately goes further
and floods a message across several hops (see
[`docs/gossip-mesh-design.md`](gossip-mesh-design.md)), trading direct
delivery for the ability to scale a large room without every peer dialing
every other peer. Declaring `meshDelivery = false` (or `true` vacuously, on a
strictly 2-peer fabric with no third peer to relay through) records honestly
that the frame did not travel peer-to-peer.

A third shape also declares `false`, for a different reason: a seam that bonds
two separate transports into one roster — `TieredSeam`, whose members are this
server's own room *plus* the other servers — adds no hop of its own, but two of
its members can be on different transports, in which case they cannot reach each
other through it at all. `true` there would tell a reader that any member
reaches any other directly, which is the one thing that is not true, so `false`
is the honest value even though nothing is relaying.

### reportsLiveCapability — fabrics without a path observer

Ask a device "is the network up?" and there are three honest answers: yes, no,
and *I don't know*. Most fabrics can only give the third one. A connection that
opened a moment ago proves the network was reachable *then*; it says nothing
about whether the Wi-Fi has since dropped, the phone has gone into a tunnel, or
the user has revoked the local-network permission. Only a fabric wired to the
operating system's own path monitor — today just `kuilt-nw`, via
`NWPathMonitor` — can actually watch that and report a live answer.

So `Seam.capability` starts at "I don't know" and a fabric has to earn anything
stronger. A fabric with no observer declares `reportsLiveCapability = false`
and keeps reporting `Unknown`; one with a real observer declares `true` and its
`FabricAvailability` moves with the device. The alternative — every fabric
asserting `Available` because a seam exists — is worse than saying nothing: a
consumer that surfaces it to a user would confidently claim the network is fine
while the phone sits on a dead radio. Silence is recoverable; a false all-clear
is not.

Unlike the two gaps above, this one is not permanent. Each fabric's observer is
wired one lane at a time, and a lane flips its flag to `true` in the same change
that lands the observer and a test proving the value actually moves.

### Not every flag describes a design choice

One flag is different in kind and is deliberately not explained here:
`collapsesPeersOnTear` says whether a disconnected seam still lists the peers it
could reach before it died. It always should — a dead connection reaches nobody —
so there is no honest reason to answer "no". Every fabric that currently answers
"no" is doing so because of a bug, and each one links to the issue tracking its
fix rather than to this page. Treat a `false` there as a countdown, not a
characteristic.

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
brings the target's log up to its own *last* log index (not merely the commit
index: an uncommitted tail would otherwise trigger a premature `TimeoutNow` to a
target whose election then fails), sends `TimeoutNow` to trigger an immediate
election there, and stops accepting new proposals until the handoff completes or
`cancelTransfer()` aborts it.

## Session metadata convergence

`kuilt-session` and `kuilt-crdt` compose to provide live-converging session
metadata without a separate replication protocol. The three-layer chain is:

1. **`MuxSeam`** (`kuilt-core`) — N-way Seam multiplexer; multiple logical
   channels share one physical transport collection.
2. **`Room.channel(id)`** (`kuilt-session`) — a `Seam` view scoped to admitted
   members only. Its `peers` is the live admitted roster, so a replicator
   running over it never sends state to unadmitted peers.
3. **`Quilter<LWWMap<PeerId, String>>`** (`kuilt-crdt`) — runs over
   `room.channel("member-metadata")` to converge display names live across
   all admitted members with no explicit `merge()` call.

See the [session-metadata example](https://tractat-us.github.io/kuilt/guide/crdt-quilter.html#session-metadata-convergence) in the Quilter guide.

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
arrays, so `Quilter`'s causal-stability GC barrier fires correctly for
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

## Server-cluster topology

`:kuilt-cluster` packages the **server-cluster topology** — a small set of
servers hosting consensus with many learner-clients that submit actions and
observe state. This is the two-tier overlay model: a densely-connected voter
core plus a sparse client periphery.

### Two-tier overlay

```
   voter core — complete graph K_m  (consensus lives ENTIRELY here)
   ┌─────────────────────────────┐
   │     S1 ──── S2 ──── S3       │   m = 1/3/5  (fault-tolerance dial)
   │      \      │      /         │   every voter pair linked; leader replicates to all
   └───────\─────┼─────/──────────┘
            \    │    /
   d=1 ──────C   │   C   C────── d=1   (star leaf: one live link per client)
  (single-link   │
   clients)      C
                 learner periphery — clients forward-to-propose, replicate-to-observe
```

- **Voter core — a complete graph `K_m`.** The `m` voter servers (m = 1/3/5)
  each hold a link to every other voter. Election, quorum, and commitment live
  here and nowhere else.
- **Learner periphery — sparse.** Many clients, each attached to the core via
  one or more server links, never voting and never counting toward quorum. A
  complete graph over clients would be the O(n²) cost the two-tier split exists
  to avoid.

### Attachment degree `d`

Each client holds `d` concurrent server links. `d` is the **attachment degree**
— the client's vertex connectivity to the voter core:

- **`d = 1` — star leaf (current).** One live link plus a static list of other
  endpoints to round-robin to on tear. Survives 0 *simultaneous* server
  failures without reconnect (it must fail over, but the failure is safe).
- **`d > 1` — multi-homed leaf (future).** `d` redundant links; the client
  rides out `d − 1` server failures with no reconnect at all. A later dial not
  committed in the current facade.

### Safety is topology-independent

**A client's attachment degree can never threaten Raft safety.** Raft's
consistency guarantee depends only on the voter quorum. A client that is
momentarily disconnected during failover — attachment degree `0` — cannot
produce stale reads or lost writes: proposals block until the client reconnects
and the cluster commits them; committed entries are not retracted. Client
connectivity is purely an **availability / forward-latency** dial, not a
correctness one. Under-provisioning it costs reconnect latency, never wrong
state.

### Point-to-point star implementation

The `:kuilt-cluster` facade realises the **point-to-point star** (slice 2): the
voter core meshes separately (in-process `Channel` transports under simulation,
real WebSocket sockets in the M=3 E2E), and each client holds a strict **2-peer
`Seam`** to exactly one server via `KtorRoomHost`. The client's Seam cannot
address the leader, so the attached server **relays** the client's raft messages
into the core — the `RaftRelayHub` routes each inbound learner frame by its `dest`
to exactly the addressed voter's inbound (true origin preserved), and the client's
player relay transport (over a hot-swappable `ManagedSeam`) wraps every send as a
`RaftRelay(dest = leader)` addressed to its single relay peer. This lets a client keep committing through *any* relay endpoint
regardless of which voter leads (the precondition for failover without moving
leadership). The *relay-room* shape — where a client is logically a peer on one
shared cluster `Seam` — is the separate `examples/` demo (slice 1), not this
facade. See the [star relay sub-spec](superpowers/specs/2026-06-17-star-relay-sub-spec.md)
for framing/addressing/back-pressure/resume details.

Client onboarding is a `changeMembership` that adds the client as a learner in
`ClusterConfig.learners`. Learner-set-only changes skip joint consensus — each
join is a cheap simple config entry. The client is assigned a `NodeId` derived
from its Seam `selfId` at join time; the server derives the same `NodeId` from
the room roster — no explicit client identification step is needed.

### Current limitations

- **M=3 voter mesh is proven under simulation; M=1 is proven over real sockets
  (`ServerClusterE2ETest`).** Real-socket M>1 E2E is a follow-up (see #545).
- **Cross-server resume degrades to fresh-join** (see #532). Each server's
  reconnect window registry is in-memory and per-room-instance; a `ResumeToken`
  issued by server-A is unknown to server-B, so failover always produces
  `ResumeResult.WindowClosed` and the client fresh-joins the new server.
  This is correct and fast; it costs a re-snapshot on the client's Raft log.
- **The `CoroutineScope.clusterClient()` production path** (relay-room, full
  reconnect wiring) requires a stable client identity derived from the `Loom`'s
  `Seam.selfId`. The current production entry point is `clusterClientWithNode()`
  for caller-managed transport (see #544 for the pending stable-identity work).

### `NodeId` ↔ `PeerId` alignment

Each voter's `NodeId` must equal `NodeId(serverPeerId.value)` — that is, the
server's `KtorRoomHost.serverPeerId` cast to a `NodeId`. The `RaftRelayHub`
stamps `Seam.broadcast`'s sender as `serverPeerId` and carries the true voter as the
`RaftRelay.origin`; the client's player relay transport maps the origin to a `NodeId`.
Mismatched IDs cause silently dropped AppendEntries.

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

- **Application message semantics** — what the `Swatch` bytes / `RoomFrame`
  bytes mean is the consumer's job.

## Module boundary

```
kuilt-core         the contract + InMemoryLoom + MuxSeam + NamedMux + CompositeLoom + SeamConformanceSuite (depends on nothing fabric-specific)
  ├── kuilt-raft        Raft consensus (election, log, snapshots, membership, reads, transfer)  → depends on kuilt-core
  │     ├── kuilt-game  turn-based game facade (TurnSequencer / SpeculativeSequencer)  → depends on kuilt-raft
  │     └── kuilt-cluster  server-cluster facade (ServerCluster / ClusterClient / VoterMesh)  → depends on kuilt-raft + kuilt-session + kuilt-websocket (JVM/Android)
  ├── kuilt-crdt        delta-state CRDT zoo + Quilter   → depends on kuilt-core
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
