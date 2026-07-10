// kuilt-websocket/src/jvmTest/kotlin/us/tractat/kuilt/websocket/KtorClientLoomWeftTest.kt
package us.tractat.kuilt.websocket

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.testApplication
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies #1330's per-dial hook at the [KtorClientLoom] unit level: `weft` is invoked fresh on
 * every [KtorClientLoom.join] call, its [WebSocketDialContext.queryParams] and
 * [WebSocketDialContext.headers] reach the server, and two sequential joins each get their own
 * fresh value rather than a cached one.
 *
 * This does not stand up the `SeamRoom`/`JoinerResumeMachine` reconnect harness — that composition
 * is verified by reading the actual call sites instead (`SeamRoom.kt:134`'s
 * `reweave = { loom.join(tag) }`, invoked fresh by `JoinerResumeMachine.runReconnect` on every
 * retry; see the design doc's "why this needs no changes" section). Calling `join()` twice here
 * exercises the same `weave()` path those retries call — it's a deliberate unit-scope boundary,
 * not a claim that this test alone proves the full reconnect composition.
 */
class KtorClientLoomWeftTest {

    private val serverPath = "/ws/weft-test"

    @Test
    fun `weft query params and headers land on the dial, percent-encoded`() =
        testApplication {
            var capturedQuery: String? = null
            var capturedHeader: String? = null
            val serverLoom = KtorServerLoom(
                application,
                serverPath,
                principalExtractor = { call ->
                    capturedQuery = call.request.queryParameters["ticket"]
                    capturedHeader = call.request.headers["X-Auth"]
                    null
                },
            )
            val clientLoom = KtorClientLoom(
                httpClient = createClient { install(WebSockets) },
                weft = {
                    WebSocketDialContext(
                        queryParams = mapOf("ticket" to "abc 123&x"),
                        headers = mapOf("X-Auth" to "bearer-xyz"),
                    )
                },
            )
            val advertisement = WebSocketAdvertisement(
                url = "ws://localhost$serverPath",
                serverPeerId = serverLoom.selfPeerId,
                sessionName = "client",
            )

            val (_, clientSeam) = connectPair(serverLoom, advertisement, clientLoom)
            clientSeam.close(CloseReason.Normal)

            assertAll(
                { assertEquals("abc 123&x", capturedQuery, "query param round-trips through percent-encoding") },
                { assertEquals("bearer-xyz", capturedHeader, "header lands on the upgrade request") },
            )
        }

    @Test
    fun `weft is invoked fresh on every join, not cached`() =
        testApplication {
            var callCount = 0
            val seenTickets = mutableListOf<String?>()
            val serverLoom = KtorServerLoom(
                application,
                serverPath,
                principalExtractor = { call ->
                    seenTickets += call.request.queryParameters["ticket"]
                    null
                },
            )
            val clientLoom = KtorClientLoom(
                httpClient = createClient { install(WebSockets) },
                weft = {
                    callCount++
                    WebSocketDialContext(queryParams = mapOf("ticket" to "ticket-$callCount"))
                },
            )
            val advertisement = WebSocketAdvertisement(
                url = "ws://localhost$serverPath",
                serverPeerId = serverLoom.selfPeerId,
                sessionName = "client",
            )

            val (_, firstSeam) = connectPair(serverLoom, advertisement, clientLoom)
            firstSeam.close(CloseReason.Normal)

            val (_, secondSeam) = connectPair(serverLoom, advertisement, clientLoom)
            secondSeam.close(CloseReason.Normal)

            assertAll(
                { assertEquals(2, callCount, "weft invoked once per join attempt, including the redial") },
                { assertEquals(listOf<String?>("ticket-1", "ticket-2"), seenTickets, "server saw a fresh ticket on each dial") },
            )
        }
}
