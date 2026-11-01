@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fakeSeamPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val SELF = PeerId("self")
private val OTHER = PeerId("other")
private val SEND_TIME = 10.milliseconds

private fun messageSerializer() = QuiltMessage.serializer(GCounter.serializer())

/**
 * A [Seam] whose [broadcast] **really suspends** before the frame reaches [delegate].
 *
 * This suspension is the whole rig. Against a seam that sends without suspending, a `launch` that
 * merely got dispatched finishes in one go, so a single `yield()` before `close()` looks like a fix
 * and a test of the hazard is green by accident.
 *
 * Every call is counted three ways, so a test can state which window it reached: [entered] (the
 * send began), [cancelled] (it was torn out of its suspension), and the [delegate]'s own record of
 * what it was handed. Plain counters are enough: these tests run on one test dispatcher.
 */
private class SuspendingSendSeam(
    private val delegate: FakeSeam,
    private val suspendSend: suspend (call: Int) -> Unit,
) : Seam by delegate {
    var entered = 0
        private set
    var cancelled = 0
        private set

    /** The delta sequence numbers the seam has actually been handed, in order. */
    val handedSeqs: List<Long>
        get() = delegate.broadcasts.map { frame ->
            val message = Cbor.decodeFromByteArray(messageSerializer(), frame)
            assertTrue(message is QuiltMessage.Delta<GCounter>, "a GCounter broadcasts only deltas, got $message")
            message.seq
        }

    override suspend fun broadcast(payload: ByteArray) {
        val call = entered++
        try {
            suspendSend(call)
        } catch (e: CancellationException) {
            cancelled++
            throw e
        }
        delegate.broadcast(payload)
    }
}

/** One [CompletableDeferred] per broadcast call, so a test can release the sends one at a time. */
private fun gates(count: Int): List<CompletableDeferred<Unit>> = List(count) { CompletableDeferred() }

private fun counterQuilter(seam: Seam, scope: CoroutineScope) = Quilter(
    replica = ReplicaId(seam.selfId.value),
    seam = seam,
    initial = GCounter.ZERO,
    messageSerializer = messageSerializer(),
    scope = scope,
    config = QuilterConfig(expectVirtualTime = true),
)

private fun Quilter<GCounter>.write() = apply(state.value.inc(replica))

private fun Quilter<GCounter>.assertClosed() {
    assertTrue(backgroundJobsForTest.none { it.isActive }, "the replica's collectors should be stopped")
    assertFailsWith<IllegalStateException>("a closed replica refuses a write") { write() }
}

/**
 * [Quilter.drainAndClose]: a write followed by a teardown must have a way not to lose its frame
 * (#2616).
 *
 * Every test runs on the `runTest` default, a `StandardTestDispatcher`, because the hazard has two
 * windows and only a lazy dispatcher can show the first: the send not yet dispatched, and the send
 * dispatched and suspended inside the seam. An eager `UnconfinedTestDispatcher` runs the launched
 * send up to its suspension inline, so it never reaches the first window at all.
 *
 * The assertions are on what the seam was handed, never on [Quilter.state], which reflects a write
 * the moment `apply` returns whether or not its frame ever leaves.
 */
class QuilterDrainAndCloseTest {

    private fun TestScope.fixture(suspendSend: suspend (call: Int) -> Unit): Pair<SuspendingSendSeam, Quilter<GCounter>> {
        val (fake, _) = fakeSeamPair(SELF, OTHER)
        val seam = SuspendingSendSeam(fake, suspendSend)
        val quilter = counterQuilter(seam, backgroundScope)
        runCurrent()
        return seam to quilter
    }

    // ── The hazard itself: the control arms the drain tests are measured against ─────────────

    /**
     * The first window: `apply` has only queued the send, so `close` cancels it before the seam
     * hears anything. This arm and [drainAndCloseHandsTheWriteToTheSeam] are the same fixture and
     * the same write, differing only in which teardown runs.
     */
    @Test
    fun closeRightAfterApplyCancelsTheSendBeforeItStarts() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val (seam, quilter) = fixture { delay(SEND_TIME) }

        quilter.write()
        quilter.close()
        advanceTimeBy(SEND_TIME * 2)
        runCurrent()

        assertAll(
            { assertEquals(0, seam.entered, "the send was cancelled before it began") },
            { assertEquals(emptyList(), seam.handedSeqs, "close lost the frame") },
        )
    }

    /**
     * The `yield()` workaround from the issue, measured. It carries the send past the first window
     * and into the second, where `close` then cancels it inside the seam's suspension. So any seam
     * whose send suspends defeats it, and [SuspendingSendSeam.entered] shows the yield did its one
     * job first.
     */
    @Test
    fun yieldBeforeCloseOnlyMovesTheLossIntoTheSeam() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val (seam, quilter) = fixture { delay(SEND_TIME) }

        quilter.write()
        yield()
        assertEquals(1, seam.entered, "precondition: the yield let the send begin")

        quilter.close()
        advanceTimeBy(SEND_TIME * 2)
        runCurrent()

        assertAll(
            { assertEquals(1, seam.cancelled, "close cancelled the send mid-suspension") },
            { assertEquals(emptyList(), seam.handedSeqs, "close lost the frame") },
        )
    }

    // ── The issue's shape ────────────────────────────────────────────────────────────────────

    /** `apply(departingPresence); drainAndClose()`, in exactly the order the issue wrote it. */
    @Test
    fun drainAndCloseHandsTheWriteToTheSeam() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val (seam, quilter) = fixture { delay(SEND_TIME) }

        quilter.write()
        assertEquals(0, seam.entered, "precondition: apply returned with the send still queued")

        quilter.drainAndClose()

        assertAll(
            { assertEquals(listOf(1L), seam.handedSeqs, "the frame reached the seam") },
            { assertEquals(0, seam.cancelled, "the send was not cancelled") },
            { quilter.assertClosed() },
        )
    }

    /**
     * The ordering, seen from inside the window: while the send is parked in the seam the drain
     * does not return and the replica is still running. Only the frame reaching the seam lets the
     * scope be cancelled.
     */
    @Test
    fun theScopeOutlivesTheSuspendedSend() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val gate = CompletableDeferred<Unit>()
        val (seam, quilter) = fixture { gate.await() }

        quilter.write()
        val drain = launch { quilter.drainAndClose() }
        runCurrent()

        assertAll(
            { assertEquals(1, seam.entered, "the send reached the seam and suspended there") },
            { assertEquals(emptyList(), seam.handedSeqs, "the suspended send has not finished") },
            { assertTrue(drain.isActive, "the drain waits for it") },
            { assertTrue(quilter.backgroundJobsForTest.all { it.isActive }, "the replica is still running") },
        )

        gate.complete(Unit)
        runCurrent()

        assertAll(
            { assertEquals(listOf(1L), seam.handedSeqs, "released, the frame reached the seam") },
            { assertTrue(drain.isCompleted, "and the drain finished") },
            { quilter.assertClosed() },
        )
    }

    // ── Cancellation: the caller bounds the wait, and the replica ends up closed regardless ──

    /** A send that never finishes, under the caller's timeout: the timeout surfaces, the replica closes. */
    @Test
    fun aCallerTimeoutSurfacesAndStillClosesTheReplica() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val neverReleased = CompletableDeferred<Unit>()
        val (seam, quilter) = fixture { neverReleased.await() }

        quilter.write()
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(1.seconds) { quilter.drainAndClose() }
        }
        runCurrent()

        assertAll(
            { assertEquals(1, seam.entered, "precondition: the drain really was stuck on a suspended send") },
            { assertEquals(1, seam.cancelled, "closing cancelled the stuck send") },
            { assertEquals(emptyList(), seam.handedSeqs, "the frame never reached the seam") },
            { quilter.assertClosed() },
        )
    }

    /** The same obligation when the caller's own job is cancelled rather than timed out. */
    @Test
    fun cancellingTheCallerStillClosesTheReplica() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val neverReleased = CompletableDeferred<Unit>()
        val (seam, quilter) = fixture { neverReleased.await() }

        quilter.write()
        val drain = launch { quilter.drainAndClose() }
        runCurrent()
        assertEquals(1, seam.entered, "precondition: the drain is parked on the suspended send")

        drain.cancelAndJoin()
        runCurrent()

        assertAll(
            { assertTrue(drain.isCancelled, "the cancellation is the caller's to see, not swallowed") },
            { assertEquals(1, seam.cancelled, "closing cancelled the stuck send") },
            { quilter.assertClosed() },
        )
    }

    // ── Races: a write during the drain, traffic during the drain, a torn seam ───────────────

    /**
     * A concurrent write during the drain is admitted and drained too: the replica stays open until
     * nothing is in flight, so an `apply` that returned normally never has its frame cancelled.
     * Closing on the first snapshot would lose the second frame here.
     */
    @Test
    fun aWriteDuringTheDrainIsDrainedToo() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val gates = gates(2)
        val (seam, quilter) = fixture { call -> gates[call].await() }

        quilter.write()
        val drain = launch { quilter.drainAndClose() }
        runCurrent()

        quilter.write()
        runCurrent()
        assertEquals(2, seam.entered, "precondition: the second send began after the drain did")

        gates[0].complete(Unit)
        runCurrent()
        assertAll(
            { assertEquals(listOf(1L), seam.handedSeqs, "first frame out") },
            { assertTrue(drain.isActive, "the second send is still in flight, so the drain waits") },
        )

        gates[1].complete(Unit)
        runCurrent()
        assertAll(
            { assertEquals(listOf(1L, 2L), seam.handedSeqs, "both frames out") },
            { assertEquals(0, seam.cancelled, "neither send was cancelled") },
            { assertTrue(drain.isCompleted, "and the drain finished") },
            { quilter.assertClosed() },
        )
    }

    /**
     * Only this replica's own writes hold the drain open. A peer's `Resend` also re-broadcasts a
     * delta, but that is inbound traffic, and a peer that kept asking must not be able to keep a
     * departing replica alive. The retransmission is cancelled with everything else.
     */
    @Test
    fun aRetransmissionDoesNotHoldTheDrainOpen() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val gates = gates(2)
        val (fake, _) = fakeSeamPair(SELF, OTHER)
        val seam = SuspendingSendSeam(fake) { call -> gates[call].await() }
        val quilter = counterQuilter(seam, backgroundScope)
        runCurrent()

        quilter.write()
        val drain = launch { quilter.drainAndClose() }
        runCurrent()

        val resend = QuiltMessage.Resend<GCounter>(
            requester = ReplicaId(OTHER.value),
            sender = quilter.replica,
            fromSeq = 1L,
            toSeq = 1L,
        )
        fake.deliver(OTHER, Cbor.encodeToByteArray(messageSerializer(), resend))
        runCurrent()
        assertEquals(2, seam.entered, "precondition: the retransmission is in flight beside the write")

        gates[0].complete(Unit)
        runCurrent()

        assertAll(
            { assertTrue(drain.isCompleted, "the write is out, so the drain finished") },
            { assertEquals(listOf(1L), seam.handedSeqs, "only the write's own send reached the seam") },
            { assertEquals(1, seam.cancelled, "the retransmission was cancelled by the close") },
            { quilter.assertClosed() },
        )
    }

    /**
     * A seam torn mid-drain closes the replica, which cancels the send: the drain returns rather
     * than hanging, and the frame is gone. This is why the KDoc promises the send *finished*, not
     * that it succeeded.
     */
    @Test
    fun aSeamTornDuringTheDrainEndsItWithoutTheFrame() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val neverReleased = CompletableDeferred<Unit>()
        val (fake, _) = fakeSeamPair(SELF, OTHER)
        val seam = SuspendingSendSeam(fake) { neverReleased.await() }
        val quilter = counterQuilter(seam, backgroundScope)
        runCurrent()

        quilter.write()
        val drain = launch { quilter.drainAndClose() }
        runCurrent()
        assertTrue(drain.isActive, "precondition: the drain is parked on the suspended send")

        fake.tear()
        runCurrent()

        assertAll(
            { assertTrue(drain.isCompleted, "the drain did not hang") },
            { assertFalse(drain.isCancelled, "and it returned normally, because nobody cancelled the caller") },
            { assertEquals(1, seam.cancelled, "the replica's self-close cancelled the send") },
            { assertEquals(emptyList(), seam.handedSeqs, "the frame did not reach the seam") },
        )
    }

    // ── Degenerate calls ─────────────────────────────────────────────────────────────────────

    @Test
    fun withNothingInFlightItJustCloses() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val (seam, quilter) = fixture { error("no write, so no send") }

        quilter.drainAndClose()

        assertAll(
            { assertEquals(0, seam.entered, "nothing was sent") },
            { quilter.assertClosed() },
        )
    }

    @Test
    fun itIsIdempotentAndHarmlessAfterAPlainClose() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val (_, drainedTwice) = fixture { delay(SEND_TIME) }
        drainedTwice.drainAndClose()
        drainedTwice.drainAndClose()
        drainedTwice.assertClosed()

        val (_, closedFirst) = fixture { delay(SEND_TIME) }
        closedFirst.close()
        closedFirst.drainAndClose()
        closedFirst.assertClosed()
    }
}
