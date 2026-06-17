@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.conformance.CloseableLifecycleConformanceSuite
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.ScopedCloseable
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.fakeSeamPair
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val TEST_CONFIG = QuilterConfig(expectVirtualTime = true)

// Uses a non-suspend [fakeSeamPair] seam so the TCK's non-suspend `create` can construct
// the replicator (the real `loom.host` is a suspend call). The lifecycle tests only need a
// working `incoming`/`peers`/`selfId`, which the fake provides.
private fun makeReplicator(scope: CoroutineScope): Quilter<GCounter> {
    val (seam, _) = fakeSeamPair(PeerId("self"), PeerId("other"))
    return Quilter(
        replica = ReplicaId(seam.selfId.value),
        seam = seam,
        initial = GCounter.ZERO,
        messageSerializer = QuiltMessage.serializer(GCounter.serializer()),
        scope = scope,
        config = TEST_CONFIG,
    )
}

class QuilterCloseTest : CloseableLifecycleConformanceSuite() {

    override fun create(scope: CoroutineScope): ScopedCloseable = makeReplicator(scope)

    override fun backgroundJobsOf(instance: ScopedCloseable): List<Job> =
        (instance as Quilter<*>).backgroundJobsForTest

    // ── Tier 2 — auto-close on seam teardown (re-entrancy proof) ─────────────
    //
    // When the seam closes, its `incoming` flow completes. The `onCompletion { close() }`
    // added to the incoming collector then calls close() from within the coroutine that
    // is itself completing — a re-entrant call. This test proves that path is benign:
    // jobs go inactive and no exception is thrown.

    @Test
    fun seamCloseTrigersAutoCloseViaOnCompletion() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seam = loom.host(Pattern("auto-close-test"))
        val replicator = Quilter(
            replica = ReplicaId(seam.selfId.value),
            seam = seam,
            initial = GCounter.ZERO,
            messageSerializer = QuiltMessage.serializer(GCounter.serializer()),
            scope = backgroundScope,
            config = TEST_CONFIG,
        )
        // Sanity: background jobs are active before the seam is torn.
        assertTrue(replicator.backgroundJobsForTest.all { it.isActive })

        // Closing the seam completes incoming → onCompletion fires → replicator.close().
        seam.close()

        // With UnconfinedTestDispatcher all pending coroutine work runs eagerly.
        // The replicator should now be closed and all background jobs inactive.
        assertTrue(replicator.backgroundJobsForTest.all { !it.isActive })
    }

    // ── Tier 3 — fail loud after close ───────────────────────────────────────

    @Test
    fun applyAfterCloseThrows() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seam = loom.host(Pattern("fail-loud-test"))
        val replica = ReplicaId(seam.selfId.value)
        val replicator = Quilter(
            replica = replica,
            seam = seam,
            initial = GCounter.ZERO,
            messageSerializer = QuiltMessage.serializer(GCounter.serializer()),
            scope = backgroundScope,
            config = TEST_CONFIG,
        )
        replicator.close()
        assertFailsWith<IllegalStateException> {
            replicator.apply(GCounter.ZERO.inc(replica))
        }
    }

    // ── #329 regression — child-Job ownership stops jobs on parent cancel ─────
    //
    // The original #329 hang came from an anti-entropy loop left running when a test
    // forgot to close the replicator. Child-Job ownership (ScopedCloseable) makes the
    // owned coroutines structural children of the parent scope's Job, so cancelling the
    // parent — exactly what `runTest` does at teardown — stops them WITHOUT any explicit
    // close(). This proves the mechanism deterministically (no reliance on runTest cleanup
    // timing, so it cannot itself hang), and confirms that relying on scope cancellation
    // for cleanup is now a legitimate, leak-free pattern.

    @Test
    fun parentScopeCancellationStopsJobsWithoutClose() = runTest(UnconfinedTestDispatcher()) {
        val parent = CoroutineScope(coroutineContext + Job())
        val replicator = makeReplicator(parent)
        assertTrue(replicator.backgroundJobsForTest.all { it.isActive }, "jobs active before cancel")

        parent.cancel() // caller's scope dies; no one called close()

        assertTrue(
            replicator.backgroundJobsForTest.all { !it.isActive },
            "child-Job ownership must stop owned jobs when the parent scope is cancelled (the #329 fix)",
        )
    }
}
