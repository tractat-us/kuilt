package us.tractat.kuilt.multipeer

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The wasmJs [MultipeerServiceBrowser] stub's two halves, held apart — the same statement
 * `MultipeerAndroidStubTest` makes about the androidMain stub, for the same reason.
 *
 * No `DiscoverySourceConformanceSuite` binds this class: every property in that suite starts from
 * an arrival, and [MultipeerServiceBrowser.discoveries] here throws, so the only `causeArrival` it
 * admits is an untrue one. What is asserted instead is stronger than the
 * `DepartureFixture.NoLeaveSignal` arm — the departure flow is over before any window could open,
 * not merely silent through one.
 */
class MultipeerWasmJsStubTest {

    @Test
    fun discoveriesRefusesRatherThanQuietlyReturningAnEmptyFeed() {
        val browser = MultipeerServiceBrowser(MultipeerPeerLinkFactory("stub-device", "kuilt-stub"))
        assertFailsWith<UnsupportedOperationException>(
            "discoveries() must name the platform it cannot browse on; an empty feed here would be " +
                "indistinguishable from a working browser that has found nobody yet",
        ) { browser.discoveries() }
    }

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
