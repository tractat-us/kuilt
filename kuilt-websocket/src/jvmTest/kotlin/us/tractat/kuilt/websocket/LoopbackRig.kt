package us.tractat.kuilt.websocket

import org.junit.Assume
import java.net.BindException
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/**
 * The one address a real-socket conformance rig here binds **and** dials (#3754).
 *
 * Ktor's Netty connector binds the wildcard by default. On macOS a second socket may then bind
 * `127.0.0.1` on the same port: Java sets `SO_REUSEADDR`, and BSD lets a more specific address share
 * a port with the wildcard. That socket then takes every loopback dial to the port, `localhost`
 * included. If it is an HTTP app without our path the handshake gets a 404; if not, it sees the
 * connection close. Those are the two shapes #3754 and #3603 recorded under load. A rig bound to
 * this address holds the exact address a thief would need, so the thief's bind fails instead.
 */
internal const val LOOPBACK_HOST = "127.0.0.1"

/**
 * Plant a loopback thief on [port] and require the bind to fail with `Address already in use`. This
 * is the property that makes a rig's dial its own.
 *
 * macOS only. The steal was measured under a BSD binding rule, and Linux's rule differs, so this arm
 * makes no claim about Linux. The bind is still correct there: it names the address the rig dials.
 */
internal fun assertLoopbackThiefRefused(port: Int) {
    Assume.assumeTrue(
        "the loopback-over-wildcard bind is a BSD behaviour",
        System.getProperty("os.name").orEmpty().startsWith("Mac"),
    )
    ServerSocketChannel.open().use { thief ->
        val refused = assertFailsWith<BindException>(
            "a socket bound $LOOPBACK_HOST:$port while the rig held the port, so it would take the rig's " +
                "loopback dials (#3754). Bind the rig to $LOOPBACK_HOST, not the wildcard.",
        ) {
            thief.bind(InetSocketAddress(LOOPBACK_HOST, port))
        }
        assertContains(refused.message.orEmpty(), "Address already in use")
    }
}
