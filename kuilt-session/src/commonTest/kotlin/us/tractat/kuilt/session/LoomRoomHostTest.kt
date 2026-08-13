package us.tractat.kuilt.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class LoomRoomHostTest {

    private val zeroClock: () -> Instant = { Instant.fromEpochMilliseconds(0) }

    @Test
    fun `drives the admit handshake over an in-memory loom under virtual time`() = runTest {
        val loom = InMemoryLoom()
        val host = LoomRoomHost(loom, Pattern("server"), clock = zeroClock)
        val hostRoomReady = CompletableDeferred<Room>()

        val hostJob = backgroundScope.launch {
            host.start { room -> hostRoomReady.complete(room) }
        }
        val hostRoom = hostRoomReady.await()

        // A joiner connects over the same in-memory mesh — no socket bound — and
        // completes Hello → Welcome → onPeer purely under runTest virtual time.
        val joinerFactory = SeamRoomFactory(loom = loom, scope = backgroundScope, clock = zeroClock)
        val joinerRoom = joinerFactory.join(InMemoryTag("server"))

        hostRoom.roster.first { it.size == 1 }
        joinerRoom.roster.first { it.isNotEmpty() }

        assertTrue(
            hostRoom.roster.value.any { it.id == joinerRoom.selfId },
            "host roster must contain the admitted joiner",
        )
        hostJob.cancel()
    }

    @Test
    fun `start a second time on the same host throws`() = runTest {
        val host = LoomRoomHost(InMemoryLoom(), Pattern("dup"), clock = zeroClock)

        val firstStarted = CompletableDeferred<Unit>()
        val job = backgroundScope.launch {
            host.start {
                firstStarted.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        firstStarted.await()

        val ex = runCatchingCancellable { host.start { } }.exceptionOrNull()
        assertTrue(ex is IllegalStateException, "second start must throw IllegalStateException, got $ex")
        job.cancel()
    }

    /**
     * Cancelling the host's scope must still leave the room — the leave is *not* optional cleanup.
     *
     * Scope cancellation is the whole documented lifecycle here ([LoomRoomHost.close] is a no-op and says
     * so), and [LoomRoomHost.start]'s body ends in `awaitCancellation()`, so by the time its `finally`
     * runs this coroutine is **already cancelled**. [Room.leave] is a suspending call, so unshielded it
     * throws at its first cancellable suspension point and the leave never completes: `seam.close` is
     * never reached, and every host that shuts down the documented way silently vanishes off the fabric
     * rather than departing (#2286).
     *
     * **Why the seam's `close` suspends here.** Every real one does — a WebSocket close handshake, a
     * Multipeer disconnect, an `NwConnection` cancel — which is the premise the shield's contract rests on
     * (see `NwLoom.discardUnreturnedSeam`). `InMemoryLoom`'s `close` never suspends, so the two tests
     * above are green with or without the shield; a seam that suspends is what makes this one able to
     * fail.
     */
    @Test
    fun `leaves the room on the way out even though the teardown is a cancellation`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val seam = SuspendingCloseSeam(FakeSeam(selfId = PeerId("host")))
            val host = LoomRoomHost(SoleSeamLoom(seam), Pattern("shielded"), clock = zeroClock)
            val hosting = CompletableDeferred<Unit>()

            val hostJob = backgroundScope.launch { host.start { hosting.complete(Unit) } }
            hosting.await()

            hostJob.cancelAndJoin()

            assertEquals(
                listOf(CloseReason.Normal),
                seam.closes,
                "leave(Normal) must run to completion on the way out of a cancelled start()",
            )
        }

    /** A [Loom] that hands out one prepared [Seam], however it is woven. */
    private class SoleSeamLoom(private val seam: Seam) : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam = seam
    }

    /**
     * A [Seam] whose `close` suspends *before* it takes effect, and records the reasons it completed for.
     *
     * The suspension is the point: it stands in for the round trip a real transport's close performs, and
     * it is the only thing that lets an unshielded teardown be observed — the recording happens after it,
     * so a `close` that throws at that suspension point records nothing.
     */
    private class SuspendingCloseSeam(private val delegate: FakeSeam) : Seam by delegate {
        private val _closes = mutableListOf<CloseReason>()

        /** Reasons for which `close` ran to completion, in call order. */
        val closes: List<CloseReason> get() = _closes.toList()

        override suspend fun close(reason: CloseReason) {
            yield()
            _closes += reason
            delegate.close(reason)
        }
    }
}
