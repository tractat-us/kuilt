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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val TEST_CONFIG = SeamReplicatorConfig(expectVirtualTime = true)

class SeamReplicatorCloseTest {

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
        replicator.close() // prevent infinite anti-entropy loop from blocking runTest cleanup
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
}
