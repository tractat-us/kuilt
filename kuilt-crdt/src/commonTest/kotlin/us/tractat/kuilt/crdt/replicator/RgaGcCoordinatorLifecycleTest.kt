@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.crdt.replicator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import us.tractat.kuilt.conformance.CloseableLifecycleConformanceSuite
import us.tractat.kuilt.core.ScopedCloseable
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.VersionVector

class RgaGcCoordinatorLifecycleTest : CloseableLifecycleConformanceSuite() {

    override fun create(scope: CoroutineScope): ScopedCloseable = RgaGcCoordinator(
        state = MutableStateFlow(Rga.empty<String>()),
        cutFrontier = MutableStateFlow(CutFrontier.EMPTY),
        delivered = MutableStateFlow(VersionVector.EMPTY),
        applyCompaction = {},
        scope = scope,
    )

    override fun backgroundJobsOf(instance: ScopedCloseable): List<Job> =
        listOf((instance as RgaGcCoordinator<*>).gcJobForTest)
}
