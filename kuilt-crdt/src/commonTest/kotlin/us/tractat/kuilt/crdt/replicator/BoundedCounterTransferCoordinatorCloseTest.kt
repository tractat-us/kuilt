@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.crdt.replicator

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.MuxSeam
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.BoundedCounter
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val CLOSE_TEST_REPLICATOR_CFG = SeamReplicatorConfig(expectVirtualTime = true)

private suspend fun makeCoordinator(
    scope: kotlinx.coroutines.CoroutineScope,
): BoundedCounterTransferCoordinator {
    val loom = InMemoryLoom()
    val rawSeam = loom.host(Pattern("coord-close-test"))
    val mux = MuxSeam(rawSeam, scope)
    val replicaId = ReplicaId(rawSeam.selfId.value)
    val initial = BoundedCounter.init(mapOf(replicaId to 100L))
    val replicator = SeamReplicator(
        replica = replicaId,
        seam = mux.channel(0x00),
        initial = initial,
        messageSerializer = ReplicatorMessage.serializer(BoundedCounter.serializer()),
        scope = scope,
        config = CLOSE_TEST_REPLICATOR_CFG,
    )
    return BoundedCounterTransferCoordinator(
        coordSeam = mux.channel(0x01),
        state = replicator.state,
        self = replicaId,
        applyTransfer = { patch -> replicator.apply(patch) },
        scope = scope,
    )
}

class BoundedCounterTransferCoordinatorCloseTest {

    @Test
    fun backgroundJobsActiveBeforeClose() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = makeCoordinator(backgroundScope)
        assertTrue(coordinator.backgroundJobsForTest.all { it.isActive })
    }

    @Test
    fun closeStopsAllBackgroundJobs() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = makeCoordinator(backgroundScope)
        coordinator.close()
        assertTrue(coordinator.backgroundJobsForTest.all { !it.isActive })
    }

    @Test
    fun closeIsIdempotent() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = makeCoordinator(backgroundScope)
        coordinator.close()
        coordinator.close() // must not throw
        assertFalse(coordinator.backgroundJobsForTest.any { it.isActive })
    }
}
