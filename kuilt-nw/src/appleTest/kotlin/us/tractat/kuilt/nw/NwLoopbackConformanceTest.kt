@file:Suppress("ForbiddenImport") // real-network loopback conformance harness — a real Network.framework socket needs a real IO dispatcher; there is no virtual-time option here

package us.tractat.kuilt.nw

import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real-network loopback conformance harness — a real Network.framework socket needs a real IO dispatcher; there is no virtual-time option here
import kotlinx.coroutines.runBlocking
import us.tractat.kuilt.conformance.JoinerRosterOrigin
import us.tractat.kuilt.conformance.ObligationDeclaration
import us.tractat.kuilt.conformance.SeamCapabilities
import us.tractat.kuilt.conformance.SeamConformanceSuite
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Tag
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
 * ## Real dispatcher, and a real-clock deadline ([loopbackLoomPair])
 * [NwLoom.weave] derives its seam scope from `currentCoroutineContext()`, so under the suite's
 * `runTest` virtual clock its `withTimeout` would fast-forward and spuriously time out before the
 * real GCD sockets connect. [loopbackLoomPair] therefore dispatches each `weave` onto
 * [Dispatchers.Default] — the seam's timers and collectors then run in real wall-clock time, while
 * the suite still collects the resulting flows from its test dispatcher (a real-IO test cannot be
 * driven by a test scheduler).
 *
 * Because the weave really does run on the wall clock, the deadline it fails at is a *test gate*, and
 * the same factory injects [LOOPBACK_CONFORMANCE_WEAVE_BACKSTOP] in place of production's shipped 30 s
 * so a contended runner is not indistinguishable from a broken fabric (#2386). It also arms [FAIL_FAST],
 * which is what keeps a deadline that generous affordable when the fabric really is broken. All three
 * live in that one factory precisely so this suite cannot forget any of them.
 */
class NwLoopbackConformanceTest : SeamConformanceSuite() {

    private companion object {
        const val SERVICE_TYPE = "_kuilt._tcp"
        const val ROOM_KEY = "loopback-secret"

        /**
         * Suite-scoped, so it must live here: `kotlin.test` builds a fresh test-class instance per test
         * method, and an instance field would reset between the 30 tests and latch nothing.
         */
        val FAIL_FAST = LoopbackWeaveFailFast("NwLoopbackConformanceTest")
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
        return loopbackLoomPair(
            failFast = FAIL_FAST,
            serviceType = SERVICE_TYPE,
            hostApi = hostApi,
            joinerApi = joinerApi,
            weaveDispatcher = Dispatchers.Default,
        )
    }

    override fun joinTag(): Tag = InMemoryTag(sessionName = "host", peerKey = "nw-loopback-joiner")

    /** The flip: the loopback link is real TLS-PSK, so every capability — including wire encryption — holds. */
    override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL.copy(securesTransport = true)

    /** No gaps — this test IS the proof that closed the `securesTransport` gap for kuilt-nw. */
    override fun capabilityGaps(): Map<String, String> = emptyMap()

    /** #2591: the joiner starts at `{ selfId }` and grows only through the join path. */
    override fun joinerRosterOrigin(): JoinerRosterOrigin =
        JoinerRosterOrigin.TheJoinPath(
            "NwSeam._peers opens at { selfId } and grows only from a resolved connection - here a real " +
            "Network.framework TLS-PSK socket over 127.0.0.1. The two looms share only a port number.",
        )

    /**
     * No gap: [NwSeam] publishes its 16 MiB frame ceiling as of #2134, so this harness is held to the
     * number by [payloadOfExactlyTheBudgetIsCarried] and [overBudgetAddressedSendIsRefusedNotLeaked]
     * instead of declaring it away (#2069). That first case is the one that found #2134 — a payload of
     * exactly the budget arrives as 256+ chunks, which is what the lossy receive path could not survive.
     */
    override fun payloadBudgetGap(): String? = null

    /**
     * Cancel every live `NWConnection` under the pair, so each seam observes a real remote
     * disconnect with no `Seam.close()` anywhere. Not a proof of
     * [SeamConformanceSuite.incomingCompletesOnInjectedMidSessionDeath] — see
     * [midSessionDeathDeclaration].
     */
    override suspend fun injectMidSessionDeath(host: Seam, joiner: Seam): Boolean {
        // WOVEN, not merely "not Torn". `Weaving` is the exact state NwSeam re-forms to after losing a
        // remote (#1513), so a not-Torn precondition is satisfied by a pair that has ALREADY lost its
        // link — and the deviation would then be credited to a tear this rig did not cause. Since the
        // promptness half of the check is near-vacuous under virtual time here, this precondition is the
        // load-bearing half (#2568 review).
        check(host.state.value is SeamState.Woven && joiner.state.value is SeamState.Woven) {
            "mid-session-death rig precondition: both seams must be WOVEN before the connections are " +
                "cancelled; got host=${host.state.value}, joiner=${joiner.state.value}"
        }
        return dropEveryLiveConnection(apis) > 0
    }

    /**
     * **Not a gap — the fabric answers this event differently by design (#2568).** Same argument as
     * `NwConformanceTest`'s, over the real transport: since #1513 `NwSeam` treats a dropped remote as
     * recoverable, re-forming `Woven`→`Weaving` and keeping `incoming` open so [NwLoom] can redial.
     * **Do not "fix" this by re-introducing tear-on-death.**
     *
     * **Weaker here than on the fake-backed harness, and worth saying so.**
     * [SeamConformanceSuite.midSessionDeathDeclarationIsHonest] proves the deviation by injecting and
     * then observing that no `Torn` arrives within a bound — and that bound is `runTest`'s *virtual*
     * clock while these seams run on a real dispatcher, so it elapses without giving the real sockets
     * wall-clock time to answer. What this arm genuinely buys over a self-certification is that the
     * injection must FIRE (the rig asserts both seams were live and that it cancelled at least one
     * connection); the promptness half of the check is strong only on `NwConformanceTest`.
     */
    override fun midSessionDeathDeclaration(): ObligationDeclaration =
        ObligationDeclaration.NotApplicable.ContractDiffers(
            "NwSeam treats a dropped remote as recoverable (#1513): losing the last remote re-forms " +
                "Woven -> Weaving and keeps incoming open so NwLoom can redial. Torn means only an " +
                "explicit close() or a weave timeout, never peer loss. Demonstrated strongly by the " +
                "fake-backed NwConformanceTest; here the deviation check is bounded on virtual time " +
                "over a real socket, so it proves the injection fired and no prompt tear followed.",
        )
}
