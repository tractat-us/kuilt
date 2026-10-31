@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.core.PayloadTooLarge
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Cache-resident committed results survive a smaller leader budget; forward hops still must fit. */
class CachedProposePayloadBudgetTest {
    @Test
    fun localRetryReturnsCommittedEntryAfterLeaderBudgetShrinks() = raftRunTest {
        retryAfterShrink(forwarded = false)
    }

    @Test
    fun forwardedRetryReturnsCommittedEntryAfterLeaderBudgetShrinks() = raftRunTest {
        retryAfterShrink(forwarded = true)
    }

    private suspend fun TestScope.retryAfterShrink(forwarded: Boolean) {
        val sim = raftSim(this, backgroundScope, n = 3, maxPayloadBytes = 4096)
        val leader = sim.awaitLeader()
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val proposerId = if (forwarded) sim.nodeIds.first { it != leaderId } else leaderId
        val proposer = sim.nodes.getValue(proposerId)
        val command = ByteArray(1024) { it.toByte() }
        val original = proposer.propose(command, requestId = 41L)
        sim.awaitCommit(original.index)
        val before = sim.storages.getValue(leaderId).entries(1L)
        sim.network.setNodeMaxPayloadBytes(leaderId, 1024)
        sim.network.recording = true

        val outcome = runCatchingCancellable { proposer.propose(command, requestId = 41L) }
        val retry = assertNotNull(outcome.getOrNull(), "cached retry must succeed: ${outcome.exceptionOrNull()}")
        val afterRetry = sim.storages.getValue(leaderId).entries(1L)
        assertAll(
            { assertEquals(original, retry, "retry returns the original entry, including command and key") },
            { assertEquals(before, afterRetry, "retry appends nothing") },
            {
                if (forwarded) assertTrue(
                    sim.network.sent.any { it.from == proposerId && it.to == leaderId && it.message is RaftMessage.Forward },
                    "the fitting forward hop must actually reach the leader",
                )
            },
        )

        // A cache miss with the same bytes must still be refused before append.
        val refusal = assertFailsWith<PayloadTooLarge> { proposer.propose(command, requestId = 42L) }
        val afterRefusal = sim.storages.getValue(leaderId).entries(1L)
        assertAll(
            { assertEquals(1024, refusal.budgetBytes + refusal.reservedBytes, "leader's current budget is enforced") },
            { assertEquals(before, afterRefusal, "fresh refusal appends nothing") },
            { assertTrue(sim.network.overBudget.isEmpty(), "no oversized frame was sent") },
        )

        if (forwarded) {
            sim.network.setNodeMaxPayloadBytes(proposerId, 1024)
            val sentBefore = sim.network.sent.size
            assertFailsWith<PayloadTooLarge> { proposer.propose(command, requestId = 41L) }
            assertEquals(sentBefore, sim.network.sent.size, "a cached result cannot bypass the forward-hop limit")
        }
    }

    @Test
    fun localCachedKeyCannotAcknowledgeChangedCommand() = raftRunTest {
        changedCommandIsRejected(forwarded = false)
    }

    @Test
    fun forwardedCachedKeyCannotAcknowledgeChangedCommand() = raftRunTest {
        changedCommandIsRejected(forwarded = true)
    }

    private suspend fun TestScope.changedCommandIsRejected(forwarded: Boolean) {
        val sim = raftSim(this, backgroundScope, n = 3, maxPayloadBytes = 4096)
        val leader = sim.awaitLeader()
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val proposer = if (forwarded) sim.nodes.getValue(sim.nodeIds.first { it != leaderId }) else leader
        val original = proposer.propose(ByteArray(1024), requestId = 41L)
        sim.awaitCommit(original.index)
        val before = sim.storages.getValue(leaderId).entries(1L)
        sim.network.setNodeMaxPayloadBytes(leaderId, 1024)
        // Smaller bytes would pass the new budget: they must not receive a false commit receipt.
        // One type on both paths, so a caller's catch does not depend on which node leads.
        assertFailsWith<RequestIdConflictException> { proposer.propose(byteArrayOf(1), requestId = 41L) }
        assertEquals(before, sim.storages.getValue(leaderId).entries(1L), "changed bytes never append under a cached key")
    }
}
