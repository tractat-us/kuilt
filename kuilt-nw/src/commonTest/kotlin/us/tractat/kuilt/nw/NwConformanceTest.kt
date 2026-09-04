package us.tractat.kuilt.nw

import us.tractat.kuilt.conformance.CapabilityGaps
import us.tractat.kuilt.conformance.JoinerRosterOrigin
import us.tractat.kuilt.conformance.ObligationDeclaration
import us.tractat.kuilt.conformance.SeamCapabilities
import us.tractat.kuilt.conformance.SeamConformanceSuite
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
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
 * a distinct UUID [NwLoom.selfId] (default [us.tractat.kuilt.core.freshPeerId]), so no single instance ever sees both ends
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
 * [us.tractat.kuilt.core.FabricAvailability.Unknown] floor (#1712).
 *
 * **The [SATISFIED_WIFI_PATH] emissions in [newLoomPair] are load-bearing — do not delete them.** The
 * flag selects the AWAITING branch of [SeamConformanceSuite.wovenSeamCapabilityIsHonest], which blocks on
 * `capability.first { it.availability !is Unknown }`; and since #1712 the path observer is the ONLY thing
 * that can satisfy it — there is no static availability seed left ([NwLoom]'s static report supplies the
 * ROLES only). [FakeNwApi] starts from a `null` path, so an unseeded [NwSeam] publishes `Unknown`
 * forever: the await would never complete, the test would die on `runTest`'s 60 s wall-clock timeout, and
 * it would do so on every common target (jvm, Android, iosSimulatorArm64, macosArm64) — red `build-jvm`
 * AND `build-native`. So the assertion **is** discriminating here: it is precisely what catches a harness
 * claiming a live observer it never routed a value through. What it does not do is watch the value
 * *move*; the tests that pin the observer driving transitions are [NwSeamCapabilityTest] (availability)
 * and [NwInterfaceRolesTest] (roles).
 */
class NwConformanceTest : SeamConformanceSuite() {

    private companion object {
        const val SERVICE_TYPE = "_kuilt._tcp"
        const val HOST_DEVICE = "host"

        /**
         * A live, satisfied infrastructure-Wi-Fi path. Published on both fakes in [newLoomPair] to drive
         * the seams' #1541 path-observer loop, which since #1712 is the ONLY source of a
         * non-[us.tractat.kuilt.core.FabricAvailability.Unknown] availability — [NwSeam] no longer seeds
         * one from [FakeNwApi.availability] (that answers *platform support*, not *live path*), so the
         * static report supplies roles only. Required setup, not decoration: without it
         * `SeamConformanceSuite.wovenSeamCapabilityIsHonest` awaits a verdict that never arrives and fails
         * on the 60 s `runTest` timeout.
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
        // LOAD-BEARING (#1712): the path observer is the only source of a non-Unknown availability, and
        // the suite's reportsLiveCapability branch AWAITS one. Delete these and the suite hangs to timeout.
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
    override fun selfDialDeclaration(): ObligationDeclaration = ObligationDeclaration.Proven

    /**
     * No gap: [NwSeam] publishes its 16 MiB frame ceiling as of #2134, so this harness is held to the
     * number by [payloadOfExactlyTheBudgetIsCarried] and [overBudgetAddressedSendIsRefusedNotLeaked]
     * instead of declaring it away (#2069). That first case is the one that found #2134 — a payload of
     * exactly the budget arrives as 256+ chunks, which is what the lossy receive path could not survive.
     */
    override fun payloadBudgetGap(): String? = null

    /**
     * Drop every live link the radio holds, so both seams observe their remote go away with no
     * `Seam.close()` anywhere. This does **not** prove
     * [SeamConformanceSuite.incomingCompletesOnInjectedMidSessionDeath] — see
     * [midSessionDeathDeclaration]; it is the injection that arm is required to *perform* so the
     * suite can watch the by-design deviation instead of believing it.
     */
    override suspend fun injectMidSessionDeath(host: Seam, joiner: Seam): Boolean {
        val r = radio ?: return false
        // WOVEN, not merely "not Torn": `Weaving` is the exact state this seam re-forms to after losing
        // a remote (#1513), so a not-Torn precondition is satisfied by a pair that has ALREADY lost its
        // link and the deviation would be credited to a tear this rig did not cause (#2568 review).
        check(host.state.value is SeamState.Woven && joiner.state.value is SeamState.Woven) {
            "mid-session-death rig precondition: both seams must be WOVEN before the links are dropped, " +
                "or the declaration would be judged on a tear this rig did not cause; got " +
                "host=${host.state.value}, joiner=${joiner.state.value}"
        }
        // Zero dropped ⇒ honestly un-injected: returning false reds midSessionDeathDeclarationIsHonest
        // on the ContractDiffers arm rather than crediting a deviation to an injection that never ran.
        return r.dropAllLinks() > 0
    }

    /**
     * **Not a gap — the fabric answers this event differently by design (#2568).** Since #1513 `NwSeam`
     * treats a dropped remote as *recoverable*: losing the last remote re-forms `Woven`→`Weaving` and
     * keeps `incoming` OPEN so [NwLoom] can redial, rather than latching `Torn` on both ends.
     * [SeamConformanceSuite.incomingCompletesOnInjectedMidSessionDeath] requires both ends to latch
     * `Torn`, so it is unprovable on this fabric **by design** — `Torn` here means only an explicit
     * `close()` or a weave timeout, never peer loss. **Do NOT "fix" this by re-introducing
     * tear-on-death**; that would undo #1513 and break redial.
     *
     * That instruction used to live in a comment, which is not what someone burning down a tracked gap
     * list reads — the whole of #2568. As a declaration it is also *enforced*:
     * [SeamConformanceSuite.midSessionDeathDeclarationIsHonest] makes [injectMidSessionDeath] really
     * drop the links and then requires the obligation's own postcondition to FAIL. So the arm reds the
     * day the fabric starts tearing on peer loss — the regression this comment could only warn about.
     */
    override fun midSessionDeathDeclaration(): ObligationDeclaration =
        ObligationDeclaration.NotApplicable.ContractDiffers(
            "NwSeam treats a dropped remote as recoverable (#1513): losing the last remote re-forms " +
                "Woven -> Weaving and keeps incoming open so NwLoom can redial. Torn means only an " +
                "explicit close() or a weave timeout, never peer loss, so the both-ends-latch-Torn " +
                "obligation cannot hold here. Do not re-introduce tear-on-death to satisfy it.",
        )

    /** Session name matches `Pattern("host")`; discovery is by [SERVICE_TYPE] so this only satisfies join()'s signature. */
    override fun joinTag(): Tag = InMemoryTag(sessionName = "host", peerKey = "nw-joiner")

    override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL.copy(securesTransport = false)

    // The fake is a plaintext in-memory double (by-design); the real transport's encryption is
    // proven by NwLoopbackConformanceTest. Points at the by-design in-memory anchor, not #1412.
    override fun capabilityGaps(): Map<String, String> =
        mapOf("securesTransport" to CapabilityGaps.SECURES_TRANSPORT)

    /** #2591: the joiner starts at `{ selfId }` and grows only through the join path. */
    override fun joinerRosterOrigin(): JoinerRosterOrigin =
        JoinerRosterOrigin.TheJoinPath(
            "NwSeam._peers opens at { selfId } and grows only from a connection this seam resolved. Each " +
            "loom has its own FakeNwApi with its own event flows and its own selfId; only the radio is " +
            "shared, and a radio is a transport bus, not a roster.",
        )
}
