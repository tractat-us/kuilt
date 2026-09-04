# kuilt cookbook — for coding agents

**Read this before writing networking, session, or shared-state code against kuilt.**
It is a lookup table: find the thing you're trying to do on the left, use the kuilt
primitive on the right, instead of building your own. Each entry links a runnable,
compile-checked snippet.

## Don't build this yourself

If you catch yourself writing any of these, stop — kuilt already ships it:

| You're about to write… | Use instead | Jump to |
|---|---|---|
| a rejoin / reconnect loop, a resume token, a "grace window / hold the slot open" | `Room.resumeToken` + `Room.resume` | [Rejoin & reconnect](#rejoin--reconnect) |
| a fixed-list or exponential retry/back-off loop | `ExponentialBackoff` | [Rejoin & reconnect](#rejoin--reconnect) |
| a reconnect banner / "why did we drop" classifier — transient vs. unrecoverable buckets | `MembershipEvent.Partitioned.reason` + `HostLost.reason` (`ReconnectReason`/`FailureReason`), plus their `localFabric` tag | [Rejoin & reconnect](#rejoin--reconnect) |
| a propose→authoritative/rejected turn/session facade, host election with a term | `GameSession` + `TurnSequencer` | [Consensus & turns](#consensus--turns) |
| a `host == selfId` check plus a re-election when the peer that was hosting walks out mid-lobby | `ElectionLobby.awaitRoom` → `ElectionOutcome.BecameHost` → `start()` on the **same** lobby | [Host election & the lobby](#host-election--the-lobby) |
| a heartbeat, an idle reaper, "is this peer still alive", "evict stale session" | `HeartbeatPartitionDetector` | [Liveness & presence](#liveness--presence) |
| "close a room nobody joined", "reap an abandoned table/lobby", "nobody ever showed up" | `SoloDeadlineDetector` | [Liveness & presence](#liveness--presence) |
| a "hold the seat open" / reconnect grace window on the host, a `pendingSeats` or `disconnectedAt` map | `JoinerReconnectController` | [Liveness & presence](#liveness--presence) |
| a "paused / reconnecting…" presence flag, a `lastSeen` map for greying out a player | `Room.roster` + `Member.liveness` — the level; `Room.events` is the notification | [Liveness & presence](#liveness--presence) |
| that same "paused / reconnecting…" surface for a **game** (not a bare room), a `room.events` → game-presence adapter | `RoomGameSession.presence` via `gameOverRoom` | [Liveness & presence](#liveness--presence) |
| a "you are offline" / "your connection dropped" indicator, distinguishing *your* outage from *their* outage | `Room.localFabric` + `MembershipEvent.LocalFabricLost` | [Liveness & presence](#liveness--presence) |
| a last-write-wins register, a grow-only set/counter, an add/remove set, a version vector, "merge these two states" | the CRDT zoo (`LWWRegister`, `GSet`, `PNCounter`, `ORSet`, …) | [Replicated data](#replicated-data) |
| replicating a CRDT over a connection by hand | `Quilter` | [Replicated data](#replicated-data) |
| a conditional write to shared state — "claim it only if it's free", "publish only if nobody already did" — or an identity/no-op patch meaning "I decided not to write" | `Quilter.mutateOrSkip { … }` (return `null` to decline) | [Replicated data](#replicated-data) |
| a forwarding hop through the host so two guests can see each other — because a `Quilter` between two joiners never converges, or a peer is in the roster but unreachable | `Room.channel(id)` — the room already relays | [Replicated data](#replicated-data) |
| averaging model updates from many devices without collecting their data — federated learning / federated analytics, "train locally, share only the update" | `FedAvg` + `TrainingUpdate` | [Replicated data](#replicated-data) |
| checking two peers hold the same state across a process/socket boundary — hand-hashing a replicated state so you can compare it as one number | `canonicalDigest` | [Replicated data](#replicated-data) |
| splitting a big blob into frames — picking a chunk size, or chasing a `FrameTooLargeException` that only appears once a peer drops | `Room.maxPayloadBytes` / `Seam.maxPayloadBytes` | [Payload limits](#payload-limits) |
| a `seenIds` set to skip already-handled messages | `GSet` / kuilt dedup | [Dedup](#dedup) |
| saving bytes so they survive a restart — a write-temp-then-`fsync`-then-atomic-rename dance, a per-platform file helper, an IndexedDB wrapper, "did that write actually land before we crashed?" | `DurableStore` + `StoreKey` | [Durable storage](#durable-storage) |
| a per-line flush loop in a log/telemetry exporter — or a fix for "capturing logs is slow", "the app stalls when it logs a lot" | `WarpLogRecordExporter.export(records)` + `installLogCapture` | [Telemetry & log capture](#telemetry--log-capture) |
| stamping the session/game/request a log line belongs to — an MDC equivalent, a global holding "the current session" for a log mapper to read, lines from one session tagged with another's id | `withLogContext` | [Telemetry & log capture](#telemetry--log-capture) |
| deleting a telemetry store's files to reset it, or a "clear on next launch" flag so the delete lands before recovery | `WarpTelemetry.clear()` | [Telemetry & log capture](#telemetry--log-capture) |
| your own flag or counter tracking whether telemetry is still being written — "has anything landed since launch?", "are we losing log lines?" | `WarpLogRecordExporter.health` + `LogCaptureInstallation.health` | [Telemetry & log capture](#telemetry--log-capture) |
| a second, longer-retention copy of a replicated log — "keep a year on the server beside an hour on the phone", "gossiped records vanish when the peer forgets them", a hand-rolled tee of what a replica applied | `BoltDecorator` + `AppliedOpSink` | [Telemetry & log capture](#telemetry--log-capture) |
| reading that archive back — "replay what the phone compacted away", "did I get the whole history or did it stop somewhere?", a resume cursor over an append-only log, a hand-rolled "is my archive intact" check | `Bolt.replay` + `ReplayScope` + the terminal verdict | [Telemetry & log capture](#telemetry--log-capture) |
| merging several mDNS/Multipeer discovery feeds into one lobby roster | `discoveryRoster` | [Discovery](#discovery) |
| a stale-peer sweeper over a discovery list that only ever grows — peers that left still on screen, "nobody is ever removed", a `lastSeen` timeout over *discovered* (not admitted) peers | `PeerDiscoverySource.departures()` — implement it, and hold it to `DiscoverySourceConformanceSuite` | [Peers pile up and are never removed](#peers-pile-up-and-are-never-removed) |
| a weighted / fair-share scheduler — "give this group 3× the share", "who runs the next quantum", a hoarder-proof round-robin | `HeddlePolicy` + `HeddleNode` | [Fair share & placement](#fair-share--placement) |
| an entitlement / quota ledger, "reserve a slot before running then charge once", a coordination-free budget that converges across peers | `EntitlementLedger` + `HeddleNode.reserve`/`complete` | [Fair share & placement](#fair-share--placement) |
| minting new quota or re-parenting a group at runtime and needing everyone to agree on the order (no double-mint on a split) | `heddleGoverned` (`GovernedHeddleNode`) | [Fair share & placement](#fair-share--placement) |
| gating a `WarpNode`'s tasks by a weighted lane — "interactive gets 3× batch" | `HeddleAdmissionControl` + `TaskDescriptor.inLane` | [Fair share & placement](#fair-share--placement) |
| "only run this on a GPU / in-region peer", a placement predicate over peer capabilities, "can this peer run this task" | `Affinity` + `TaskDescriptor.where` + `CapSet` | [Fair share & placement](#fair-share--placement) |
| a blob cache keyed by a content hash, a "have you got these bytes?" request/response, a manifest of what each peer holds | `Creel` + `BobbinExchange` | [Code mobility](#code-mobility) |
| running code that arrived from another peer — a plugin loader, an `eval`, a bespoke sandbox or timeout-and-kill wrapper | `WasmRuntime` + `WasmSandboxConfig` + `WarpLazyFetch` | [Code mobility](#code-mobility) |
| a `try`/`catch` inside an `onEach { … }.launchIn(scope)` so one bad item cannot kill a long-lived collector — or a fix for "the pump stopped and nothing said so", a `Seam`/`Room` that goes deaf after one throw, an iOS crash traced to an unhandled coroutine exception | `Flow.pumpIn(scope, onFailure, name) { … }` — it owns the upstream half your `try` structurally cannot see | [Long-lived pumps](#long-lived-pumps) |
| declaring a `MutableStateFlow<SeamState>` while writing a fabric, or a `closed`/`tornDown` flag beside one — or a fix for "my `close()` publishes `Torn` and something overwrites it", `state.first { it is Torn }` that hangs forever, a closed seam still reporting `Woven`, a seam slot that never frees | `SeamStateGate` — it fuses the latch check and the flow write, and replaces your single-shot flag | [A seam's terminal state](#a-seams-terminal-state) |
| turning a peer-supplied id into a roster entry while writing a fabric — `PeerId(bytes.decodeToString())`, a `Set<PeerId>` a callback adds to and removes from, `peers == setOf(selfId)` meaning "the session is over" | `PeerIdentityRegistry` | [A peer id off the wire](#a-peer-id-off-the-wire-straight-into-a-setpeerid) |
| a 4-byte length prefix and a reassembly loop over a socket or in-house RPC — "I have a byte stream, kuilt wants a fabric" | `framed()` → `handshaking()` | [Your own transport](#your-own-transport) |
| that same plumbing when the transport really is just TCP | `TcpLoom.host` / `TcpLoom.join` | [Plain TCP is already assembled](#plain-tcp-is-already-assembled) |
| dealing cards nobody can peek at — a shuffle on one device, "the dealer could cheat", hiding a card from the player holding it | `DealSession` | [Dealing cards nobody can peek at](#dealing-cards-nobody-can-peek-at) |
| a shared random seed nobody could steer — one peer picks a number and broadcasts it | `FairRandom.roll()` | [Nobody chose that number](#nobody-chose-that-number) |
| a server holding the authoritative log while many clients propose into it, and a client that must survive losing its server | `ClusterClient` + `ServerCluster` | [When one of the peers is a server](#when-one-of-the-peers-is-a-server) |
| a session too big for everyone-talks-to-everyone — N² links, a broadcast sent N times, memory that grows with the room | `GossipSeam` + `deltaTargets = { gossip.activePeers.value }` | [Scaling to many peers](#scaling-to-many-peers) |

## Discovery

**Intent:** merge several `PeerDiscoverySource` feeds (mDNS, Multipeer, …) into one live roster for a lobby UI — "who can I currently see?"
**Primitive:** `discoveryRoster(sources, scope)` (`us.tractat.kuilt.core.discovery`). Folds `discoveries()` minus `departures()`, keyed on `Tag.peerKey`, into one `StateFlow<Set<Tag>>`. Don't hand-roll the merge.

It returns only **this peer's current best view** — not an agreement. It is **not** an election input: pick a host from `Seam.peers` once connected, never from this roster. And note the ghost caveat — a source whose `departures()` returns `emptyFlow()` is add-only, so departed peers linger forever. There is no interface default for `departures()`: a source with no leave signal has to write the `emptyFlow()` out, so you can tell which of your sources the caveat applies to by reading them.

<!-- verbatim from kuilt-core/src/commonSamples/kotlin/us/tractat/kuilt/core/discovery/DiscoverySamples.kt#sampleDiscoveryRoster -->
```kotlin
// One StateFlow the lobby UI renders directly — no hand-rolled merge.
val roster = discoveryRoster(listOf(mdns, multipeer), backgroundScope)
runCurrent()

mdnsPeers.emit(InMemoryTag("alice"))
mdnsPeers.emit(InMemoryTag("bob"))
runCurrent()
check(roster.value.map { it.peerKey }.toSet() == setOf("alice", "bob"))

// A departure removes the peer, keyed on Tag.peerKey.
mdnsGone.emit("alice")
runCurrent()
check(roster.value.map { it.peerKey }.toSet() == setOf("bob"))
```

### A peer id off the wire, straight into a `Set<PeerId>`

**Intent:** you are writing a fabric, and a remote has just told you who it is — a display name, a handshake payload, a native callback's string. You are about to write `PeerId(bytes.decodeToString())` and `peers.update { it + peer }`, and decide the session is over when `peers == setOf(selfId)`.

**Primitive:** `PeerIdentityRegistry` (`:kuilt-core`). Those bytes are the least trustworthy input a fabric handles, and every path that judged them for itself judged them differently — three times, over #1432, #1466, #1494 and #1821. Key membership by the underlying **device identity** and ask the registry:

<!-- verbatim from kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/PeerIdentityRegistry.kt#bind -->
```kotlin
public fun bind(
    id: PeerId,
    token: T,
): BindResult =
    lock.withLock {
        when {
            id.value.isBlank() -> BindResult.REFUSED_BLANK
            id == selfId -> BindResult.REFUSED_SELF
            else ->
                when (bound[id]) {
                    null -> {
                        bound[id] = token
                        BindResult.BOUND
                    }
                    token -> BindResult.ALREADY_BOUND
                    else -> BindResult.COLLISION
                }
        }
    }
```

Derive the roster from `registry.peers + selfId`, evict with the identity-scoped `unbind(id, token)` so a drop can only ever remove the device that actually holds the id, and `clear()` at teardown so a post-tear callback cannot recompute the roster from stale bindings.

Three things a bare set gets wrong, each of which has shipped:

- **Two devices on one id merge, then one drop evicts both.** A set has one entry; whoever leaves first takes the other with them. `COLLISION` refuses the newcomer instead, and the incumbent keeps the id.
- **A peer registers *itself*.** A symmetric advertise+browse fabric is handed its own advertisement, dials it, and the eventual drop of that self-link evicts the peer from its own roster — the #1466 signature. `REFUSED_SELF`.
- **A blank id is unaddressable and wedges teardown.** `PeerId("")` in the roster makes a `remaining == setOf(selfId)` end-of-session test unsatisfiable forever, so the seam never tears, `incoming` never completes, and the session slot is never freed. `REFUSED_BLANK` — and ask `registry.peers.isEmpty()` rather than comparing a set that peer-supplied strings can pollute.

**Decoding is still yours.** The registry judges an id, not bytes: `ByteArray.decodeToString()` defaults to *lossy*, mapping every malformed sequence to U+FFFD, so two entirely different announcements arrive as the same id and there is nothing left for a collision check to see. Decode with `throwOnInvalidSequence = true` and refuse what does not decode.

**The one thing it cannot do** is un-merge what the layer beneath already merged. If your transport hands up only the id string with no device handle to key by, pass the id as its own token: you still get the refusals and the identity-scoped eviction, but `COLLISION` is unreachable and closing it means changing the layer below. Say so where a reader will hit it, rather than leaving the arm looking covered.

### Peers pile up and are never removed

**Intent:** the list of nearby games only ever grows. Someone closes the app, walks out of the building, or turns their Wi-Fi off, and their name stays on the screen; tapping it connects to nothing. You are about to write a sweeper that quietly drops anyone you have not heard from in thirty seconds.
**Primitive:** don't — the removal already exists. `discoveryRoster` drops a peer the instant one of its sources emits that peer's `Tag.peerKey` from `departures()`, so a roster that only grows means some source never emits. Read each source's `departures()` body: there is **no interface default**, so a source with no leave signal has the `emptyFlow()` written out where you can see it, and those sources — only those — are the ones the ghost caveat covers.

If you own the source, `departures()` is the thing to fix and `DiscoverySourceConformanceSuite` (`:kuilt-conformance`) is how you find out whether it works — binding an existing backend to it is also how you learn what it does *not* yet do, since each backend's own conformance test is where its standing is recorded. Subclass, say how a peer arrives, and declare the leave signal: `DepartureFixture.Emits { … }` when there is one, `DepartureFixture.NoLeaveSignal` when there honestly isn't. The second arm is a claim the suite then checks, not an exemption — a source that declares it and emits anything at all fails.

<!-- verbatim from kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/ReferenceDiscoverySourceConformanceTest.kt#ReferenceDiscoverySourceConformanceTest -->
```kotlin
class ReferenceDiscoverySourceConformanceTest : DiscoverySourceConformanceSuite() {
    override fun newSource(): PeerDiscoverySource = ReferenceDiscoverySource()

    override suspend fun causeArrival(source: PeerDiscoverySource) {
        (source as ReferenceDiscoverySource).advertise()
    }

    override fun departureFixture(source: PeerDiscoverySource): DepartureFixture =
        DepartureFixture.Emits { (source as ReferenceDiscoverySource).withdraw() }
}
```

Two traps that shipped here, and that the suite exists to catch. **Emitting *something* is not enough** — the roster removes by exact key, so a departure carrying a display name, a socket address, or another transport's handle leaves precisely the same ghost while looking correct in a log. And **a leave signal that only works while somebody is watching arrivals is not a leave signal** — `discoveryRoster` merges the two feeds, and `merge` subscribes to inner flows in separately launched coroutines, so a departure feed fed off the `discoveries()` session can lose the event to its own sibling.

The reason this is more than a missing `override`: on every mDNS platform the goodbye carries only the service **name**, never the TXT map the peer id lives in. Read the id off the removal event and you get null, on all three backends. The peer id has to be remembered at resolution time and looked up when the name goes away — which also means a service that never resolved emits nothing, exactly as it emitted nothing on arrival.

## Your own transport

You already have a way for two machines to talk — a TCP socket, an in-house RPC, a serial
link, something a customer insists on — and you want a kuilt room on top of it: a roster,
reconnect, shared state. You are about to write a length prefix, a read loop that
reassembles it, and a small "who are you?" exchange so each end learns the other's name.

**Intent:** turn a byte stream you already have into a `Seam`.
**Primitive:** `framed(source, sink)` (`:kuilt-stream`), then `handshaking(connection, selfId, dispatcher)`
(`:kuilt-core`). Between them that is the whole bridge; the only transport-specific code left is your
own connect/accept. `framed` wraps a kotlinx-io `Source`/`Sink` as a `Connection` with a 4-byte
big-endian length prefix per frame, and `handshaking` negotiates identity in-band and hands back a
2-peer `Seam` — after which `Room`, `Quilter` and `GameSession` all work unchanged, because they only
ever knew about `Seam`.

<!-- verbatim from kuilt-tcp/src/jvmTest/kotlin/us/tractat/kuilt/tcp/ProprietaryRpcExampleTest.kt#ProprietaryRpcExampleTest -->
```kotlin
private fun rpcConnection(socket: Socket): Connection =
    framed(
        source = socket.getInputStream().asSource().buffered(),
        sink = socket.getOutputStream().asSink().buffered(),
    )

/** Wrap a connected socket as a 2-peer kuilt [Seam], identity negotiated in-band. */
private suspend fun weaveSeam(socket: Socket, selfId: PeerId): Seam =
    handshaking(rpcConnection(socket), selfId, Dispatchers.IO)
```

**The size ceiling is symmetric, and publishing it matters as much as enforcing it.**
`maxFrameSize` (default `DEFAULT_MAX_FRAME_SIZE`, 16 MiB) is checked in both directions: an oversize
`send` throws `FrameTooLargeException` before writing a byte, and a hostile length prefix on the wire
throws *before anything is allocated for it*. The same number then travels upward as
`Connection.maxFrameBytes` and surfaces as `Seam.maxPayloadBytes` — the budget
[Payload limits](#payload-limits) tells callers to chunk to. A fabric that leaves it unset leaves
that whole mechanism inert, which is why TCP is the in-tree fabric held to the number rather than
declaring the gap away.

<!-- verbatim from kuilt-stream/src/commonTest/kotlin/us/tractat/kuilt/stream/FramedTest.kt#rejectsOversizePrefixWithoutAllocating -->
```kotlin
val wire = Buffer()
wire.writeInt(Int.MAX_VALUE)        // hostile length prefix — validates before allocating
val conn = framed(source = wire, sink = wire, maxFrameSize = 16)
assertFailsWith<FrameTooLargeException> { conn.incoming.toList() }
```

Two assumptions to hold on to. Reading uses `Source.readByteArray`, which **blocks the collecting
coroutine** until the bytes arrive — so collect on a real IO dispatcher, never under a virtual-time
test scheduler, where a blocking read never advances and the test hangs rather than fails. And a
clean EOF *at a frame boundary* completes `incoming` normally, while an EOF *mid-frame* propagates as
an `EOFException`: a truncated frame is an error, not a tidy end of stream.

### Plain TCP is already assembled

**Intent:** the transport really is just TCP and you are about to do the plumbing above by hand.
**Primitive:** `TcpLoom.host(serverSocket, selfId, selector)` and `TcpLoom.join(selfId, selector)`
(`:kuilt-tcp`, JVM/Android), joining a `TcpAddress`. It is `framed()` + `handshaking()` already
wired, and it is held to the same `SeamConformanceSuite` as every other fabric.

<!-- verbatim from kuilt-tcp/src/jvmTest/kotlin/us/tractat/kuilt/tcp/TcpConformanceTest.kt#TcpConformanceTest -->
```kotlin
override fun newLoomPair(): Pair<Loom, Loom> {
    val hostLoom = TcpLoom.host(serverSocket, PeerId("tcp-host"), selector)
    val joinerLoom = TcpLoom.join(PeerId("tcp-joiner"), selector)
    return hostLoom to joinerLoom
}

override fun joinTag(): Tag = TcpAddress(host = "127.0.0.1", port = port)
```

Bind the `ServerSocket` yourself before calling `host` — `aSocket(selector).tcp().bind(host, 0)`, then
read the port back off the socket you are holding. Probing for a free port and re-binding the number
is a TOCTOU another process can win in the window between the probe closing and the real bind. And
`weave` refuses outright to build under a `TestDispatcher`: this is real socket IO, and under virtual
time it would deadlock silently instead of failing. Test the layers above it over an in-memory
`Connection` pair instead.

## Rejoin & reconnect

**Intent:** a per-game / per-session id both peers agree on — to key a durable `(session, device) → seat` record, scope a log, or name a table.
**Primitive:** `Room.roomId` (`us.tractat.kuilt.session`). Agreed in the admit handshake at zero extra traffic — don't mint your own and replicate it over a side channel, and don't use the host's peer id, which names the *device* and repeats across every room it hosts.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#perSessionIdSample -->
```kotlin
public suspend fun perSessionIdSample(room: Room) {
    // Null means "this joiner is not admitted yet", not "this room has no id" — so wait for the
    // value rather than sampling it. A host is non-null immediately and this returns at once.
    val id: RoomId = room.roomId.filterNotNull().first()
    // Safe as a durable key — a fresh room means a fresh id, including two games in a row from one
    // device and the games either side of an app kill.
    println("seat record key: ${id.value}/${room.selfId.value}")
}
```

**Intent:** the room's identity is decided *outside* kuilt — a lobby code, an invite link, a matchmaker-assigned game id — and every member must read back that exact value.
**Primitive:** `RoomFactory.host(pattern, roomId = …)`. Note what this is **not**: supplying an id does not make a *host restart* resumable. A restarted host has no roster and no reconnect-window registry, so a `ResumeToken` it accepts on identity grounds still cannot complete — cold-start rejoin is [#1593](https://github.com/tractat-us/kuilt/issues/1593), and reusing an id does not solve it.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#callerSuppliedRoomIdSample -->
```kotlin
public suspend fun callerSuppliedRoomIdSample(
    factory: RoomFactory,
    pattern: Pattern,
    lobbyCode: String,
): RoomId {
    val room = factory.host(pattern, roomId = RoomId(lobbyCode))
    // Read back off the room, not off the variable you passed in: the room is the thing joiners
    // agree with, and on a joiner this same property is how you learn the value at all.
    return room.roomId.value ?: error("a host room knows its id at construction")
}
```

**Intent:** rejoin / reconnect after a dropped connection; "hold the slot open" for a grace window.
**Primitive:** `ResumeToken` (`us.tractat.kuilt.session.partition`) presented to `Room.resume` — `Room.resumeToken` mints it, `Room` is the whole public surface. (`SeamRoom`, which this entry used to name, is `internal`; `SeamRoomFactory` builds it.) Don't re-track the grace window yourself.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#resumeAfterDropSample -->
```kotlin
public suspend fun resumeAfterDropSample(room: Room): Boolean {
    // After the admit handshake the joiner holds a reconnect credential — save it.
    val token: ResumeToken = room.resumeToken ?: return false
    // ... transport drops; you redial the fabric and rebuild the room ...
    // Present the saved token to re-enter within the leader's grace window.
    return when (val outcome = room.resume(token)) {
        ResumeResult.Success -> false // back in the room; state resync follows
        // The host answered, and said no. WHY is in the code, never in the message: an elapsed
        // window and a token for another room are terminal, a window the host has not opened
        // yet is not. See classifyRejectCodeSample.
        is ResumeResult.Refused -> outcome.code.retryable
        ResumeResult.TimedOut -> true // no reply within resumeTimeout — the host is unreachable now
        // We never asked: this room is already over (left, or the host was lost), or the frame
        // could not be sent. Re-join fresh.
        ResumeResult.WindowClosed -> false
    }
}
```

Those four arms are the whole of `ResumeResult.JoinerOutcome`. The host's own verdicts —
`WindowNotYetOpen`, `TokenInvalid` — are the other half of the hierarchy (`ResumeResult.HostVerdict`)
and never travel to a joiner as values; they arrive as the `RejectCode` on `Refused`. Branching on
one here is a **compile error**, which is how this entry stopped being able to advertise a
discrimination the API does not offer (#2364). Until then it listed both, the sample compiled, and a
consumer reading `WindowClosed -> // grace window elapsed` acted on a value that also meant "wrong
room" and "the host hasn't noticed your drop yet" — the last of which is *transient*, and re-joining
fresh is the wrong move for it.

**Intent:** decide whether a host's *refusal* is worth retrying, instead of string-matching the reason.
**Primitive:** `RejectCode` on `ResumeResult.Refused` / `FailureReason.Refused` / `AdmissionFailure.Rejected` (`us.tractat.kuilt.session.admit`) — branch on the code and treat anything unrecognised as retryable. The same classifier serves an in-flight resume and a session that already ended.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#classifyRejectCodeSample -->
```kotlin
public fun classifyRejectCodeSample(reason: FailureReason.Refused): Boolean =
    when (reason.code) {
        // Terminal: the window closed, the credential can never validate here, or the two peers
        // speak incompatible protocol versions (retrying a version you don't support is futile).
        RejectCode.ResumeWindowExpired, RejectCode.ResumeTokenInvalid,
        RejectCode.RoomMismatch, RejectCode.ProtocolMismatch,
        -> false
        // Transient: the host hasn't opened the window yet (the fast-reconnect race).
        RejectCode.ResumeWindowNotYetOpen -> true
        // Anything else, including a code this build has never heard of.
        else -> reason.code.retryable
    }
```

**Intent:** retry with back-off after a failed dial.
**Primitive:** `core.util.ExponentialBackoff` — don't hand-roll a `listOf(1.s, 5.s, 30.s)` delay table.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#retryWithBackoffSample -->
```kotlin
public suspend fun retryWithBackoffSample(random: Random, dial: suspend () -> Boolean) {
    val backoff = ExponentialBackoff(base = 1.seconds, cap = 30.seconds, random = random)
    var attempt = 0
    while (!dial()) {
        delay(backoff.delay(attempt++)) // full-jitter; decorrelates simultaneous retriers
    }
}
```

**Intent:** drive a "reconnecting…" banner, or decide "give up and show an error", from the reason kuilt already observed.
**Primitive:** `MembershipEvent.Partitioned.reason` (`ReconnectReason`) and `HostLost.reason` (`FailureReason`) — don't re-derive your own transient/unrecoverable classification.

Check the `localFabric` tag **before** the reason. Both events carry this peer's own
`Room.localFabric` as it stood when they were emitted, and when that reads `Unavailable` for silence
**you observed yourself**, the event is not evidence about the peer it names — your own end was down,
so their quiet says nothing about them. Skip that check and a joiner whose own radio died renders
"lost the host", which is the bug this pattern used to ship.

One boundary worth knowing before you apply it broadly. `HostLost` is always something you observed,
so the tag always inverts attribution there. `Partitioned` is not always yours: on a joiner in a room
of three or more, the host relays "peer C paused", and that report is authoritative — it reached you
over a link that was working, so an `Unavailable` tag means only that your end was down when you
processed it, not that the report is wrong. Suppress it and C stays shown as present while the host
holds its seat open. The event carries no provenance field and `Room` exposes no host id, so you
cannot tell the two apart from the event alone; in a two-peer session it makes no difference, and in
a larger room scope the check to peers you watch yourself.

Precedence is otherwise readable straight off the event — you never have to correlate two streams by
timestamp. See [the "you are offline" entry](#liveness--presence) for the level behind the tag.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#reconnectBannerSample -->
```kotlin
public suspend fun reconnectBannerSample(room: Room) {
    room.events.collect { event ->
        // The tag inverts attribution only for silence *we* observed — our own detector, or our own
        // link tearing. `HostLost` is always that. `Partitioned` is not: on a joiner in a 3+-peer
        // room the host relays "peer C paused", and that report is host-authoritative — it arrived
        // over a link working well enough to deliver it, so an `Unavailable` tag there says our end
        // was down when we *processed* the report, not that the report is unfounded. Suppressing it
        // would leave C shown as present while the host holds its seat open.
        //
        // The event carries no provenance field, and `Room` exposes no host id, so a consumer cannot
        // tell the two apart from the event alone. In a two-peer session it does not matter (the only
        // peer you watch *is* the host). In a larger room, scope this to peers you observe yourself.
        val ourOwnEndWasDown = when (event) {
            is MembershipEvent.HostLost -> event.localFabric is FabricAvailability.Unavailable
            else -> false
        }
        when {
            // First branch, deliberately: never say "lost the host" when *we* are the ones offline.
            ourOwnEndWasDown -> Unit // "You're offline — check your connection"
            event is MembershipEvent.Partitioned -> when (event.reason) {
                ReconnectReason.LinkTimeout, ReconnectReason.TransportClosed -> Unit // "Reconnecting…"
                ReconnectReason.Backpressure -> Unit // "Connection congested…"
            }
            event is MembershipEvent.HostLost -> when (val reason = event.reason) {
                FailureReason.WindowExpired -> Unit // "Lost the host — rejoin"
                FailureReason.Unrecoverable -> Unit // "Can't reconnect — return to lobby"
                is FailureReason.Refused -> Unit // show reason.message (auth-expired / version, …)
            }
            else -> Unit
        }
    }
}
```

## Replicated data

Need a value that stays in sync across peers with no server to arbitrate conflicts? Don't
hand-roll merge logic — the CRDT zoo already has a lattice for the shape you need, and
`Quilter` already knows how to ship deltas over a `Seam`. The five below cover the cases
that come up constantly; the full 14-type zoo (`GCounter`, `TwoPhaseSet`, `MVRegister`,
`LWWMap`, `ORMap`, `BoundedCounter`, `Rga`, `Causal`, `JsonCrdt`, `EphemeralMap`, and more)
is documented in [`Writerside/topics/crdt-overview.md`](../Writerside/topics/crdt-overview.md).

**Intent:** a shared value where the most recent write wins, converging across peers with no server to arbitrate.
**Primitive:** `LWWRegister` (`us.tractat.kuilt.crdt`).

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleLWWRegister -->
```kotlin
val a = ReplicaId("A")
val b = ReplicaId("B")

val left = LWWRegister.empty<String>().set(a, timestamp = 1L, value = "v1")
val right = LWWRegister.empty<String>().set(b, timestamp = 2L, value = "v2")

check(left.piece(right).value == "v2")  // ts=2 wins
check(right.piece(left).value == "v2")  // commutative
```

**Intent:** a set that only ever grows — completed tasks, acknowledged events, registered participants — with no remove needed and no server to arbitrate.
**Primitive:** `GSet` (`us.tractat.kuilt.crdt`).

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleGSet -->
```kotlin
var set = GSet.empty<String>()
set = set.piece(set.add("alice"))
set = set.piece(set.add("bob"))
check(set.elements == setOf("alice", "bob"))
```

**Intent:** a shared counter that peers increment and decrement independently — a score, a budget, a tally — converging to the same net value with no coordinator.
**Primitive:** `PNCounter` (`us.tractat.kuilt.crdt`).

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#samplePNCounter -->
```kotlin
val a = ReplicaId("A")
val b = ReplicaId("B")

var counter = PNCounter.ZERO
counter = counter.piece(counter.increment(a, 10))
counter = counter.piece(counter.decrement(b, 3))

check(counter.value == 7L)
```

**Intent:** a shared set where peers add and remove concurrently, and a concurrent re-add should beat a concurrent remove rather than silently vanishing.
**Primitive:** `ORSet` (`us.tractat.kuilt.crdt`).

`add`/`remove` return a `Patch` — the one element they touched — so a write costs the same whether
the set holds ten entries or ten thousand. Ship it with `quilter.mutate { it.add(replica, x) }`;
absorb it locally with `set.piece { it.add(replica, x) }`.

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleORSet -->
```kotlin
// Two peers have converged: "alice" is present on both, added by B.
var alpha = ORSet.empty<String>().piece { it.add(b, "alice") }
var bravo = alpha

// A re-adds "alice" and puts only the change on the wire. The delta names A's new dot
// *and* B's older one, which the re-add supersedes — so both peers drop the old dot.
val readd = alpha.add(a, "alice")
alpha = alpha.piece(readd)
bravo = bravo.piece(readd)
check(alpha == bravo)

// A concurrent add beats a concurrent remove: B's re-add mints a dot A's remove never saw.
val concurrent = alpha.add(b, "alice")
check(alpha.piece(alpha.remove("alice")).piece(concurrent).contains("alice"))
```

**Intent:** drop many elements — or empty the set entirely — without paying a causal merge per element.
**Primitive:** `ORSet.removeAll` (`us.tractat.kuilt.crdt`).

Absorbing a patch is a join over the whole set, so `elements.fold(set) { s, e -> s.piece { it.remove(e) } }`
is Θ(n·N) — measured at ~3.5 s over a 10,000-element set. `removeAll` pays one join for the batch
(~4 ms) and is otherwise indistinguishable: same dots retired, same retained context, same bytes. The
retained context is what keeps the emptied set **dominant** over a peer that re-merges its old copy.

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleORSetBulkRemoval -->
```kotlin
// One patch drops all three — the same dots the per-element loop would have retired, so one
// causal join does the work of three.
set = set.piece { it.removeAll(set.elements) }
check(set.elements.isEmpty())

// The retained context is the point: a peer re-merging its pre-removal copy stays empty
// rather than resurrecting everyone.
check(set.piece(snapshot).elements.isEmpty())
```

**Intent:** a shared **map** whose keys peers add and remove concurrently, each key holding a value that merges in its own right — a roster, a task board, a nested document.
**Primitive:** `ORMap` (`us.tractat.kuilt.crdt`).

Same story one level up: `put`/`remove` return a `Patch` carrying one key, and a put's delta carries
**only the value you passed** — the receiver re-does the merge against its own copy, so a nested
`ORMap<K, ORSet<X>>` ships one element rather than the whole roster.

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleORMap -->
```kotlin
// Two peers have converged: "team" already holds a long roster, put there by B.
var alpha = ORMap.empty<String, GSet<String>>()
    .piece { it.put(b, "team", GSet.of("alice", "bob", "carol", "dan")) }
var bravo = alpha

// A adds one member and puts only the change on the wire. The delta carries A's one name —
// not the merged roster — because the receiver re-does that merge against its own copy.
val hire = alpha.put(a, "team", GSet.of("erin"))
check(hire.delta["team"] == GSet.of("erin"))
```

**Intent:** a shared **settings**-shaped map — key → latest value, last writer wins per key, concurrent edits resolved rather than surfaced.
**Primitive:** `LWWMap` (`us.tractat.kuilt.crdt`).

`set`/`remove` return a one-cell `Patch`; a removal ships a *tombstone cell*, never an empty map
(an empty map is the lattice identity and would say nothing at all).

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleLWWMap -->
```kotlin
// Two peers have converged on a settings map.
var alpha = LWWMap.empty<String, String>()
    .piece { it.set(a, timestamp = 1L, key = "lang", value = "en") }
    .piece { it.set(a, timestamp = 2L, key = "tz", value = "UTC") }
    .piece { it.set(a, timestamp = 3L, key = "theme", value = "dark") }
var bravo = alpha

// B changes one setting and puts only that cell on the wire. The frame is the same size
// whether the map holds three keys or ten thousand, and the other keys are untouched.
val change = alpha.set(b, timestamp = 4L, key = "theme", value = "light")
alpha = alpha.piece(change)
bravo = bravo.piece(change)
check(alpha == bravo)
```

**Intent:** a shared **JSON document** — nested objects, arrays and scalars — edited concurrently by several peers and converging without a merge step.
**Primitive:** `JsonCrdt` (`us.tractat.kuilt.crdt`).

`set`/`remove` return a `Patch` carrying the one key they touched, so editing one field of a
1,000-field document sends 177 bytes rather than the 127 KB the whole document weighs. A change
*inside* a nested object is still expressed by rebuilding that object and setting it at the top —
one key, but that key's value is the whole rebuilt subtree
([#2469](https://github.com/tractat-us/kuilt/issues/2469)).

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleJsonCrdt -->
```kotlin
// Two peers have converged on a document with a title and a long body.
var alpha = JsonCrdt.empty(a)
    .piece { it.set("title", text(a, "Draft")) }
    .piece { it.set("body", text(a, "a very long document body")) }
var bravo = alpha.withReplica(b)

// B retitles the document and puts only that key on the wire. The body does not travel —
// that is the whole saving, and it holds however large the rest of the document gets.
val retitle = bravo.set("title", text(b, "Final"))
check(retitle.delta.keys == setOf("title"))
check(retitle.delta["body"] == null)
```

**Intent:** replicating a CRDT live over a `Seam` by hand — collecting inbound deltas, merging them, broadcasting outbound deltas, and exposing the converged value as a `StateFlow`.
**Primitive:** `Quilter` (`us.tractat.kuilt.quilter`). Don't drive `Seam.incoming` and delta merge/broadcast yourself.

<!-- verbatim from kuilt-quilter/src/commonSamples/kotlin/us/tractat/kuilt/quilter/QuilterSamples.kt#sampleQuilterSetup -->
```kotlin
internal fun sampleQuilterSetup() = runTest(
    StandardTestDispatcher(),
    timeout = TEST_WEDGE_BACKSTOP,
) {
    val loom = InMemoryLoom()
    val seam = loom.host(Pattern("my-session"))

    val cfg = QuilterConfig(expectVirtualTime = true)
    val replicator = Quilter(
        replica = ReplicaId(seam.selfId.value),
        seam = seam,
        initial = GCounter.ZERO,
        messageSerializer = QuiltMessage.serializer(GCounter.serializer()),
        scope = backgroundScope,
        config = cfg,
    )

    // Apply a mutation — the delta is broadcast to all current peers automatically.
    replicator.apply(replicator.state.value.inc(replicator.replica, 1L))

    // state is a StateFlow — always the current converged value.
    assertEquals(1L, replicator.state.value.value)
}
```

See [`crdt-quilter.md`](../Writerside/topics/crdt-quilter.md) for `Quilter`'s wire protocol,
late-joiner full-state sync, and [Scaling to many peers](#scaling-to-many-peers) below for the
`GossipSeam` pairing.

**Intent:** read the shared state, decide, and *maybe* write — "claim the seat only if it's free", "publish this only if nobody already did", "apply the op if the state still allows it". The read and the decision have to be atomic with the write, and a refusal must be silent.
**Primitive:** `Quilter.mutateOrSkip { … }`. Return `null` from the transform to decline; it returns whether anything was published. Don't return an identity patch to mean "no change" — it leaves the state alone but still burns a sequence number and broadcasts an empty delta to every peer. Don't test the condition *before* the call either: that decides against a state nothing is holding still, so another writer can make the answer wrong before you publish.

<!-- verbatim from kuilt-quilter/src/commonSamples/kotlin/us/tractat/kuilt/quilter/QuilterSamples.kt#sampleQuilterMutateOrSkip -->
```kotlin
    // Claim a seat only if it is still free. Read and write are one atomic step, so no other
    // writer can take the seat between the check and the publish.
    fun claim(seat: String, player: String, at: Long): Boolean =
        seats.mutateOrSkip { board ->
            if (board[seat] != null) null else board.set(seats.replica, at, seat, player)
        }

    assertEquals(true, claim("north", "alice", 1L), "the seat was free — published")
    assertEquals(false, claim("north", "bob", 2L), "already taken — nothing published, no frame sent")
```

Use plain `mutate { … }` when the transform always writes.

**Intent:** two guests in the same room never see each other's updates — a `Quilter` between two joiners never converges, one peer's messages reach the host and stop, or a co-member is in the roster but every send to it fails. Typically on Multipeer, Nearby, a WebSocket hub, or any "everybody connects to the host" wiring.
**Primitive:** `Room.channel(id)` — and then nothing. The room relays through the host for you (#1994); don't build a forwarding protocol, and don't reach past the room to the raw fabric `Seam`.

The distinction that decides it: a fabric `Seam` from `Loom.host`/`Loom.join` reports the peers it holds a **direct link** to, which on a star is just the host. A `Room`'s channel view reports the **admitted roster**, and `Room.broadcast` / `Room.sendTo` wrap anything the transport cannot address directly and send it to the host to forward. So run replicators over `room.channel(...)`, never over the fabric seam the room was built on. (`Seam.sendTo` to a co-spoke still throws `PeerNotConnected` on those fabrics, and that is correct — the link really is absent.)

Two things the relay deliberately does not do. Presence is not relayed: a co-member with no direct link is watched by the host, not by you, so read `Room.roster` / `Room.events` rather than expecting a heartbeat to answer. And relayed delivery is best-effort past the first hop — `Room.broadcast` never throws, and `Room.sendTo` reports only the hop *this* member makes, naming the **host** when that is the hop that failed.

<!-- verbatim from kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/StarQuilterConvergenceTest.kt#setReplicator -->
```kotlin
private fun setReplicator(room: Room, scope: CoroutineScope): Quilter<GSet<String>> = Quilter(
    replica = ReplicaId(room.selfId.value),
    seam = room.channel("star-set"),
    initial = GSet.empty(),
    messageSerializer = QuiltMessage.serializer(GSet.serializer(String.serializer())),
    scope = scope,
    config = quilterConfig,
)
```

**Intent:** learn one shared model from data spread across many devices without collecting the data — federated learning / federated analytics, "each device trains locally and publishes only the update".
**Primitive:** `FedAvg` + `TrainingUpdate` (`:kuilt-warp-ml`). The count-weighted mean across peers, as a CRDT: contributions merge in any order, duplicates are absorbed, and every replica reads the same model bit-for-bit. Don't hand-roll a `(Σweights, Σcount)` accumulator or a round barrier.

<!-- verbatim from kuilt-warp-ml/src/commonSamples/kotlin/us/tractat/kuilt/warp/FedAvgSamples.kt#sampleFedAvg -->
```kotlin
val alice = ReplicaId("alice")
val bob = ReplicaId("bob")
val carol = ReplicaId("carol")

// Each peer trains locally and contributes its results.
val fromAlice = FedAvg.contribution(alice, sampleCount = 100L, localWeights = listOf(0.5, 0.3))
val fromBob   = FedAvg.contribution(bob,   sampleCount = 200L, localWeights = listOf(0.7, 0.1))
val fromCarol = FedAvg.contribution(carol, sampleCount = 300L, localWeights = listOf(0.9, 0.5))

// Any replica merges contributions in any order — result is the same.
val merged = FedAvg.ZERO.piece(fromAlice).piece(fromBob).piece(fromCarol)

// weights[i] = Σ(n_k * w_k[i]) / Σ(n_k)
val w = merged.weights
check(w.size == 2)
// Spot-check: (100*0.5 + 200*0.7 + 300*0.9) / (100+200+300) = 460/600 ≈ 0.7667
check(w[0] in 0.766..0.768)

// Idempotent: absorbing the same contribution again changes nothing.
check(merged.piece(fromAlice) == merged)
check(merged.piece(fromBob) == merged)

// Rides the coordination-free path — no Seam or Raft required.
val free = CoordinationFree(fromAlice).embroider(CoordinationFree(fromBob))
check(free.state == FedAvg.ZERO.piece(fromAlice).piece(fromBob))
```

The training step can travel too: `FedAvgKernelCodec` marshals the same step as a
content-addressed WebAssembly kernel shipped through [Code mobility](#code-mobility), and
`ReferenceTrainer` is the Kotlin oracle it is held bit-for-bit equal to. See
[`kuilt-warp-ml/module.md`](../kuilt-warp-ml/module.md) — including the honest seam on lane
costing if you gate the workload with `HeddleAdmissionControl`.

**Intent:** check that two peers hold the same state when you *can't* compare the objects — a cross-process or real-socket test where shipping a whole state back to assert on is impractical, or a divergence alarm between live peers in a harness.
**Primitive:** `canonicalDigest(serializer, value)` (`:kuilt-conformance`, `us.tractat.kuilt.conformance`). A 64-bit FNV-1a hash over the value's canonical CBOR encoding — converged replicas share a digest, diverged ones almost certainly don't, and one `Long` crosses the boundary instead of a whole state. Test- and harness-side only: `:kuilt-conformance` `api`-exposes `kotlin-test`, so it does not belong on a production classpath.

**In-process, don't use it.** `assertEquals(a, b)` on the states themselves is strictly better:
exact, no collision risk, and a far better failure message. `LatticeLawHarness` deliberately
compares raw bytes rather than digests for that reason. Reach for `canonicalDigest` only where the
comparison has to cross a process, a socket, or a live-peer boundary. And it is **not
cryptographic** — a 64-bit non-keyed hash is fine against accidental divergence and no defence at
all against a peer that forges a matching digest.

It also inherits the [canonical-encoding invariant](../kuilt-crdt/module.md): a digest over a state
that encodes non-canonically reports *permanent* false divergence between replicas that agree. The
zoo holds that invariant; a bespoke state of your own has to earn it.

<!-- verbatim from kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/CanonicalDigestTest.kt#convergedReplicasShareADigest -->
```kotlin
val ser = GSet.serializer(String.serializer())
val forward = GSet.of("alpha").piece(GSet.of("beta")).piece(GSet.of("gamma"))
val reverse = GSet.of("gamma").piece(GSet.of("beta")).piece(GSet.of("alpha"))
assertAll(
    { assertEquals(forward, reverse, "sanity: same logical state") },
    {
        assertEquals(
            canonicalDigest(ser, forward),
            canonicalDigest(ser, reverse),
            "converged replicas must share a digest",
        )
    },
)
```

**Intent:** keep only the newest N entries of a shared, replicated list or log — a chat backlog, an
audit trail, an on-device telemetry buffer — and actually *get the memory back*. Removing an entry
from an `Rga` only hides it: the insert, body and all, stays in the op-log forever, so a cap on how
much is *visible* is not a cap on how much is *held*. And once you do delete those ops, a peer that
never heard about the removal will happily re-add them on the next merge.
**Primitive:** `Rga.dropWindow(self, dropped)` (`us.tractat.kuilt.crdt`). It drops the ops **and**
leaves a suppression record behind, so the entries stay gone across a merge. Pair it with
`Rga.sequence`/`Rga.tombstones` to work out which ids fall outside the window you want to keep.

**Reading only the head, without paying for the whole log.** `toList()` and `entries()` each build
two lists the size of the log, so working out "which ids fall outside the window" by materialising
everything and then taking a handful throws nearly all of that work away. Walk `Rga.sequence`
lazily instead, filter `Rga.tombstones`, `take` what you need, and resolve just those with
`Rga.valueAt(id)` — an O(1) read of one element by its id:

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleRgaHeadWindow -->
```kotlin
    // Remove the first entry. It is TOMBSTONED, not gone — still in `sequence`, and its value
    // is still readable — which is exactly why the walk has to filter `tombstones` itself.
    log = checkNotNull(log.removeAt(0)).first
    check(ids.first() in log.tombstones)
    check(log.valueAt(ids.first()) == "entry-1")

    // The two oldest VISIBLE entries, resolving only those two.
    val head = log.sequence.asSequence()
        .filter { id -> id !in log.tombstones }
        .take(2)
        .map { id -> log.valueAt(id) }
        .toList()
    check(head == listOf("entry-2", "entry-3"))
```

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleRgaDropWindow -->
```kotlin
val a = ReplicaId("A")

var log = Rga.empty<String>()
var after = RgaId.HEAD
val ids = (1..5).map { i ->
    val (next, op) = log.insertAfter(replica = a, after = after, value = "entry-$i")
    log = next
    after = op.id
    op.id
}
val peer = log // a peer that still holds all five inserts

// Retain the newest two; drop the rest.
val (windowed, delta) = checkNotNull(log.dropWindow(self = a, dropped = ids.take(3).toSet()))
check(windowed.toList() == listOf("entry-4", "entry-5"))

// This replica's OWN dots fold into a floor — one entry per author, not one per element.
check(windowed.causalFloor()[a] == 3L)
check(windowed.compactOpCount == 0)

// The drop is permanent suppression, not deletion: merging the peer's log back in
// re-purges the dropped entries instead of resurrecting them.
check(windowed.piece(peer).toList() == listOf("entry-4", "entry-5"))

// Any peer performs the same drop by absorbing the returned delta.
check(peer.piece(delta.delta).toList() == listOf("entry-4", "entry-5"))
```

**How cheap the drop is depends on who wrote the entry, and only one of the two arms is a bound.**
Your own entries fold into a per-author *floor* — one number per author, however many entries you
drop — which is why a log you alone append to settles back to O(window). An entry **another** peer
wrote cannot: raising someone else's floor would annihilate entries they have not written yet, so
`dropWindow` records those individually, and nothing prunes those records. So the honest shape is
**bounded on the local-append path, still growing on the gossip path** — a strict improvement on
retaining the whole entry, but not a bound. Say so wherever you quote a bound.

Two more things to know before you reach for it. The window is *positional*: an entry whose
predecessor you dropped re-anchors to the front of the list rather than to that predecessor, so
relative order with older entries is not preserved (see `Rga.compactedBelow`). And if you replicate
through `Quilter`, read `causalFloor()` alongside `causalDots()` when you fold a delivered
frontier — the floored dots leave `causalDots()` entirely, and a walk that counts only dots reports
a frontier of zero for that author and stalls every downstream collection.

**Primitive:** `VersionVector.contiguous(dots, floor)` (`us.tractat.kuilt.crdt`) — the same function
`Quilter` folds its own cut with, so there is nothing left to hand-roll. It stops at the first gap
on purpose: a replica holding `1, 2, 4` has *not* delivered `4`, because `3` is still in flight and
something it has not seen may depend on it.

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleVersionVectorContiguous -->
```kotlin
val a = ReplicaId("A")
val b = ReplicaId("B")

// A holds 1, 2, 4 — seq 3 is still in flight, so A has delivered 2, not 4.
val dots = setOf(Dot(a, 1L), Dot(a, 2L), Dot(a, 4L), Dot(b, 1L))
val frontier = VersionVector.contiguous(dots, floor = VersionVector.EMPTY)
check(frontier[a] == 2L)
check(frontier[b] == 1L)

// After compaction swallows A's 1..2 without keeping their ids, the floor asserts they were
// delivered and the walk starts above them — so 4 is still gapped, but 3 would now count.
val floor = VersionVector.of(mapOf(a to 2L))
check(VersionVector.contiguous(setOf(Dot(a, 4L)), floor)[a] == 2L)
check(VersionVector.contiguous(setOf(Dot(a, 3L), Dot(a, 4L)), floor)[a] == 4L)
```

`floor` is deliberately not defaulted. Passing `VersionVector.EMPTY` where a real floor exists
collapses that author's high-water to `0`, and a gossiped regression there pins every downstream
compaction below the gap forever.

**I want the edit history, not the current value — and I want it to outlive what the replica
forgets.** A replicated list is really a log of small edits, and `dropWindow`/`compact` above throw
old ones away. If you want a record that *survives* that — an audit trail, an archive, a server
holding a year of history beside a phone holding an hour — you cannot get it by merging: merging
makes forgetting **contagious**, so a compacted peer propagates its compaction to everyone it syncs
with. You have to consume the **operations** instead, as they arrive.

**Primitive:** `OpLogCrdt` (`us.tractat.kuilt.crdt`), implemented by both `Rga` and `Fugue`.
`operations()` is the live log, `classify(op)` splits it three ways, and `dotOf(id)` projects an
id to its causal dot. The split is the safety-critical part: `LogOp.Insert` and `LogOp.Remove` are
*content*, while `LogOp.Compact` is a record of *forgetting*. Keep the first two and discard the
third and your history outlives its source's — which is the whole trick.

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleOpLogCrdt -->
```kotlin
// The same three-way split for any op-log CRDT — Fugue implements the identical contract.
val content = log.operations().filterNot { log.classify(it) is LogOp.Compact }
check(content.count() == 2)

// Inserts mint dots; a remove reuses its target's id, so a dot cursor is defined over
// inserts only.
val dots = log.operations()
    .mapNotNull { (log.classify(it) as? LogOp.Insert)?.id }
    .map { id -> log.dotOf(id) }
    .toSet()
check(dots == setOf(Dot(a, 1L), Dot(a, 2L)))
```

Two traps. **`operations()` is the *live* log, never a complete history** — a replica that has
already compacted no longer holds what it dropped, and for `Rga` an op below the `compactedBelow`
floor leaves with **no** `Compact` naming it. So feed an archive as ops arrive; you cannot
reconstruct one from a replica afterwards. And **encode ops with `opSerializer`**, never a
compiler-generated serializer: the generated one writes a different wire format and cannot encode a
polymorphic element type under CBOR, putting your bytes outside the golden vectors that pin the
format across versions. It is on the interface, and on the `Rga`/`Fugue` companions for a decoder
that has bytes but no replica.

## Scaling to many peers

Everyone-talks-to-everyone is fine for a card game and stops working for a room of a hundred:
each device ends up holding a hundred links, every change goes out a hundred times, and the
bookkeeping each peer keeps about the others grows with the size of the room rather than with
how much of it that peer actually talks to.

**Intent:** make a large session practical without changing anything above the fabric.
**Primitive:** `GossipSeam` (`:kuilt-gossip`) wrapped around the seam you already have, paired with
`deltaTargets = { gossip.activePeers.value }` on your `Quilter`. A `GossipSeam` **is** a `Seam`, so
`Room`, `Quilter` and everything downstream are untouched: `broadcast` floods to a handful of
neighbours who re-flood to *their* handful, duplicates are recognised and dropped, and `Quilter`'s
anti-entropy reconcile is the backstop that makes "usually connected" good enough.

<!-- verbatim from kuilt-scale/src/test/kotlin/us/tractat/kuilt/scale/GossipQuilterConvergenceTest.kt#quilterOverGossipSeamConverges -->
```kotlin
val gossips = mesh.seams.mapIndexed { i, base ->
    GossipSeam(base = base, random = Random(1 + i), clock = clock, config = noHeartbeat, jitter = ZERO..ZERO)
}
gossips.forEach { it.start(backgroundScope) }
flush()

// The overlay holds a k-regular active view, strictly smaller than full membership —
// the ack-set every replicator GCs against.
val k = recommendedActiveViewSize(n)
gossips.forEach { g ->
    assertEquals(k, g.activePeers.value.size, "each node holds k=$k active neighbours")
}
assertTrue(k < n - 1, "k=$k must be a strict subset of the N-1=${n - 1} full membership")

// One Quilter per node, GCing only against that node's active neighbours.
val quilters = gossips.mapIndexed { i, gossip ->
    Quilter(
        seam = gossip,
        initial = GCounter.ZERO,
        valueSerializer = GCounter.serializer(),
        scope = backgroundScope,
        config = quilterCfg,
        deltaTargets = { gossip.activePeers.value },
        random = Random(100 + i),
    )
}
```

(`mesh.seams` there is just the seams you already have; the zeroed jitter and hour-scale heartbeats
are what make that test deterministic — production takes the defaults.)

**Two views, answering different questions.** `activePeers` is the ~k neighbours this peer exchanges
updates with, and is what `deltaTargets` should point at so the GC watermark tracks *those* acks
rather than the whole room's — that is the scaling win, and it is the line people leave out. `peers`
is full membership, delegated from the base seam, and is the pool anti-entropy samples from.
`recommendedActiveViewSize(n)` is the k the default policy draws (`max(4, ⌈ln N⌉ + 2)`, so ~4–7 for
tens to low hundreds), which is what keeps the union of everyone's independent choices a single
connected graph even though nobody is connected to everybody. **Seed `random` per peer** — a shared
seed makes every peer pick the same neighbours and collapses that graph. (`spares` is the short
standby list a lost neighbour is replaced from; read it to inspect failover, don't drive it.)

`start(scope)` once, on a scope you own. `sendTo` is deliberately **not** shaped by the overlay: it
passes straight through to the base seam, so point-to-point still reaches any peer the transport can
address. `incoming` keeps the single-collection contract, and `GossipSeam` is itself the sole
collector of the base seam's — ping/pong frames are consumed by the per-neighbour detectors and never
surface to you.

Reach for it by size, not by reflex: at a handful of peers a partial mesh buys nothing and costs a
hop, and adding it later is a one-line change where the seam is built. The other shipped
`TopologyPolicy` is `FullFanout`, where one hub re-floods every broadcast to all its spokes;
`CoroutineScope.hostedOverlay(selfId, source, dispatcher, …)` is that hub already assembled over a
`ConnectionSource`.

## Liveness & presence

People close laptops, step into lifts, and lose Wi-Fi. When that happens your app has to say
something — "reconnecting…", "waiting for player", or "you're offline" — and *which* of those it says
is most of the problem. Getting it wrong is very visible: a phone that drops its own signal and then
announces "everyone else vanished" has told the user the one thing that isn't true.

kuilt already notices the silence, holds someone's seat open while they are away, and — where the
platform can tell it — knows whether the connection that broke was theirs or yours. Read the state it
publishes instead of building a `lastSeen` map on top of your own traffic.

**Intent:** detect that a peer went silent / is no longer alive; "heartbeat", "lastSeen".
**Primitive:** `HeartbeatPartitionDetector` + `HeartbeatConfig` (`:kuilt-liveness`). Don't hand-roll a `while (true) { delay(); ping() }` loop.

<!-- verbatim from kuilt-liveness/src/commonSamples/kotlin/us/tractat/kuilt/liveness/AgentCookbookSamples.kt#detectSilentPeerSample -->
```kotlin
public suspend fun detectSilentPeerSample(
    link: Seam,
    peerId: PeerId,
    scope: CoroutineScope,
    clock: () -> Instant,
) {
    val detector = HeartbeatPartitionDetector(link, peerId, HeartbeatConfig(), clock)
    detector.start(scope)
    detector.events.collect { event ->
        when (event) {
            is PartitionEvent.PeerUnresponsive -> Unit // pause app processing; reason says why
            is PartitionEvent.PeerRecovered -> Unit // peer came back within the reconnect window
            is PartitionEvent.PeerLost -> Unit // reconnect window elapsed — vacate the slot
        }
    }
}
```

**Intent:** close a room/table/lobby that never filled; "idle-reap a session nobody joined", "nobody ever showed up", "expire an abandoned table".
**Primitive:** `SoloDeadlineDetector` + `SoloDeadlineEvent` (`:kuilt-liveness`). Don't hand-roll a `launch { delay(timeout); if (peers.size < 2) close() }`. It **emits**, it never closes — reaping policy stays yours.

<!-- verbatim from kuilt-liveness/src/commonSamples/kotlin/us/tractat/kuilt/liveness/AgentCookbookSamples.kt#reapNeverPairedRoomSample -->
```kotlin
public suspend fun reapNeverPairedRoomSample(
    link: Seam,
    scope: CoroutineScope,
    clock: Clock,
    closeRoom: suspend () -> Unit,
) {
    val detector = SoloDeadlineDetector(
        minimumMembers = 2, // this peer plus one — "never paired"
        deadline = 5.minutes,
        clock = clock,
        scope = scope,
    )
    // Feed it the roster on every change.
    scope.launch { link.peers.collect { detector.observeMembership(it) } }
    when (detector.events.first()) {
        is SoloDeadlineEvent.NeverPaired -> closeRoom() // nobody came; reaping policy is yours
        is SoloDeadlineEvent.Paired -> Unit // someone joined in time; the detector is done
    }
}
```

**Intent:** hold a dropped peer's **seat** open for a grace window instead of evicting it — "keep the slot", "reserved", "reconnect window", "don't kick them yet".
**Primitive:** `JoinerReconnectController` (`:kuilt-session`, `us.tractat.kuilt.session.partition`). It *is* the server-side seat-hold: `onPeerUnresponsive` opens the timed window, `onPeerRecovered` closes it when the peer comes back on its own, `tryResume` validates the returning peer's `ResumeToken` (right room, window still open, token not already used), and `events` reports `WindowOpened` / `Resumed` / `WindowExpired`. A `SeamRoom` host wires one for you — reach for this directly only when you own the host loop. Don't keep your own `pendingSeats` / `disconnectedAt` map.

> **If you implement this interface yourself, echo the detection instant.** Every `WindowOpened` and `WindowExpired` you emit must carry, as `detectedAt`, the exact `at` you were handed in `onPeerUnresponsive` for the drop that opened the window — unchanged, including on a later announcement that extends that same window. It names *which partition episode* the deadline is for, and the room drops any announcement whose episode is not the one it currently holds; a freshly-read clock would name *when you announced*, which is precisely the ambiguity it exists to remove, and every one of your refinements would be discarded (look for `room.window.stale-episode` at `debug`). Nothing checks this at compile time.

> **And disarm on `onPeerRecovered`.** Most peers come back without ever presenting a token — a blip is restored by the liveness detector alone, so `tryResume` is never reached and `onPeerRecovered` is the only thing that tells you the seat is occupied again. A `WindowExpired` you emit afterwards is not cosmetic: the room fans it out as an authoritative `Farewell`, so a healthy member is evicted from every roster but the host's, with no re-admit path behind it. Close the window there; do not route it through `expire`, which *is* an expiry. The room refuses such an expiry anyway (`windowExpired.suppressed … reason=recovered` at `info` is the tell), but that is a backstop, not the contract.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#holdTheSeatOpenSample -->
```kotlin
public suspend fun holdTheSeatOpenSample(
    controller: JoinerReconnectController,
    dropped: PeerId,
    nowEpochMs: Long,
) {
    // The peer's link dropped: open (or refresh) its reconnect window rather than evicting.
    controller.onPeerUnresponsive(dropped, at = nowEpochMs)
    controller.events.collect { event ->
        when (event) {
            // The seat is reserved until event.expiresAt.
            is JoinerReconnectEvent.WindowOpened -> Unit
            // It came back in time — push an application-state snapshot to event.peerId.
            is JoinerReconnectEvent.Resumed -> Unit
            // Window elapsed; the seat is released and MembershipEvent.Left(PartitionExpired) follows.
            is JoinerReconnectEvent.WindowExpired -> Unit
        }
    }
}
```

> **Which one?** `SoloDeadlineDetector` answers *"did anyone ever join?"* — it disarms
> permanently on first pairing, so a room that fills and later empties emits nothing more.
> `HeartbeatPartitionDetector` answers *"is this peer, who **was** here, still alive?"*.
> Every `PartitionEvent` names a `peerId`; "nobody ever came" has no peer to name, which is
> why the never-paired case is a separate type rather than a `PartitionEvent` variant.

**Intent:** show a peer as **paused** (seat held) rather than gone — a greyed-out avatar, "reconnecting…", "waiting for player".
**Primitive:** `Room.roster` + `Member.liveness` (`:kuilt-session`) — the **level**, and what to key your UI on. `Room.events` (`MembershipEvent.Partitioned` / `WindowOpened` / `Recovered`) is the *notification* that it moved. Don't build a `lastSeen` map on top of application traffic.

The roster entry reads `Liveness.Partitioned(since, windowExpiresAt)` for as long as the seat is
held, so the countdown you display needs no event replay, and a late subscriber reads the current
state rather than missing the `Partitioned` that announced it. It reads the same way on **both**
roles — a joiner watching its host, and a host watching a joiner — so you do not need a different
strategy per role. Where the two surfaces can differ, the level is the one to trust: it is never
*staler* than an event, though during a rapid flap it can already be **ahead** of the one you are
handling. Two corollaries worth spelling out:

- **Don't key the un-grey on `Recovered` vs `Resumed`.** They differ by role and by recovery path,
  so either one alone leaves a real case hanging. The level clears on both.
- **A later `WindowOpened` for the same peer supersedes an earlier one.** A dropped link can
  re-open (refresh) its window, and the host also fans out its own authoritative deadline — so hold
  the latest `expiresAt` rather than assuming the first is final. Reading `windowExpiresAt` off the
  roster sidesteps the question entirely.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#observePausedPeersSample -->
```kotlin
public suspend fun observePausedPeersSample(room: Room) {
    // room.roster.value.filter { it.liveness is Liveness.Partitioned } is the same fact, pull-style —
    // and each Partitioned carries windowExpiresAt, so the countdown needs no event replay.
    room.events.collect { event ->
        when (event) {
            is MembershipEvent.Partitioned -> Unit // grey the seat out — this peer's link dropped
            // Held until event.expiresAt — but a later WindowOpened for the same peer supersedes
            // this one (a more authoritative deadline arrived), so keep the latest, not the first.
            is MembershipEvent.WindowOpened -> Unit
            is MembershipEvent.Recovered -> Unit // it returned inside the window — un-grey it
            is MembershipEvent.Left -> Unit // gone for good: Normal (clean) or PartitionExpired
            else -> Unit
        }
    }
}
```

> These events mean the same thing on **every** member, whatever the topology underneath.
> Liveness is detected locally, which is enough on a mesh but blind on a star — so the host
> also fans out an authoritative `AdmitMessage.Paused` / `Unpaused`, and a `Farewell` when a
> window expires (#1557). Receipt is idempotent: a peer that detects the drop itself *and*
> receives the fan-out emits one event, not two.

**Intent:** the same pause/resume presence, but your session is a **game** bootstrapped over a room — "how do I know a player dropped?" should have one answer for a game and a room, not two.
**Primitive:** `RoomGameSession.presence` + `RoomGameSession.roster` (`:kuilt-game`). Bootstrap the game with `gameOverRoom(adoptedRoom)` and it returns a `RoomGameSession` whose `presence` **is** `room.events` and whose `roster` **is** `room.roster` — the exact `MembershipEvent` + `Member.liveness` vocabulary above. Don't hand-wire a `room.events` → game-presence adapter (and don't infer presence from Raft roster churn — that is where a "premature Resumed" lives). Presence here is **link liveness**: a `Resumed` a few seconds after a drop can be a legitimate link heal, so layer human "seated / away" state on top. `gameOverRoom` owns the room — tear both down with `RoomGameSession.close`, never `room.leave()`. See the compiled `sampleGameOverRoom` (`kuilt-game/src/commonSamples/kotlin/us/tractat/kuilt/game/GameSamples.kt`).

**Intent:** say "**you're** offline" instead of "everyone else vanished" — a "your connection dropped" banner, and deciding whether a peer's silence is really about *them*.
**Primitive:** `Room.localFabric` + `MembershipEvent.LocalFabricLost` / `LocalFabricRestored` (`:kuilt-session`). Every other member of the presence vocabulary names *somebody else*, so a device that loses its own network attributes the outage to its peers — and in a two-peer session the two cases are indistinguishable from peer-side observation alone. `localFabric` publishes the fact the transport already knew. Don't reach past `Room` into a transport-specific path monitor, and don't try to tell the cases apart by racing timestamps between two flows.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#localFabricBannerSample -->
```kotlin
public suspend fun localFabricBannerSample(room: Room) {
    // Bind the banner to the LEVEL, not to the edges. A StateFlow replays its current value to a late
    // collector, so this cannot miss a drop that happened before you subscribed, and it keeps the UI
    // reading the authoritative surface rather than a notification that may already be superseded.
    room.localFabric.collect { availability ->
        when (availability) {
            FabricAvailability.Available -> Unit // no banner
            is FabricAvailability.Unavailable -> Unit // "You're offline" — this room's fabric, not the device
            is FabricAvailability.Unknown -> Unit // kuilt cannot tell on this fabric — say nothing
        }
    }
    // The edges are for things a level cannot express — logging the transport's own words, or firing a
    // one-shot. Only transitions into Unavailable and into Available emit; a move into Unknown emits
    // nothing, because "we stopped being able to tell" is not a loss. Re-read the level when handling
    // one: under a rapid flap the level may already be ahead of the edge in your hand.
    room.events.collect { event ->
        when (event) {
            is MembershipEvent.LocalFabricLost ->
                Unit // event.reason is the transport's own words; room.localFabric.value is the truth now
            is MembershipEvent.LocalFabricRestored -> Unit // may arrive with no preceding Lost
            else -> Unit
        }
    }
}
```

Five things to know before you bind this to a UI:

- **On `:kuilt-websocket` you have to wire the observer — it is off by default.** Pass one to the
  loom: `KtorClientLoom(httpClient, connectivity = androidConnectivityObserver(context))` on Android,
  `browserConnectivityObserver()` on wasmJs. Without it the loom uses `UnobservedConnectivity` and
  every room over it reads `Unknown` forever, which is honest but useless for a banner. You own the
  observer's lifetime — `close()` it when the looms built from it are done. On the desktop JVM there
  is deliberately nothing to wire; don't synthesise reachability from socket state, because that
  reports the **relay's** health as the **device's** and those are different questions (the second is
  peer liveness — see the entry above).
- **It is session-scoped, never device-scoped.** A `Room` rides exactly one fabric, so this only ever
  means *"my end of **this room's** fabric."* A peer in two rooms over two fabrics has two independent
  values and neither speaks for the other; kuilt has no device-level registry. A room over a bonded
  `CompositeSeam` reports `Unavailable` only when **every** woven ply is down.
- **`Unknown` is a real third answer, and it is still a common one.** Only a fabric wired to an
  observer that watches its own reachability can give you a live yes or no; the lanes without one
  honestly report `Unknown`, meaning *kuilt cannot tell on this fabric*. Treat it as no information,
  never as either answer, and expect it rather than treat it as an error. It is also **per target,
  not per fabric**: `kuilt-websocket` watches Android's `ConnectivityManager` and the browser's
  `navigator.onLine`, while the desktop JVM has no portable observer and honestly says nothing — so
  one lane can answer on one platform and shrug on another. Which lanes have an observer changes as
  they are wired up one at a time, so read the flag rather than a list: a fabric's conformance test
  declares `reportsLiveCapability`, and
  [architecture.md](architecture.md#reportslivecapability--fabrics-without-a-path-observer) explains
  what earns a `true`.
- **`Partitioned` and `HostLost` carry the same value as a tag**, captured at the instant they were
  emitted, which is what makes precedence readable from the stream. When the tag is `Unavailable` for
  silence *you* observed, that event is not evidence about the peer it names. `HostLost` always is
  yours; a `Partitioned` relayed by the host about a third peer is **not**, and stays authoritative —
  see [the reconnect-banner entry](#rejoin--reconnect) for where that boundary falls and why the event
  alone cannot tell you which side of it you are on.
- **The level is authoritative; the edges are notifications.** `Room.localFabric` is a `StateFlow`, so
  a late collector cannot miss a drop, while the events only announce transitions *into* `Unavailable`
  and *into* `Available` — a move into `Unknown` emits nothing. A `LocalFabricRestored` can therefore
  arrive with no preceding `LocalFabricLost` (a room whose fabric was already down when it was built),
  and the level can legitimately be *ahead* of the edge you are handling during a rapid flap. On a
  bonded `CompositeSeam` the **tag** is best-effort when every transport drops inside one dispatch
  window (#1778): re-read `Room.localFabric` at handling time if a decision must be certain.

## Host election & the lobby

**Intent:** several peers are connected and one of them has to host — and then that one walks out before the session starts.
**Primitive:** `SeamRoomFactory.electLobby(pattern)` → `ElectionLobby` (`:kuilt-session`). Every peer computes the same `electHost(peers)`; the elected one calls `start()`, the rest call `awaitRoom()`.

`awaitRoom()` returns a sealed **`ElectionOutcome`**, not a bare `Room`, and the case that catches people is `BecameHost`: the peer that was hosting left the roster, so this peer is now the elected host — the seam is healthy and the other members are still parked on it. **The recovery is `start()` on the SAME lobby.** Re-running `electLobby(...)` weaves a *fresh* seam and strands them; `leave()` first closes the shared seam and collapses them (#1483).

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#handleEveryElectionOutcomeSample -->
```kotlin
public suspend fun handleEveryElectionOutcomeSample(lobby: ElectionLobby): Room? =
    when (val outcome = lobby.awaitRoom(memberName = "Player 2")) {
        is ElectionOutcome.Adopted -> outcome.room
        // The hosting peer left and this peer is now the elected host, with the co-members still
        // parked in their own awaitRoom on the SAME seam — so they ack this freeze round at once.
        // Re-running electLobby(...) would weave a FRESH seam and strand them; calling leave() first
        // would close the shared seam and collapse them.
        ElectionOutcome.BecameHost -> lobby.start(memberName = "Player 2")
        // A genuine mid-2PC collapse: the co-electors are gone. Retryable — re-run electLobby(...).
        is ElectionOutcome.Torn -> null
    }
```

`awaitRoom` suspends indefinitely while the lobby is simply empty or still weaving in — that is a lobby doing its job, not a collapse, and it is why `host == selfId` on its own does **not** mean you were promoted (during weave-in you are momentarily the lowest id you can see). Cancel the call to stop waiting.

## Consensus & turns

**Intent:** a turn-based session where actions are proposed and become authoritative (or rejected), with a leader/host and a term — "propose", "authoritative", "host elected".
**Primitive:** `GameSession` + `TurnSequencer` (`:kuilt-game`) over `:kuilt-raft`. If you're building a `propose() → Proposed/Authoritative/Rejected` facade with a `HostElected(term)`, you're rebuilding this.

<!-- verbatim from kuilt-game/src/commonSamples/kotlin/us/tractat/kuilt/game/GameSamples.kt#sampleGameHostJoin -->
```kotlin
internal fun sampleGameHostJoin() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
    val loom = InMemoryLoom()
    val hostSeam = loom.host(Pattern("tic-tac-toe"))
    val joinSeam = loom.join(InMemoryTag("player-2"))

    // Launch concurrently: gameHost suspends while admitting joiners;
    // gameJoin suspends until the host promotes it to voter.
    val hostDeferred = async {
        backgroundScope.gameHost(
            hostSeam,
            peerCount = 2,
            raftConfig = RaftConfig(expectVirtualTime = true),
            // clock is required (no wall-clock default); production callers pass the system clock.
            clock = { Clock.System.now() },
        )
    }
    val joinDeferred = async {
        backgroundScope.gameJoin(
            joinSeam,
            raftConfig = RaftConfig(expectVirtualTime = true),
        )
    }

    val host = hostDeferred.await()
    val joiner = joinDeferred.await()

    // Both nodes are voters. propose() may be called on any node —
    // followers forward to the leader transparently.
    val hostGame = TurnSequencer(host.node, Int.serializer())
    val joinerGame = TurnSequencer(joiner.node, Int.serializer())

    val move = hostGame.propose(1)
    assertEquals(1, move.action)

    // Any node may propose; the joiner's call is forwarded to the host (leader).
    val joinerMove = joinerGame.propose(2)
    assertEquals(2, joinerMove.action)

    // Ride an application channel (chat, cursors, …) over the same fabric as consensus.
    // Subscribe before the sender broadcasts: delivery is best-effort (`replay = 0`), so a
    // frame sent while nobody is collecting is dropped and this receiver waits forever (#2289).
    val incoming = async { joiner.appChannel("chat").incoming.first() }
    runCurrent()
    host.appChannel("chat").broadcast(byteArrayOf(0x68, 0x69)) // "hi"
    assertEquals(2, incoming.await().payloadSize)

    // Collect committed turns on any node in the game loop:
    // scope.launch {
    //     joinerGame.events.collect { event ->
    //         when (event) {
    //             is TurnEvent.Committed -> applyMove(event.indexed.index, event.indexed.action)
    //             is TurnEvent.Reset -> resetStateMachine(event.snapshot)
    //         }
    //     }
    // }

    // Tear the session down when done (stops the node, then closes the fabric).
    host.close()
    joiner.close()
}
```

## When one of the peers is a server

Sometimes the peers are not symmetric. A handful of machines hold the authoritative record
and a much larger number of clients connect in, ask for something to be written, and read
back what was agreed — and a client whose machine goes away has to come back on another one
without losing its place in the queue.

**Intent:** exactly that shape — a small core of servers that agree among themselves, many
clients attached to the edge, proposals forwarded to whichever server is currently in charge.
**Primitive:** `ClusterClient` on the client side (`:kuilt-cluster`, every target) and
`ServerCluster` on the server side (JVM/Android). Don't hand-roll the forwarding hop, the
endpoint rotation, or the "which server is the leader now" bookkeeping.

Client side: `CoroutineScope.clusterClient(loom, clusterEndpoints, clientNodeId, clusterConfig, raftConfig, clock)`
owns the whole connect → use → reconnect lifecycle, rotating through `ClusterEndpoints` on a tear and
swapping the transport underneath one long-lived node rather than rebuilding it.
`clusterClientWithNode(raftNode)` is the plainer entry point when you manage the transport yourself.

<!-- verbatim from kuilt-cluster/src/commonSamples/kotlin/us/tractat/kuilt/cluster/samples/ClusterClientSample.kt#connectAndPropose -->
```kotlin
val client: ClusterClient = clusterClientWithNode(fakeNode)

// Propose with an auto-minted requestId — at-least-once but survives failover.
val entry = client.propose("set x=1".encodeToByteArray())

// Propose with a caller-pinned requestId for cross-crash exactly-once semantics.
val dedupEntry = client.propose("set y=2".encodeToByteArray(), requestId = 42L)

// Collect the committed stream and apply through ClientSessionTable for dedup.
val table = ClientSessionTable()
val committed = client.committed
    .filterIsInstance<Committed.Entry>()
    .first { table.shouldApply(it.entry.dedupKey) }
```

**Ask for exactly-once or you get at-least-once.** The one-argument `propose(command)` mints a fresh
request id per call, which survives a *failover* but not a *crash*: after a restart the retry looks
like a brand-new command and can apply twice. `propose(command, requestId)` with an id you persisted
**before** calling is the cross-crash form — the server's `ClientSessionTable` recognises the replay.
Pair it with `ClientIdentity.Durable(clientId)`, because the identity that table keys on has to
outlive the restart too; the default `ClientIdentity.Auto` mints a new one per incarnation, which is
right only where at-least-once genuinely is.

Server side: `CoroutineScope.serverCluster(host, voterIds, raftConfig)` stands the voter mesh up and
mounts a `RoomHost` — a `KtorRoomHost` on a WebSocket path, in practice — as the relay clients attach
to. `start()` runs the accept loop (launch it; it suspends until the scope is cancelled), `committed`
is the stream of agreed entries to apply, and `awaitLeader()` waits for the mesh to elect one.
`runRelay(anotherHost)` mounts a second endpoint onto the *same* mesh, which is the server half of
cross-relay failover: cancelling one relay's coroutine tears just that endpoint's rooms, and its
clients reattach elsewhere with the same node id and the same log position.

Two boundaries worth designing around rather than discovering. **A cross-server reconnect is always a
fresh join** — each server's reconnect-window registry is in-memory and per-room, so a token issued by
one server can never validate at another. `clusterClient` therefore does not even attempt an
optimistic resume: every reconnect is a plain `join`, and the cost is a re-snapshot of the client's
log rather than a lost session. And `committed` keeps `RaftNode.committed`'s
single-collection contract: collect it once per client, `shareIn` for fan-out.

## Dealing cards nobody can peek at

You want to deal a hand where nobody — not even whoever is running the app — can see a card
they were not dealt, and nobody can arrange which card they get. There is no trusted dealer
and no server holding the deck. The obvious thing, shuffling an array on one device and
sending each player their slice, asks every player to trust that device completely.

**Intent:** a fair deal with no dealer.
**Primitive:** `DealSession` (`:kuilt-deal`) over any `Seam`. Every player encrypts the whole deck
with their own key; because the cipher **commutes**, those layers peel off in any order, so a card is
readable by exactly the players who still hold an unstripped layer of it. `assignQuorums` says who
those players are, per card; `strip()` removes the layers that are not protecting anybody's secrecy;
`decrypt(index)` reads a card once it is `CardPhase.REVEALED`.

<!-- verbatim from kuilt-deal-test/src/commonTest/kotlin/us/tractat/kuilt/deal/test/DealSessionTest.kt#twoPlayerPokerDeal_aliceSeesHerCard_bobCannotRead -->
```kotlin
val alice = PeerId("alice")
val bob = PeerId("bob")
val (aliceSession, bobSession) =
    fakeDealSessionPair(alice, bob, seededXorSchemes(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

val originalCard = "ACE_OF_SPADES".encodeToByteArray()
val deck = listOf(originalCard)

// Shuffle: both players encrypt the deck (alice first, then bob builds on it)
aliceSession.shuffle(deck)
bobSession.shuffle(deck)

// Deal: alice's hand — only alice can see card 0
val quorumAlice = mapOf(0 to setOf(alice))
aliceSession.assignQuorums(quorumAlice)
bobSession.assignQuorums(quorumAlice)

// Reveal: non-quorum players (bob) strip their layers
bobSession.strip()

// Alice decrypts her own layer
val revealed = aliceSession.decrypt(0)
assertEquals(originalCard.toList(), revealed.toList())

// Secrecy: bob is not in the quorum — he cannot recover the plaintext.
val bobAttempt = runCatchingCancellable { bobSession.decrypt(0) }.getOrNull()
assertNotEquals(originalCard.toList(), bobAttempt?.toList())
```

That test drives the session with a fast XOR stand-in for the cipher; in production the
`CommutativeScheme` is `SraScheme()`. Pass a **factory**, not one instance — every player mints their
own key in their own process, and a harness that hands one object to both sides is testing the single
arrangement that cannot expose a cross-instance disagreement. For your own tests,
`fakeDealSessionPair` / `fakeDealSessionGroup` (`:kuilt-deal-test`) wire a group of sessions over fake
seams, and `CommutativeSchemeConformanceSuite` is how a scheme of your own earns the round-trip,
commutativity and strip-order-independence properties the whole deal rests on.

Three things to know before building on it. **Assign every quorum on every peer before any peer
strips** — quorum membership is what decides whether a strip is accepted, so one that arrives before
the local assignment is dropped and never retried. **A partial quorum takes more than one pass**:
where three or more players share sight of one card, members strip each other's reveal tracks in a
canonical order, so call `strip()` again as remote ops arrive until the card reads `REVEALED`. And a
card *index* is not a secret — the deal hides values; which slot went to whom is your protocol's
business.

### Nobody chose that number

**Intent:** a shared random value — a seed, a first player, a die roll — that every peer agrees on and
no peer could steer. You are about to have one peer pick it and broadcast it.
**Primitive:** `FairRandom(seam, peers).roll()` (`:kuilt-deal`). Two phases: everyone publishes a hash
of their secret, then the secret itself, and the seed is derived from all of them — so one honest
contributor is enough to make the result unpredictable to everybody, including that contributor.

<!-- verbatim from kuilt-deal/src/commonTest/kotlin/us/tractat/kuilt/deal/FairRandomTest.kt#twoPeers_agreeOnIdenticalSeed -->
```kotlin
val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
val peers = setOf(alice, bob)
val (aliceSeam, bobSeam) = fakeSeamPair(alice, bob)

val aliceDef = scope.async { FairRandom(aliceSeam, peers).roll() }
val bobDef = scope.async { FairRandom(bobSeam, peers).roll() }

val aliceSeed = aliceDef.await()
val bobSeed = bobDef.await()

assertEquals(aliceSeed, bobSeed, "Both peers must derive the same seed")
```

`roll()` is one round — build a fresh `FairRandom` per roll. It **throws rather than hangs** when the
seam tears, or when a required participant leaves the live peer set mid-round: the missing
commit or reveal is never coming, so it raises `SeamCollapsedException` within a bound and you need no
outer timeout of your own. The one thing it cannot defend against is stated plainly in its own KDoc.
A last mover sees every other reveal before deciding whether to send its own, so **withholding is a
game-layer concern** — forfeit the peer that goes quiet; commit-reveal cannot.

## Fair share & placement

When several groups draw from one shared pool — computing time, task slots, a rate
budget — and you want each to get the slice it was promised (say three parts to one),
a spare-capacity lender when someone is idle, and all of it holding up while the
network is flaky with **no central referee**, that is `:kuilt-heddle`. Don't hand-roll
a weighted round-robin, a quota counter, or a "reserve then charge" bookkeeper — the
pieces below already converge across partitioned peers with no coordination on the
spend path.

### Who runs the next quantum (weighted fair share)

**Intent:** decide which of several competing children gets the next slice of a shared
budget, weighted (3:1) and hoarder-proof — "who runs next", a weighted scheduler, EEVDF.
**Primitive:** `HeddlePolicy.pick(edges, config, localHoldings)` (`:kuilt-heddle`) — a
**pure** function: no wall clock, no randomness, no floating point, so every replica picks
the same winner. It serves the eligible child (not running ahead of its fair share) whose
next grant finishes soonest in virtual time. Returns `null` when nobody is both eligible
and demanding.

<!-- verbatim from kuilt-heddle/src/commonSamples/kotlin/us/tractat/kuilt/heddle/EntitlementLedgerSamples.kt#samplePolicyPick -->
```kotlin
    // Both start level (no service yet); the heavier-weighted child has the earliest
    // virtual deadline, so it is served first.
    val grant = HeddlePolicy.pick(
        edges = listOf(edge("heavy", Weight.of(3), issued = 0L), edge("light", Weight.of(1), issued = 0L)),
        config = PolicyConfig(quantum = 6L),
        localHoldings = 1_000L,
    )
    check(grant == Grant(AttachmentId("heavy"), 6L))
```

### Run it over a network — reserve, run, charge once

**Intent:** put the fair-share tally on a live connection between peers — advertise appetite,
allocate entitlement down a tree, then reserve a slot before running work and charge it
exactly once on completion (even if `complete` is called twice).
**Primitive:** `HeddleNode` via `heddleStatic(...)` (`:kuilt-heddle`) — hands you the tally,
the demand board, reservations, and liveness over a `Seam` from a fixed roster. Every peer
that bootstraps with the same root, mint, and topology begins from an identical ledger and
stays in step over the wire on its own.

<!-- verbatim from kuilt-heddle/src/commonSamples/kotlin/us/tractat/kuilt/heddle/EntitlementLedgerSamples.kt#sampleHeddleNode -->
```kotlin
    // The leaf wants work; one scheduling round delegates entitlement down toward it.
    node.advertise(e.id, Demand(targetOutstanding = 100L, maximumUsefulGrant = 100L))
    node.schedule(root)

    // Leaf work reserves a slice, runs, then completes — completing twice charges once.
    val reservation = node.reserve(leaf, maximumCost = 10L)
    if (reservation != null) {
        node.complete(reservation, actualCost = 7L)
        node.complete(reservation, actualCost = 7L) // idempotent no-op
    }
```

### Creating quota or reshaping the tree at runtime (with agreement)

**Intent:** mint new entitlement or re-parent a group *while the system runs*, and need
everyone to agree on the order so a split-brain can't double-mint and two overlapping
reshapes don't corrupt the tree.
**Primitive:** `heddleGoverned(...)` → `GovernedHeddleNode` (`:kuilt-heddle`) — the same data
plane as `heddleStatic`, but `mint`/`prepare`/`activate`/`close`/`retire` are serialized
through `:kuilt-raft`; each returns a `ControlOutcome` (`Applied`, or `Conflict` with the
structured reason when it loses a race). If a gossip-lagged peer's `retire` races a delegate
and strands budget on a since-reparented child, `reconcile(child)` re-homes it — net inflow
*and* any service already charged through it — onto the child's live lineage through the log,
conservingly (mints nothing), clearing the resulting
`PersistentNegativeHoldings`/`PerEdgeSafety`/`ClosureViolation`. **It sends no magnitudes:** it
opens a `quiesce(edge)` barrier over each retired inbound edge, every peer promises never to
write that edge again and acks its own final values, and the move is *derived at apply time*
from those recorded promises — so a lagged or deposed proposer cannot commit a wrong amount.
Expect to call it twice: the acks are separate committed acts, so the first call is usually
refused naming the peers it waits on (`pendingAcks(edge)` reads that set). A **transfer-tangled
strand is re-homed with its hand-offs**, not refused: the three terms of a pocket — net inflow,
already-charged service, and the transfer rows — travel together, so a recipient who merely holds
handed-off credit keeps it across the move. It still refuses when the arithmetic or the fence says
so: a replica net-negative on the strand, fenced edges that together cannot cover what was charged
through them, a cross-parent re-home, or a carried hand-off whose donor is no longer on the roster
to ack it. And it **blocks while any enrolled peer is down** — that peer is
exactly the one that may hold an unreplicated reservation, so the wait is the safety property,
not a bug. `enroll(replica)`/`depart()` keep the **agreed participant list** the barrier
quantifies over (`enrolledReplicas()` reads it back); only a peer may depart itself, and
**`enroll(self)` is what opens a node's write gate** — until it applies, `reserve` returns
`null` and `schedule` delegates nothing (`isWritable`). The spend path
(`schedule`/`reserve`/`complete`) never touches the log.

<!-- verbatim from kuilt-heddle/src/commonSamples/kotlin/us/tractat/kuilt/heddle/EntitlementLedgerSamples.kt#sampleHeddleGoverned -->
```kotlin
    // Enrolling self is what opens this node's write gate: until it applies, `reserve` returns null
    // and `schedule` delegates nothing, so an unenrolled peer can never author entitlement (#1693).
    check(node.enroll(self) is ControlOutcome.Applied)

    // Mint and reshape are serialized through the Raft log — each returns a structured outcome.
    check(node.mint(self, 100L) is ControlOutcome.Applied)
    node.prepare(AttachmentRecord(edge, root, leaf, Weight.ONE))
    node.activate(edge)

    // The spend path is coordination-free — it issues no consensus messages.
    node.advertise(edge, Demand(targetOutstanding = 100L, maximumUsefulGrant = 100L))
    node.schedule(root)
    node.reserve(leaf, maximumCost = 10L)?.let { node.complete(it, actualCost = 7L) }
```

### Weighted lanes over a warp workload

**Intent:** you already run tasks across peers with `:kuilt-warp` and want to say
"interactive work gets 3× the pool that batch work does" — without changing how warp picks
who runs what.
**Primitive:** `HeddleAdmissionControl(heddle)` plugged into `WarpNode`'s admission gate,
plus `TaskDescriptor.inLane("...")` on the producer side (`:kuilt-warp-heddle`). A lane maps
to a fair-share leaf; the task reserves that leaf's entitlement before it runs and is charged
on completion. Out of entitlement ⇒ the task **defers** (never dropped). An untagged task
rides the root lane and is admitted for free — warp's fast path stays exactly as cheap.

<!-- verbatim from kuilt-warp-heddle/src/commonSamples/kotlin/us/tractat/kuilt/warp/heddle/WarpHeddleSamples.kt#sampleHeddleAdmissionControl -->
```kotlin
    // 1. Build the adapter — warp's opaque AdmissionControl, backed by the fair-share ledger.
    val admission = HeddleAdmissionControl(heddle)
    // Pass it to a node:  WarpNode(selfId, seam, roster, scope, clock = …, registry = …,
    //                              admissionControl = admission, epoch = <per-boot counter>)

    // 2. Tag a task into a lane on the producer side.
    val interactive: TaskDescriptor =
        TaskDescriptor(op = OpId("score"), args = "doc-1".encodeToByteArray())
            .inLane("acme/interactive")
    check(interactive.lane == Lane("acme/interactive"))

    // 3. An untagged task rides the default root lane and is admitted un-gated.
    val untagged = TaskDescriptor(op = OpId("score"), args = ByteArray(0))
    check(untagged.lane == Lane.ROOT)
    check(admission.admit(untagged) === AdmissionTicket.NOOP)
```

### Where a task may run (location eligibility)

**Intent:** *where* a task is allowed to run — "needs a GPU", "must stay in us-east", "sit
where the data is". This is orthogonal to the *how much* a lane answers: eligibility
introduces no budget and never touches the ledger.
**Primitive:** `TaskDescriptor.where(affinity)` with an `Affinity` predicate over the `CapSet`
tokens a peer advertises (`:kuilt-warp`). The predicate is a serializable value (`has`/`attr`
combined with `and`/`or`/`not`), not a lambda — it rides the wire, and placement hashes over
only the eligible peers. `Affinity.Anywhere` (the default) requires nothing. Composes with
`inLane(...)`: a task may carry both.

<!-- verbatim from kuilt-warp/src/commonSamples/kotlin/us/tractat/kuilt/warp/WarpSamples.kt#sampleAffinity -->
```kotlin
    // "must run on a GPU node in us-east" — a composable predicate, not a lambda (it rides the wire).
    val where = Affinity.has("GPU") and Affinity.attr("region", "us-east")

    val gpuUsEast = CapSet(tokens = setOf("GPU"), attributes = mapOf("region" to "us-east"))
    val cpuUsWest = CapSet(tokens = setOf("CPU"), attributes = mapOf("region" to "us-west"))
    check(where.matches(gpuUsEast))       // eligible
    check(!where.matches(cpuUsWest))      // not eligible
    check(Affinity.Anywhere.matches(cpuUsWest)) // the default requires nothing

    // Tag a task with the requirement; placement then hashes over only the eligible peers.
    val task = TaskDescriptor(OpId("train"), byteArrayOf(1, 2, 3)).where(where)
    check(task.affinity == where)
```

## Code mobility

**Intent:** the peer that should do the work doesn't have the code. You want to ship it there,
cache it, and run it — without shipping a whole new build, and without trusting whatever arrives.

**Primitive:** `Creel` + `BobbinExchange` for the bytes, `WasmRuntime` + `WarpLazyFetch` for
running them (`:kuilt-warp`; the engines are in `:kuilt-warp-runtime`). Don't hand-roll a blob
cache, a "who has these bytes" protocol, or a sandbox.

- **`Creel`** is the local cache, keyed by the SHA-256 of the bytes. Content addressing means
  merge is free (same key ⇒ same bytes, no conflict possible) and `putVerified` re-hashes
  anything that came off the wire before trusting it. `get` returning `null` is the ordinary
  "not fetched yet" state, not an error.
- **`BobbinExchange`** gossips a manifest of *which* kernels exist eagerly and fetches the
  *bytes* on demand — concurrent callers of the same hash share one in-flight request, and a
  re-request loop reaches a peer that only later acquires the bytes.
- **`WarpLazyFetch`** is the capability bundle you hand a `WarpNode` so an unknown `OpId`
  resolves at execution time: fetch, load under the sandbox, run, cache. A fetch that times out
  is **transient** — the task stands by and is retried; only a kernel that is broken or hostile
  fails terminally.
- **`WasmRuntime`** is the sandbox contract, and it is strict on purpose because kernels come
  from peers you don't control: a module declaring any import is rejected, it must declare a
  bounded memory maximum within `WasmSandboxConfig.maxMemoryPages`, and every invocation is cut
  off at `WasmSandboxConfig.executionTimeout`. `ChicoryWasmRuntime` (JVM), `Wasm3WasmRuntime`
  (iOS/macOS) and `BrowserWasmRuntime` (wasmJs) all pass the same `WasmRuntimeConformanceSuite`.

<!-- verbatim from kuilt-warp/src/commonSamples/kotlin/us/tractat/kuilt/warp/WarpSamples.kt#sampleLazyFetch -->
```kotlin
    val creel = Creel()

    // Storing bytes yields their content address; storing them again is a no-op.
    val kernel = byteArrayOf(0x00, 0x61, 0x73, 0x6d)
    val hash: BobbinHash = creel.put(kernel)
    check(creel.put(kernel) == hash)

    // Bytes that arrived from a neighbour are re-hashed before being cached — a mismatch throws.
    creel.putVerified(hash, kernel)
    check(creel.contains(hash))
    check(hash in creel.loaded)          // the fragment this peer can serve to neighbours

    // A miss is the legitimate "not fetched yet" state, not an error.
    check(creel.get(BobbinHash("deadbeef")) == null)

    // The capability bundle a WarpNode needs to run an op it has never seen.
    val lazyFetch = WarpLazyFetch(
        creel = creel,
        runtime = runtime,
        opToBobbin = { op -> if (op == OpId("reverse")) hash else null },
    )
    check(lazyFetch.opToBobbin(OpId("reverse")) == hash)
    check(lazyFetch.opToBobbin(OpId("unknown")) == null) // nothing to fetch — the task stands by
```

## Sending to yourself

**Intent:** loop a frame back to your own peer — replay your own move locally, feed your own replicator, treat "everyone" uniformly by iterating `peers` and sending to each.
**Primitive:** `Seam.broadcast` / `Room.broadcast`. **Not** `sendTo(selfId, …)`, which throws `IllegalArgumentException` on every fabric.

`broadcast` is the loop-back surface; `sendTo` names *another* peer. The refusal is deliberately not `PeerNotConnected` — `selfId` **is** in `peers`, always, so reporting the peer as absent would state something false and push a caller into reconnecting over what is really a bug in its own addressing.

The trap is the uniform loop. `peers` includes you, so `peers.value.forEach { seam.sendTo(it, frame) }` sends to yourself on the first or last iteration, and before #2428 what happened next depended on which fabric you were on — a 2-peer link delivered the frame to the *other* peer and reported success. Filter, or broadcast:

```kotlin
// Wrong: `peers` includes selfId, so this self-sends.
seam.peers.value.forEach { seam.sendTo(it, frame) }

// Either filter…
seam.peers.value.filter { it != seam.selfId }.forEach { seam.sendTo(it, frame) }
// …or just broadcast, which is what "everyone else" means at this layer.
seam.broadcast(frame)
```

Order matters if you are catching: the `Torn` check runs first, so a self-send on a closed seam is an `IllegalStateException`, not this. Pinned for every fabric by `SeamConformanceSuite.sendToSelfIsRefused`.

## Payload limits

**Intent:** pick a chunk size for a big payload — or explain a `FrameTooLargeException` that appears only after somebody drops out.
**Primitive:** `Room.maxPayloadBytes` (`:kuilt-session`), and `Seam.maxPayloadBytes` (`:kuilt-core`) one layer down. Size to that, not to the fabric's frame limit.

`null` means **unknown, not unbounded** — the honest answer from a fabric that names no ceiling. A non-null value is a promise: a payload that size or smaller will not be refused for being too big, *whatever route the frame takes*.

That last clause is the whole point. On a star, a spoke's frame reaches only the host, so once the roster diverges from what the transport can address the payload is wrapped in a relay envelope and forwarded — and the wrapper costs bytes. The budget holds them back **unconditionally**, even while no relay is in use, because routing flips the instant a member enters its reconnect window: a limit that moved with the route would be a trap for a caller that checked it and then sent. A `room.channel(id)` view reports a further-reduced budget, since its own framing costs bytes too.

The number is a **promise, not the refusal threshold** — refusal is measured on the frame that actually goes on the wire, so a payload above the budget that still fits (a direct send, where no envelope is applied) is delivered rather than rejected. Size to the budget anyway: it is the only number that holds whichever route the frame takes.

It is also **a reading, not a lease.** Route-independent is not the same as time-independent: a mesh reports the minimum across its live links, so a peer attaching over a tighter transport lowers the number under you. Re-read it per send rather than once per batch.

When a frame genuinely will not fit, each call keeps its own contract: `sendTo` raises `PayloadTooLarge` (addressed sends report), `broadcast` drops it with a debug log (it is lossy-without-error by contract). Neither lets the fabric's own oversize error out.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#chunkToTheRoomsBudgetSample -->
```kotlin
var start = 0
while (start < blob.size) {
    // Re-read per chunk, not once for the loop: the budget is a reading, not a lease. On a mesh
    // it is the minimum across live links, so a peer attaching over a tighter transport lowers
    // it under you mid-blob. null means "this fabric names no ceiling" — unknown, not
    // unbounded; floored at 1 because the budget is legitimately 0 on a fabric whose ceiling is
    // under the relay reservation.
    val budget = (room.maxPayloadBytes ?: DEFAULT_CHUNK_BYTES).coerceAtLeast(1)
    val end = minOf(start + budget, blob.size)
    // Index arithmetic, not `asSequence().chunked()` — the latter boxes every byte and builds
    // an ArrayList<Byte> per chunk. On a blob big enough to need chunking that is the point.
    // Past the budget, sendTo reports PayloadTooLarge (addressed sends do) while broadcast
    // drops with a log (lossy by contract) — neither surfaces the fabric's own oversize error.
    room.sendTo(peer, blob.copyOfRange(start, end))
    start = end
}
```

## Long-lived pumps

**Intent:** collect a flow for the life of a session — a peer's state, a roster, an inbound frame stream — without one throw ending the *collector* rather than the item. Don't write `flow.onEach { try { … } catch (…) { … } }.launchIn(scope)`.
**Primitive:** `Flow.pumpIn(scope, onFailure, name) { … }` (`:kuilt-core`).

There are **two** ways such a collector dies and a hand-written `try` only covers one. `onEach { … }.launchIn(scope)` desugars to `scope.launch { flow.onEach { … }.collect() }`, so your `try` sits *inside* the collector: it sees what the body throws and never sees a throw raised by the **flow itself**, which ends the flow and escapes the `launch` entirely. On Kotlin/Native that escape is not a dead coroutine, it is a dead **process** — an unhandled coroutine exception reaches the runtime's default handler and aborts, and a `SupervisorJob` is the mechanism rather than the protection. `pumpIn` is one call owning both halves for exactly that reason, and `PumpFailure.ITEM` / `PumpFailure.UPSTREAM` tells your handler whether the pump survived.

It also settles the cancellation question you would otherwise get wrong: `runCatchingCancellable` discriminates on *type*, which cannot tell your own cancellation from a `CancellationException` a callee minted (a consumer's `withTimeout` inside `sendTo`). Rethrown from a pump, that one **cancels it silently** — no report, no stack trace. `pumpIn` uses `currentCoroutineContext().ensureActive()`, which decides it at runtime; cancelling your scope still cancels the pump.

`name` is **required**, and it is not decoration. `launchIn` keeps the `onEach` lambda out of the suspended continuation chain, so every pump of this shape parks at the same frame with no library frame in its stack at all — a coroutine dump renders your whole pump set as one indistinguishable blob, which looks the same whether they are healthy or one of them is wedged. `pumpIn` attaches the name as a `CoroutineName` on the launch, so anything reading `CoroutineInfo.context` can attribute a parked pump to the pump it belongs to. Make it distinct **per pump instance**, not per call site: where several pumps of one kind run side by side, qualify it (`"room-peers[$roomId]"`) so a census can group by kind and still name the instance.

<!-- verbatim from kuilt-core/src/commonSamples/kotlin/us/tractat/kuilt/core/PumpInSamples.kt#samplePumpIn -->
```kotlin
// A consumer-authored flow that hands over one item the body cannot apply, and then fails outright.
val updates = flow {
    emit("apply-me")
    emit("i-will-not-apply")
    error("…and then the flow itself gave up")
}

val pump = updates.pumpIn(
    scope = backgroundScope,
    // ITEM: that update was lost, the pump lives. UPSTREAM: the pump is over — say so, loudly.
    onFailure = { half, _ -> reported += half },
    // What a coroutine census calls this pump when it is the one that wedged.
    name = "sample-updates",
) { update ->
    if (update == "i-will-not-apply") error("this update could not be applied")
    applied += update
}
pump.join()
```

## A seam's terminal state

**Intent:** publish your fabric's `SeamState` when two different threads write it — a transport callback promoting `Weaving → Woven`, and `close()` latching the terminal `Torn`.
**Primitive:** `SeamStateGate` (`:kuilt-core`).

A `Seam` has **two** state writers and they are genuinely concurrent: the promotion runs on whatever thread your transport calls back on (a JNA trampoline, MC's private delegate queue, a socket reader), while `close()` runs on the consumer's. So this, which is what everyone writes, is a **check-then-set**:

```kotlin
if (_state.value is SeamState.Weaving) _state.value = SeamState.Woven   // ← read and write are not atomic
```

A tear landing between that read and that write is stamped over with `Woven`. It is **permanent**, not transient: the spool is closed, `incoming` has completed, `peers` has collapsed, and both writers have retired, so no later emission can correct it. Every consumer on `state.first { it is Torn }` hangs forever, and a factory that frees its seam slot on `Torn` never frees — so no later `weave()` succeeds either.

**A `closed` flag does not fix it.** The flag read and the flow write are still two steps, so a callback can read `closed == false`, be preempted by a complete `close()`, and resume into the same clobber. Check-a-flag-then-write *is* the race. This is not hypothetical: four fabrics hand-rolled a latch while this type was `internal`, and three wrote precisely that shape (#1803).

`update` no-ops once `tear` has latched, and `tear` returns `true` for exactly one caller — so it **replaces** your single-shot atomic rather than sitting beside it. Publish `Torn` through `tear`, never `update`: `update` refuses it, because a `Torn` published without latching is the same bug through the front door.

<!-- verbatim from kuilt-core/src/commonSamples/kotlin/us/tractat/kuilt/core/SeamStateGateSamples.kt#sampleSeamStateGate -->
```kotlin
val gate = SeamStateGate(SeamState.Weaving)

// The transport callback's promotion. Unconditional: `Woven` over `Woven` conflates, and once
// the gate has latched it cannot land at all — so no `if (state.value is Weaving)` guard, which
// was never a promotion rule but the read half of a race.
gate.update(SeamState.Woven)
assertIs<SeamState.Woven>(gate.state.value)

// The close decision. Single-shot: `true` for the one winning caller, so this IS the seam's
// terminal latch and it needs no separate `closed` atomic beside it.
assertTrue(gate.tear(CloseReason.Normal))
assertEquals(false, gate.tear(CloseReason.RemoteRequested), "a second tear loses; the first reason stands")

// The whole point: a promotion still in flight when the tear landed. Before the gate this write
// stamped `Woven` over the terminal `Torn` — permanently, because both writers then retire and
// every `state.first { it is Torn }` waiter hangs forever.
gate.update(SeamState.Woven)
assertIs<SeamState.Torn>(gate.state.value)
```

**Two shapes genuinely do not need it**, and churning them onto the gate buys nothing: one shared mutual-exclusion primitive covering *every* write to the flow (`NwSeam` takes all three under its own lock), and a single-threaded target, where nothing can run between the read and the write (`WebRTCPeerLink` on wasmJs, which documents exactly that).

## Dedup

**Intent:** skip a message/id you've already handled ("seenIds", "skip-if-exists").
**Primitive:** `GSet` (`:kuilt-crdt`) for a converging grow-only set, or kuilt's dedup key where you're inside a replicated log. Don't keep an ad-hoc `mutableSetOf<Long>()`.

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleGSet -->
```kotlin
var set = GSet.empty<String>()
set = set.piece(set.add("alice"))
set = set.piece(set.add("bob"))
check(set.elements == setOf("alice", "bob"))
```

## Durable storage

**Intent:** keep a blob of bytes under a name so it is still there after a restart or a crash — "save this to disk", "persist it across launches", "write it somewhere it won't be lost". Don't hand-roll a write-temp-then-`fsync`-then-atomic-rename dance, an `expect`/`actual` file helper per platform, or an IndexedDB wrapper.
**Primitive:** `DurableStore` (`:kuilt-store`) — `read` / `write` / `delete` under a `StoreKey`. `InMemoryDurableStore` in tests; `FileChannelDurableStore` (JVM/Android), `NSFileManagerDurableStore` (iOS/macOS) and `IndexedDbDurableStore` (wasmJs) in production.

The contract is about *timing*, not just storage: **`write` returns only once the bytes are committed**, so the caller can report its own success at that moment rather than holding everything open until something downstream confirms. That is what makes an offline-first exporter possible — and each implementation's KDoc names the exact instant it treats as the commit, plus where its guarantee stops (the Apple store does not force before its rename, so power loss can differ from process death — #2141).

It is deliberately small: no iteration, no query, no transaction across two keys, no opinion about what the bytes mean. If you want history you can replay and forget, that is `Bolt` (`:kuilt-bolt`); if you want state that merges across peers, that is the CRDT zoo (`:kuilt-crdt`).

<!-- verbatim from kuilt-store/src/commonSamples/kotlin/us/tractat/kuilt/store/Samples.kt#sampleDurableStore -->
```kotlin
// Every platform has its own crash-safe implementation; a test uses the in-memory one.
val store: DurableStore = InMemoryDurableStore()
val key = StoreKey("draft")

// `write` returns only once the bytes are committed — that is the whole contract.
store.write(key, byteArrayOf(1, 2, 3))

// A later session (a fresh store over the same backing directory or database)
// reads back exactly what was committed; an unwritten key reads back null.
val recovered: ByteArray? = store.read(key)
check(recovered.contentEquals(byteArrayOf(1, 2, 3)))
check(store.read(StoreKey("never-written")) == null)

store.delete(key)
check(store.read(key) == null)
```

## Telemetry & log capture

**Intent:** keep an app's own log lines on the device without the logging path costing real time — "capturing logs is slow", "the app stalls when it logs a lot", "logging is slowing us down on a phone".
**Primitive:** `installLogCapture` (`:kuilt-otel-logging`) for the whole path, and `WarpLogRecordExporter.export(records)` (`:kuilt-otel`) when you hold the records yourself. Don't write a flush-per-line loop.

`export(records)` applies a whole run as **one write turn** — one CRDT append pass, one CBOR encode of the active segment, one segment write — instead of paying that fixed cost once per record. `installLogCapture` already drains into it that way, so a consumer gets the amortisation without doing anything; reach for the bulk overload directly only when you hold the records yourself.

Nothing is held back waiting for a batch to form, so durability is unchanged: `export` returns after its own durable write, exactly as the single-record overload does. A batch is only ever what was *already* queued — which is why one forms when the producer is outrunning the drain, and never on an idle app.

Two things stay per-record: a duplicate `LogRecord.recordId` is still skipped, and the buffer cap is still enforced one record at a time. And a run too large for one segment is split across turns, so a `Failure` means "stop", not "none of it landed" — earlier records in the run may already be durable.

<!-- verbatim from kuilt-otel/src/commonSamples/kotlin/us/tractat/kuilt/otel/Samples.kt#sampleBulkExport -->
```kotlin
val pending: List<LogRecord> = drainedFromSomeQueue()
when (val result = exporter.export(pending)) {
    ExportResult.Success -> Unit // every record in the run is now durable
    is ExportResult.Failure -> {
        // The store refused. Earlier records in the run may already be durable — a run
        // too large for one segment is split across turns — so this is "stop", not
        // "none of it landed".
        println("export failed: ${result.cause}")
    }
}
```

**Intent:** stamp which session / game / request / screen a log line belongs to — an MDC equivalent, a "logger context". Don't keep a mutable global holding "the current session" for a `CaptureConfig.attributeMapper` to read.
**Primitive:** `withLogContext(attributes) { … }` (`:kuilt-otel-logging`).

`CaptureConfig.attributeMapper` is installed once on the whole **process's** capture edge, so it has one answer to "which session is this?". An app that runs two at a time — a server-mediated game alongside an offline mesh one — therefore stamps the second one's lines with the first one's id, and no downstream filter can tell: selecting on `session.id` hands back records that never belonged to it. Edge resolution does not help, and this is the distinction worth holding on to — resolving the mapper at the emit edge (#1630) fixes *when* it is asked, not *which* of the concurrent sessions it is able to see. Keep the mapper for facts that really are app-wide (device, build, logger name).

Precedence is one rule at every level, **narrower scope wins**: mapper < outer `withLogContext` < inner. Nesting merges rather than replaces, so a key only an outer scope set is inherited, and leaving an inner scope restores the outer binding. The direction is what makes it a fix — entering the emitting session's scope must *correct* a stale app-wide stamp, not lose to it. The consequence to know: a scope attribute also beats that key in the mapper's output, including one the mapper derived from the log call's own payload.

Reach is exactly `withActiveTrace`'s, and for the same reason — the capture edge is a non-`suspend` callback, so it reads an execution-local slot rather than the coroutine context. **The guarantee is not the same on every platform, and the weaker half is easy to over-trust.**

On **JVM/Android** a `ThreadContextElement` re-establishes that slot on every dispatch, so the binding survives thread hops, is inherited by child coroutines, and keeps two **interleaved** sessions apart. "Enclosing" there means the true structural parent, read from the coroutine context.

On **iOS/macOS/wasmJs** that primitive does not exist (coroutines 1.11.0), so the slot is set once on entry and the binding is reliable only for a line logged **synchronously within the block**. A scope that suspends and resumes while a sibling scope is mid-block on the same thread **reads the sibling's attributes** — a real mis-attribution, not a dropped stamp — and an app running concurrent sessions on `Dispatchers.Main` is exactly that shape. Relatedly, "merged over the enclosing binding" degrades to "merged over whatever the thread last set", so a sibling's keys can be inherited into this scope (this scope's own keys still win). Still a strict improvement on the process-global mapper, which is wrong for every line of every non-armed session — but an improvement, not a guarantee. Keep a session's logging synchronous within its block there; the gap is tracked in [#2569](https://github.com/tractat-us/kuilt/issues/2569).

<!-- verbatim from kuilt-otel-logging/src/commonSamples/kotlin/us/tractat/kuilt/otel/logging/Samples.kt#sampleWithLogContext -->
```kotlin
val log = KotlinLogging.logger("com.example.Session")

// This process runs two sessions at once. A CaptureConfig.attributeMapper is
// installed on the whole process, so it could only ever stamp whichever session
// is "current" — and would stamp the other session's lines with it too. Binding
// the id to the scope that emits makes it per-emitter instead.
withLogContext("session.id" to "server-game-42") {
    log.info { "dealt the opening hand" } // session.id = server-game-42
}

// Concurrently, on another scope, with its own binding. Neither borrows the
// other's id, however they interleave.
withLogContext("session.id" to "mesh-7") {
    // Nesting merges, and the inner scope wins a collision — narrower scope wins.
    withLogContext("turn" to "3") {
        log.info { "peer joined" } // session.id = mesh-7, turn = 3
    }
}

// Outside any scope, capture is exactly what it was before.
log.info { "background heartbeat" }
```

**Intent:** empty a telemetry store — "reset the logs", "clear my data", "start the next run clean". Don't delete the store's files per platform, and don't set a "clear on next launch" flag so the delete lands before recovery.
**Primitive:** `WarpTelemetry.clear()` (`:kuilt-otel`), or a single signal's own `clear()` on `WarpLogRecordExporter` / `WarpSpanExporter` / `WarpMetricExporter`.

It runs on a **live** instance — no restart — and the same instance keeps exporting straight afterwards; a later restart sees only what was written after the clear. That is the point: a `DurableStore` has no key-enumeration API, so a consumer holding one cannot discover the segment keys to delete them, which is what forced the per-platform directory delete this replaces (#2208).

Logs and spans **suppress** what they drop rather than merely forgetting it, so a peer still holding the pre-clear ops cannot push them back through a merge. Metrics can only forget **locally** — a monotonic join has no merge-safe forget, so merging with a peer that still holds the old totals restores them. On a replica that does not gossip its metrics, that distinction never arises.

<!-- verbatim from kuilt-otel/src/commonSamples/kotlin/us/tractat/kuilt/otel/Samples.kt#sampleWarpTelemetryClear -->
```kotlin
when (val result = telemetry.clear()) {
    is ExportResult.Success -> println("store emptied; the same instance keeps exporting")
    is ExportResult.Failure -> println("clear failed: ${result.cause}; retry converges")
}
```

**Intent:** keep a longer history than the live replica does — "a year on the server beside an hour on the phone", "the records a peer gossiped to us disappear once that peer forgets them", an archive or audit trail of a replicated log. Don't tee what a replica applied by hand, and don't try it with a second replica of a bigger size: forgetting is contagious through a merge, so the big one shrinks to the small one on first contact.
**Primitive:** `BoltDecorator` (`:kuilt-bolt`) fed by `WarpLogRecordExporter`'s `appliedOps` (`AppliedOpSink`, `:kuilt-otel`) — or by any other `Rga`/`Fugue` owner, since neither side knows what the other is for.

**Wire the merge path or the whole thing is pointless.** `WarpLogRecordExporter` already publishes on both, and the merge one is why: a merge is a state join with no operations to tee, and gossip is how another device's records arrive. An archive fed only by local exports holds this replica's own telemetry and nobody else's.

Re-merging the same peer every anti-entropy round does **not** re-archive its log — `BoltDecorator` suppresses what it has already kept, and for *inserts* it does so at one entry per author rather than one per operation: an insert mints a unique causal dot, so a frontier of archived dots answers "kept already?" with no working set to exceed. That frontier is bounded too, but in **runs** — `frontierWindow`, one per author plus one for each unfilled hole in that author's sequence, so ordinary traffic never approaches it; past it the shortest run is evicted and the inserts it covered are archived a second time. Holes come from a peer that compacted before you first met it, so an archive attached to an *established* mesh is the one to size against. A **remove** mints no dot (it reuses its target insert's id), so those are remembered one at a time in a bounded LRU window — size `removalWindow` to the aggregate live *tombstones* you expect to be offered, not to whole logs. Past it the window thrashes and suppression collapses: the archive then grows by roughly the *whole* offered set of removes per round, not by the excess. A miss costs a duplicate operation in the archive, never a lost one.

**Completeness is bounded by how often you merge, not by how much the archive holds.** A peer can only hand over what it *still has*, and a peer running its own buffer cap windows its oldest records away with no marker saying so. Merge with it more slowly than its buffer turns over and the archive is exactly as complete as your gossip schedule allowed — quietly, because a replay's truncation verdict reports damage to the *archive*, not a gap at the *source*.

A `clear()` empties the replica and leaves the archive alone; that asymmetry is the entire point. A refused append is reported on `BoltDecorator.health` with the **dots** of the records it could not keep, because the live replica windows those away next — so they are lost from both sides, and a count would leave nothing to act on. That surface is bounded and conflating, so a consumer that must not lose an identity calls `BoltDecorator.publish` itself and reads its `AppendResult` rather than routing through a `Unit`-returning sink.

<!-- verbatim from kuilt-otel/src/commonSamples/kotlin/us/tractat/kuilt/otel/Samples.kt#sampleArchivingExporter -->
```kotlin
val format = BoltArchiveFormat.rga(LogRecord.serializer())
val bolt = InMemoryBolt(format, Clock.System)
val archive = BoltDecorator(bolt, format)

// The exporter publishes the operations it applied; the decorator archives them. Neither
// knows the other's job, so the same decorator serves any Rga/Fugue owner.
val exporter = WarpLogRecordExporter(
    replica = ReplicaId("server-uuid-abc123"),
    store = InMemoryDurableStore(),
    appliedOps = { ops -> archive.publish(ops) },
)

// Records that arrived by GOSSIP are archived too: a merge publishes the remote log, which
// is the only reason a server's archive ever holds a phone's records. Re-merging the same
// peer costs nothing — the decorator suppresses what it has already kept. Merge OFTEN
// ENOUGH, though: this only ever carries what the peer has not yet windowed away.
exporter.merge(peersLog)

// And the archive keeps them after the live replica has forgotten them.
exporter.clear()
val kept = bolt.replay(ReplayScope.All).frames().toList().flatMap { it.ops }
check(kept.isNotEmpty()) { "a clear empties the replica, never the archive" }
```

**Intent:** know whether telemetry is still being written at all — "has anything landed since launch?", "are we losing log lines?" — instead of keeping your own flag or counter beside the exporter.
**Primitive:** `WarpLogRecordExporter.health` (`ExporterHealth`, `:kuilt-otel`) and `LogCaptureInstallation.health` (`CaptureHealth`, `:kuilt-otel-logging`). Both are `StateFlow`s, so read a point-in-time answer or collect and alarm on a stall.

A failed durable write returns `ExportResult.Failure`, but on the logging path every caller discards it — the per-platform appender signatures return `void`. A device therefore stopped accepting telemetry and stayed that way for hours with nothing written and nothing logged (#1860); these counters are the out-of-band answer. `ExporterHealth.isDead` already derives "nothing accepted since process start" — there is deliberately no timestamp, because an exporter holds no `Clock` and a wall-clock read does not belong on the export path.

Read the two together. `CaptureHealth.droppedEvents` climbing while the exporter is healthy is not a broken export path — it is a bounded queue shedding its oldest events because the app logs faster than the drain exports (#2124), which is what the queue is for.

**Intent:** read that archive back — "replay what the phone compacted away", "give me everything this machine wrote last Tuesday", resuming where the last pass stopped. Don't hand-roll an "is my archive intact" check, and don't decide the history is complete because the replay finished.
**Primitive:** `Bolt.replay(scope)` (`:kuilt-bolt`), returning a cold flow of `Archived` frames terminated by exactly one verdict — `CleanTail` or `Truncated`.

**The verdict is the product; collect to completion or you don't get one.** A replay that stopped at damage and one that read everything both just *end*, so a stream without a verdict hands back an incomplete history indistinguishable from a complete one — and "I still hold what the live replica forgot" is the only thing an archive sells. `take(n)`/`first()` get no verdict, honestly: they stopped reading before the archive said how it ended. `.frames()` discards it deliberately — fine for a diagnostic dump, not for anything acting on the archive being whole.

`TruncationReason` splits on the **remedy**, not the layer. `SegmentHeader` and `Frame` mean "not readable *yet*" — a writer mid-append, a device still locked — so a later resume from `atOffset` can work. `MissingRegion` means the bytes are **gone**, and `atOffset` is the honest end of the readable history rather than a cursor. Retrying it will never produce those records.

<!-- verbatim from kuilt-bolt/src/commonSamples/kotlin/us/tractat/kuilt/bolt/BoltSamples.kt#sampleBoltReplayVerdict -->
```kotlin
var records = 0
var complete = false

// Collect to COMPLETION. The terminal verdict is what a replay sells — a history that
// stopped at damage, and one that did not, are otherwise indistinguishable. A consumer
// that cuts the flow short (take, first, an early return) gets no verdict, honestly.
bolt.replay(ReplayScope.All).collect { event ->
    when (event) {
        is Archived -> records += event.ops.size
        CleanTail -> complete = true
        is Truncated -> when (event.reason) {
            // Not readable YET — a writer mid-append, a device still locked. Resuming
            // from atOffset later can work.
            TruncationReason.SegmentHeader, TruncationReason.Frame -> retryFrom(event.atOffset)
            // GONE. atOffset is the honest end of the readable history and is NOT a
            // resume cursor: nothing will ever produce the records behind it.
            TruncationReason.MissingRegion -> reportPermanentGap(event.atOffset)
        }
    }
}

if (!complete) reportPartialHistory(records)
```

**Two of the four scopes are cursors and two are queries; resume only from a cursor.** `All` and `FromOffset` are total over the frames they have not yet seen. `Arrived` filters by **arrival** time — when the archive was *told*, arbitrarily later than when it happened for anything that came by merge — and `InsertsAbove` filters by causal coverage over **inserts only**, because a `Remove` mints no dot of its own. So a frame of pure removes is selected by no dot scope at all, however recent: resuming from a dot frontier would skip it and replay a removed record as live.

<!-- verbatim from kuilt-bolt/src/commonSamples/kotlin/us/tractat/kuilt/bolt/BoltSamples.kt#sampleBoltResumeCursor -->
```kotlin
// Consume what the archive holds now, remembering where each frame ended. `.frames()`
// deliberately drops the terminal verdict — fine for a cursor walk, not for anything
// that acts on the history being complete.
var cursor = 0L
bolt.replay(ReplayScope.All).frames().collect { frame ->
    ship(frame.ops)
    cursor = frame.endOffset
}

// Later — after more appends — pick up exactly there. An offset that falls inside a frame
// yields that frame from its start, so a cursor can never point at half a record.
bolt.replay(ReplayScope.FromOffset(cursor)).frames().collect { frame ->
    ship(frame.ops)
    cursor = frame.endOffset
}
```

**A replay may be READ. It must never be AUTHORED FROM.** Folding one into a fresh replica produces a structurally valid state, so nothing stops you — the damage lands one step later and is permanent. A replica seeded from a replay missing frames at its tail re-mints an already-used `(replica, seq)` dot carrying different content, breaking the dense per-author delivery counter every causal-stability version vector depends on, mesh-wide, with nothing to purge it.

Before trimming the live replica's own window on the strength of "the archive has it", ask `Bolt.durability()` (or read it off `BoltDecorator.health`). A flush covers a **range**, so a failed one puts every frame since the last good flush in doubt — not the append that triggered it, whose result is already in your past. It is sticky and widening precisely so it can be polled.

## Reporting a failure that will be retried

**Intent:** log something that has gone wrong on a path that will simply try again — a durable write the store refused, a delete that keeps failing, a send that keeps bouncing. Don't put `logger.error(cause) { … }` in the failure arm and leave it there.
**Rule:** report the failure that **opens** the outage; stay quiet until the thing works again.

A retried failure is reported once per *attempt*, and the retry decides how many attempts there are — so one unchanging condition becomes unbounded log volume. That is not a hypothetical tidiness point. A quota-bound `IndexedDbDurableStore` refuses every write; the exporter above it retries on the next export; the result was measured on three separate exporters in this repo at **one line, and one stack trace, per export, forever** — 300 of each over a 300-export outage. It cost real time on Apple targets, where every trace is symbolicated, and it silently destroyed test results on wasm, where the volume walked a class's output past the harness's 1 MB-per-message ceiling — past which it drops the class and **exits 0**.

Latch the outage, and let the *success* arm clear it.

The failure arm reports only when it *wins* the latch, and returns the failure either way:

<!-- verbatim from kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpSpanExporter.kt#durableWriteFailed -->
```kotlin
private fun durableWriteFailed(cause: Throwable, report: (Throwable) -> Unit): ExportResult {
    if (durableWriteOutage.compareAndSet(expect = false, update = true)) report(cause)
    return ExportResult.Failure(cause)
}
```

The success arm clears it, and every durable write must call this — including the ones you think of as rare:

<!-- verbatim from kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpSpanExporter.kt#durableWriteSucceeded -->
```kotlin
private fun durableWriteSucceeded() {
    durableWriteOutage.value = false
}
```

Four things decide whether this is a fix or a worse bug.

**The key must cover exactly the population the line is about.** The tempting key is a counter you already keep — a health streak, "consecutive failures", a last-error field. It is almost always *wider* than the set of failures this particular line reports, and then a member of the difference opens the latch first and the outage is reported **zero** times instead of once, with the log pointing at whatever failed earlier. That is strictly worse than the noise it replaced, and it is what happened here on the first attempt. A private latch owned by the one function that performs the retried operation makes the two populations the same set by construction. Where two operations really are one condition — five metric kinds writing five keys in one store — share a latch; where they are not — a refused *delete* says nothing about whether a *write* would land — keep them apart.

**A boolean is the wrong latch as soon as one success does not prove the next attempt will land.** Ask what a *partial* recovery looks like. If each attempt targets one resource — one store key, one endpoint, one peer — a backend that refuses one while accepting another makes a boolean **alternate**: the refused one opens it, the accepted one clears it, the refused one reports again. That is the original defect back at a workload-dependent constant, under a comment still promising "once per outage". Hold the **set of things currently failing**, report on `empty → non-empty`, and remove only the one that succeeded. A boolean is safe only where a turn's operations are grouped so that a partial refusal fails the whole turn. And this is not exotic: an `IndexedDbDurableStore` under quota pressure refuses **large** writes while small ones succeed, so a big blob and a small counter alternate by construction.

**Clearing it must be unconditional.** Set the latch with a CAS so exactly one racing caller reports; clear it with a CAS loop that retries until it lands. A lost update on the failure side costs one duplicate line, which is honest. A lost update on the success side leaves the latch stuck against a healthy backend, which silences the *next* outage entirely.

**Deduplicate the line, never the result.** Every failure still comes back to the caller carrying its cause, so a programmatic reader loses nothing — only the log gets quieter.

Keep the throwable on the once-per-outage line: at one line per outage the trace is affordable, and a store rejecting the application's own data is not routine. Drop it (interpolating `"…: $cause"` so the type and message survive) only where the failure is *both* routine and high-multiplicity — a per-segment sweep of superseded garbage that is retried on every pass, where the count is `Θ(passes × ledger)` rather than one.
