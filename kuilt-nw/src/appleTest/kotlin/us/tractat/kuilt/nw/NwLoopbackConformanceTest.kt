@file:Suppress("ForbiddenImport", "ForbiddenMethodCall") // real-network loopback conformance harness — a real Network.framework socket needs a real IO dispatcher; there is no virtual-time option here

package us.tractat.kuilt.nw

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import us.tractat.kuilt.conformance.SeamCapabilities
import us.tractat.kuilt.conformance.SeamConformanceSuite
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.core.TransportCapability
import kotlin.random.Random
import kotlin.test.AfterTest

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
 * Both looms run over [RealNwApi] built with a [NwLoopbackConfig]: each binds a direct **ephemeral**
 * `127.0.0.1` listener with no Bonjour advertise, so the run needs no multicast, no network
 * permissions, and no second device. `includePeerToPeer(false)` keeps it off AWDL; the TLS-PSK
 * params are otherwise byte-identical to the P2P surface.
 *
 * ## Asymmetric host/joiner over an in-process rendezvous (no port race)
 * There is no pre-allocated port and no probe socket, so there is no TOCTOU window that could lose
 * the port (the old flake). The HOST binds an OS-assigned ephemeral listener and never dials; on
 * `ready` it publishes its REAL bound port into a shared [NwLoopbackRendezvous]. The JOINER awaits
 * that port, then dials `127.0.0.1:port` — a port that is always genuinely bound. Each side still
 * runs the full `NwLoom.weave` (advertise + browse + auto-dial), so exactly one host↔joiner link
 * forms with no double-dial.
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

    /** The real APIs built for the current test, torn down (listeners/browsers cancelled) in [tearDown]. */
    private val apis = mutableListOf<RealNwApi>()

    @AfterTest
    fun tearDown() = runBlocking {
        // Cancel the loopback listeners/browsers so no NW resources leak across the run. Seams are
        // closed by the tests themselves.
        apis.forEach { api ->
            api.stopListening()
            api.stopBrowsing()
            api.cancelPathMonitor() // #1541: don't leave the nw_path_monitor's queue callback armed across the run
        }
        apis.clear()
    }

    override fun newLoomPair(): Pair<Loom, Loom> {
        val psk = NwPsk.derive(ROOM_KEY, SERVICE_TYPE)
        // One shared rendezvous per pair: the host publishes its real bound port, the joiner awaits it.
        val rendezvous = NwLoopbackRendezvous()
        val hostApi = RealNwApi(psk, NwLoopbackConfig(dial = false, rendezvous = rendezvous))
        val joinerApi = RealNwApi(psk, NwLoopbackConfig(dial = true, rendezvous = rendezvous))
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
     * [NwSeam] has a 16 MiB ceiling and refuses above it, but cannot yet *promise* it — and this
     * harness is the one that would prove it over a real TLS-PSK Network.framework link, so it is
     * also the one most exposed: `RealNwApi` publishes received bytes with a bounded `tryEmit`,
     * which drops under the 256+ chunk burst a 16 MiB frame arrives as (#2134).
     */
    override fun payloadBudgetGap(): String = "https://github.com/tractat-us/kuilt/issues/2134"

    /**
     * Wrap [delegate] so `weave` runs on a real [Dispatchers.Default]. [NwLoom] captures its seam
     * scope from `currentCoroutineContext()`, so without this the suite's virtual-time test dispatcher
     * would drive the seam's `withTimeout`/timers and fast-forward past the real socket connect.
     */
    private fun realDispatchLoom(delegate: Loom): Loom = object : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam =
            withContext(Dispatchers.Default) { delegate.weave(rendezvous) }

        override fun capability(): TransportCapability = delegate.capability()
    }
}
