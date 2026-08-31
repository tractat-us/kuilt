@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft.test

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Self-tests for [dumpOnWedge] — the wedge-legibility half of the #1382 fix.
 *
 * A genuine wedge inside a [raftSimTest] body is reported by `runTest` cancelling the test
 * coroutine, which historically surfaced as a bare `UncompletedCoroutinesError` carrying **no**
 * cluster state at all. [dumpOnWedge] intercepts that cancellation, emits a
 * [MultiNodeRaftSim.dumpState] snapshot, and rethrows.
 *
 * These tests drive the cancellation **directly** rather than waiting out the real
 * [RAFT_SIM_WEDGE_BACKSTOP] budget, so they cost the same as any other harness self-test.
 */
class RaftSimWedgeDumpTest {

    /**
     * The wedge path: a body that never returns is cancelled, and before the cancellation
     * propagates a full state dump must be emitted. The dump has to reflect the *live* cluster —
     * asserting it names a leader proves [MultiNodeRaftSim.dumpState] actually ran (it is a
     * `suspend` function touching storage, so it only works under `NonCancellable`).
     */
    @Test
    fun cancelledBodyEmitsStateDumpThenRethrows() = raftSimTest(n = 3) { sim ->
        sim.awaitLeader()
        val dumps = mutableListOf<String>()
        var rethrew = false

        val wedged = launch {
            try {
                dumpOnWedge(sim, emit = { dumps += it }) { awaitCancellation() }
            } catch (e: CancellationException) {
                rethrew = true
                throw e
            }
        }
        sim.settle()
        wedged.cancelAndJoin()

        val dump = dumps.singleOrNull()
        assertAll(
            { assertEquals(1, dumps.size, "expected exactly one wedge dump, got $dumps") },
            { assertNotNull(dump) },
            { assertContains(dump.orEmpty(), "MultiNodeRaftSim state dump") },
            { assertContains(dump.orEmpty(), "Leader") },
            { assertTrue(rethrew, "dumpOnWedge must rethrow the CancellationException, not swallow it") },
        )
    }

    /**
     * Only cancellation is a wedge. An ordinary assertion/logic failure inside the body — including
     * the [AssertionError] the bounded `await*` helpers already throw *with* their own dump — must
     * propagate untouched and must not emit a second, redundant dump.
     *
     * The `catch (e: IllegalStateException)` below is the exact shape #2535 is about —
     * `CancellationException` extends [IllegalStateException], so such an arm normally swallows the
     * very cancellation this file is a self-test for. It is sound **here** only because the guarded
     * body cannot suspend: `error("boom")` throws synchronously, so [dumpOnWedge] returns without
     * reaching a suspension point and no cancellation can be delivered inside the `try`. Give the body
     * anything that suspends and this arm must gain `currentCoroutineContext().ensureActive()`.
     */
    @Test
    fun ordinaryFailureIsNotTreatedAsAWedge() = raftSimTest(n = 3) { sim ->
        val dumps = mutableListOf<String>()
        var caughtMessage: String? = null

        try {
            dumpOnWedge(sim, emit = { dumps += it }) { error("boom") }
        } catch (e: IllegalStateException) {
            caughtMessage = e.message
        }

        assertAll(
            { assertEquals("boom", caughtMessage, "the original failure must propagate untouched") },
            { assertTrue(dumps.isEmpty(), "an ordinary failure must not emit a wedge dump: $dumps") },
        )
    }

    /**
     * The happy path must be completely transparent: the body's value comes back and nothing is
     * emitted.
     */
    @Test
    fun normalCompletionReturnsTheBodyValueAndEmitsNothing() = raftSimTest(n = 3) { sim ->
        val dumps = mutableListOf<String>()
        val result = dumpOnWedge(sim, emit = { dumps += it }) { "ok" }

        assertAll(
            { assertEquals("ok", result) },
            { assertTrue(dumps.isEmpty(), "a passing test must not emit a wedge dump: $dumps") },
        )
    }
}
