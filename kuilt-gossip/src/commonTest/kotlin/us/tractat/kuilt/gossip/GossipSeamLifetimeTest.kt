package us.tractat.kuilt.gossip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class GossipSeamLifetimeTest {
    private val self = PeerId("self")
    private val remote = PeerId("remote")

    private fun gossip(base: Seam): GossipSeam =
        GossipSeam(
            base = base,
            random = Random(0),
            clock = { Instant.fromEpochMilliseconds(0) },
            jitter = Duration.ZERO..Duration.ZERO,
        )

    private fun TestScope.callerScope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))

    @Test
    fun repeatedCloseLeavesOnlyTheCallersOtherWork() = runTest {
        val scope = callerScope()
        val caller = scope.coroutineContext[Job] ?: error("missing caller job")
        val sibling = scope.launch { awaitCancellation() }
        repeat(3) { cycle ->
            val base = FakeSeam(self, setOf(self, remote))
            val seam = gossip(base)
            seam.start(scope)
            runCurrent()
            assertEquals(setOf(remote), seam.activePeers.value, "detectors must start before close")

            seam.close()
            seam.close()
            runCurrent()

            assertAll(
                { assertTrue(caller.isActive, "close must preserve the caller scope") },
                { assertTrue(sibling.isActive, "close must preserve unrelated work") },
                { assertEquals(listOf(sibling), caller.children.toList(), "cycle $cycle left gossip workers") },
            )
        }
    }

    @Test
    fun baseTearStopsWorkersEvenWithAStaleRoster() = runTest {
        val scope = callerScope()
        val caller = scope.coroutineContext[Job] ?: error("missing caller job")
        val base = FakeSeam(self, setOf(self, remote))
        // Deliberately non-conforming roster: cleanup must not rely on the base
        // removing neighbours to stop their detectors.
        val staleRoster = MutableStateFlow(setOf(self, remote))
        val seam = gossip(object : Seam by base {
            override val peers = staleRoster
        })
        seam.start(scope)
        runCurrent()
        assertEquals(setOf(remote), seam.activePeers.value, "detectors must be running")

        base.tear()
        runCurrent()

        assertAll(
            { assertEquals(setOf(self, remote), seam.peers.value, "stale-roster rig must remain active") },
            { assertTrue(caller.isActive, "base tear must preserve the caller scope") },
            { assertEquals(emptyList(), caller.children.toList(), "base tear left gossip workers") },
        )
    }

    @Test
    fun closeBeforeWorkersRunCompletesIncoming() = runTest {
        val scope = callerScope()
        val caller = scope.coroutineContext[Job] ?: error("missing caller job")
        val seam = gossip(FakeSeam(self))
        seam.start(scope)
        seam.close()
        val collector = backgroundScope.launch { seam.incoming.collect {} }
        runCurrent()

        assertAll(
            { assertTrue(collector.isCompleted, "incoming must complete even if the inbound body never ran") },
            { assertEquals(emptyList(), caller.children.toList(), "queued workers survived close") },
            { assertTrue(caller.isActive) },
        )
    }

    @Test
    fun closeBeforeStartCannotCreateWorkers() = runTest {
        val scope = callerScope()
        val caller = scope.coroutineContext[Job] ?: error("missing caller job")
        val seam = gossip(FakeSeam(self))
        seam.close()
        seam.start(scope)
        runCurrent()

        assertAll(
            { assertEquals(emptyList(), caller.children.toList(), "start after close created workers") },
            { assertTrue(caller.isActive) },
        )
    }

    @Test
    fun failingBaseCloseStillStopsWorkers() = runTest {
        val scope = callerScope()
        val caller = scope.coroutineContext[Job] ?: error("missing caller job")
        val base = FakeSeam(self, setOf(self, remote))
        val seam = gossip(object : Seam by base {
            override suspend fun close(reason: CloseReason): Unit = throw UnsupportedOperationException("close failed")
        })
        seam.start(scope)
        runCurrent()
        assertEquals(setOf(remote), seam.activePeers.value)

        assertFailsWith<UnsupportedOperationException> { seam.close() }
        runCurrent()

        assertAll(
            { assertEquals(emptyList(), caller.children.toList(), "failed base close left gossip workers") },
            { assertTrue(caller.isActive) },
        )
    }

    @Test
    fun callerCancellationStopsWorkersAndCompletesIncoming() = runTest {
        val scope = callerScope()
        val caller = scope.coroutineContext[Job] ?: error("missing caller job")
        val seam = gossip(FakeSeam(self, setOf(self, remote)))
        seam.start(scope)
        runCurrent()
        assertEquals(setOf(remote), seam.activePeers.value)
        val collector = backgroundScope.launch { seam.incoming.collect {} }
        caller.cancel()
        runCurrent()

        assertAll(
            { assertTrue(caller.isCompleted, "workers must remain children of the caller") },
            { assertTrue(collector.isCompleted, "caller cancellation must complete incoming") },
        )
    }

    @Test
    fun callerCancellationBeforeWorkersRunCompletesIncoming() = runTest {
        val scope = callerScope()
        val caller = scope.coroutineContext[Job] ?: error("missing caller job")
        val seam = gossip(FakeSeam(self))
        seam.start(scope)
        caller.cancel()
        val collector = backgroundScope.launch { seam.incoming.collect {} }
        runCurrent()

        assertAll(
            { assertTrue(caller.isCompleted, "queued workers must stop with the caller") },
            { assertTrue(collector.isCompleted, "cancellation before first dispatch must complete incoming") },
        )
    }
}
