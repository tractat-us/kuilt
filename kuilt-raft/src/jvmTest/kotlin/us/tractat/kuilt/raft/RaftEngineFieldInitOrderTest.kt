@file:Suppress("ForbiddenImport") // deliberate: real OS-thread construct/cancel race — the #1077 field-init-order NPE only manifests when the actor teardown runs on a different thread than the still-in-flight constructor, so this probe needs a real dispatcher, not a virtual one.

package us.tractat.kuilt.raft

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import us.tractat.kuilt.raft.internal.RaftEngine
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * Regression for #1077 — a field-initialization-order race in [RaftEngine].
 *
 * The `init` block launches the actor coroutine; the actor's `finally` teardown calls
 * `failForwardedProposals`, which dereferences `forwardedProposals` and `waitingForLeader`.
 * Before the fix those two fields were declared *after* the `init` block, so when a node's
 * scope was cancelled in the narrow window during construction, the teardown ran before
 * their initializers had executed → a `NullPointerException` escaped `failForwardedProposals`.
 * Every *other* field the teardown touches (`pending`/`pendingConfigChange`/`pendingReads`/
 * transfer state) is declared before `init`, which is why only these two NPE'd. The fix moves
 * the two declarations above `init`.
 *
 * **The race is genuinely multi-threaded** and does NOT reproduce under a single-threaded /
 * virtual-time dispatcher: there the constructor runs atomically with respect to coroutine
 * suspension, so every field is initialized before the launched actor can reach its teardown.
 * It only manifests when the actor teardown runs on a *different* thread than the still-in-flight
 * constructor. So this probe hammers the construct-then-cancel window on a real multi-threaded
 * dispatcher and asserts no NPE escapes. On the buggy code it surfaces (~12 hits / 5000
 * iterations locally); after the fix, zero.
 *
 * **JVM-hosted on purpose.** The fix lives in `commonMain`, but the race needs real OS-thread
 * parallelism: wasmJs is single-threaded and Kotlin/Native's pool is too slow for the iteration
 * count. The JVM gives fast, reliable real-thread coverage.
 */
class RaftEngineFieldInitOrderTest {

    @Test
    fun scopeCancelDuringConstruction_neverNpes() {
        val firstNpe = AtomicReference<Throwable?>(null)
        val cluster = ClusterConfig(voters = setOf(NodeId("a"), NodeId("b"), NodeId("c")))

        repeat(ITERATIONS) { i ->
            val handler = CoroutineExceptionHandler { _, e ->
                val npe = e as? NullPointerException ?: e.cause as? NullPointerException
                if (npe != null) firstNpe.compareAndSet(null, npe)
            }
            val network = InMemoryRaftNetwork()
            val scope = CoroutineScope(Dispatchers.Default + Job() + handler)
            RaftEngine(
                cluster,
                network.transport(NodeId("a")),
                InMemoryRaftStorage(),
                RaftConfig(random = Random(RAFT_TEST_SEED + i)),
                scope,
                onMetric = null,
            )
            scope.cancel() // races the still-in-flight construction — the #1077 window
        }

        // Let any in-flight actor-teardown coroutines drain before asserting.
        Thread.sleep(DRAIN_MILLIS)
        assertNull(
            firstNpe.get(),
            "actor teardown NPE'd during construction — #1077 field-init-order race regressed",
        )
    }

    private companion object {
        /** Wide enough that the ~0.24%/iteration race is hit with overwhelming probability on buggy code. */
        const val ITERATIONS = 5000

        /** Grace period for teardown coroutines dispatched onto the shared pool to complete. */
        const val DRAIN_MILLIS = 500L
    }
}
