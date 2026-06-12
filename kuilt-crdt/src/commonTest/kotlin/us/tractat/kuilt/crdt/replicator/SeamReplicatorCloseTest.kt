@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.crdt.replicator

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val TEST_CONFIG = SeamReplicatorConfig(expectVirtualTime = true)

class SeamReplicatorCloseTest {

    // ── Tier 1 regression — child-Job ownership ──────────────────────────────

    @Test
    fun backgroundJobsActiveBeforeClose() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seam = loom.host(Pattern("close-test"))
        val replicator = SeamReplicator(
            replica = ReplicaId(seam.selfId.value),
            seam = seam,
            initial = GCounter.ZERO,
            messageSerializer = ReplicatorMessage.serializer(GCounter.serializer()),
            scope = backgroundScope,
            config = TEST_CONFIG,
        )
        assertTrue(replicator.backgroundJobsForTest.all { it.isActive })
        replicator.close()
    }

    @Test
    fun closeStopsAllBackgroundJobs() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seam = loom.host(Pattern("close-test"))
        val replicator = SeamReplicator(
            replica = ReplicaId(seam.selfId.value),
            seam = seam,
            initial = GCounter.ZERO,
            messageSerializer = ReplicatorMessage.serializer(GCounter.serializer()),
            scope = backgroundScope,
            config = TEST_CONFIG,
        )
        replicator.close()
        assertTrue(replicator.backgroundJobsForTest.all { !it.isActive })
    }

    @Test
    fun closeIsIdempotent() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seam = loom.host(Pattern("close-idempotent"))
        val replicator = SeamReplicator(
            replica = ReplicaId(seam.selfId.value),
            seam = seam,
            initial = GCounter.ZERO,
            messageSerializer = ReplicatorMessage.serializer(GCounter.serializer()),
            scope = backgroundScope,
            config = TEST_CONFIG,
        )
        replicator.close()
        replicator.close() // must not throw
        assertTrue(replicator.backgroundJobsForTest.all { !it.isActive })
    }

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
        val replicator = SeamReplicator(
            replica = ReplicaId(seam.selfId.value),
            seam = seam,
            initial = GCounter.ZERO,
            messageSerializer = ReplicatorMessage.serializer(GCounter.serializer()),
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
        val replicator = SeamReplicator(
            replica = replica,
            seam = seam,
            initial = GCounter.ZERO,
            messageSerializer = ReplicatorMessage.serializer(GCounter.serializer()),
            scope = backgroundScope,
            config = TEST_CONFIG,
        )
        replicator.close()
        assertFailsWith<IllegalStateException> {
            replicator.apply(GCounter.ZERO.inc(replica))
        }
    }
}
