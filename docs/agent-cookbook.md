# kuilt cookbook — for coding agents

**Read this before writing networking, session, or shared-state code against kuilt.**
It is a lookup table: find the thing you're trying to do on the left, use the kuilt
primitive on the right, instead of building your own. Each entry links a runnable,
compile-checked snippet.

## Don't build this yourself

If you catch yourself writing any of these, stop — kuilt already ships it:

| You're about to write… | Use instead | Jump to |
|---|---|---|
| a rejoin / reconnect loop, a resume token, a "grace window / hold the slot open" | `Room.resumeToken` + `Room.resume` | [Rejoin & reconnect](agent-cookbook/session.md#rejoin--reconnect) |
| a fixed-list or exponential retry/back-off loop | `ExponentialBackoff` | [Rejoin & reconnect](agent-cookbook/session.md#rejoin--reconnect) |
| a reconnect banner / "why did we drop" classifier — transient vs. unrecoverable buckets | `MembershipEvent.Partitioned.reason` + `HostLost.reason` (`ReconnectReason`/`FailureReason`), plus their `localFabric` tag | [Rejoin & reconnect](agent-cookbook/session.md#rejoin--reconnect) |
| a propose→authoritative/rejected turn/session facade, host election with a term | `GameSession` + `TurnSequencer` | [Consensus & turns](agent-cookbook/consensus.md#consensus--turns) |
| a `host == selfId` check plus a re-election when the peer that was hosting walks out mid-lobby | `ElectionLobby.awaitRoom` → `ElectionOutcome.BecameHost` → `start()` on the **same** lobby | [Host election & the lobby](agent-cookbook/session.md#host-election--the-lobby) |
| a heartbeat, an idle reaper, "is this peer still alive", "evict stale session" | `HeartbeatPartitionDetector` | [Liveness & presence](agent-cookbook/session.md#liveness--presence) |
| "close a room nobody joined", "reap an abandoned table/lobby", "nobody ever showed up" | `SoloDeadlineDetector` | [Liveness & presence](agent-cookbook/session.md#liveness--presence) |
| a "hold the seat open" / reconnect grace window on the host, a `pendingSeats` or `disconnectedAt` map | `JoinerReconnectController` | [Liveness & presence](agent-cookbook/session.md#liveness--presence) |
| a "paused / reconnecting…" presence flag, a `lastSeen` map for greying out a player | `Room.roster` + `Member.liveness` — the level; `Room.events` is the notification | [Liveness & presence](agent-cookbook/session.md#liveness--presence) |
| that same "paused / reconnecting…" surface for a **game** (not a bare room), a `room.events` → game-presence adapter | `RoomGameSession.presence` via `gameOverRoom` | [Liveness & presence](agent-cookbook/session.md#liveness--presence) |
| a "you are offline" / "your connection dropped" indicator, distinguishing *your* outage from *their* outage | `Room.localFabric` + `MembershipEvent.LocalFabricLost` | [Liveness & presence](agent-cookbook/session.md#liveness--presence) |
| a last-write-wins register, a grow-only set/counter, an add/remove set, a version vector, "merge these two states" | the CRDT zoo (`LWWRegister`, `GSet`, `PNCounter`, `ORSet`, …) | [Replicated data](agent-cookbook/replication.md#replicated-data) |
| replicating a CRDT over a connection by hand | `Quilter` | [Replicated data](agent-cookbook/replication.md#replicated-data) |
| a conditional write to shared state — "claim it only if it's free", "publish only if nobody already did" — or an identity/no-op patch meaning "I decided not to write" | `Quilter.mutateOrSkip { … }` (return `null` to decline) | [Replicated data](agent-cookbook/replication.md#replicated-data) |
| a forwarding hop through the host so two guests can see each other — because a `Quilter` between two joiners never converges, or a peer is in the roster but unreachable | `Room.channel(id)` — the room already relays | [Replicated data](agent-cookbook/replication.md#replicated-data) |
| averaging model updates from many devices without collecting their data — federated learning / federated analytics, "train locally, share only the update" | `FedAvg` + `TrainingUpdate` | [Replicated data](agent-cookbook/replication.md#replicated-data) |
| checking two peers hold the same state across a process/socket boundary — hand-hashing a replicated state so you can compare it as one number | `canonicalDigest` | [Replicated data](agent-cookbook/replication.md#replicated-data) |
| splitting a big blob into frames — picking a chunk size, or chasing a `FrameTooLargeException` that only appears once a peer drops | `Room.maxPayloadBytes` / `Seam.maxPayloadBytes` | [Payload limits](agent-cookbook/fabrics.md#payload-limits) |
| a `seenIds` set to skip already-handled messages | `GSet` / kuilt dedup | [Dedup](agent-cookbook/replication.md#dedup) |
| saving bytes so they survive a restart — a write-temp-then-`fsync`-then-atomic-rename dance, a per-platform file helper, an IndexedDB wrapper, "did that write actually land before we crashed?" | `DurableStore` + `StoreKey` | [Durable storage](agent-cookbook/fabrics.md#durable-storage) |
| a Raft node's term, vote and log that must survive a restart — a node that comes back having forgotten who it voted for, an `expect`/`actual` `RaftStorage` per platform, a SQLite or IndexedDB schema for consensus state | `DurableStoreRaftStorage.open(store)` | [Durable consensus state](agent-cookbook/consensus.md#durable-consensus-state) |
| a per-line flush loop in a log/telemetry exporter — or a fix for "capturing logs is slow", "the app stalls when it logs a lot" | `WarpLogRecordExporter.export(records)` + `installLogCapture` | [Telemetry & log capture](#telemetry--log-capture) |
| stamping the session/game/request a log line belongs to — an MDC equivalent, a global holding "the current session" for a log mapper to read, lines from one session tagged with another's id | `withLogContext` | [Telemetry & log capture](#telemetry--log-capture) |
| deleting a telemetry store's files to reset it, or a "clear on next launch" flag so the delete lands before recovery | `WarpTelemetry.clear()` | [Telemetry & log capture](#telemetry--log-capture) |
| your own flag or counter tracking whether telemetry is still being written — "has anything landed since launch?", "are we losing log lines?" | `WarpLogRecordExporter.health` + `LogCaptureInstallation.health` | [Telemetry & log capture](#telemetry--log-capture) |
| a second, longer-retention copy of a replicated log — "keep a year on the server beside an hour on the phone", "gossiped records vanish when the peer forgets them", a hand-rolled tee of what a replica applied | `BoltDecorator` + `AppliedOpSink` | [Archiving what the live replica forgets](agent-cookbook/replication.md#archiving-what-the-live-replica-forgets) |
| reading that archive back — "replay what the phone compacted away", "did I get the whole history or did it stop somewhere?", a resume cursor over an append-only log, a hand-rolled "is my archive intact" check | `Bolt.replay` + `ReplayScope` + the terminal verdict | [Archiving what the live replica forgets](agent-cookbook/replication.md#archiving-what-the-live-replica-forgets) |
| merging several mDNS/Multipeer discovery feeds into one lobby roster | `discoveryRoster` | [Discovery](agent-cookbook/fabrics.md#discovery) |
| a stale-peer sweeper over a discovery list that only ever grows — peers that left still on screen, "nobody is ever removed", a `lastSeen` timeout over *discovered* (not admitted) peers | `PeerDiscoverySource.departures()` — implement it, and hold it to `DiscoverySourceConformanceSuite` | [Peers pile up and are never removed](agent-cookbook/fabrics.md#peers-pile-up-and-are-never-removed) |
| a weighted / fair-share scheduler — "give this group 3× the share", "who runs the next quantum", a hoarder-proof round-robin | `HeddlePolicy` + `HeddleNode` | [Fair share & placement](#fair-share--placement) |
| an entitlement / quota ledger, "reserve a slot before running then charge once", a coordination-free budget that converges across peers | `EntitlementLedger` + `HeddleNode.reserve`/`complete` | [Fair share & placement](#fair-share--placement) |
| minting new quota or re-parenting a group at runtime and needing everyone to agree on the order (no double-mint on a split) | `heddleGoverned` (`GovernedHeddleNode`) | [Fair share & placement](#fair-share--placement) |
| gating a `WarpNode`'s tasks by a weighted lane — "interactive gets 3× batch" | `HeddleAdmissionControl` + `TaskDescriptor.inLane` | [Fair share & placement](#fair-share--placement) |
| "only run this on a GPU / in-region peer", a placement predicate over peer capabilities, "can this peer run this task" | `Affinity` + `TaskDescriptor.where` + `CapSet` | [Fair share & placement](#fair-share--placement) |
| a blob cache keyed by a content hash, a "have you got these bytes?" request/response, a manifest of what each peer holds | `Creel` + `BobbinExchange` | [Code mobility](#code-mobility) |
| running code that arrived from another peer — a plugin loader, an `eval`, a bespoke sandbox or timeout-and-kill wrapper | `WasmRuntime` + `WasmSandboxConfig` + `WarpLazyFetch` | [Code mobility](#code-mobility) |
| a `try`/`catch` inside an `onEach { … }.launchIn(scope)` so one bad item cannot kill a long-lived collector — or a fix for "the pump stopped and nothing said so", a `Seam`/`Room` that goes deaf after one throw, an iOS crash traced to an unhandled coroutine exception | `Flow.pumpIn(scope, onFailure, name) { … }` — it owns the upstream half your `try` structurally cannot see | [Long-lived pumps](agent-cookbook/fabrics.md#long-lived-pumps) |
| declaring a `MutableStateFlow<SeamState>` while writing a fabric, or a `closed`/`tornDown` flag beside one — or a fix for "my `close()` publishes `Torn` and something overwrites it", `state.first { it is Torn }` that hangs forever, a closed seam still reporting `Woven`, a seam slot that never frees | `SeamStateGate` — it fuses the latch check and the flow write, and replaces your single-shot flag | [A seam's terminal state](agent-cookbook/fabrics.md#a-seams-terminal-state) |
| turning a peer-supplied id into a roster entry while writing a fabric — `PeerId(bytes.decodeToString())`, a `Set<PeerId>` a callback adds to and removes from, `peers == setOf(selfId)` meaning "the session is over" | `PeerIdentityRegistry` | [A peer id off the wire](agent-cookbook/fabrics.md#a-peer-id-off-the-wire-straight-into-a-setpeerid) |
| a 4-byte length prefix and a reassembly loop over a socket or in-house RPC — "I have a byte stream, kuilt wants a fabric" | `framed()` → `handshaking()` | [Your own transport](agent-cookbook/fabrics.md#your-own-transport) |
| that same plumbing when the transport really is just TCP | `TcpLoom.host` / `TcpLoom.join` | [Plain TCP is already assembled](agent-cookbook/fabrics.md#plain-tcp-is-already-assembled) |
| dealing cards nobody can peek at — a shuffle on one device, "the dealer could cheat", hiding a card from the player holding it | `DealSession` | [Dealing cards nobody can peek at](agent-cookbook/replication.md#dealing-cards-nobody-can-peek-at) |
| a shared random seed nobody could steer — one peer picks a number and broadcasts it | `FairRandom.roll()` | [Nobody chose that number](agent-cookbook/replication.md#nobody-chose-that-number) |
| a server holding the authoritative log while many clients propose into it, and a client that must survive losing its server | `ClusterClient` + `ServerCluster` | [When one of the peers is a server](agent-cookbook/consensus.md#when-one-of-the-peers-is-a-server) |
| a session too big for everyone-talks-to-everyone — N² links, a broadcast sent N times, memory that grows with the room | `GossipSeam` + `deltaTargets = { gossip.activePeers.value }` | [Scaling to many peers](agent-cookbook/replication.md#scaling-to-many-peers) |

## Families

- [Fabrics](agent-cookbook/fabrics.md) — discovery, transports, the seam, payload limits, pumps, durable storage.
- [Session](agent-cookbook/session.md) — rejoin & reconnect, liveness & presence, host election & the lobby.
- [Replication](agent-cookbook/replication.md) — replicated data, scaling to many peers, dealing cards, dedup, archiving what the live replica forgets.
- [Consensus](agent-cookbook/consensus.md) — consensus & turns, when one of the peers is a server, durable consensus state.

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

**Intent:** know whether telemetry is still being written at all — "has anything landed since launch?", "are we losing log lines?" — instead of keeping your own flag or counter beside the exporter.
**Primitive:** `WarpLogRecordExporter.health` (`ExporterHealth`, `:kuilt-otel`) and `LogCaptureInstallation.health` (`CaptureHealth`, `:kuilt-otel-logging`). Both are `StateFlow`s, so read a point-in-time answer or collect and alarm on a stall.

A failed durable write returns `ExportResult.Failure`, but on the logging path every caller discards it — the per-platform appender signatures return `void`. A device therefore stopped accepting telemetry and stayed that way for hours with nothing written and nothing logged (#1860); these counters are the out-of-band answer. `ExporterHealth.isDead` already derives "nothing accepted since process start" — there is deliberately no timestamp, because an exporter holds no `Clock` and a wall-clock read does not belong on the export path.

Read the two together. `CaptureHealth.droppedEvents` climbing while the exporter is healthy is not a broken export path — it is a bounded queue shedding its oldest events because the app logs faster than the drain exports (#2124), which is what the queue is for.

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
