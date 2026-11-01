@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.heddle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: the race is between two lock blocks with no suspension between them, so a single-threaded dispatcher runs them back to back and cannot enter the window.
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.FakeRaftNode
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.runConcurrencyStress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A `submit` racing the halt is always answered, and its answer says whether it wrote anything
 * (#1738).
 *
 * The defect this watches for is a check and a registration in two separate lock blocks: a call
 * passes the halt check, the apply loop latches and releases everyone registered, and the call then
 * registers a deferred nobody will ever complete, because nothing applies past the halt. No test
 * dispatcher can enter that window, and nothing in the code can be made to wait inside it, so this is
 * a real-threaded probe rather than a forced interleaving.
 *
 * **Its only failure signal for a lost call is the harness's cap.** Nothing here sets a wall-clock
 * budget of its own: a call that is never answered leaves `awaitAll` parked, and
 * [runConcurrencyStress] turns that into a failure naming the iteration. A starved box makes this
 * slower, not redder.
 *
 * The rig has to straddle the latch to mean anything, so it counts both sides: calls refused because
 * they came after the latch, and calls answered [ControlOutcome.Indeterminate] because they came
 * before it. Each proposal is counted where it lands, so "a refusal wrote nothing" is checked rather
 * than assumed.
 *
 * **JVM-hosted, `-Pconcurrency.stress.tests`-gated**, as every `*ConcurrencyTest` is.
 */
class HeddleSubmitHaltRaceConcurrencyTest {

    @Test
    fun aSubmitRacingTheHaltIsAlwaysAnsweredAndItsAnswerSaysWhetherItWrote() = runConcurrencyStress { stage ->
        var refused = 0
        var indeterminate = 0
        var iterations = 0
        repeat(ITERATIONS) { iter ->
            val log = HaltingLog()
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val plane = HeddleControlPlane(
                raft = log,
                self = ReplicaId("self"),
                scope = scope,
                sink = ControlLedgerSink { },
                membership = ControlMembershipSink { },
                barrier = ControlBarrierSink { SlotFinals.ZERO },
                initial = EntitlementLedger.ZERO,
                incarnation = "probe",
            )
            stage.at("iter=$iter") {
                "proposed=${log.proposed.get()} of $SUBMITTERS gate=${plane.writeGateClosedBecause}"
            }
            val outcomes = coroutineScope {
                val go = CompletableDeferred<Unit>()
                val calls = List(SUBMITTERS) { n ->
                    async(Dispatchers.Default) {
                        go.await()
                        spin((iter + n) % STAGGER)
                        plane.submit(ControlCommand.Enroll(ReplicaId("other")))
                    }
                }
                val halting = async(Dispatchers.Default) {
                    go.await()
                    log.commitUnreadable()
                }
                go.complete(Unit)
                halting.await()
                calls.awaitAll()
            }
            scope.cancel()

            val released = outcomes.count { it is ControlOutcome.Indeterminate }
            assertAll(
                {
                    assertEquals(
                        SUBMITTERS,
                        released + outcomes.count { it is ControlOutcome.Conflict },
                        "iter=$iter: $outcomes",
                    )
                },
                { assertEquals(log.proposed.get(), released, "iter=$iter: a refusal means nothing was written") },
            )
            refused += outcomes.size - released
            indeterminate += released
            iterations++
        }
        assertAll(
            { assertEquals(ITERATIONS, iterations, "rig: every iteration ran") },
            { assertTrue(refused > 0, "rig: no call came after the latch ($indeterminate before it)") },
            { assertTrue(indeterminate > 0, "rig: no call came before the latch ($refused after it)") },
        )
    }

    /** Spins rather than suspends, so the stagger between callers is not a trip through the scheduler. */
    private fun spin(rounds: Int) {
        var sink = 0
        repeat(rounds) { sink += it }
        check(sink >= 0)
    }

    /**
     * A log whose proposals never commit, so a call can only be answered by the halt, and which is safe
     * to drive from several threads, unlike the double it delegates the rest of [RaftNode] to.
     */
    private class HaltingLog(
        delegate: RaftNode = FakeRaftNode(initialRole = RaftRole.Leader),
    ) : RaftNode by delegate {
        val proposed = AtomicInteger()
        private val entries = Channel<Committed>(Channel.UNLIMITED)

        override fun committedFrom(fromIndex: Long): Flow<Committed> = entries.receiveAsFlow()

        override suspend fun propose(command: ByteArray, requestId: Long): LogEntry {
            val at = proposed.incrementAndGet()
            return LogEntry(index = at + 1L, term = 1L, command = command)
        }

        fun commitUnreadable() {
            val entry = LogEntry(index = 1L, term = 1L, command = ControlLogVectors.taggedAtVersion(99))
            check(entries.trySend(Committed.Entry(entry)).isSuccess)
        }
    }

    private companion object {
        /**
         * Headroom, not the mechanism: with the check and the registration split, the first measured
         * run hung at `iter=20`. Each iteration logs one halt at `error`, so more only costs XML.
         */
        const val ITERATIONS = 1000
        const val SUBMITTERS = 4
        const val STAGGER = 64
    }
}
