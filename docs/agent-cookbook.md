# kuilt cookbook — for coding agents

**Read this before writing networking, session, or shared-state code against kuilt.**
It is a lookup table: find the thing you're trying to do on the left, use the kuilt
primitive on the right, instead of building your own. Each entry links a runnable,
compile-checked snippet.

## Don't build this yourself

If you catch yourself writing any of these, stop — kuilt already ships it:

| You're about to write… | Use instead | Jump to |
|---|---|---|
| a rejoin / reconnect loop, a resume token, a "grace window / hold the slot open" | `ResumeToken` + `SeamRoom` resume | [Rejoin & reconnect](#rejoin--reconnect) |
| a fixed-list or exponential retry/back-off loop | `ExponentialBackoff` | [Rejoin & reconnect](#rejoin--reconnect) |
| a reconnect banner / "why did we drop" classifier — transient vs. unrecoverable buckets | `MembershipEvent.Partitioned.reason` + `HostLost.reason` (`ReconnectReason`/`FailureReason`), plus their `localFabric` tag | [Rejoin & reconnect](#rejoin--reconnect) |
| a propose→authoritative/rejected turn/session facade, host election with a term | `GameSession` + `TurnSequencer` | [Consensus & turns](#consensus--turns) |
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
| a per-line flush loop in a log/telemetry exporter — or a fix for "capturing logs is slow", "the app stalls when it logs a lot" | `WarpLogRecordExporter.export(records)` + `installLogCapture` | [Telemetry & log capture](#telemetry--log-capture) |
| merging several mDNS/Multipeer discovery feeds into one lobby roster | `discoveryRoster` | [Discovery](#discovery) |
| a weighted / fair-share scheduler — "give this group 3× the share", "who runs the next quantum", a hoarder-proof round-robin | `HeddlePolicy` + `HeddleNode` | [Fair share & placement](#fair-share--placement) |
| an entitlement / quota ledger, "reserve a slot before running then charge once", a coordination-free budget that converges across peers | `EntitlementLedger` + `HeddleNode.reserve`/`complete` | [Fair share & placement](#fair-share--placement) |
| minting new quota or re-parenting a group at runtime and needing everyone to agree on the order (no double-mint on a split) | `heddleGoverned` (`GovernedHeddleNode`) | [Fair share & placement](#fair-share--placement) |
| gating a `WarpNode`'s tasks by a weighted lane — "interactive gets 3× batch" | `HeddleAdmissionControl` + `TaskDescriptor.inLane` | [Fair share & placement](#fair-share--placement) |
| "only run this on a GPU / in-region peer", a placement predicate over peer capabilities, "can this peer run this task" | `Affinity` + `TaskDescriptor.where` + `CapSet` | [Fair share & placement](#fair-share--placement) |
| a blob cache keyed by a content hash, a "have you got these bytes?" request/response, a manifest of what each peer holds | `Creel` + `BobbinExchange` | [Code mobility](#code-mobility) |
| running code that arrived from another peer — a plugin loader, an `eval`, a bespoke sandbox or timeout-and-kill wrapper | `WasmRuntime` + `WasmSandboxConfig` + `WarpLazyFetch` | [Code mobility](#code-mobility) |

## Discovery

**Intent:** merge several `PeerDiscoverySource` feeds (mDNS, Multipeer, …) into one live roster for a lobby UI — "who can I currently see?"
**Primitive:** `discoveryRoster(sources, scope)` (`us.tractat.kuilt.core.discovery`). Folds `discoveries()` minus `departures()`, keyed on `Tag.peerKey`, into one `StateFlow<Set<Tag>>`. Don't hand-roll the merge.

It returns only **this peer's current best view** — not an agreement. It is **not** an election input: pick a host from `Seam.peers` once connected, never from this roster. And note the ghost caveat — a source whose `departures()` is the default (`emptyFlow()`) is add-only, so departed peers linger forever.

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

## Rejoin & reconnect

**Intent:** rejoin / reconnect after a dropped connection; "hold the slot open" for a grace window.
**Primitive:** `ResumeToken` + the `SeamRoom` resume flow (`us.tractat.kuilt.session.partition`). Don't re-track the grace window yourself.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#resumeAfterDropSample -->
```kotlin
public suspend fun resumeAfterDropSample(room: Room) {
    // After the admit handshake the joiner holds a reconnect credential — save it.
    val token: ResumeToken = room.resumeToken ?: return
    // ... transport drops; you redial the fabric and rebuild the room ...
    // Present the saved token to re-enter within the leader's grace window.
    when (room.resume(token)) {
        ResumeResult.Success -> Unit // back in the room; state resync follows
        ResumeResult.WindowClosed -> Unit // grace window elapsed — re-join fresh
        ResumeResult.WindowNotYetOpen -> Unit // host hasn't noticed the drop yet — retry shortly
        ResumeResult.TimedOut -> Unit // no reply within resumeTimeout (host unreachable) — retry shortly
        is ResumeResult.TokenInvalid -> Unit // wrong session — re-join fresh
    }
}
```

**Intent:** decide whether a host's *refusal* is worth retrying, instead of string-matching the reason.
**Primitive:** `RejectCode` on `FailureReason.Refused` / `AdmissionFailure.Rejected` (`us.tractat.kuilt.session.admit`) — branch on the code and treat anything unrecognised as retryable.

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
late-joiner full-state sync, and scaling to many peers via `GossipSeam`.

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
a frontier of zero for that author and stalls every downstream collection. `Quilter` already does
this; hand-rolled frontier arithmetic must too.

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
**Primitive:** `JoinerReconnectController` (`:kuilt-session`, `us.tractat.kuilt.session.partition`). It *is* the server-side seat-hold: `onPeerUnresponsive` opens the timed window, `tryResume` validates the returning peer's `ResumeToken` (right room, window still open, token not already used), and `events` reports `WindowOpened` / `Resumed` / `WindowExpired`. A `SeamRoom` host wires one for you — reach for this directly only when you own the host loop. Don't keep your own `pendingSeats` / `disconnectedAt` map.

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

Four things to know before you bind this to a UI:

- **It is session-scoped, never device-scoped.** A `Room` rides exactly one fabric, so this only ever
  means *"my end of **this room's** fabric."* A peer in two rooms over two fabrics has two independent
  values and neither speaks for the other; kuilt has no device-level registry. A room over a bonded
  `CompositeSeam` reports `Unavailable` only when **every** woven ply is down.
- **`Unknown` is a real third answer, and today it is the usual one.** Only a fabric wired to the
  operating system's own path monitor can watch its own reachability — that is `kuilt-nw`, and nothing
  else so far — so every other lane honestly reports `Unknown`, meaning *kuilt cannot tell on this
  fabric*. Treat it as no information, never as either answer, and expect it as the common case rather
  than an error. The per-fabric flag is `reportsLiveCapability`; see
  [architecture.md](architecture.md#reportslivecapability--fabrics-without-a-path-observer).
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
    val incoming = async { joiner.appChannel("chat").incoming.first() }
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
refused naming the peers it waits on (`pendingAcks(edge)` reads that set). It fails closed on a
transfer-tangled strand, and it **blocks while any enrolled peer is down** — that peer is
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
    node.prepare(AttachmentRecord(edge, root, leaf, Weight.ONE, initialVirtualTime = 0L))
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
