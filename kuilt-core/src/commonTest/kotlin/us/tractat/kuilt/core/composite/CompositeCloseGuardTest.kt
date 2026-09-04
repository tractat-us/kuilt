package us.tractat.kuilt.core.composite

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.TransportRole
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `CompositeSeam.close` must close **every** ply, whatever any one of them does on the way down (#1861).
 *
 * ### The defect
 * The per-ply loop was `toClose.forEach { it.seam.close(reason) }` — completely unguarded — and it is the
 * plies' *only* remaining holder: `close()` has already drained `live` and is single-shot, so a ply it
 * skips is never closed by anything. One ply throwing therefore leaked every ply after it in iteration
 * order, and a ply minting a `CancellationException` (the `withTimeout(closeTimeout)` a real close
 * handshake is written with) did that *and* made this seam violate the `Seam.close` obligation it is
 * itself a `Seam` under — "a close failure must NOT be reported as a cancellation" (#1826/#1859) — with the
 * masquerade **cancelling** the caller rather than failing it: no handler, no stack trace.
 *
 * It passed `SeamConformanceSuite.closeDoesNotReportFailureAsCancellation` throughout, because every
 * in-tree ply conforms. That is the correct-by-convention shape #1826 exists to remove: a decorator must
 * survive a *non-conforming* member, since `Loom`/`Seam` plies are consumer-authored.
 *
 * ### What these assert, and why they are not vacuous
 * Every assertion is on the **outcome** — a sibling ply reaching the terminal [SeamState.Torn], i.e. its
 * transport genuinely torn down — never on the guard's instrument. A fix that caught the throw but stopped
 * iterating would leave every one of them red.
 *
 * The healthy plies are placed **after** the failing one deliberately: `live` is a `LinkedHashMap` in
 * insertion order, so "b and c are still Torn" is a statement about the loop having continued past a, and
 * is false at every position if it has not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositeCloseGuardTest {

    /**
     * A ply whose `close()` throws an ordinary exception must not strand the plies after it — and the
     * composite's own `close()` must still return, absorbing the ply's failure the way every other foreign
     * close in this class does, and reporting it through `onPlyFailure`.
     */
    @Test
    fun aPlyWhoseCloseThrowsDoesNotStrandThePliesAfterIt() = runTest {
        val doomed = ClosingLoom(DOOMED, CloseBehaviour.THROWS)
        val second = ClosingLoom(SECOND, CloseBehaviour.CLEAN)
        val third = ClosingLoom(THIRD, CloseBehaviour.CLEAN)
        val raised = mutableListOf<PlyReconcileException>()
        val composite = CompositeLoom(
            plies = listOf(PlyId(DOOMED) to doomed as Loom, PlyId(SECOND) to second, PlyId(THIRD) to third),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            onPlyFailure = { raised += it },
        ).host(Pattern("host"))
        runCurrent()

        val escaped = closeCapturingEscape(composite)

        assertAll(
            {
                assertIs<SeamState.Torn>(
                    second.fake.state.value,
                    "the ply after the throwing one must still be closed: close() has already drained `live` " +
                        "and is single-shot, so nothing else will ever close this transport",
                )
            },
            { assertIs<SeamState.Torn>(third.fake.state.value, "…and so must every ply after that") },
            {
                assertNull(
                    escaped,
                    "one ply's ordinary close failure must not escape the composite's own close(), got: $escaped",
                )
            },
            {
                assertEquals(
                    listOf(PlyId(DOOMED) to PlyReconcileException.Phase.DETACH),
                    raised.map { it.plyId to it.phase },
                    "absorbed is not silent — the failing ply must be raised, carrying its identity",
                )
            },
            { assertIs<SeamState.Torn>(composite.state.value, "the composite must still be terminal") },
        )
    }

    /**
     * The same, against a ply whose `close()` mints a `CancellationException` — `withTimeout(closeTimeout)`,
     * the natural way to bound a close handshake, which throws `TimeoutCancellationException` **to its
     * caller** without cancelling that caller's job.
     *
     * Two things go wrong at once when it is unguarded, and both are asserted: the remaining plies are
     * stranded, and the composite reports the failure *as a cancellation*, which the caller cannot
     * distinguish from its own — so the caller is cancelled silently instead of failed.
     */
    @Test
    fun aPlyWhoseCloseMintsACancellationDoesNotStrandThePliesAfterIt() = runTest {
        val doomed = ClosingLoom(DOOMED, CloseBehaviour.MINTS_CANCELLATION)
        val second = ClosingLoom(SECOND, CloseBehaviour.CLEAN)
        val third = ClosingLoom(THIRD, CloseBehaviour.CLEAN)
        val raised = mutableListOf<PlyReconcileException>()
        val composite = CompositeLoom(
            plies = listOf(PlyId(DOOMED) to doomed as Loom, PlyId(SECOND) to second, PlyId(THIRD) to third),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            onPlyFailure = { raised += it },
        ).host(Pattern("host"))
        runCurrent()

        val escaped = closeCapturingEscape(composite)
        // Sampled here, not inside the assertions: the masquerade's damage is that it CANCELS its caller,
        // and this is the caller.
        val callerStillActive = currentCoroutineContext().isActive

        assertAll(
            {
                assertIs<SeamState.Torn>(
                    second.fake.state.value,
                    "a ply minting a CancellationException must not strand the plies after it — the " +
                        "masquerade cancels the teardown loop rather than failing one item",
                )
            },
            { assertIs<SeamState.Torn>(third.fake.state.value, "…and so must every ply after that") },
            {
                assertTrue(
                    escaped !is CancellationException,
                    "CompositeSeam.close must not report a ply's failure as a cancellation (Seam.close, " +
                        "#1826): a caller cannot tell it from its own, so it is CANCELLED rather than " +
                        "failed — no handler, no stack trace. Got: $escaped",
                )
            },
            { assertNull(escaped, "…and a ply's close failure must not escape at all, got: $escaped") },
            {
                assertEquals(
                    listOf(PlyId(DOOMED) to PlyReconcileException.Phase.DETACH),
                    raised.map { it.plyId to it.phase },
                    "the minted cancellation is the consumer's failure and must be raised, not swallowed",
                )
            },
            { assertTrue(callerStillActive, "the test coroutine must not have been cancelled by the masquerade") },
        )
    }

    /**
     * One guard **per item**, not one around the loop: two plies failing in different ways must both be
     * absorbed, and the ply after both must still close.
     *
     * A single `try` wrapped around the whole `forEach` passes the two tests above — it absorbs the first
     * failure — while still stranding everything after it. This is the case that separates them.
     */
    @Test
    fun everyFailingPlyIsAbsorbedIndividuallyAndTheLoopRunsToTheEnd() = runTest {
        val doomed = ClosingLoom(DOOMED, CloseBehaviour.THROWS)
        val second = ClosingLoom(SECOND, CloseBehaviour.MINTS_CANCELLATION)
        val third = ClosingLoom(THIRD, CloseBehaviour.CLEAN)
        val raised = mutableListOf<PlyReconcileException>()
        val composite = CompositeLoom(
            plies = listOf(PlyId(DOOMED) to doomed as Loom, PlyId(SECOND) to second, PlyId(THIRD) to third),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            onPlyFailure = { raised += it },
        ).host(Pattern("host"))
        runCurrent()

        val escaped = closeCapturingEscape(composite)

        assertAll(
            {
                assertIs<SeamState.Torn>(
                    third.fake.state.value,
                    "the guard is per-ply: two failures of different kinds must both be absorbed and the " +
                        "last ply must still be closed",
                )
            },
            {
                assertEquals(
                    listOf(PlyId(DOOMED), PlyId(SECOND)),
                    raised.map { it.plyId },
                    "both failing plies must be raised, in iteration order",
                )
            },
            { assertNull(escaped, "neither failure may escape the composite's own close(), got: $escaped") },
        )
    }

    /**
     * The other half of the discriminator: the **caller's own** cancellation must still come out.
     *
     * Absorbing every `Throwable` unconditionally would pass all three tests above while swallowing a
     * structured-concurrency cancel — the thing the guard's `currentCoroutineContext().ensureActive()`
     * exists to prevent, and the reason a bare `catch (_: Throwable) { }` is not the right shape here.
     * Without that one line nothing else in this file reds, so this is what pins it.
     *
     * The ply cancels its caller and *then* throws an ordinary exception: the guard must report the
     * cancellation, not the exception. That the plies after it are consequently left open is the correct
     * trade and not an oversight — this site is deliberately unshielded, because a consumer that cancels
     * `close()` has to be able to stop it. The shielded teardowns ([detachPly], [discardOrphanedPly]) are
     * where the opposite trade is made, and their KDoc argues why.
     */
    @Test
    fun aGenuineCallerCancellationIsNotSwallowed() = runTest {
        val doomed = ClosingLoom(DOOMED, CloseBehaviour.CANCELS_ITS_CALLER)
        val second = ClosingLoom(SECOND, CloseBehaviour.CLEAN)
        val composite = CompositeLoom(
            plies = listOf(PlyId(DOOMED) to doomed as Loom, PlyId(SECOND) to second),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        ).host(Pattern("host"))
        runCurrent()

        // A child job of our own, so the cancellation the ply issues is a REAL one rather than a mint.
        val escaped = CompletableDeferred<Throwable?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            val failure = try {
                composite.close(CloseReason.Normal)
                null
            } catch (thrown: Throwable) {
                thrown
            }
            // Reporting it is the last thing this now-cancelled coroutine does, so it must be shielded.
            withContext(NonCancellable) { escaped.complete(failure) }
        }
        runCurrent()

        assertAll(
            { assertTrue(escaped.isCompleted, "the closing coroutine must have finished") },
            {
                assertIs<CancellationException>(
                    escaped.getCompleted(),
                    "close() must not swallow its caller's own cancellation — `ensureActive()` is what tells " +
                        "it apart from one a ply minted, and absorbing it turns a structured-concurrency " +
                        "cancel into a silent success",
                )
            },
        )
    }

    /**
     * Run `close()` and hand back whatever escaped it, rather than letting it end the test.
     *
     * The discrimination is by **state**, not by type: a `CancellationException` caught here is this
     * coroutine's own only if this coroutine is in fact cancelled, which [ensureActive] is the exact test
     * for. Ours propagates (never swallow a structured-concurrency cancel); anything else is what `close()`
     * reported, and returning it is what lets the assertions above say so instead of the test dying
     * silently — which is precisely the damage the obligation under test forbids.
     */
    private suspend fun closeCapturingEscape(seam: Seam): Throwable? =
        try {
            seam.close(CloseReason.Normal)
            null
        } catch (failure: Throwable) {
            currentCoroutineContext().ensureActive()
            failure
        }

    /** How a ply's `close()` behaves — the shapes a consumer-authored teardown can take. */
    private enum class CloseBehaviour { CLEAN, THROWS, MINTS_CANCELLATION, CANCELS_ITS_CALLER }

    /**
     * A [Loom] weaving one ply whose `close()` behaves as [behaviour].
     *
     * [MINTS_CANCELLATION][CloseBehaviour.MINTS_CANCELLATION] is written the way a real close handshake is
     * bounded — `withTimeout(…) { … }`, which throws `TimeoutCancellationException` to its caller without
     * cancelling that caller's job. That makes it *non-conforming* under `Seam.close` (#1826), which is the
     * point: the composite cannot trust a consumer's ply to conform.
     */
    private class ClosingLoom(id: String, private val behaviour: CloseBehaviour) : Loom {
        val fake: FakeSeam = FakeSeam(selfId = PeerId("ply-$id"))

        private val seam: Seam = object : Seam by fake {
            override suspend fun close(reason: CloseReason) {
                when (behaviour) {
                    CloseBehaviour.CLEAN -> fake.close(reason)
                    CloseBehaviour.THROWS -> throw IllegalStateException(REFUSES_MESSAGE)
                    CloseBehaviour.MINTS_CANCELLATION ->
                        withTimeout(CLOSE_TIMEOUT) { delay(1.seconds) }
                    // Cancels the coroutine calling close() and *then* fails ordinarily: the guard must
                    // report the real cancellation, not the exception layered over it.
                    CloseBehaviour.CANCELS_ITS_CALLER -> {
                        currentCoroutineContext().cancel()
                        throw IllegalStateException(REFUSES_MESSAGE)
                    }
                }
            }
        }

        override suspend fun weave(rendezvous: Rendezvous): Seam = seam

        override fun capability(): TransportCapability =
            TransportCapability(setOf(TransportRole.Data), FabricAvailability.Available)
    }

    private companion object {
        const val DOOMED = "doomed"
        const val SECOND = "second"
        const val THIRD = "third"
        const val REFUSES_MESSAGE = "this fabric refuses to close"
        val CLOSE_TIMEOUT = 1.milliseconds
    }
}
