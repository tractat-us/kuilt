package us.tractat.kuilt.conformance

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What [Connection.send] owes its caller once the link behind it has died.
 *
 * The link is gone, so the frame cannot go anywhere. The only question is how the caller hears
 * about it, and the answer has to be an ordinary exception. `MeshSeam` and `LinkSeam` both evict a
 * dead link from the failure of `send`, through a guard that rethrows any `CancellationException`,
 * so a link that reports its death as a cancellation is never evicted, and the coroutine that
 * tried to send is cancelled rather than failed. Nothing logs it and nothing handles it.
 *
 * Subclass this once per real transport **and per way that transport can die**, and return from
 * [deadLink] a link that really has died. There is no in-memory reference binding on purpose:
 * `connectionPair` drops a frame sent after a close and returns normally, so it cannot reach the
 * failure this suite is about. A bare `session.send` over a closed Ktor session could, and did,
 * which is why the bindings are real sessions and real sockets.
 *
 * ## What it does not reach
 *
 * Nothing here sends into a link that dies *while* the send is suspended, because a binding needs a
 * transport-specific way to hold a send there. Nothing calls [Connection.close], and nothing
 * collects [Connection.incoming].
 */
public abstract class ConnectionSendConformanceSuite {

    /**
     * A real link that has died, already past the point where the transport knows it.
     *
     * Non-nullable, and with no "cannot reach this" opt-out: a transport with no way to die is not
     * something this suite can say anything about, so the answer for one is not to subclass it.
     * A binding whose fixture hands back a healthy link fails loudly, because
     * [sendIntoADeadLinkFailsALiveCallerWithAnOrdinaryException] requires the send to fail.
     *
     * The binding owns the link's lifetime and releases it after the test. It must not parent the
     * transport's coroutines to the calling coroutine, which is the test's own job.
     */
    protected abstract suspend fun deadLink(): Connection

    /** What this transport's `send` can do for a caller that is already cancelled. */
    protected abstract fun cancelledCaller(): CancelledCallerFixture

    /**
     * A caller that nobody cancelled hears about a dead link through an ordinary exception.
     *
     * This is the obligation `Seam.sendTo` and [Connection.send] both state. Reds on a bare
     * `session.send` over a closed Ktor session, which throws the session's own cancellation.
     */
    @Test
    public fun sendIntoADeadLinkFailsALiveCallerWithAnOrdinaryException(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) { assertLiveCallerGetsAnOrdinaryFailure(deadLink()) }

    /**
     * The converse, and what stops a fix for the first property overshooting: converting every
     * cancellation into a plain exception would hide the caller's own, and the caller would then
     * carry on as though it were still running.
     */
    @Test
    public fun sendIntoADeadLinkLeavesACancelledCallerCancelled(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) { assertCancelledCallerOutcome(deadLink(), cancelledCaller()) }
}

/**
 * What a transport's [Connection.send] does for a caller that is already cancelled.
 *
 * Two arms rather than a nullable hook, because the second is a real answer rather than a missing
 * one, and the suite checks each.
 */
public sealed interface CancelledCallerFixture {

    /** `send` observes the caller's cancellation, and the suite requires the caller to see it. */
    public data object Observes : CancelledCallerFixture

    /**
     * `send` blocks its thread and never suspends the caller, so no cancellation check lies on its
     * path. `:kuilt-stream`'s `framed()` over a blocking `Sink` has this shape: the transport's
     * failure reaches the caller as it stands, and the caller's cancellation lands at its next
     * suspension point.
     *
     * The suite still requires that nothing in `send` cleared the cancellation. It cannot tell a
     * transport that truly has no check from one that has a check and swallows what it found, which
     * is what [reason] is for: a sentence for a reader, unchecked beyond not being blank.
     */
    public data class NeverChecks(val reason: String) : CancelledCallerFixture
}

/** How many frames a dead link may swallow before the suite calls it healthy. */
private const val MAX_SENDS_BEFORE_REFUSAL = 100_000

/** A dead TCP link can take the first frames, because the kernel accepts them before the reset. */
private suspend fun Connection.sendUntilRefused(): Throwable? {
    repeat(MAX_SENDS_BEFORE_REFUSAL) {
        try {
            send(FRAME)
        } catch (failure: Throwable) {
            return failure
        }
        yield()
    }
    return null
}

private val FRAME = byteArrayOf(1)

/** The property [ConnectionSendConformanceSuite.sendIntoADeadLinkFailsALiveCallerWithAnOrdinaryException] asserts. */
internal suspend fun assertLiveCallerGetsAnOrdinaryFailure(link: Connection) {
    val failure = assertNotNull(
        link.sendUntilRefused(),
        "A dead link took $MAX_SENDS_BEFORE_REFUSAL frames without refusing one. Either deadLink() " +
            "handed back a healthy link, or this transport cannot report the failure at all.",
    )
    assertTrue(
        currentCoroutineContext().isActive,
        "The caller must be live for this to be a statement about a minted cancellation, and it is " +
            "not, so `send` cancelled it: $failure",
    )
    if (failure is CancellationException) {
        fail(
            "send reported a dead link as a cancellation to a live caller: $failure. The caller's " +
                "guard rethrows it, so the link is never evicted and the caller is cancelled silently.",
        )
    }
}

/** The property [ConnectionSendConformanceSuite.sendIntoADeadLinkLeavesACancelledCallerCancelled] asserts. */
internal suspend fun CoroutineScope.assertCancelledCallerOutcome(link: Connection, fixture: CancelledCallerFixture) {
    var reachedSend = false
    var thrown: Throwable? = null
    var cancelledAfterSend = false
    val caller = launch {
        currentCoroutineContext().cancel(CancellationException("the caller's own cancellation"))
        assertFalse(currentCoroutineContext().isActive, "the rig did not cancel the caller")
        reachedSend = true
        thrown = link.sendCatching()
        cancelledAfterSend = !currentCoroutineContext().isActive
    }
    caller.join()
    assertTrue(reachedSend, "the caller never reached send, so nothing was tested")
    assertTrue(caller.isCancelled, "the caller finished normally, so its cancellation was lost on the way out")
    when (fixture) {
        CancelledCallerFixture.Observes ->
            assertIs<CancellationException>(thrown, "a cancelled caller's send into a dead link must report the cancellation")

        is CancelledCallerFixture.NeverChecks -> {
            assertTrue(fixture.reason.isNotBlank(), "NeverChecks needs a reason a reader can check")
            assertTrue(cancelledAfterSend, "send cleared the caller's cancellation")
        }
    }
}

private suspend fun Connection.sendCatching(): Throwable? =
    try {
        send(FRAME)
        null
    } catch (failure: Throwable) {
        failure
    }
