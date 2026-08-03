@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * **TEMPORARY — pre-fix evidence for #1984, deleted by the commit that adds the `require`s.**
 *
 * The issue derived the collapsed-window failure from `Random.nextLong`'s *documented* contract read against
 * the call site, not from running it. This probe pins what the engine actually does today, so the fix is
 * built on an observation rather than a prediction. It cannot survive the fix: after it,
 * `RaftConfig(electionTimeoutMin = 10.ms, electionTimeoutMax = 10.ms)` throws at construction, so there is no
 * node to start and nothing left to observe here. The permanent assertions live in
 * [RaftConfigTimingValidationTest].
 *
 * Deliberately **not** on `backgroundScope`: that reports uncaught exceptions as test failures, which would
 * make the expected failure unassertable. Same [SupervisorJob] + [CoroutineExceptionHandler] shape as
 * `awaitRestoreFailure` in `RaftTestFixtures`, and for the same reason — the failure happens in a coroutine,
 * not in the `raftNode` call.
 */
internal class RaftConfigCollapsedWindowProbeTest {

    @Test
    fun aCollapsedElectionWindowFailsInsideTheElectionTimerCoroutine() = raftRunTest(timeout = 30.seconds) {
        val collapsed = RaftConfig(
            electionTimeoutMin = 10.milliseconds,
            electionTimeoutMax = 10.milliseconds,
            heartbeatInterval = 2.milliseconds,
            expectVirtualTime = true,
            random = Random(RAFT_TEST_SEED),
        )

        val caught = CompletableDeferred<Throwable>()
        val nodeScope = CoroutineScope(
            StandardTestDispatcher(testScheduler) +
                SupervisorJob() +
                CoroutineExceptionHandler { _, e -> caught.complete(e) },
        )
        val failure = try {
            val self = NodeId("solo")
            nodeScope.raftNode(
                ClusterConfig(voters = setOf(self)),
                InMemoryRaftNetwork().transport(self),
                InMemoryRaftStorage(),
                collapsed,
            )
            withTimeoutOrNull(2.seconds) { caught.await() }
        } finally {
            nodeScope.cancel()
        }

        // Asserted narrowly on purpose. A green run then *is* the evidence — it says the failure is an
        // IllegalArgumentException raised by the stdlib draw on the empty range, not merely "something went
        // wrong" — and a red one prints whatever actually happened instead.
        val observed = assertNotNull(
            failure,
            "expected the collapsed window to fail the node's election timer, but the node started cleanly",
        )
        assertTrue(
            observed is IllegalArgumentException,
            "expected IllegalArgumentException from the election-timeout draw, " +
                "observed ${observed::class.simpleName}: ${observed.message}",
        )
        assertContains(
            observed.message.orEmpty(),
            "Random range is empty: [10, 10)",
            message = "expected the stdlib empty-range refusal, observed: ${observed.message}",
        )
    }
}
