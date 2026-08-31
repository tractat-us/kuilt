@file:Suppress("ForbiddenImport") // real-network loopback conformance harness — a TCP socket needs a real IO dispatcher

package us.tractat.kuilt.tcp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real-network loopback conformance harness — a TCP socket needs a real IO dispatcher
import kotlinx.coroutines.runBlocking
import us.tractat.kuilt.conformance.CapabilityGaps
import us.tractat.kuilt.conformance.JoinerRosterOrigin
import us.tractat.kuilt.conformance.SeamCapabilities
import us.tractat.kuilt.conformance.SeamConformanceSuite
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Tag
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/**
 * Verifies that the TCP fabric ([TcpLoom]) satisfies every invariant in
 * [SeamConformanceSuite] over a **real localhost socket** — a real Ktor TCP
 * server socket bound on an ephemeral port, a real Ktor TCP client socket, real
 * bytes framed by `:kuilt-stream`'s `framed()`.
 *
 * This is a real-IO test, not a virtual-time test (sockets cannot be driven by a
 * test scheduler), mirroring [us.tractat.kuilt.websocket]'s conformance harness.
 *
 * [newLoomPair] returns distinct host/joiner [TcpLoom]s: the host accepts one
 * connection on the pre-bound [serverSocket]; the joiner connects to [joinTag]'s
 * address. The suite drives `host()`/`join()` concurrently, so the host loom's
 * accept-then-handshake satisfies the suspend-until-joiner contract naturally.
 */
class TcpConformanceTest : SeamConformanceSuite() {

    private val selector = SelectorManager(Dispatchers.IO)
    private lateinit var serverSocket: ServerSocket
    private var port: Int = 0

    @BeforeTest
    fun setUp() = runBlocking {
        // Bind 0 and read the port back off the socket we actually hold. Probing a free port with a
        // throwaway `ServerSocket(0).use { it.localPort }` and re-binding the number is a TOCTOU:
        // the probe closes before the real bind, so on a loaded box another process can take the
        // port in that window (`BindException: Address already in use` — #1590, twice observed on
        // this very line in #1750). Binding 0 has no window.
        serverSocket = aSocket(selector).tcp().bind("127.0.0.1", 0)
        port = (serverSocket.localAddress as InetSocketAddress).port
    }

    @AfterTest
    fun tearDown() {
        serverSocket.close()
        selector.close()
    }

    override fun newLoomPair(): Pair<Loom, Loom> {
        val hostLoom = TcpLoom.host(serverSocket, PeerId("tcp-host"), selector)
        val joinerLoom = TcpLoom.join(PeerId("tcp-joiner"), selector)
        return hostLoom to joinerLoom
    }

    override fun joinTag(): Tag = TcpAddress(host = "127.0.0.1", port = port)

    /**
     * meshDelivery vacuously true — strictly 2-peer direct socket (Task 1.8 / #1408
     * meshEvidence: 2-peer vacuity). Raw bytes, no wire encryption, and no path observer (#1712).
     */
    override fun capabilities(): SeamCapabilities =
        SeamCapabilities.FULL.copy(securesTransport = false, reportsLiveCapability = false)

    override fun capabilityGaps(): Map<String, String> = mapOf(
        "securesTransport" to CapabilityGaps.SECURES_TRANSPORT,
        "reportsLiveCapability" to CapabilityGaps.LIVE_CAPABILITY,
    )

    /** #2591: the joiner starts at `{ selfId }` and grows only through the join path. */
    override fun joinerRosterOrigin(): JoinerRosterOrigin =
        JoinerRosterOrigin.TheJoinPath(
            "handshaking()'s Hello preamble over a real loopback socket: the joiner learns the host's PeerId " +
            "off the wire before its LinkSeam exists. Honest weakness: that exchange is a PRECONDITION of " +
            "weave() returning, so a join path that stopped recording the peer would wedge the weave rather " +
            "than red this arm.",
        )

    /**
     * No gap: TCP is the one in-tree fabric that **publishes** a frame ceiling. `framed()` names its
     * `maxFrameSize`, [TcpConnection] passes it up as `Connection.maxFrameBytes`, and `LinkSeam`
     * surfaces it as `Seam.maxPayloadBytes` — so this harness is held to the number by
     * [payloadOfExactlyTheBudgetIsCarried] and [overBudgetAddressedSendIsRefusedNotLeaked] instead of
     * declaring it away (#2069).
     */
    override fun payloadBudgetGap(): String? = null
}
