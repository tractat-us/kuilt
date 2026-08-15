package us.tractat.kuilt.conformance

import us.tractat.kuilt.core.discovery.PeerDiscoverySource

/**
 * Binds [DiscoverySourceConformanceSuite]'s [DepartureFixture.NoLeaveSignal] arm.
 *
 * What it proves is narrow, and stating it is half the point of having the arm: this source is
 * *honest* — it declares no leave signal and emits nothing, including while a peer is arriving. It
 * proves nothing about departures a real transport could have reported, which is the failure the
 * mDNS and Multipeer backends actually have.
 */
class NoLeaveSignalDiscoverySourceConformanceTest : DiscoverySourceConformanceSuite() {
    override fun newSource(): PeerDiscoverySource = NoLeaveSignalDiscoverySource()

    override suspend fun causeArrival(source: PeerDiscoverySource) {
        (source as NoLeaveSignalDiscoverySource).advertise()
    }

    override fun departureFixture(source: PeerDiscoverySource): DepartureFixture =
        DepartureFixture.NoLeaveSignal
}
