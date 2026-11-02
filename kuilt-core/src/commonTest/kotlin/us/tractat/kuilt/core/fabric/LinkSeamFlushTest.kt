package us.tractat.kuilt.core.fabric

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.FlushOutcome
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * What only [LinkSeam]'s own internals can show about `flush`: the states `SeamConformanceSuite`
 * cannot build generically, since it does not know this seam has an outbox.
 *
 * The suite's `Holdable` arm proves the barrier. These are the edges where it could wedge or lie.
 */
class LinkSeamFlushTest {
    private val self = PeerId("self")
    private val remote = PeerId("remote")

    private fun TestScope.seamOver(conn: Connection): Seam =
        identified(conn, self, remote, StandardTestDispatcher(testScheduler))

    /** A connection that has not described what a returning `send` means proves nothing. */
    @Test
    fun aConnectionThatCannotTellMakesTheSeamSayUnsupported() = runTest {
        val conn = FlushTestConnection()
        val seam = seamOver(conn)
        seam.broadcast(byteArrayOf(1))
        val outcome = seam.flush(Duration.INFINITE)
        assertAll(
            { assertEquals(FlushOutcome.Unsupported, outcome, "the outbox drained, but nothing is proven below it") },
            { assertEquals(1, conn.written, "the barrier did wait for the outbox, so the frame went first") },
        )
        seam.close()
    }

    /** An unaware seam or connection, implemented member by member, answers by inheriting the default. */
    @Test
    fun anUnawareImplementationInheritsUnsupportedRatherThanDrained() = runTest {
        val unaware = FlushTestConnection()
        val seam = seamOver(unaware)
        val outcomes = listOf(unaware.flush(Duration.INFINITE), InterfaceDefaultSeam(seam).flush(Duration.INFINITE))
        assertEquals(listOf<FlushOutcome>(FlushOutcome.Unsupported, FlushOutcome.Unsupported), outcomes)
        seam.close()
    }

    /**
     * Class delegation is not that case. Recompiled, `Seam by inner` forwards `inner`'s answer, so a
     * decorator with a queue of its own has to override `flush` or it claims a drain above that queue.
     */
    @Test
    fun aDecoratorByClassDelegationForwardsTheInnerAnswer() = runTest {
        val seam = seamOver(FlushTestConnection(flushWith = { FlushOutcome.Drained }))
        assertEquals(FlushOutcome.Drained, ClassDelegatingSeam(seam).flush(Duration.INFINITE))
        seam.close()
    }

    /** The seam passes up a failure from below. It must not launder it into a drain. */
    @Test
    fun aConnectionFlushFailureIsReportedAsFailed() = runTest {
        val failure = IllegalStateException("the transport refused")
        val seam = seamOver(FlushTestConnection(flushWith = { throw failure }))
        val outcome = assertIs<FlushOutcome.Failed>(seam.flush(Duration.INFINITE))
        // By message, not identity: coroutines' stack-trace recovery may hand back a copy.
        assertEquals(failure.message, outcome.cause.message)
        seam.close()
    }

    /** `withTimeout` inside a connection's flush mints a cancellation nobody asked for. It is a failure. */
    @Test
    fun aCancellationMintedBelowIsAFailureNotACancellation() = runTest {
        val seam = seamOver(FlushTestConnection(flushWith = { withTimeout(1.milliseconds) { awaitCancellation() } }))
        val outcome = assertIs<FlushOutcome.Failed>(seam.flush(Duration.INFINITE))
        assertIs<CancellationException>(outcome.cause, "rig: the connection really did mint one")
        assertIs<SeamState.Woven>(seam.state.value, "and the seam is untouched")
        seam.close()
    }

    /** A connection stuck in its own flush is still bounded by the seam's timeout. */
    @Test
    fun aStuckConnectionFlushTimesOut() = runTest {
        val seam = seamOver(FlushTestConnection(flushWith = { awaitCancellation() }))
        assertEquals(FlushOutcome.TimedOut, seam.flush(100.milliseconds))
        seam.close()
    }

    /**
     * The writer's own send failing tears the seam, and the flush says why.
     *
     * Reports the send's failure rather than a bare "closed" because that is what a caller needs to
     * read in a log.
     */
    @Test
    fun aFailedWriteEndsAFlushWithThatFailure() = runTest {
        val refusal = IllegalStateException("the peer went away")
        val seam = seamOver(FlushTestConnection(sendWith = { throw refusal }))
        seam.broadcast(byteArrayOf(1))
        val outcome = assertIs<FlushOutcome.Failed>(seam.flush(Duration.INFINITE))
        assertEquals(refusal.message, outcome.cause.message)
    }

    /**
     * A marker parked behind a full outbox still ends when the link tears.
     *
     * `Channel.close` wakes no suspended sender, so a marker waiting for room stays parked on a torn
     * seam, and the writer that would have made room has gone. The sends parked ahead of it stay parked
     * too; what must not happen is the flush joining them. Unbounded on purpose: a flush that waits on
     * the parked marker alone trips the suite's backstop rather than timing out into a pass.
     */
    @Test
    fun aFlushParkedBehindAFullOutboxEndsWhenTheLinkTears() = runTest {
        val release = CompletableDeferred<Unit>()
        val seam = seamOver(FlushTestConnection(sendWith = { release.await(); error("the link died") }))
        val overfill = DeliveryPolicy.DEFAULT_CAPACITY + 43
        val parkedSends = List(overfill) { launch { seam.broadcast(byteArrayOf(it.toByte())) } }
        testScheduler.advanceUntilIdle()
        assertTrue(parkedSends.any { it.isActive }, "rig: the outbox is full, so some sends are parked")

        val flush = async { seam.flush(Duration.INFINITE) }
        testScheduler.advanceUntilIdle()
        assertTrue(flush.isActive, "rig: the flush's marker has no room and is parked behind them")

        release.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertAll(
            { assertIs<SeamState.Torn>(seam.state.value, "rig: the failed write tore the seam") },
            { assertTrue(parkedSends.any { it.isActive }, "rig: the sends ahead of the marker stay parked") },
            { assertTrue(flush.isCompleted, "the tear must end the flush; it is still parked with them") },
        )
        assertIs<FlushOutcome.Failed>(flush.await(), "a torn link cannot say Drained")
        parkedSends.forEach { it.cancelAndJoin() }
    }

    /**
     * A writer that drains past a close must not get a flush to say `Drained`.
     *
     * `Channel.close` leaves queued items readable, and a connection may drop a frame after its own close
     * and return normally, as the in-memory pair does. So the writer can walk the rest of the outbox and
     * reach the marker on a torn seam, with those frames gone. The rig forces the one order where the
     * flush then sees both signals at once: room opens and the flush's resume is queued, then the
     * writer closes the seam and reaches the marker before that resume runs.
     */
    @Test
    fun aWriterDrainingPastACloseDoesNotConfirmTheBarrier() = runTest {
        val gate = CompletableDeferred<Unit>()
        val sends = atomic(0)
        var seam: Seam? = null
        val conn = FlushTestConnection(
            sendWith = {
                when (sends.incrementAndGet()) {
                    1 -> gate.await()
                    2 -> requireNotNull(seam).close()
                }
            },
            flushWith = { FlushOutcome.Drained },
        )
        seam = seamOver(conn)
        val fill = List(DeliveryPolicy.DEFAULT_CAPACITY + 1) { launch { seam.broadcast(byteArrayOf(1)) } }
        testScheduler.advanceUntilIdle()
        fill.forEach { assertTrue(it.isCompleted, "rig: the writer holds one and the outbox holds the rest") }
        val flush = async { seam.flush(Duration.INFINITE) }
        testScheduler.advanceUntilIdle()
        assertTrue(flush.isActive, "rig: the marker has no room and is parked")

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        val outcome = flush.await()
        assertAll(
            { assertIs<SeamState.Torn>(seam.state.value, "rig: the writer closed the seam") },
            { assertEquals(sends.value, conn.written, "rig: the writer walked the whole outbox past the close") },
            { assertIs<FlushOutcome.Failed>(outcome, "those frames went nowhere, so this cannot be Drained") },
        )
    }

    /**
     * Repeated timed-out flushes cost no frames, and do not wedge the seam.
     *
     * A marker that made it into the outbox stays there when its flush gives up, so this runs more of
     * them than the outbox holds and proves every real frame still goes, in order, once the writer moves.
     */
    @Test
    fun timedOutFlushesLeaveEveryFrameToGoInOrder() = runTest {
        val release = CompletableDeferred<Unit>()
        val conn = FlushTestConnection(sendWith = { release.await() })
        val seam = seamOver(conn)
        seam.broadcast(byteArrayOf(0))
        repeat(DeliveryPolicy.DEFAULT_CAPACITY + 8) {
            assertEquals(FlushOutcome.TimedOut, seam.flush(1.milliseconds))
        }
        val later = launch { seam.broadcast(byteArrayOf(1)) }

        release.complete(Unit)
        testScheduler.advanceUntilIdle()
        later.join()
        assertEquals(listOf<Byte>(0, 1), conn.frames.map { it.single() })
        seam.close()
    }

    /**
     * A close from elsewhere ends a flush whose marker has passed and which is now asking the connection.
     *
     * A `Connection.flush` owes the same ending, but a seam cannot lean on that: this stage has to answer
     * for a connection that does not keep it. Unbounded on purpose, so a flush that waits on the
     * connection alone stays parked rather than timing out into a pass.
     */
    @Test
    fun aCloseEndsAFlushWaitingOnTheConnection() = runTest {
        val asked = CompletableDeferred<Unit>()
        val seam = seamOver(FlushTestConnection(flushWith = { asked.complete(Unit); awaitCancellation() }))
        val flush = async { seam.flush(Duration.INFINITE) }
        testScheduler.advanceUntilIdle()
        assertAll(
            { assertTrue(asked.isCompleted, "rig: the marker passed and the connection is being asked") },
            { assertTrue(flush.isActive, "rig: the connection has not answered") },
        )

        seam.close()
        testScheduler.advanceUntilIdle()

        assertTrue(flush.isCompleted, "the close must end the flush; it is still waiting on the connection")
        assertIs<FlushOutcome.Failed>(flush.await(), "a close from elsewhere cannot leave a flush saying Drained")
    }

    /**
     * A send minting a cancellation kills the writer without a tear, and a flush must not wait for a
     * writer that has gone. `Connection.send` is not allowed to do this, but a flush with no timeout is
     * the one place it would turn into a caller stuck forever.
     */
    @Test
    fun aWriterKilledByAMintedCancellationEndsAFlush() = runTest {
        val minted = CancellationException("a send's own withTimeout")
        val seam = seamOver(FlushTestConnection(sendWith = { throw minted }, flushWith = { FlushOutcome.Drained }))
        seam.broadcast(byteArrayOf(1))
        testScheduler.advanceUntilIdle()
        assertIs<SeamState.Woven>(seam.state.value, "rig: the writer died and nothing tore the seam")

        val flush = async { seam.flush(Duration.INFINITE) }
        testScheduler.advanceUntilIdle()

        assertTrue(flush.isCompleted, "the flush is waiting on a writer that has gone")
        val outcome = assertIs<FlushOutcome.Failed>(flush.await())
        assertEquals(minted.message, outcome.cause.message, "and it says what killed the writer")
        seam.close()
    }

    /**
     * Two paths tearing at once: the one that lost the latch closes the connection while the winner is
     * still between publishing `Torn` and finishing up. The frames ahead of the marker are gone by then,
     * so a flush must already be able to see the tear.
     *
     * The window is real only across threads, but it can be forced on one: an unconfined collector runs
     * on the winner's own stack, inside the state write, where `torn` is still open. So the only thing
     * left to say this flush failed is its read of `state`, and this reds without that read.
     * `LinkSeamFlushConcurrencyTest` is the same race unforced, on real threads.
     */
    @Test
    fun aFlushCannotConfirmInsideAnotherPathsTear() = runTest {
        val writerStuck = CompletableDeferred<Unit>()
        val conn = FlushTestConnection(sendWith = { writerStuck.await() }, flushWith = { FlushOutcome.Drained })
        val seam = seamOver(conn)
        seam.broadcast(byteArrayOf(1))
        val flush = async { seam.flush(Duration.INFINITE) }
        testScheduler.advanceUntilIdle()
        assertTrue(flush.isActive, "rig: the marker is behind a frame the writer is stuck on")

        var ranInsideTheTear = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            seam.state.first { it is SeamState.Torn }
            ranInsideTheTear = true
            // The losing path: the remote ends too, so `readLoop` loses the latch and closes the link.
            conn.remoteEnds()
            writerStuck.complete(Unit)
            testScheduler.advanceUntilIdle()
        }
        seam.close()
        testScheduler.advanceUntilIdle()

        assertAll(
            { assertTrue(ranInsideTheTear, "rig: the collector never ran") },
            { assertEquals(1, conn.written, "rig: the writer walked past the close to the marker") },
        )
        assertIs<FlushOutcome.Failed>(flush.await(), "those frames went nowhere, so this cannot be Drained")
    }

    /** A flush on a torn seam answers without touching the connection. */
    @Test
    fun aFlushOnATornSeamNeverAsksTheConnection() = runTest {
        val asked = atomic(0)
        val seam = seamOver(FlushTestConnection(flushWith = { asked.incrementAndGet(); FlushOutcome.Drained }))
        seam.close()
        assertIs<FlushOutcome.Failed>(seam.flush(Duration.INFINITE))
        assertEquals(0, asked.value)
    }

    private class ClassDelegatingSeam(inner: Seam) : Seam by inner

    /** A decorator written before `flush` existed: it overrides nothing, so it inherits the default. */
    private class InterfaceDefaultSeam(private val inner: Seam) : Seam {
        override val selfId get() = inner.selfId
        override val peers get() = inner.peers
        override val state get() = inner.state
        override val incoming get() = inner.incoming
        override suspend fun broadcast(payload: ByteArray) = inner.broadcast(payload)
        override suspend fun sendTo(peer: PeerId, payload: ByteArray) = inner.sendTo(peer, payload)
        override suspend fun close(reason: us.tractat.kuilt.core.CloseReason) = inner.close(reason)
    }
}

/**
 * A connection whose [send] and [flush] are each whatever a test needs. With no `flushWith` it inherits
 * the interface default, which is the case worth testing.
 */
private class FlushTestConnection(
    private val sendWith: suspend () -> Unit = {},
    private val flushWith: (suspend () -> FlushOutcome)? = null,
) : Connection {
    private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
    val frames = mutableListOf<ByteArray>()
    val written get() = frames.size

    override suspend fun send(frame: ByteArray) {
        sendWith()
        frames += frame
    }

    override val incoming: Flow<ByteArray> = inbound.receiveAsFlow()

    override suspend fun flush(timeout: Duration): FlushOutcome =
        flushWith?.invoke() ?: super.flush(timeout)

    override suspend fun close() {
        inbound.close()
    }

    /** The far end going away, as `incoming` sees it. */
    fun remoteEnds() {
        inbound.close()
    }
}
