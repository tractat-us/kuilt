@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

package us.tractat.kuilt.session

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
// ALLOW-realDispatcher: the events collector must run inside the room's own emit, on the room's thread, to act between failAdmission's critical section and its leave without a test hook in production
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.RejectCode
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * A never-admitted joiner ends once (review of #3697). A refusal fails its admission, and a
 * superseded `Reject` naming the same Hello is handled after `failAdmission` has claimed the ending
 * and before its `leave` has closed the room. The room must not then also end superseded: one
 * `AdmissionFailed`, and no `Left(self, Superseded)`.
 *
 * The window has no suspension point in it, so only a second thread can land there, and only a
 * thread held inside the window can make that happen every time. The hold is the room's own
 * `events` emit: a collector on an unconfined dispatcher runs inside that emit, on the thread
 * running `failAdmission`, after its critical section and before its `leave`. It hands the room the
 * superseded `Reject` and waits until the room's other thread has checked it
 * ([SeamRoom.rejectCheckedForTest]). `leave` then waits on the lock that check holds, so the
 * decision is taken with the admission failed and the room still open.
 *
 * Real threads, but not a stress probe, so not a `*ConcurrencyTest`: the interleaving is forced,
 * not sampled, and every wait is bounded, so a broken rig fails rather than hangs.
 */
class SupersededAfterAdmissionFailureTest {
    private val self = PeerId("tab-a")
    private val host = PeerId("host")

    @Test
    fun aSupersededRejectHandledWhileAFailedAdmissionIsEndingAddsNoSecondEnding() = runBlocking {
        withTimeout(60.seconds) { probe() }
    }

    private suspend fun probe() {
        Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
            val scope = CoroutineScope(dispatcher + SupervisorJob())
            val events = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            val seam = HandSeam(self, host)
            try {
                val room = SeamRoomFactory(InMemoryLoom(), scope, { Clock.System.now() }, fakeKeyStore(1))
                    .adopt(seam, SessionRole.Joiner, memberName = "A") as SeamRoom
                val hello = withTimeout(STEP.seconds) { seam.hellos.first { it.isNotEmpty() } }.single()
                val nonce = checkNotNull(hello.joinerNonce) { "a current Hello carries a nonce" }

                // The second Reject the room checks is the superseded one: the refusal is the first.
                val checks = AtomicInteger()
                val supersededChecked = CountDownLatch(1)
                val supersededNamedLatest = AtomicBoolean(false)
                val closedWhenChecked = AtomicBoolean(true)
                room.rejectCheckedForTest = { answersLatest ->
                    if (checks.incrementAndGet() == 2) {
                        supersededNamedLatest.set(answersLatest)
                        closedWhenChecked.set(seam.closed)
                        supersededChecked.countDown()
                    }
                }
                val seen = Collections.synchronizedList(mutableListOf<MembershipEvent>())
                val heldInEmit = AtomicBoolean(false)
                val checkedWhileHeld = AtomicBoolean(false)
                events.launch {
                    room.events.collect { event ->
                        seen += event
                        if (event is MembershipEvent.AdmissionFailed && heldInEmit.compareAndSet(false, true)) {
                            seam.inbound.trySend(Swatch(AdmitMessage.encode(AdmitMessage.Reject.superseded("tab-b", nonce)), sender = host))
                            checkedWhileHeld.set(supersededChecked.await(STEP, TimeUnit.SECONDS))
                        }
                    }
                }

                seam.inbound.trySend(Swatch(AdmitMessage.encode(AdmitMessage.Reject("key-conflict: held", RejectCode.KeyConflict, nonce)), sender = host))
                val bothHandled = withTimeoutOrNull(STEP.seconds) { seam.handled.first { it >= 2 } } != null
                val closed = withTimeoutOrNull(STEP.seconds) { seam.closing.first { it } } == true
                // Run whatever the second Reject launched: a pool that is shut down still runs its queue.
                (dispatcher.executor as ExecutorService).let { pool ->
                    pool.shutdown()
                    pool.awaitTermination(STEP, TimeUnit.SECONDS)
                }
                val terminal = seen.filter { it is MembershipEvent.AdmissionFailed || (it is MembershipEvent.Left && it.peerId == self) }

                assertAll(
                    { assertTrue(heldInEmit.get(), "rig: the room's AdmissionFailed emit was held") },
                    { assertTrue(checkedWhileHeld.get(), "rig: the superseded Reject was checked while the emit was held") },
                    { assertTrue(supersededNamedLatest.get(), "rig: the superseded Reject named the latest Hello") },
                    { assertFalse(closedWhenChecked.get(), "rig: the room had not closed when the Reject was checked") },
                    { assertTrue(bothHandled, "rig: the room handled both Rejects") },
                    { assertTrue(closed, "the room ended") },
                    { assertEquals(1, terminal.size, "one ending: ${terminal.map { it.describe() }}") },
                    { assertTrue(terminal.singleOrNull() is MembershipEvent.AdmissionFailed, "the admission failure: ${terminal.map { it.describe() }}") },
                )
            } finally {
                events.cancel()
                scope.cancel()
            }
        }
    }

    /**
     * The joiner's seam, played by hand: frames go in through [inbound], and [handled] counts each
     * one the room has finished handling. Records the joiner's Hellos and whether it closed.
     */
    private class HandSeam(override val selfId: PeerId, host: PeerId) : Seam {
        // Bounded, as every Swatch delivery channel is (forbidUnboundedSwatchDelivery): the probe sends two frames.
        val inbound = Channel<Swatch>(capacity = 8)
        val handled = MutableStateFlow(0)
        val hellos = MutableStateFlow<List<AdmitMessage.Hello>>(emptyList())
        val closing = MutableStateFlow(false)
        val closed: Boolean get() = closing.value

        override val peers = MutableStateFlow(setOf(selfId, host))
        override val state = MutableStateFlow<SeamState>(SeamState.Woven)
        override val incoming = flow {
            for (frame in inbound) {
                emit(frame)
                handled.update { it + 1 }
            }
        }

        override suspend fun broadcast(payload: ByteArray) = record(payload)

        override suspend fun sendTo(peer: PeerId, payload: ByteArray) = record(payload)

        private fun record(payload: ByteArray) {
            val message = AdmitMessage.decode(payload)
            if (message is AdmitMessage.Hello) hellos.update { it + message }
        }

        override suspend fun close(reason: CloseReason) {
            closing.value = true
        }
    }

    private companion object {
        const val STEP = 10L
    }
}
