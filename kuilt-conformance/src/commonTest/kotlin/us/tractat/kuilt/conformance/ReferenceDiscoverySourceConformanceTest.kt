package us.tractat.kuilt.conformance

import us.tractat.kuilt.core.discovery.PeerDiscoverySource

/**
 * Binds [DiscoverySourceConformanceSuite]'s [DepartureFixture.Emits] arm to a reference source that
 * gets the contract right, so the suite's arm has at least one green subclass and the properties
 * are known to be reachable rather than merely written.
 *
 * Its counterpart on the other arm is [NoLeaveSignalDiscoverySourceConformanceTest]; that the two
 * properties with teeth actually go **red** on a source that gets them wrong is proved separately,
 * by `DiscoverySourceConformanceSuiteRigTest`.
 */
class ReferenceDiscoverySourceConformanceTest : DiscoverySourceConformanceSuite() {
    override fun newSource(): PeerDiscoverySource = ReferenceDiscoverySource()

    override suspend fun causeArrival(source: PeerDiscoverySource) {
        (source as ReferenceDiscoverySource).advertise()
    }

    override fun departureFixture(source: PeerDiscoverySource): DepartureFixture =
        DepartureFixture.Emits { (source as ReferenceDiscoverySource).withdraw() }
}
