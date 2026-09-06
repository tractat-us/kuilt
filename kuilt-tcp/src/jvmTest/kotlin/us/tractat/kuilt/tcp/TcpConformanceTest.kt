package us.tractat.kuilt.tcp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real-network loopback conformance harness — a TCP socket needs a real IO dispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.conformance.CapabilityGaps
import us.tractat.kuilt.conformance.JoinerRosterOrigin
import us.tractat.kuilt.conformance.ObligationDeclaration
import us.tractat.kuilt.conformance.SeamCapabilities
import us.tractat.kuilt.conformance.SeamConformanceSuite
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Tag
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.time.Duration.Companion.seconds

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

    private companion object {
        /**
         * A TCP session is ONE connection, so one accepted socket is the whole transport under both
         * seams — unlike the per-side handles the radio fakes sever in pairs (#2637). The count still
         * earns its keep: `0` means the pair never reached `accept()` and the rig severed nothing,
         * and anything `> 1` means a stale socket from an earlier pair leaked into the count.
         */
        const val THE_ONE_LINK = 1

        /**
         * How long [injectMidSessionDeath]'s barrier waits, in **real** time, for a loopback FIN to
         * reach two read loops — microseconds of work, so this is a wedge backstop and not an
         * assertion. Generous on purpose: a tight bound here would measure the *host* rather than
         * the code, and a saturated box would red a fabric that was working fine. Nothing is proven
         * by it expiring; the obligation is what fails in that case.
         */
        val TEAR_BARRIER = 10.seconds
    }

    private val selector = SelectorManager(Dispatchers.IO)
    private lateinit var serverSocket: AcceptRecordingServerSocket
    private var port: Int = 0

    @BeforeTest
    fun setUp() = runBlocking {
        // Bind 0 and read the port back off the socket we actually hold. Probing a free port with a
        // throwaway `ServerSocket(0).use { it.localPort }` and re-binding the number is a TOCTOU:
        // the probe closes before the real bind, so on a loaded box another process can take the
        // port in that window (`BindException: Address already in use` — #1590, twice observed on
        // this very line in #1750). Binding 0 has no window.
        serverSocket = AcceptRecordingServerSocket(aSocket(selector).tcp().bind("127.0.0.1", 0))
        port = (serverSocket.localAddress as InetSocketAddress).port
    }

    @AfterTest
    fun tearDown() {
        serverSocket.close()
        selector.close()
    }

    override fun newLoomPair(): Pair<Loom, Loom> {
        // Forget any earlier pair's socket so `injectMidSessionDeath`'s count is about THIS pair.
        serverSocket.forgetAccepted()
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

    /**
     * Kill the transport under both seams with no `close()` anywhere: the harness closes the socket
     * its own listening socket accepted, which is the *whole* TCP connection. The host's read loop
     * fails on a dead channel and the joiner's reads FIN — both reach [SeamState.Torn] through the
     * remote-disconnect path rather than a local [Seam.close], which is the half of the
     * `incoming`-completes contract this fabric had never proven (#1442).
     *
     * ## Why this returns a count rather than a bare `true`
     *
     * [SeamConformanceSuite.incomingCompletesOnInjectedMidSessionDeath] reads only *terminal* state,
     * so it would pass just as happily on a pair that was already dead — crediting a tear this rig
     * did not cause. Two guards make the injection prove it is the thing being observed: the `check`
     * asserts both seams were live *before* the close (the `dropBothEnds` pattern in
     * `:kuilt-conformance`), and [AcceptRecordingServerSocket.severAccepted] reports how many
     * sockets it **actually** severed (the `FakeNwRadio.dropAllLinks` pattern). Anything but
     * [THE_ONE_LINK] leaves this honestly `false`, so the harness reads as unproven and
     * [midSessionDeathDeclarationIsHonest] reds — never falsely green.
     *
     * ## Why the wait below is real time, and why it is only a barrier
     *
     * The four rigs converted in #2637 all drive an in-process fake, so the seam is `Torn` before the
     * injector returns. A real socket is not: the FIN crosses loopback and two read loops notice it
     * on `Dispatchers.IO`, *asynchronously*. The suite's own `withTimeout(5.seconds)` runs on
     * `runTest`'s **virtual** clock, which fast-forwards the whole bound the instant the test
     * coroutine parks — measured here at `Timed out after 5s of _virtual_ time`, `time="0.008"`. That
     * is the timing mismatch #1442 recorded as the blocker, and it is why the wait has to change
     * dispatcher: [withContext] to [Dispatchers.IO] swaps the interceptor for one with no `Delay`, so
     * [withTimeoutOrNull] falls back to the default (real) delay.
     *
     * It is deliberately **`OrNull` and deliberately not part of the return value.** This is a
     * synchronisation barrier, never the verdict: if a future regression stops TCP tearing on a dead
     * socket, the barrier expires quietly, the injector still reports the socket it severed, and the
     * *obligation* reds on its own terms. Folding the wait into the boolean would move the verdict
     * into the meta-test and leave `incomingCompletesOnInjectedMidSessionDeath` unable to fail —
     * asserting the instrument instead of the outcome.
     */
    override suspend fun injectMidSessionDeath(host: Seam, joiner: Seam): Boolean {
        check(host.state.value !is SeamState.Torn && joiner.state.value !is SeamState.Torn) {
            "mid-session-death rig precondition: both seams must be live before the socket is " +
                "closed, or the obligation would pass on a tear this rig did not cause; got " +
                "host=${host.state.value}, joiner=${joiner.state.value}"
        }
        val severed = serverSocket.severAccepted()
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(TEAR_BARRIER) { host.state.first { it is SeamState.Torn } }
            withTimeoutOrNull(TEAR_BARRIER) { joiner.state.first { it is SeamState.Torn } }
        }
        return severed == THE_ONE_LINK
    }

    /** Proven: this harness closes the accepted socket under a live pair, so no gap (#1442). */
    override fun midSessionDeathDeclaration(): ObligationDeclaration = ObligationDeclaration.Proven

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

/**
 * The harness's bound listening socket, decorated to keep a handle on the [Socket] it accepts.
 *
 * This is the whole answer to #1442's "`TcpLoom.weave` accepts the socket internally and exposes it
 * nowhere". Ktor's [ServerSocket] is an *interface* and [TcpLoom.host] takes one, so the harness can
 * hand the loom a decorator and keep the accepted connection — **no production API change**. The
 * joiner's dialled socket stays out of reach (`TcpLoom.join` calls `connect()` itself), and it does
 * not need to be reachable: TCP is one connection, so closing the accepted end tears the host's read
 * loop and delivers FIN to the joiner's.
 */
private class AcceptRecordingServerSocket(
    private val delegate: ServerSocket,
) : ServerSocket by delegate {

    /** Concurrent because `accept()` runs on the host loom's coroutine, not the test's. */
    private val accepted = CopyOnWriteArrayList<Socket>()

    override suspend fun accept(): Socket = delegate.accept().also { accepted += it }

    /** Drop the current pair's socket from the record, so a later count is about the next pair. */
    fun forgetAccepted() {
        accepted.clear()
    }

    /**
     * Close every socket accepted since [forgetAccepted], returning how many were **actually**
     * severed — a socket already dead when this runs is not counted, so a caller comparing against
     * an expected count cannot credit a tear it did not cause.
     */
    fun severAccepted(): Int = accepted.count { socket ->
        val live = socket.socketContext.isActive
        if (live) socket.close()
        live
    }
}
