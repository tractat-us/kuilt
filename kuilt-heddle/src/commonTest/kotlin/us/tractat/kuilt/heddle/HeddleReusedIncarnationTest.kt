package us.tractat.kuilt.heddle

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A peer restarted under the incarnation it already used (#3057). Its new acts draw the sequence
 * numbers the earlier run drew, and before the fix a new act could share a key with an old entry
 * in the log: the call answered with the old entry's outcome, and the new entry was deduped on
 * every peer, so the act vanished and nothing said so.
 *
 * Every arm restarts peer [RESTARTED] of a three-peer [governedCluster], as the same replica under
 * the same incarnation, and issues its act at sequence number 1, where the earlier run minted.
 * Each arm first asserts that the earlier run's log entry holds exactly that base key, so a rig
 * whose keys never met is not mistaken for a fix. "Before replay" draws the key before the
 * restarted control plane has applied a single entry; "after replay" draws it once replay has
 * passed the old one. The two reach different code: before replay the old entry resolves the
 * waiting call, after replay the key is already in the dedup table when it is drawn.
 *
 * - A `Prepare` carries the per-run nonce now, so it applies on every peer whatever the earlier run
 *   left in the log.
 * - A mint keeps the reproducible key, so a different mint at a reused key is refused, loudly, and
 *   mints nothing. The residual, an identical mint re-issued before replay minting once, is not
 *   asserted here: it is the documented cost of keeping `MintId` reproducible.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeddleReusedIncarnationTest {

    @Test
    fun prepareIssuedBeforeReplayUnderAReusedIncarnationReachesEveryPeer() = prepareArm(beforeReplay = true)

    @Test
    fun prepareIssuedAfterReplayUnderAReusedIncarnationReachesEveryPeer() = prepareArm(beforeReplay = false)

    @Test
    fun differentMintIssuedBeforeReplayUnderAReusedIncarnationIsRefused() = mintArm(beforeReplay = true)

    @Test
    fun differentMintIssuedAfterReplayUnderAReusedIncarnationIsRefused() = mintArm(beforeReplay = false)

    private fun prepareArm(beforeReplay: Boolean) = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val run = reusedIncarnationRun(beforeReplay) { it.prepare(NEW_EDGE) }
        assertAll(
            *run.rig(),
            {
                val applied = assertIs<ControlOutcome.Applied>(run.outcome, "the prepare applies")
                assertTrue(applied.index > run.oldIndex, "at its own entry, past the old one: $applied, old at ${run.oldIndex}")
            },
            *run.cluster.peers.map { peer ->
                { assertEquals(NEW_EDGE, peer.node.ledger.value.record(NEW_EDGE.id), "the record reaches ${peer.replica}") }
            }.toTypedArray(),
        )
    }

    private fun mintArm(beforeReplay: Boolean) = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val run = reusedIncarnationRun(beforeReplay) { it.mint(it.self, SUPPLY + 1L) }
        val newMints = run.cluster.controlLog(RESTARTED).filter { (_, envelope) ->
            envelope.command == ControlCommand.Mint(ACCEPTANCE_ROOT, run.replica, SUPPLY + 1L)
        }
        assertAll(
            *run.rig(),
            {
                // Before replay the act is already proposed when the old entry resolves it, so its
                // entry reaches the log and the dedup is what holds it. After replay it never leaves.
                assertEquals(if (beforeReplay) 1 else 0, newMints.size, "the rig: the new mint's entries in the log: $newMints")
            },
            {
                val refused = assertIs<ControlOutcome.Conflict>(run.outcome, "a different mint at a reused key is refused")
                val reason = assertIs<ControlConflict.Refused>(refused.conflict).reason
                assertTrue(run.baseKey in reason && "incarnation" in reason, "the refusal names the reuse: $reason")
            },
            {
                // A refused act reports where it was written: its own entry when proposed, else nowhere.
                val ownIndex = newMints.singleOrNull()?.first ?: ControlOutcome.NOT_COMMITTED
                assertEquals(ownIndex, run.outcome.index, "the refusal reports the act's own index, not the old one's ${run.oldIndex}")
            },
            *run.cluster.peers.map { peer ->
                { assertEquals(SUPPLY, peer.node.ledger.value.mintedTotal(), "nothing more is minted on ${peer.replica}") }
            }.toTypedArray(),
        )
    }

    /** What one run saw: the act's answer, and the witnesses that its key met the old one. */
    private class Run(
        val cluster: GovernedCluster,
        val replica: ReplicaId,
        val baseKey: String,
        val oldIndex: Long,
        val oldCommand: ControlCommand?,
        val beforeReplay: Boolean,
        val appliedWhenDrawn: Set<ReplicaId>,
        val enrolled: ControlOutcome,
        val outcome: ControlOutcome,
    ) {
        fun rig(): Array<() -> Unit> = arrayOf(
            {
                assertEquals(
                    ControlCommand.Mint(ACCEPTANCE_ROOT, replica, SUPPLY),
                    oldCommand,
                    "the rig: the earlier run's entry at $baseKey is its mint",
                )
            },
            { assertIs<ControlOutcome.Applied>(enrolled, "the rig: the restarted run enrolled itself") },
            {
                if (beforeReplay) {
                    assertEquals(emptySet(), appliedWhenDrawn, "the rig: replay had applied nothing when the key was drawn")
                } else {
                    assertTrue(replica in appliedWhenDrawn, "the rig: replay had passed the old entry when the key was drawn")
                }
            },
        )
    }

    /**
     * Boot the cluster with the earlier run's mint at sequence number 1, restart [RESTARTED] under
     * the same incarnation, and issue [act] at sequence number 1 of the new run, before or after
     * replay. Pumps until the act's entry, if it has one, has applied on every peer.
     */
    private suspend fun TestScope.reusedIncarnationRun(
        beforeReplay: Boolean,
        act: suspend (GovernedHeddleNode) -> ControlOutcome,
    ): Run {
        val cluster = governedCluster(size = 3, quantum = 10L, cap = SUPPLY * 2)
        cluster.bootstrap(topology = emptyList(), mint = mapOf(RESTARTED to SUPPLY))
        val earlier = cluster.peers[RESTARTED]
        // The earlier run drew 0 for its enroll and 1 for its mint; so does the new one.
        val baseKey = "${earlier.replica.value}#${earlier.incarnation}#1"
        val old = cluster.controlLog(RESTARTED).singleOrNull { (_, envelope) -> envelope.requestKey == baseKey }

        val fresh = cluster.restartUnderTheSameIncarnation(RESTARTED)
        val node = fresh.node
        // UNDISPATCHED, so before replay both keys are drawn before the apply loop has run at all.
        val enrolling = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { node.enroll(fresh.replica) }
        if (!beforeReplay) cluster.sim.awaitTrue("the enroll applies, so replay has passed the old entry") { enrolling.isCompleted }
        val appliedWhenDrawn = node.enrolledReplicas()
        val acting = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { act(node) }
        cluster.sim.awaitTrue("the act and the enroll answer") { enrolling.isCompleted && acting.isCompleted }
        cluster.pump()
        cluster.pump()
        return Run(
            cluster = cluster,
            replica = fresh.replica,
            baseKey = baseKey,
            oldIndex = old?.first ?: -1L,
            oldCommand = old?.second?.command,
            beforeReplay = beforeReplay,
            appliedWhenDrawn = appliedWhenDrawn,
            enrolled = enrolling.await(),
            outcome = acting.await(),
        )
    }

    private companion object {
        const val RESTARTED = 1
        const val SUPPLY = 100L
        val NEW_EDGE = AttachmentRecord(AttachmentId("root/n"), ACCEPTANCE_ROOT, GroupId("root/n"), Weight.ONE)
    }
}
