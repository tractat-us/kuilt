package us.tractat.kuilt.websocket

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals

class KtorConnectionSourceRoundTripTest {

    @Test
    fun acceptedConnectionReceivesClientFrame() = testApplication {
        val source = KtorConnectionSource(application, "/hub")
        val client = createClient { install(ClientWebSockets) }

        val payload = byteArrayOf(1, 2, 3, 4)
        coroutineScope {
            val accepted = async { source.accept() }
            client.webSocket("/hub") {
                send(Frame.Binary(fin = true, data = payload))
                val conn = withTimeout(5_000) { accepted.await() }
                assertContentEquals(payload, withTimeout(5_000) { conn.incoming.first() })
            }
        }
    }
}
