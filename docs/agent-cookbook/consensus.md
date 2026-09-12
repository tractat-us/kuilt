# Consensus

Sometimes a whole group of devices needs to agree on exactly one order of events — who moved
first, what happened next — even though no single device can simply be trusted to decide alone.
This page is about the pattern that makes that possible: one device leads for a while and the
rest copy its history, so everyone ends up agreeing even as the leader changes over time. That
shows up as a game's turns, where a move only counts once it has been agreed rather than the
moment a player makes it; as a small cluster of dedicated machines that a much larger crowd of
clients send requests into, rather than clients agreeing among themselves; and as making sure
that agreed history survives a crash or a restart instead of being forgotten the moment a
device reboots.

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

## Durable consensus state

**Intent:** make a Raft node remember what it knew across a restart — its current term, who it voted for in that term, the leader it established, its log and its snapshot. A node that forgets any of those can vote twice in one term, which is the single thing Raft exists to prevent. Don't write an `expect`/`actual` `RaftStorage` per platform, and don't invent a SQLite or IndexedDB schema for consensus state.
**Primitive:** `DurableStoreRaftStorage.open(store)` (`:kuilt-raft`) over any `DurableStore`. `InMemoryRaftStorage` stays the right answer for tests and for peers that rejoin from scratch.

**One store per node.** The three keys are fixed — `raft/meta`, `raft/log`, `raft/snapshot` — so two nodes pointed at one directory or one database overwrite each other's term and vote, and nothing shows it until the cluster splits. There is deliberately no prefix parameter to make sharing safe: a key built from data (a game id) walks into `StoreKey`'s length and case-folding caveats. They are public constants so a consumer re-provisioning a node knows exactly what to delete; `DurableStore` has no key enumeration, which is the gap #2208 recorded.

Every mutator encodes the state it is about to have, waits for `DurableStore.write` to commit it, and only then updates its own memory — so the engine is never told a term is durable when it is not. `saveTermAndVotedFor` and `saveLeaderForTerm` each write **one** record, which is what makes their §5.1/§5.2 atomicity requirements hold on a store that is atomic per key.

An append rewrites the **whole retained log** as one record, so cost is O(retained entries) and what bounds it is how often you compact — publish a state-machine snapshot into `RaftNode.snapshots` and the engine drops the covered prefix. That makes this the reference adapter for a bounded log (a game, a room, a small cluster), not a high-throughput log engine; a log too large to rewrite wants your own `RaftStorage`, bound to `RaftStorageConformanceSuite`. `Bolt` is not the medium for this — it is write-only and forbids authoring from a replay, while a Raft log must be restored from and truncated at the tail.

The stored format is private and versioned, independent of the wire format. A record that will not decode, or one written by a newer build, raises `CorruptDurableStateException` naming the key. `open` validates nothing else: range checks are the engine's (#1887), and an adapter that repaired a bad term would be laundering the evidence that the disk is wrong.

<!-- verbatim from kuilt-raft/src/commonSamples/kotlin/us/tractat/kuilt/raft/RaftSamples.kt#sampleDurableRaftStorage -->
```kotlin
// One store per node. In production this is the platform's crash-safe implementation —
// FileChannelDurableStore(nodeDirectory), NSFileManagerDurableStore(nodeDirectory), or
// IndexedDbDurableStore.open(nodeDatabase). A sample uses the in-memory one.
val store: DurableStore = InMemoryDurableStore()

val storage = DurableStoreRaftStorage.open(store)

// Every mutator commits to the medium before it updates its own memory, so a term that has
// been saved is a term that survives — pass this to `scope.raftNode(cluster, transport, storage)`.
storage.saveTermAndVotedFor(term = 4L, votedFor = NodeId("node-a"))
storage.appendEntries(listOf(LogEntry(index = 1L, term = 4L, command = byteArrayOf(7, 8, 9))))

// A restart: a second handle onto the same store, decoding what the first one wrote.
val restarted = DurableStoreRaftStorage.open(store)
check(restarted.term() == 4L)
check(restarted.votedFor() == NodeId("node-a"))
check(restarted.entries().single().command.contentEquals(byteArrayOf(7, 8, 9)))
```
