package us.tractat.kuilt.test

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.PeerId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Consumer-level invariant: an **establishment await** over a controllable seam must **abort**
 * (throw within a bound) when membership drains mid-handshake — it must **never suspend forever**.
 *
 * ## What it models — the [#1466] hardware failure
 *
 * A *membership drain* is distinct from a *transport tear*: `Seam.peers` collapses to `{self}`
 * while `Seam.state` stays [us.tractat.kuilt.core.SeamState.Woven] — the co-elector simply left
 * the roster, no `close()` and no `Torn` latch. A consumer that only wakes on a seam **tear**
 * (the transport-death half) silently suspends forever on this event; that is exactly how #1466
 * shipped green. [FakeSeam.removePeer] reproduces the drain precisely (drops `_peers`, leaves
 * `state` [us.tractat.kuilt.core.SeamState.Woven]) — this helper drives it.
 *
 * ## The protocol it drives
 *
 * 1. Launch [establish] in a caught driver coroutine so a thrown exception is captured, not
 *    propagated to (and cancelling) the test scope.
 * 2. [runCurrent] — let [establish] subscribe to `incoming` / broadcast its first handshake frame
 *    and suspend at the mid-handshake point (awaiting a `Freeze`, a `FreezeAck`, etc.).
 * 3. Drain membership: [FakeSeam.removePeer] drops [drainedPeer] from `peers` — the seam stays
 *    **Woven** (no tear). This is the collapse.
 * 4. Assert [establish] resolves by **throwing** [E] within [timeout]. A [TimeoutCancellationException]
 *    here means [establish] suspended past the bound — the very hang this invariant forbids — and is
 *    reported as a loud [AssertionError], never a silent timeout.
 *
 * Runs under [TestScope]; pair with `runTest(timeout = TEST_WEDGE_BACKSTOP)` and a
 * `StandardTestDispatcher`.
 *
 * This does not depend on `kotlin-test` (not a `commonMain` dependency of `:kuilt-test`); it raises a
 * plain [AssertionError] on failure, which every kotlin-test runner surfaces identically.
 *
 * @param seam the controllable seam the consumer establishes over — must be the same [FakeSeam]
 *   [establish] reads, so [drainedPeer]'s removal is observed by the in-flight handshake.
 * @param drainedPeer the co-elector to drop mid-handshake (the elected host, or the awaited member).
 * @param E the terminal exception [establish] must throw on the collapse (e.g. `LobbyTornException`).
 *
 * [#1466]: https://github.com/tractat-us/kuilt/issues/1466
 */
public suspend inline fun <reified E : Throwable> TestScope.assertAbortsOnMidHandshakeCollapse(
    seam: FakeSeam,
    drainedPeer: PeerId,
    timeout: Duration = 5.seconds,
    crossinline establish: suspend () -> Unit,
) {
    val outcome = CompletableDeferred<Unit>()
    val driver = launch {
        try {
            establish()
            outcome.complete(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            outcome.completeExceptionally(e)
        }
    }

    // Let establish() subscribe / broadcast its first handshake frame and suspend mid-2PC.
    runCurrent()

    // The collapse: peer leaves the roster while the seam stays Woven (NO tear). #1466.
    seam.removePeer(drainedPeer)

    val thrown: Throwable? =
        try {
            withTimeout(timeout) { outcome.await() }
            null // establish() resolved normally
        } catch (e: TimeoutCancellationException) {
            driver.cancel()
            throw AssertionError(
                "establishment SUSPENDED past $timeout on a mid-handshake membership drain — it must " +
                    "abort by throwing ${E::class.simpleName} within the bound, never hang (see #1466)",
                e,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            e
        }

    driver.cancel()

    if (thrown == null) {
        throw AssertionError(
            "establishment resolved normally on a mid-handshake membership drain — " +
                "expected it to throw ${E::class.simpleName} (a drained co-elector cannot form a session)",
        )
    }
    if (thrown !is E) {
        throw AssertionError(
            "establishment threw ${thrown::class.simpleName} on a mid-handshake membership drain — " +
                "expected ${E::class.simpleName}",
            thrown,
        )
    }
}
