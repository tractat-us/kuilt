@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.game

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class GameNodeTest {

    @Test
    fun rosterGivenTwoPeersConverge() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val (hostSeam, joinSeam) = seats(loom, 2)
        val voters = setOf(NodeId(hostSeam.selfId.value), NodeId(joinSeam.selfId.value))

        val a = backgroundScope.gameNode(hostSeam, voters, raftConfig = fastRaftConfig(seed = 1L)).node
        val b = backgroundScope.gameNode(joinSeam, voters, raftConfig = fastRaftConfig(seed = 2L)).node

        val leader = awaitEitherLeader(a, b)
        val proposed = TurnSequencer(leader, Int.serializer()).propose(42)
        assertEquals(42, proposed.action)
    }

    @Test
    fun hostAdmitsOneJoiner() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val (hostSeam, joinSeam) = seats(loom, 2)

        // gameHost and gameJoin both suspend until membership settles — launch them concurrently
        // so the host's admit loop can process the joiner while the joiner awaits its admission
        // signal. The CoroutineScope receiver must be backgroundScope so the RaftNode lifetime is
        // tied to backgroundScope (not to the async block), allowing the async to complete once
        // the suspend work is done while nodes keep running on backgroundScope.
        val hostDeferred = async { backgroundScope.gameHost(hostSeam, peerCount = 2, raftConfig = fastRaftConfig(seed = 1L)).node }
        val joinDeferred = async { backgroundScope.gameJoin(joinSeam, raftConfig = fastRaftConfig(seed = 2L)).node }

        val host = hostDeferred.await()
        val joiner = joinDeferred.await()

        // Host is the leader; propose an action.
        val entry = TurnSequencer(host, Int.serializer()).propose(99)
        assertEquals(99, entry.action)

        // Observe the committed action on the joiner. committed is a hot flow (replay=0),
        // so we use committedFrom(1) which replays already-committed entries — safe to call
        // after the proposal without missing the entry.
        // committedFrom replays raw log entries including config entries (entry.config != null);
        // those are withheld from the live committed flow but surface in replay. Use mapNotNull
        // to skip them and decode only application entries.
        val joinerAction = joiner.committedFrom(1)
            .mapNotNull { committed ->
                if (committed !is Committed.Entry) return@mapNotNull null
                val logEntry = committed.entry
                if (logEntry.isNoOp || logEntry.config != null) return@mapNotNull null
                Cbor.decodeFromByteArray(Int.serializer(), logEntry.command)
            }
            .first()
        assertEquals(99, joinerAction)
    }

    /**
     * Validates the latecomer admission property: a voter admitted last (after the host has
     * already formed a quorum with the first joiner) can replay every committed action from the
     * log via [RaftNode.committedFrom].
     *
     * **D2 readiness-gate resolution:** [gameHost] with `peerCount = 3` returns only after full
     * membership (voters.size == peerCount), not at quorum-2. There is no API surface to propose
     * before the third voter joins. The latecomer property is therefore validated by confirming
     * that the *last-admitted* voter (joiner2 — admitted after joiner1, and potentially after
     * the host's no-op commit) can replay an action proposed once full membership is reached.
     * Return-at-quorum (D2) is a deliberate future option, not built in Task 4.
     *
     * [committedFrom] replays raw log entries including config entries (`entry.config != null`)
     * that the live [RaftNode.committed] flow withholds. Filter these before decoding application
     * entries — see the [hostAdmitsOneJoiner] test for the canonical pattern.
     */
    @Test
    fun latecomerJoinsAfterFirstCommit() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val (hostSeam, j1, j2) = seats(loom, 3)

        // Launch all three concurrently: host's admit loop blocks until voters.size == 3;
        // both joiners must be running so they can receive the membership change commits.
        // Use backgroundScope receiver so RaftNode lifetime ties to backgroundScope, not async.
        val hostDeferred = async { backgroundScope.gameHost(hostSeam, peerCount = 3, raftConfig = fastRaftConfig(seed = 1L)).node }
        val j1Deferred = async { backgroundScope.gameJoin(j1, raftConfig = fastRaftConfig(seed = 2L)).node }
        val j2Deferred = async { backgroundScope.gameJoin(j2, raftConfig = fastRaftConfig(seed = 3L)).node }

        val host = hostDeferred.await()
        val joiner1 = j1Deferred.await()
        val joiner2 = j2Deferred.await()

        // All three voters are now admitted. Propose an action on the host (leader).
        val entry = TurnSequencer(host, Int.serializer()).propose(5)
        assertEquals(5, entry.action)

        // Both joiners replay the committed action from the log.
        // joiner2 is the latecomer that may have missed earlier log entries as a non-voter;
        // committedFrom(1) guarantees replay of all committed entries from index 1 onward.
        // committedFrom replays raw log entries including config entries (entry.config != null)
        // that the live committed flow withholds — filter them before decoding application entries.
        fun appEntries(node: RaftNode) =
            node.committedFrom(1).mapNotNull { committed ->
                if (committed !is Committed.Entry) return@mapNotNull null
                val logEntry = committed.entry
                if (logEntry.isNoOp || logEntry.config != null) return@mapNotNull null
                Cbor.decodeFromByteArray(Int.serializer(), logEntry.command)
            }

        val j1Action = appEntries(joiner1).first()
        val j2Action = appEntries(joiner2).first()

        assertEquals(5, j1Action, "joiner1 must replay the committed action")
        assertEquals(5, j2Action, "joiner2 (latecomer) must replay the committed action")
    }

    /**
     * D5 incoming-guard: documents that `seam.incoming` must not be collected after
     * `gameHost`/`gameJoin`/`gameNode` wrap the seam, and verifies the supported
     * single-collection path works correctly.
     *
     * Each of the three entry points internally creates a [us.tractat.kuilt.core.MuxSeam]
     * that becomes the **sole** consumer of `seam.incoming` (ADR-034 single-collection).
     * A caller who also collects `seam.incoming` directly creates a second consumer that
     * **races** `MuxSeam` for frames: some raft messages reach the second collector instead
     * of the engine, causing silent liveness failures (dropped RPCs, stalled elections).
     * This is an unsupported usage — no guarantee about which consumer receives any given
     * frame.
     *
     * The supported contract is: pass the plain `Seam` to one of the three entry points
     * and never collect `seam.incoming` again. This test verifies that contract: a single
     * cluster bootstrapped via `gameNode` (which now wraps the seam in a `MuxSeam` — raft on
     * tag 1, the app-envelope `NamedMux` on tag 3) converges to a leader and commits an action
     * without any second collector interfering.
     *
     * The constraint is enforced by convention (KDoc on each entry point); the mechanism
     * is `MuxSeam.shareIn(SharingStarted.Eagerly)` which subscribes to the underlying
     * seam immediately. Any subsequent collect on the same seam competes for the same
     * channel's frames — the engine will eventually stall if it loses a frame it needs.
     */
    @Test
    fun incomingGuardSingleCollectorSupportedByMuxSeam() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val (hostSeam, joinSeam) = seats(loom, 2)
        val voters = setOf(NodeId(hostSeam.selfId.value), NodeId(joinSeam.selfId.value))

        // gameNode wraps the seam in a MuxSeam (sole collector) — single-collection contract satisfied.
        // This is the roster-given path; gameHost/gameJoin also satisfy the contract internally.
        val a = backgroundScope.gameNode(hostSeam, voters, raftConfig = fastRaftConfig(seed = 1L)).node
        val b = backgroundScope.gameNode(joinSeam, voters, raftConfig = fastRaftConfig(seed = 2L)).node

        // If the single-collection contract holds, the Raft engine receives all frames and
        // converges. A second collector on hostSeam.incoming running concurrently would steal
        // frames and likely prevent convergence (or stall propose), causing a test timeout.
        val leader = awaitEitherLeader(a, b)
        val committed = TurnSequencer(leader, Int.serializer()).propose(100)

        assertEquals(100, committed.action, "all raft frames must reach the engine — single-collection satisfied")
    }

    /**
     * D4 production-overload hygiene: the production `gameNode`/`gameHost`/`gameJoin`
     * overloads must not expose or default `expectVirtualTime = true`.
     *
     * The only supported path to `expectVirtualTime = true` is explicit injection of a
     * `raftConfig` parameter. Production code that calls the default overload always gets
     * `RaftConfig()` which has `expectVirtualTime = false` — the real-clock/warn guard.
     * This test locks that invariant so a future refactor cannot accidentally leak
     * `expectVirtualTime = true` into a production default.
     */
    @Test
    fun productionDefaultRaftConfigHasExpectVirtualTimeFalse() {
        val defaultConfig = RaftConfig()
        assertFalse(
            defaultConfig.expectVirtualTime,
            "RaftConfig() default must have expectVirtualTime=false — " +
                "production overloads of gameNode/gameHost/gameJoin must never set expectVirtualTime=true",
        )
    }

    /**
     * Return-at-quorum (D2): `gameHost(returnAt = ReturnPolicy.Quorum)` returns the leader as soon
     * as a **majority** of `peerCount` voters are present (here 2 of 3), without blocking on the
     * slowest/absent peer. A proposal made at quorum commits, and a latecomer that connects *after*
     * the host has already returned — and after the action was committed — is admitted by the
     * background admission loop and replays the earlier action — no restart.
     *
     * The background loop carries no time bound: it stays open for the life of the session until the
     * roster reaches `peerCount`, so the latecomer joins however late it connects. (A peer arriving
     * once the roster is already full is the separate, deferred concern in #587.)
     */
    @Test
    fun quorumModeReturnsAtQuorumThenAdmitsLatecomer() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        // Only the host and one joiner are present initially: 2 of peerCount=3 = quorum.
        val hostSeam = loom.host(Pattern("game-bootstrap"))
        val j1 = loom.join(InMemoryTag("seat-1"))

        val hostDeferred = async {
            backgroundScope.gameHost(
                hostSeam,
                peerCount = 3,
                returnAt = ReturnPolicy.Quorum,
                raftConfig = fastRaftConfig(seed = 1L),
            ).node
        }
        val j1Deferred = async { backgroundScope.gameJoin(j1, raftConfig = fastRaftConfig(seed = 2L)).node }

        // Host returns at quorum (2 voters) without waiting for the third peer.
        val host = hostDeferred.await()
        val joiner1 = j1Deferred.await()

        // Quorum present: a proposal commits even though the third voter has not joined yet.
        val entry = TurnSequencer(host, Int.serializer()).propose(5)
        assertEquals(5, entry.action)

        // The latecomer connects only now, after the host already returned, and joins.
        val j2 = loom.join(InMemoryTag("seat-2"))
        val joiner2 = backgroundScope.gameJoin(j2, raftConfig = fastRaftConfig(seed = 3L)).node

        // The background admission loop promotes the latecomer; it replays the earlier action.
        fun appEntries(node: RaftNode) =
            node.committedFrom(1).mapNotNull { committed ->
                if (committed !is Committed.Entry) return@mapNotNull null
                val logEntry = committed.entry
                if (logEntry.isNoOp || logEntry.config != null) return@mapNotNull null
                Cbor.decodeFromByteArray(Int.serializer(), logEntry.command)
            }

        assertEquals(5, appEntries(joiner2).first(), "latecomer admitted in the background must replay the committed action")
    }

    @Test
    fun rosterGivenThreePeersQuorumTwo() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val seams = seats(loom, 3)
        val voters = seams.map { NodeId(it.selfId.value) }.toSet()
        val (s0, s1, s2) = seams
        val nodes = listOf(
            backgroundScope.gameNode(s0, voters, raftConfig = fastRaftConfig(seed = 1L)).node,
            backgroundScope.gameNode(s1, voters, raftConfig = fastRaftConfig(seed = 2L)).node,
            backgroundScope.gameNode(s2, voters, raftConfig = fastRaftConfig(seed = 3L)).node,
        )

        val leader = awaitAnyLeader(nodes)
        val entry = TurnSequencer(leader, Int.serializer()).propose(7)
        assertEquals(7, entry.action)
    }

    /**
     * #587: a peer that calls [gameJoin] after the roster is already full must receive
     * [RosterFullException] immediately — not suspend indefinitely waiting for a promotion
     * that will never come.
     *
     * The host uses [ReturnPolicy.Quorum] with `peerCount = 2` so the admission loop
     * launches in the background and then exits once both seats are filled. A third peer
     * connecting after that exit point must be rejected loud and fast.
     */
    @Test
    fun joinAfterRosterFullThrowsRosterFullException() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val (hostSeam, j1Seam) = seats(loom, 2)

        // Fill both seats: host + one joiner. The background admit loop exits once voters == 2.
        val hostDeferred = async {
            backgroundScope.gameHost(
                hostSeam,
                peerCount = 2,
                returnAt = ReturnPolicy.Quorum,
                raftConfig = fastRaftConfig(seed = 1L),
            )
        }
        val j1Deferred = async { backgroundScope.gameJoin(j1Seam, raftConfig = fastRaftConfig(seed = 2L)) }
        hostDeferred.await()
        j1Deferred.await()

        // Third peer arrives after the roster is full — must throw, not hang.
        val j2Seam = loom.join(InMemoryTag("seat-2"))
        assertFailsWith<RosterFullException> {
            backgroundScope.gameJoin(j2Seam, raftConfig = fastRaftConfig(seed = 3L))
        }
    }

    /**
     * #587 backstop: a [gameJoin] against a host that never admits and never signals
     * [admissionClosed] must throw [JoinTimeoutException] after the bounded wait expires,
     * not suspend indefinitely. Uses a tight injected [joinAdmissionTimeout] so virtual
     * time reaches the bound quickly.
     */
    @Test
    fun joinWithNoAdmissionSignalThrowsJoinTimeoutException() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        // Only host seam — host is bootstrapped as a 1-node cluster with peerCount=2
        // but no second peer is launched, so the admission loop blocks indefinitely
        // and never publishes admissionClosed. The joiner's backstop must fire first.
        val hostSeam = loom.host(Pattern("game-bootstrap"))
        val joinSeam = loom.join(InMemoryTag("seat-1"))

        // Host runs but never fills the roster (no other joiner) so admissionClosed never fires.
        val hostDeferred = async {
            backgroundScope.gameHost(
                hostSeam,
                peerCount = 2,
                returnAt = ReturnPolicy.Quorum,
                raftConfig = fastRaftConfig(seed = 1L),
            )
        }

        // Joiner uses a tight backstop so the test does not hang — virtual time advances to it.
        assertFailsWith<JoinTimeoutException> {
            backgroundScope.gameJoin(
                joinSeam,
                raftConfig = fastRaftConfig(seed = 2L),
                joinAdmissionTimeout = 20.milliseconds,
            )
        }

        hostDeferred.cancel()
    }
}
