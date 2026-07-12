@file:Suppress("ForbiddenImport", "ForbiddenMethodCall") // real-network loopback conformance harness — a real Network.framework socket needs a real IO dispatcher; there is no virtual-time option here
@file:OptIn(ExperimentalForeignApi::class)

package us.tractat.kuilt.nw

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import platform.posix.AF_INET
import platform.posix.INADDR_ANY
import platform.posix.SOCK_STREAM
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import us.tractat.kuilt.conformance.SeamCapabilities
import us.tractat.kuilt.conformance.SeamConformanceSuite
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Tag
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/**
 * Verifies that [NwLoom] over the **real** Apple Network.framework binding ([RealNwApi]) satisfies
 * every invariant in [SeamConformanceSuite] on the macOS runner — a real `127.0.0.1` TLS-PSK link,
 * real GCD sockets, real bytes over the loopback interface. This is the CI behavioural proof that
 * lets the fabric declare `securesTransport = true` honestly: the suite only passes if the
 * out-of-band-derived PSK ([NwPsk.derive]) drives the TLS handshake on both ends and every frame
 * moves over the encrypted link. Closes the `securesTransport` gap the fake-backed
 * [NwConformanceTest] still carries (it runs over the in-memory [FakeNwApi], which has no wire
 * crypto).
 *
 * ## Loopback mode (no Bonjour, no AWDL)
 * Both looms run over [RealNwApi] built with a [NwLoopbackConfig]: it binds a direct
 * `127.0.0.1:port` listener with no Bonjour advertise and dials a fixed host/port, so the run needs
 * no multicast, no network permissions, and no second device. `includePeerToPeer(false)` keeps it
 * off AWDL; the TLS-PSK params are otherwise byte-identical to the P2P surface.
 *
 * ## Asymmetric host/joiner (minimises the connect race)
 * Mirrors [us.tractat.kuilt.tcp]'s `TcpConformanceTest`: the HOST listens on a known free port and
 * never dials ([NwLoopbackConfig.dialPort] = null — its browse emits nothing); the JOINER listens on
 * a throwaway port and dials the host. Each side still runs the full `NwLoom.weave` (advertise +
 * browse + auto-dial), so exactly one host↔joiner link forms with no double-dial.
 *
 * ## Real dispatcher (not virtual time)
 * [NwLoom.weave] derives its seam scope from `currentCoroutineContext()`, so under the suite's
 * `runTest` virtual clock its `withTimeout` would fast-forward and spuriously time out before the
 * real GCD sockets connect. [realDispatchLoom] wraps each loom so `weave` runs on a real
 * [Dispatchers.Default] — the seam's timers and collectors then run in real wall-clock time, while
 * the suite still collects the resulting flows from its test dispatcher (a real-IO test cannot be
 * driven by a test scheduler).
 */
class NwLoopbackConformanceTest : SeamConformanceSuite() {

    private companion object {
        const val SERVICE_TYPE = "_kuilt._tcp"
        const val ROOM_KEY = "loopback-secret"
    }

    private var hostPort: Int = 0
    private var joinerPort: Int = 0

    /** The real APIs built for the current test, torn down (listeners/browsers cancelled) in [tearDown]. */
    private val apis = mutableListOf<RealNwApi>()

    @BeforeTest
    fun setUp() {
        hostPort = freePort()
        joinerPort = freePort()
    }

    @AfterTest
    fun tearDown() = runBlocking {
        // Cancel the loopback listeners/browsers so the next test's fresh ports bind cleanly and no
        // NW resources leak across the run. Seams are closed by the tests themselves.
        apis.forEach { api ->
            api.stopListening()
            api.stopBrowsing()
        }
        apis.clear()
    }

    override fun newLoomPair(): Pair<Loom, Loom> {
        val psk = NwPsk.derive(ROOM_KEY, SERVICE_TYPE)
        val hostApi = RealNwApi(psk, NwLoopbackConfig(listenPort = hostPort, dialHost = null, dialPort = null))
        val joinerApi = RealNwApi(psk, NwLoopbackConfig(listenPort = joinerPort, dialHost = "127.0.0.1", dialPort = hostPort))
        apis += hostApi
        apis += joinerApi
        val host = NwLoom(hostApi, serviceType = SERVICE_TYPE, random = Random(0))
        val joiner = NwLoom(joinerApi, serviceType = SERVICE_TYPE, random = Random(1))
        return realDispatchLoom(host) to realDispatchLoom(joiner)
    }

    override fun joinTag(): Tag = InMemoryTag(sessionName = "host", peerKey = "nw-loopback-joiner")

    /** The flip: the loopback link is real TLS-PSK, so every capability — including wire encryption — holds. */
    override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL.copy(securesTransport = true)

    /** No gaps — this test IS the proof that closed the `securesTransport` gap for kuilt-nw. */
    override fun capabilityGaps(): Map<String, String> = emptyMap()

    /**
     * Wrap [delegate] so `weave` runs on a real [Dispatchers.Default]. [NwLoom] captures its seam
     * scope from `currentCoroutineContext()`, so without this the suite's virtual-time test dispatcher
     * would drive the seam's `withTimeout`/timers and fast-forward past the real socket connect.
     */
    private fun realDispatchLoom(delegate: Loom): Loom = object : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam =
            withContext(Dispatchers.Default) { delegate.weave(rendezvous) }

        override fun availability(): FabricAvailability = delegate.availability()
    }
}

/**
 * Grab a free ephemeral TCP port: bind a throwaway socket to `0.0.0.0:0`, read the OS-assigned port
 * via `getsockname`, then close it. The number is reused for the NWListener (bound on loopback) — a
 * small TOCTOU window, negligible with fresh ephemeral ports per test.
 *
 * `ntohs`/`htons`/`inet_addr` are C macros with no linkable K/N symbol, so the port (stored in
 * `sin_port` in network byte order) is byte-swapped to host order by hand — arm64 is little-endian.
 */
@OptIn(ExperimentalForeignApi::class)
private fun freePort(): Int = memScoped {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    check(fd >= 0) { "socket() failed grabbing a free port" }
    try {
        val addr = alloc<sockaddr_in>()
        addr.sin_len = sizeOf<sockaddr_in>().convert()
        addr.sin_family = AF_INET.convert()
        addr.sin_port = 0u // ephemeral — the OS assigns a free port
        addr.sin_addr.s_addr = INADDR_ANY.convert() // only the assigned port number matters here
        check(bind(fd, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) == 0) {
            "bind(0.0.0.0:0) failed grabbing a free port"
        }
        val len = alloc<socklen_tVar>()
        len.value = sizeOf<sockaddr_in>().convert()
        check(getsockname(fd, addr.ptr.reinterpret<sockaddr>(), len.ptr) == 0) {
            "getsockname() failed grabbing a free port"
        }
        // sin_port is network byte order (big-endian); swap to a host-order int (arm64 is little-endian).
        val netPort = addr.sin_port.toInt() and 0xFFFF
        ((netPort and 0xFF) shl 8) or ((netPort shr 8) and 0xFF)
    } finally {
        close(fd)
    }
}
