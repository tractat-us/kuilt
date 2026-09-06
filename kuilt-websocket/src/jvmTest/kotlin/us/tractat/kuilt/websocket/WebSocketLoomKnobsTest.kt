package us.tractat.kuilt.websocket

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.Overflow
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What [webSocketLoomJoin]'s construction knobs actually reach — the behavioural half of the
 * uniform `Loom`-construction convention (#1430), for `:kuilt-websocket`.
 *
 * The convention's rule is that a factory carries a knob **only** where the fabric honours it, so
 * each case here drives a knob to a non-default value and asserts a difference the default could
 * not produce. Nothing reads a getter back: a loom exposes no `policy` property, and a test that
 * read one would pin the field rather than the fabric it is meant to change.
 *
 * ## Why `policy` is asserted through a tear
 * The two landed precedents ([us.tractat.kuilt.core.fabric.handshaking]'s and `NearbyLoom`'s
 * policy tests) both run on a virtual clock, where `runCurrent()` drives every in-memory hop to a
 * standstill and a consumer can attach *after* the inbox has already overflowed. There is no such
 * instant here: this fabric's only test harness is a real in-process socket, and a consumer
 * attached to `incoming` would drain the spool as fast as it filled, so no overflow strategy of
 * any kind could be observed.
 *
 * `Overflow.FAIL` removes the need for that instant. It is the one strategy whose effect does not
 * depend on *when* a consumer arrives: with no consumer attached, a capacity-1 inbox accepts frame
 * 1, and frame 2 raises `FrameOverflow` inside the seam's read loop, which tears the seam down.
 * The tear is a `StateFlow` transition — awaited, not slept on.
 *
 * [theDefaultReliablePolicyAbsorbsTheSameBurst] is the control arm, and it is what makes the tear
 * a diagnosis rather than a coincidence: identical rig, identical burst, only the policy differs,
 * and the seam does not tear. Without it, "the seam tore" would be evidence for any number of
 * unrelated causes.
 */
class WebSocketLoomKnobsTest {

    private val serverPath = "/ws/knobs-test"

    // ── policy ────────────────────────────────────────────────────────────────

    @Test
    fun `a strict capacity-1 policy reaches the client seam's inbox`() =
        testApplication {
            val serverLoom = KtorServerLoom(application, serverPath)
            val clientLoom =
                webSocketLoomJoin(
                    httpClient = createClient { install(WebSockets) },
                    policy = DeliveryPolicy(capacity = 1, overflow = Overflow.FAIL),
                )
            val (serverSeam, clientSeam) = connectPair(serverLoom, clientLoom)

            // Vacuity guard: the rig must START in the state the assertion claims it leaves. A seam
            // already Torn on arrival (a dial that failed, a route that never mounted) would satisfy
            // the assertion below without the policy having done anything at all.
            assertEquals(
                SeamState.Woven,
                clientSeam.state.value,
                "precondition: the client seam is live before the burst — otherwise the tear below proves nothing",
            )

            // No consumer is attached to clientSeam.incoming, so the inbox never drains: frame 1 fills
            // the capacity-1 buffer and frame 2 overflows it.
            repeat(BURST) { serverSeam.broadcast(byteArrayOf(it.toByte())) }

            val torn = withTimeoutOrNull(TEAR_TIMEOUT_MS) { clientSeam.state.first { it is SeamState.Torn } }

            assertNotNull(
                torn,
                "a capacity-1 Overflow.FAIL policy must reach the seam's Spool: the second undrained " +
                    "frame raises FrameOverflow in the read loop and tears the seam. Still Woven means " +
                    "the factory's policy never got past WebSocketSeam (#2686).",
            )
            serverSeam.close(CloseReason.Normal)
        }

    /**
     * The control arm for [a strict capacity-1 policy reaches the client seam's inbox]: the same
     * three frames, the same undrained inbox, the default policy — and no tear.
     */
    @Test
    fun theDefaultReliablePolicyAbsorbsTheSameBurst() =
        testApplication {
            val serverLoom = KtorServerLoom(application, serverPath)
            val clientLoom = webSocketLoomJoin(httpClient = createClient { install(WebSockets) })
            val (serverSeam, clientSeam) = connectPair(serverLoom, clientLoom)

            repeat(BURST) { serverSeam.broadcast(byteArrayOf(it.toByte())) }

            val tornEarly = withTimeoutOrNull(TEAR_TIMEOUT_MS) { clientSeam.state.first { it is SeamState.Torn } }
            val received = withTimeout(COLLECT_TIMEOUT_MS) { clientSeam.incoming.take(BURST).toList() }

            assertAll(
                {
                    assertNull(
                        tornEarly,
                        "the identical burst under the default Reliable policy must NOT tear the seam — " +
                            "that is what makes the other arm's tear attributable to the policy and nothing else",
                    )
                },
                {
                    assertContentEquals(
                        listOf<Byte>(0, 1, 2),
                        received.map { it.toByteArray().single() },
                        "…and a 256-deep SUSPEND inbox still holds every frame of the burst",
                    )
                },
            )
            serverSeam.close(CloseReason.Normal)
        }

    // ── selfId ────────────────────────────────────────────────────────────────

    @Test
    fun `the supplied selfId is the identity presented on the wire`() =
        testApplication {
            val pinned = PeerId("pinned-client-id")
            val serverLoom = KtorServerLoom(application, serverPath)
            val clientLoom = webSocketLoomJoin(httpClient = createClient { install(WebSockets) }, selfId = pinned)
            val (serverSeam, clientSeam) = connectPair(serverLoom, clientLoom)

            val asSeenByServer = withTimeout(COLLECT_TIMEOUT_MS) {
                serverSeam.peers.first { pinned in it || it.size > 1 }
            }

            assertAll(
                { assertEquals(pinned, clientSeam.selfId, "the seam's own identity is the one supplied") },
                {
                    assertContentEquals(
                        listOf(pinned),
                        asSeenByServer.filter { it != serverSeam.selfId },
                        "…and it is the identity the far end admitted, not a freshly minted one",
                    )
                },
            )
            serverSeam.close(CloseReason.Normal)
        }

    /**
     * The default is a *fresh* identity per loom, not a shared constant — the property
     * [us.tractat.kuilt.core.freshPeerId] exists to hold, and the reason the old inlined
     * `PeerId(Uuid.random().toString())` was safe to replace with it.
     */
    @Test
    fun theDefaultSelfIdIsDistinctPerLoom() =
        testApplication {
            val client = createClient { install(WebSockets) }
            assertNotEquals(
                webSocketLoomJoin(httpClient = client).selfPeerId,
                webSocketLoomJoin(httpClient = client).selfPeerId,
                "two looms built with the same arguments must not share a fabric identity",
            )
        }

    // ── helper ────────────────────────────────────────────────────────────────

    private suspend fun connectPair(
        serverLoom: KtorServerLoom,
        clientLoom: KtorClientLoom,
    ): Pair<Seam, Seam> =
        withTimeout(COLLECT_TIMEOUT_MS) {
            coroutineScope {
                val serverLinkDeferred = async { serverLoom.nextLink() }
                val clientLink =
                    clientLoom.join(
                        WebSocketAdvertisement(
                            url = "ws://localhost$serverPath",
                            serverPeerId = serverLoom.selfPeerId,
                            sessionName = "knobs",
                        ),
                    )
                serverLinkDeferred.await() to clientLink
            }
        }

    private companion object {
        /** Three frames: one fits a capacity-1 inbox, the rest do not. */
        const val BURST = 3

        /**
         * How long a tear is waited for. It is *also* how long the control arm waits to be sure no
         * tear happens, so it cannot be trimmed to nothing: a window shorter than the round trip
         * would make the control arm pass vacuously.
         */
        const val TEAR_TIMEOUT_MS = 2_000L
        const val COLLECT_TIMEOUT_MS = 5_000L
    }
}
