package us.tractat.kuilt.core.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Per-source failure isolation in [discoveryRoster] (#1904).
 *
 * The property under test is that **one source's feed failing must not stop a sibling source's
 * later arrivals from reaching the roster**. Before the fix, `merge()` propagated the throw, which
 * cancelled `stateIn`'s single backing coroutine and froze the returned `StateFlow` at its last
 * value forever — every healthy source silently stopped being observed too.
 *
 * Every arm drives the failure through a [Signal.Fail] the test emits *itself*, so the throw lands
 * at a known point in the trajectory rather than wherever a real transport would have chosen.
 */

/** What a [drivenFeed] should do next: hand out an item, or fail the flow. */
private sealed interface Signal<out T> {
    data class Item<T>(val value: T) : Signal<T>

    data class Fail(val cause: Throwable) : Signal<Nothing>
}

/**
 * Turn a driver channel into a source feed the test fails on demand.
 *
 * The throw is raised *inside* the flow — which is where a real source's failure lives (a
 * `callbackFlow` whose `addServiceListener` throws) — rather than at flow-construction time, so it
 * reaches [discoveryRoster]'s collector exactly the way a live transport's would.
 */
private fun <T> MutableSharedFlow<Signal<T>>.drivenFeed(): Flow<T> = transform { signal ->
    when (signal) {
        is Signal.Item -> emit(signal.value)
        is Signal.Fail -> throw signal.cause
    }
}

private class DrivableSource(
    override val kind: DiscoveryKind,
    private val discoveries: Flow<Tag> = emptyFlow(),
    private val departures: Flow<String> = emptyFlow(),
) : PeerDiscoverySource {
    override fun discoveries(): Flow<Tag> = discoveries
    override fun departures(): Flow<String> = departures
}

private fun keysOf(roster: Set<Tag>): Set<String> = roster.map { it.peerKey }.toSet()

class DiscoveryRosterSourceIsolationTest {
    /**
     * The headline property, plus the decision about what happens to the failed source's peers.
     *
     * `carol` proves the fold survived; `alice` proves the failed source's already-known peers
     * **linger** rather than being synthesised away — the documented choice, and a strict
     * generalisation of the existing ghost caveat (a failed source is exactly a source that can no
     * longer report departures).
     */
    @Test
    fun aThrowingDiscoveryFeedIsolatesThatSourceOnly() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val mdns = MutableSharedFlow<Signal<Tag>>(extraBufferCapacity = 16)
        val multipeer = MutableSharedFlow<Signal<Tag>>(extraBufferCapacity = 16)
        val roster = discoveryRoster(
            listOf(
                DrivableSource(DiscoveryKind.Mdns, discoveries = mdns.drivenFeed()),
                DrivableSource(DiscoveryKind.Multipeer, discoveries = multipeer.drivenFeed()),
            ),
            backgroundScope,
        )
        runCurrent()

        // Precondition: BOTH feeds are live and contributing before anything fails. Without it the
        // arms below would pass against a roster that had never observed the mDNS source at all.
        mdns.emit(Signal.Item(InMemoryTag("alice")))
        multipeer.emit(Signal.Item(InMemoryTag("bob")))
        runCurrent()
        assertEquals(setOf("alice", "bob"), keysOf(roster.value), "precondition: both feeds observed")

        mdns.emit(Signal.Fail(IllegalStateException("jmdns IO error")))
        runCurrent()

        // The sibling's LATER arrival — emitted strictly after the failure — must still land.
        multipeer.emit(Signal.Item(InMemoryTag("carol")))
        runCurrent()

        assertAll(
            { assertTrue("carol" in keysOf(roster.value), "sibling source kept feeding the roster") },
            { assertTrue("alice" in keysOf(roster.value), "the failed source's known peers linger") },
            { assertEquals(setOf("alice", "bob", "carol"), keysOf(roster.value)) },
        )
    }

    /**
     * The other door. An unisolated `departures()` reintroduces the identical hole — and it takes
     * down the failing source's *own* `discoveries()` feed with it, so `dave`, arriving on mDNS
     * after the mDNS departure feed died, is the sharper half of this arm.
     */
    @Test
    fun aThrowingDepartureFeedIsolatesThatFeedOnly() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val mdnsDisc = MutableSharedFlow<Signal<Tag>>(extraBufferCapacity = 16)
        val mdnsDep = MutableSharedFlow<Signal<String>>(extraBufferCapacity = 16)
        val multipeer = MutableSharedFlow<Signal<Tag>>(extraBufferCapacity = 16)
        val roster = discoveryRoster(
            listOf(
                DrivableSource(DiscoveryKind.Mdns, mdnsDisc.drivenFeed(), mdnsDep.drivenFeed()),
                DrivableSource(DiscoveryKind.Multipeer, discoveries = multipeer.drivenFeed()),
            ),
            backgroundScope,
        )
        runCurrent()

        mdnsDisc.emit(Signal.Item(InMemoryTag("alice")))
        multipeer.emit(Signal.Item(InMemoryTag("bob")))
        runCurrent()
        assertEquals(setOf("alice", "bob"), keysOf(roster.value), "precondition: both feeds observed")

        // Prove the departure feed was live too, so its failure below is a real transition.
        mdnsDep.emit(Signal.Item("alice"))
        runCurrent()
        assertEquals(setOf("bob"), keysOf(roster.value), "precondition: the departure feed was live")

        mdnsDep.emit(Signal.Fail(IllegalStateException("jmdns listener removal failed")))
        runCurrent()

        multipeer.emit(Signal.Item(InMemoryTag("carol")))
        mdnsDisc.emit(Signal.Item(InMemoryTag("dave")))
        runCurrent()

        assertAll(
            { assertTrue("carol" in keysOf(roster.value), "sibling source kept feeding the roster") },
            { assertTrue("dave" in keysOf(roster.value), "the same source's discoveries feed survived") },
            { assertEquals(setOf("bob", "carol", "dave"), keysOf(roster.value)) },
        )
    }

    /**
     * The positive half of the cancellation question: a `CancellationException` a source *mints
     * itself* — an internal `withTimeout` in a consumer-authored feed — is that source's failure,
     * not ours, and must be absorbed like any other while our own job stays perfectly alive.
     *
     * **This arm is green both before and after the fix, and says so rather than being mistaken for
     * a reproduction.** `merge()` collects each flow in its own child `launch`, and structured
     * concurrency does not propagate a `CancellationException` *thrown by* a child to its parent —
     * the child merely cancels itself. So a source-minted cancellation was already isolated, by
     * accident, while every other throwable was not. It is a regression lock on that accident: the
     * fix must not turn the one shape that happened to work into one that kills the fold.
     */
    @Test
    fun aSourceMintedCancellationIsAbsorbedLikeAnyOtherFailure() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val mdns = MutableSharedFlow<Signal<Tag>>(extraBufferCapacity = 16)
        val multipeer = MutableSharedFlow<Signal<Tag>>(extraBufferCapacity = 16)
        val roster = discoveryRoster(
            listOf(
                DrivableSource(DiscoveryKind.Mdns, discoveries = mdns.drivenFeed()),
                DrivableSource(DiscoveryKind.Multipeer, discoveries = multipeer.drivenFeed()),
            ),
            backgroundScope,
        )
        runCurrent()

        mdns.emit(Signal.Item(InMemoryTag("alice")))
        runCurrent()
        assertEquals(setOf("alice"), keysOf(roster.value), "precondition: the mDNS feed was live")

        mdns.emit(Signal.Fail(CancellationException("the source's own withTimeout expired")))
        runCurrent()

        multipeer.emit(Signal.Item(InMemoryTag("bob")))
        runCurrent()

        assertTrue("bob" in keysOf(roster.value), "sibling source kept feeding the roster")
    }

    /**
     * The negative half, and the receipt that the isolation discriminates at *runtime* rather than by
     * type: cancelling the fold's own scope must still end it. A guard that swallowed our own
     * cancellation would leave the fold collecting and folding after the cancel.
     *
     * **Green both before and after the fix**, deliberately — it is the regression lock that stops
     * the isolation being bought by swallowing cancellation, which is the standing hazard whenever a
     * `catch` is added anywhere in this repo.
     */
    @Test
    fun cancellingTheScopeStillEndsTheFold() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val mdns = MutableSharedFlow<Signal<Tag>>(extraBufferCapacity = 16)
        val foldScope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val roster = discoveryRoster(
            listOf(DrivableSource(DiscoveryKind.Mdns, discoveries = mdns.drivenFeed())),
            foldScope,
        )
        runCurrent()

        mdns.emit(Signal.Item(InMemoryTag("alice")))
        runCurrent()
        assertEquals(1, mdns.subscriptionCount.value, "precondition: the fold is collecting the feed")
        assertEquals(setOf("alice"), keysOf(roster.value), "precondition: the fold is live")

        foldScope.cancel()
        runCurrent()

        mdns.emit(Signal.Item(InMemoryTag("bob")))
        runCurrent()

        assertAll(
            { assertEquals(0, mdns.subscriptionCount.value, "our cancellation ended the collection") },
            { assertEquals(setOf("alice"), keysOf(roster.value), "the fold stopped folding") },
        )
    }
}
