package us.tractat.kuilt.multipeer

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The androidMain [MultipeerServiceBrowser] stub's two halves, held apart.
 *
 * **Why this is not a `DiscoverySourceConformanceSuite` subclass**, when every other
 * [us.tractat.kuilt.core.discovery.PeerDiscoverySource] in this repo now is. Each of that suite's
 * four properties starts from an arrival, and its `causeArrival` hook is non-nullable precisely so
 * no binding can claim one it cannot produce. Here [MultipeerServiceBrowser.discoveries] throws, so
 * the only `causeArrival` this class admits is a no-op — a claim that is simply untrue, and one
 * that would make all four properties pass by never running. That is the vacuity the suite exists
 * to remove, and writing a binding anyway would move it one level up, where it is harder to see.
 *
 * What replaces it is *stronger* than the `DepartureFixture.NoLeaveSignal` arm rather than weaker.
 * That arm asks a source to stay silent through one negative window; this asks the flow to be over
 * before the window opens. A flow that has already completed cannot emit later, whatever happens.
 */
class MultipeerAndroidStubTest {

    @Test
    fun discoveriesRefusesRatherThanQuietlyReturningAnEmptyFeed() {
        val browser = MultipeerServiceBrowser(MultipeerPeerLinkFactory("stub-device", "kuilt-stub"))
        assertFailsWith<UnsupportedOperationException>(
            "discoveries() must name the platform it cannot browse on; an empty feed here would be " +
                "indistinguishable from a working browser that has found nobody yet",
        ) { browser.discoveries() }
    }

    /**
     * `departures()` is empty **and terminates** — which is what makes it honest rather than merely
     * quiet, and why it returns a feed at all where [MultipeerServiceBrowser.discoveries] refuses:
     * a consumer folding a list of sources through `discoveryRoster` should fail on the one call
     * that is genuinely unsupported, not on two.
     */
    @Test
    fun departuresIsEmptyAndCompletesAtOnce() = runTest {
        val browser = MultipeerServiceBrowser(MultipeerPeerLinkFactory("stub-device", "kuilt-stub"))
        assertEquals(
            emptyList(),
            browser.departures().toList(),
            "an unavailability stub has no leave signal to discard, so its departures() must be over " +
                "before anything could arrive on it",
        )
    }
}
