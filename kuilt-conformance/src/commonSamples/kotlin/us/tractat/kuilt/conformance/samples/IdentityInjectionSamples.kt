package us.tractat.kuilt.conformance.samples

import us.tractat.kuilt.conformance.CapabilityGaps
import us.tractat.kuilt.conformance.JoinerRosterOrigin
import us.tractat.kuilt.conformance.SeamCapabilities
import us.tractat.kuilt.conformance.ObligationDeclaration
import us.tractat.kuilt.conformance.SeamConformanceSuite
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Loom

/** Declare why a fixture cannot manufacture malformed announcements. */
public fun identityInjectionHarness(): SeamConformanceSuite = object : SeamConformanceSuite() {
    override fun capabilities(): SeamCapabilities =
        SeamCapabilities.FULL.copy(securesTransport = false, reportsLiveCapability = false)
    override fun capabilityGaps(): Map<String, String> = mapOf(
        "securesTransport" to CapabilityGaps.SECURES_TRANSPORT,
        "reportsLiveCapability" to CapabilityGaps.LIVE_CAPABILITY,
    )
    override fun joinerRosterOrigin(): JoinerRosterOrigin =
        JoinerRosterOrigin.FilledByConstruction("one in-memory loom shares the roster between both ends")

    override fun newLoomPair(): Pair<Loom, Loom> = InMemoryLoom().let { it to it }

    override fun duplicatePeerIdDeclaration(): ObligationDeclaration =
        ObligationDeclaration.NotApplicable.NotConstructible(
            "the in-memory loom mints identities internally; this fixture cannot announce an incumbent id",
        )

    override fun blankPeerIdDeclaration(): ObligationDeclaration =
        ObligationDeclaration.NotApplicable.NotConstructible(
            "the in-memory loom mints identities internally; this fixture cannot announce a blank id",
        )
}
