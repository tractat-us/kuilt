package us.tractat.kuilt.nw

import us.tractat.kuilt.conformance.CapabilityGaps
import us.tractat.kuilt.conformance.SeamCapabilities
import us.tractat.kuilt.conformance.SeamConformanceSuite
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Tag
import kotlin.random.Random

/**
 * Verifies that [NwLoom] satisfies every invariant in [SeamConformanceSuite] on the JVM, backed
 * by the in-memory [FakeNwRadio] / [FakeNwApi] harness.
 *
 * ## Role-split (fixes #1404): two DISTINCT looms on one radio
 * Unlike a single-instance fabric that returns `(loom, loom)`, [newLoomPair] returns two genuinely
 * distinct [NwLoom]s — one hosting, one joining — sharing ONE [FakeNwRadio] and the SAME Bonjour
 * [SERVICE_TYPE] so they discover each other. Each has its own [FakeNwApi] (its own event flows) and
 * a distinct UUID [NwLoom.selfId] (default [freshPeerId]), so no single instance ever sees both ends
 * of a link — cross-device roster/identity/dedup bugs surface on the JVM exactly as across two phones.
 *
 * ## Capabilities & the securesTransport gap
 * This JVM harness runs over the in-memory [FakeNwApi], which carries NO wire encryption — so it
 * declares `securesTransport = false`. This is **not** a gap in the fabric: the real Apple transport
 * ([RealNwApi]) IS encrypted, proven over a real `127.0.0.1` TLS-PSK link by
 * `NwLoopbackConformanceTest` (`appleTest`, macOS runner), which declares
 * `securesTransport = true`. The flag is `false` here only because the *fake* is a plaintext
 * in-memory double — the by-design in-memory case of [CapabilityGaps.SECURES_TRANSPORT], not the
 * temporal Phase-3 gap it once was. Every other flag is honoured: real direct-mesh delivery
 * (`meshDelivery = true`, earned by [NwMeshConformanceTest]) and real directed send
 * (`supportsSendTo = true`).
 *
 * `reportsLiveCapability = true`: [NwSeam] drives its [us.tractat.kuilt.core.Seam.capability] from
 * [NwApi.pathState] (#1541), so kuilt-nw is the one fabric off the
 * [us.tractat.kuilt.core.FabricAvailability.Unknown] floor (#1712). This harness publishes a live path on
 * both fakes ([SATISFIED_WIFI_PATH]) so the value the conformance assertion reads has come *through* the
 * observer. Be honest about what that buys: a satisfied path folds to `Available`, which is also what the
 * static seed says, so the suite assertion is green either way — the seeding fixes the value's
 * **provenance**, not the assertion's discriminating power. The tests that actually pin the observer
 * moving the value are [NwSeamCapabilityTest] (availability) and [NwInterfaceRolesTest] (roles).
 */
class NwConformanceTest : SeamConformanceSuite() {

    private companion object {
        const val SERVICE_TYPE = "_kuilt._tcp"
        const val HOST_DEVICE = "host"

        /**
         * A live, satisfied infrastructure-Wi-Fi path. Published on both fakes in [newLoomPair] so the
         * seams' #1541 path-observer loop — not the static [FakeNwApi.availability] seed — is what supplies
         * `capability`. It folds to the same `Available` the seed would give, so this does not make
         * `SeamConformanceSuite.wovenSeamCapabilityIsHonest` discriminating; it makes the harness declaring
         * `reportsLiveCapability = true` actually route through the observer it is claiming (#1712).
         */
        val SATISFIED_WIFI_PATH = NwPathState(
            status = NwPathStatus.Satisfied,
            interfaces = setOf(NwInterfaceType.WifiLan),
            isExpensive = false,
            isConstrained = false,
            unsatisfiedReason = null,
        )
    }

    // The radio backing the current pair, captured so injectSelfDial can drive the host device to dial
    // its own advertised endpoint. Tests run one pair at a time, sequentially.
    private var radio: FakeNwRadio? = null

    override fun newLoomPair(): Pair<Loom, Loom> {
        val r = FakeNwRadio()
        radio = r
        val hostApi = FakeNwApi(r, deviceId = HOST_DEVICE, serviceName = "host")
        val joinerApi = FakeNwApi(r, deviceId = "join", serviceName = "join")
        // Drive the live path observer, not just the static seed — see SATISFIED_WIFI_PATH.
        hostApi.emitPathState(SATISFIED_WIFI_PATH)
        joinerApi.emitPathState(SATISFIED_WIFI_PATH)
        val host = NwLoom(hostApi, serviceType = SERVICE_TYPE, random = Random(0))
        val joiner = NwLoom(joinerApi, serviceType = SERVICE_TYPE, random = Random(1))
        return host to joiner
    }

    /**
     * Drive the host device to dial its OWN advertised endpoint (the #1466 self-dial). The two
     * resulting connections both resolve to the host's `selfId`, which [NwSeam]'s self-connection guard
     * must drop — proving [SeamConformanceSuite.selfDialIsRejected] on a live, already-woven seam.
     */
    override suspend fun injectSelfDial(host: Seam): Boolean {
        val r = radio ?: return false
        r.injectSelfDial(HOST_DEVICE)
        return true
    }

    /** Proven: this harness drives a genuine self-dial through the radio, so no gap. */
    override fun selfDialGap(): String? = null

    // Mid-session-death obligation (13b — injectMidSessionDeath / both-ends-Torn-on-death + incoming
    // completes) is UNPROVABLE for this fabric BY DESIGN — do NOT "fix" it by re-introducing tear-on-death.
    // Since #1513 NwSeam treats a transport death (a dropped remote) as *recoverable*: the last-remote loss
    // re-forms Woven→Weaving and keeps `incoming` OPEN so NwLoom can redial, rather than latching Torn on
    // both ends. So injectMidSessionDeath is deliberately left at its default `false` and midSessionDeathGap
    // keeps its tracked-URL default — the obligation simply does not hold for a recoverable fabric. `Torn`
    // here means ONLY an explicit close()/weave-timeout, never peer loss.

    /** Session name matches `Pattern("host")`; discovery is by [SERVICE_TYPE] so this only satisfies join()'s signature. */
    override fun joinTag(): Tag = InMemoryTag(sessionName = "host", peerKey = "nw-joiner")

    override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL.copy(securesTransport = false)

    // The fake is a plaintext in-memory double (by-design); the real transport's encryption is
    // proven by NwLoopbackConformanceTest. Points at the by-design in-memory anchor, not #1412.
    override fun capabilityGaps(): Map<String, String> =
        mapOf("securesTransport" to CapabilityGaps.SECURES_TRANSPORT)
}
