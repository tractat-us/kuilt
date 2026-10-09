package us.tractat.kuilt.test

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FaultySeamReleaseCloseTest {
    @Test
    fun closeDuringReleasedBroadcastCountsDrops() = closeDuringRelease(addressed = false)

    @Test
    fun closeDuringReleasedSendToCountsDrops() = closeDuringRelease(addressed = true)

    @Test
    fun cancelledReleasedBroadcastDoesNotCountDropsOrContinue() = cancelDuringRelease(addressed = false)

    @Test
    fun cancelledReleasedSendToDoesNotCountDropsOrContinue() = cancelDuringRelease(addressed = true)

    private fun closeDuringRelease(addressed: Boolean) = runTest {
        val loom = InMemoryLoom()
        val inner = loom.host(Pattern("sender"))
        val peer = loom.join(InMemoryTag("sender"))
        val gated = GatedSendSeam(inner)
        val failures = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            backgroundScope.coroutineContext +
                SupervisorJob(backgroundScope.coroutineContext[Job]) +
                CoroutineExceptionHandler { _, failure -> failures += failure },
        )
        val faulty = FaultySeam(
            gated,
            scope,
            FaultProfile.ReorderWindow(windowSize = 3, seed = 0L, direction = Direction.Outbound),
        )
        repeat(2) { send(faulty, peer.selfId, addressed) }
        assertEquals(2L, faulty.framesDelayed, "precondition: both frames are held")

        faulty.heal()
        testScheduler.runCurrent()
        assertAll(
            { assertEquals(1, gated.attempts, "rig: release entered the delegate send") },
            { assertTrue(inner.state.value is SeamState.Woven, "rig: send entered while the seam was live") },
        )
        // Close AFTER the old state pre-check would have passed and BEFORE the send takes effect.
        // No throwing fake or timing lottery: InMemorySeam itself supplies the closed-send failure.
        faulty.close()
        gated.proceed.complete(Unit)
        testScheduler.runCurrent()

        assertAll(
            { assertEquals(2L, faulty.framesDropped, "both released frames refused by close count as drops") },
            { assertEquals(0L, faulty.framesDelivered) },
            { assertTrue(failures.isEmpty(), "release must not leak into its scope: $failures") },
        )
    }

    private fun cancelDuringRelease(addressed: Boolean) = runTest {
        val loom = InMemoryLoom()
        val inner = loom.host(Pattern("sender"))
        val peer = loom.join(InMemoryTag("sender"))
        val gated = GatedSendSeam(inner)
        val faulty = FaultySeam(
            gated,
            backgroundScope,
            FaultProfile.ReorderWindow(windowSize = 3, seed = 0L, direction = Direction.Outbound),
        )
        repeat(2) { send(faulty, peer.selfId, addressed) }
        faulty.heal()
        testScheduler.runCurrent()
        assertEquals(1, gated.attempts, "rig: release is suspended inside the delegate send")

        val releaseJob = requireNotNull(gated.sendJob)
        releaseJob.cancel()
        testScheduler.runCurrent()

        assertAll(
            { assertTrue(releaseJob.isCompleted && releaseJob.isCancelled, "rig: caller cancellation completed") },
            { assertEquals(0L, faulty.framesDropped, "caller cancellation is not a closed-link drop") },
            { assertEquals(0L, faulty.framesDelivered) },
            { assertEquals(1, gated.attempts, "cancellation must stop the remaining release") },
        )
        faulty.close()
    }

    private suspend fun send(seam: Seam, peer: PeerId, addressed: Boolean) {
        if (addressed) seam.sendTo(peer, byteArrayOf(1)) else seam.broadcast(byteArrayOf(1))
    }

    /** Schedule a close in the check/send window without changing FaultySeam's production code. */
    private class GatedSendSeam(private val delegate: Seam) : Seam by delegate {
        val proceed = CompletableDeferred<Unit>()
        var attempts = 0
        var sendJob: Job? = null

        override suspend fun broadcast(payload: ByteArray) {
            beforeSend()
            delegate.broadcast(payload)
        }

        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            beforeSend()
            delegate.sendTo(peer, payload)
        }

        private suspend fun beforeSend() {
            attempts++
            sendJob = currentCoroutineContext()[Job]
            proceed.await()
        }
    }
}
