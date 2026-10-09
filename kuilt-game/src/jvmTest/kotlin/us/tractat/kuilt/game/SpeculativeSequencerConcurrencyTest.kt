@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.raft.ClientId
import us.tractat.kuilt.raft.DedupKey
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.Snapshot
import us.tractat.kuilt.raft.test.FakeRaftNode
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pause a real proposal between reading and publishing its optimistic state, then drive a
 * committed event on another thread. With mutual exclusion the collector parks behind the
 * proposal; without it the collector finishes first and the proposal overwrites its result.
 * The scheduler controls event delivery, but the two callers really run on different threads.
 */
class SpeculativeSequencerConcurrencyTest {
    @Test
    fun foreignCommitCannotBeOverwrittenByProposal() = overlap(reset = false, explicitRequestId = false)

    @Test
    fun foreignCommitCannotBeOverwrittenByExplicitRequestProposal() = overlap(reset = false, explicitRequestId = true)

    @Test
    fun snapshotResetCannotBeOverwrittenByProposal() = overlap(reset = true, explicitRequestId = false)

    @Test
    fun snapshotResetCannotBeOverwrittenByExplicitRequestProposal() = overlap(reset = true, explicitRequestId = true)

    private fun overlap(reset: Boolean, explicitRequestId: Boolean) {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(Job() + StandardTestDispatcher(scheduler))
        val enteredApply = CountDownLatch(1)
        val releaseApply = CountDownLatch(1)
        val pauseOnce = AtomicBoolean(true)
        val failure = AtomicReference<Throwable?>()
        val game = object : SpeculativeGame<Int, Int> {
            override fun apply(state: Int, action: Int): Int {
                if (action == 10 && pauseOnce.compareAndSet(true, false)) {
                    enteredApply.countDown()
                    releaseApply.await()
                }
                return state + action
            }
            override fun snapshot(state: Int): Int = state
            override fun restore(snapshot: Int): Int = snapshot
            override fun fromSnapshot(bytes: ByteArray): Int = Cbor.decodeFromByteArray(Int.serializer(), bytes)
        }
        val node = FakeRaftNode(initialRole = RaftRole.Leader)
        // Return a successful proposal without emitting its commit yet. FakeRaftNode's mutable
        // proposal bookkeeping is only accessed by the proposal thread, after the injected event.
        node.proposeBehavior = { command -> LogEntry(index = 6L, term = 1L, command = command) }
        val seq = SpeculativeSequencer(TurnSequencer(node, Int.serializer()), game, 0, scope)
        runBlocking {
            if (reset) {
                node.pushInstall(Snapshot(throughIndex = 5L, state = Cbor.encodeToByteArray(Int.serializer(), 50)))
            } else {
                node.pushCommitted(LogEntry(
                    index = 1L,
                    term = 1L,
                    command = Cbor.encodeToByteArray(Int.serializer(), 3),
                    dedupKey = DedupKey(ClientId("foreign"), 1L),
                ))
            }
        }
        val proposer = thread(name = "speculative-proposer") {
            try {
                runBlocking {
                    if (explicitRequestId) seq.propose(10, requestId = 1L) else seq.propose(10)
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        var collector: Thread? = null
        try {
            enteredApply.await()
            collector = thread(name = "speculative-collector") {
                try {
                    scheduler.runCurrent()
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
            // Wait for an actual ordering witness, not an elapsed-time guess. A fixed collector
            // waits on the proposer's lock; an unguarded collector completes the event. Sleeping
            // yields the CPU to both workers. No timeout result is used as a test assertion.
            val threads = ManagementFactory.getThreadMXBean()
            while (collector.isAlive && threads.getThreadInfo(collector.threadId())?.lockOwnerId != proposer.threadId()) {
                Thread.sleep(1)
            }
        } finally {
            releaseApply.countDown()
            proposer.join()
            collector?.join()
            scope.cancel()
            scheduler.runCurrent()
        }
        failure.get()?.let { throw it }
        assertEquals(
            Triple(1, if (reset) 0 else 1, if (reset) 50 else 13),
            Triple(seq.confirmedCount.value, seq.pendingCount, seq.speculativeState.value),
            "committed event must survive the overlapping optimistic state publication",
        )
    }
}
