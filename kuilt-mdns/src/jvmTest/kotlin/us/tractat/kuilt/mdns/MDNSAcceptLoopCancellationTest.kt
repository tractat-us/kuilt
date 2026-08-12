@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.mdns

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A self-dial's refusal to close must not stop [MDNSMultiAcceptHost] accepting joiners (#2286).
 *
 * [acceptNonSelfSeam] closes a self-dialed seam best-effort and `continue`s. Guarding that close with
 * `runCatchingCancellable` re-throws a `CancellationException` the *seam* minted — a `withTimeout`
 * inside its own `close`, which never cancelled us. Because the escaping throwable IS a
 * `CancellationException` the accept coroutine is *cancelled rather than failed*: no handler, no
 * stack trace, and the throw escapes `nextSeam()` **and** the `seams()` flow, so a host that trips one
 * self-dial silently stops accepting joiners for the rest of its life. The discriminator is
 * `currentCoroutineContext().ensureActive()` — it re-throws only when *we* were cancelled.
 *
 * The pair below is the point. The first test proves a callee-minted cancellation is absorbed; the
 * second proves our own still escapes. Without the second, a blanket `catch { }` would pass the first
 * while breaking every caller that cancels an accept loop.
 */
class MDNSAcceptLoopCancellationTest {

    private val hostId = PeerId("mdns-host")

    @Test
    fun `a self-dial whose close mints a cancellation must not stop the accept loop`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val selfDial = StubSeam(
                selfId = hostId,
                peerSet = setOf(hostId),
                onClose = { throw CancellationException("close timed out inside the seam") },
            )
            val joiner = StubSeam(selfId = hostId, peerSet = setOf(hostId, PeerId("joiner")))
            val supplied = mutableListOf<Seam>()
            val links = ArrayDeque(listOf(selfDial, joiner))

            val accepted = acceptNonSelfSeam { links.removeFirst().also { supplied += it } }

            assertAll(
                { assertSame(joiner, accepted, "the loop must skip the self-dial and return the real joiner") },
                { assertEquals(2, supplied.size, "the loop must pull a second link after the failed close") },
                { assertTrue(selfDial.closeAttempted, "the self-dial must still have been closed best-effort") },
            )
        }

    @Test
    fun `our own cancellation still escapes the accept loop`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            // Same failing close, but this time WE are cancelled first. `nextLink` is a plain suspend
            // lambda that returns without suspending, so a swallow-everything guard would loop right
            // past the cancellation and hand a caller a live seam on a dead coroutine — which is what
            // the second assertion pins shut.
            val selfDial = StubSeam(
                selfId = hostId,
                peerSet = setOf(hostId),
                onClose = {
                    currentCoroutineContext().cancel(CancellationException("the accept loop was cancelled"))
                    throw CancellationException("…and the close failed too")
                },
            )
            val joiner = StubSeam(selfId = hostId, peerSet = setOf(hostId, PeerId("joiner")))
            val supplied = mutableListOf<Seam>()
            val links = ArrayDeque(listOf(selfDial, joiner))

            // Own scope so the cancellation lands on it and not on the test coroutine itself.
            assertFailsWith<CancellationException> {
                coroutineScope { acceptNonSelfSeam { links.removeFirst().also { supplied += it } } }
            }
            assertEquals(1, supplied.size, "our own cancellation must abort the loop, not pull another link")
        }
}

/** A [Seam] with a fixed roster whose [close] runs [onClose] — the only member carrying behaviour. */
private class StubSeam(
    override val selfId: PeerId,
    peerSet: Set<PeerId>,
    private val onClose: suspend () -> Unit = {},
) : Seam {
    override val peers: StateFlow<Set<PeerId>> = MutableStateFlow(peerSet).asStateFlow()
    override val state: StateFlow<SeamState> = MutableStateFlow<SeamState>(SeamState.Woven).asStateFlow()
    override val incoming: Flow<Swatch> = emptyFlow()

    var closeAttempted: Boolean = false
        private set

    override suspend fun broadcast(payload: ByteArray) = Unit
    override suspend fun sendTo(peer: PeerId, payload: ByteArray) = Unit

    override suspend fun close(reason: CloseReason) {
        closeAttempted = true
        onClose()
    }
}
