package us.tractat.kuilt.nw

import us.tractat.kuilt.conformance.SeamCapabilities
import us.tractat.kuilt.conformance.SeamConformanceSuite
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
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
 * The JVM harness runs over the in-memory [FakeNwApi], which carries NO wire encryption — so
 * `securesTransport = false`. This is a **temporal** gap, not a by-design one: kuilt-nw is
 * unencrypted only *by phase*. Real TLS-PSK lands in Phase 3 (tracked by
 * `https://github.com/tractat-us/kuilt/issues/1412`), to be proven by a future `appleTest` loopback
 * conformance — so the gap points at that tracking issue rather than the permanently-unencrypted
 * by-design anchor. Every other flag is honoured: real direct-mesh delivery (`meshDelivery = true`,
 * earned by [NwMeshConformanceTest]) and real directed send (`supportsSendTo = true`).
 */
class NwConformanceTest : SeamConformanceSuite() {

    private companion object {
        const val SERVICE_TYPE = "_kuilt._tcp"
    }

    override fun newLoomPair(): Pair<Loom, Loom> {
        val radio = FakeNwRadio()
        val host = NwLoom(
            FakeNwApi(radio, deviceId = "host", serviceName = "host"),
            serviceType = SERVICE_TYPE,
            random = Random(0),
        )
        val joiner = NwLoom(
            FakeNwApi(radio, deviceId = "join", serviceName = "join"),
            serviceType = SERVICE_TYPE,
            random = Random(1),
        )
        return host to joiner
    }

    /** Session name matches `Pattern("host")`; discovery is by [SERVICE_TYPE] so this only satisfies join()'s signature. */
    override fun joinTag(): Tag = InMemoryTag(sessionName = "host", peerKey = "nw-joiner")

    override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL.copy(securesTransport = false)

    override fun capabilityGaps(): Map<String, String> =
        mapOf("securesTransport" to "https://github.com/tractat-us/kuilt/issues/1412")
}
