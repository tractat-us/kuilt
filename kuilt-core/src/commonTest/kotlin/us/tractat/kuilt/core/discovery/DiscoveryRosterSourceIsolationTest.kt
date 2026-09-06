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
        val failures = mutableListOf<Pair<PeerDiscoverySource, Throwable>>()
        val roster = discoveryRoster(
            listOf(
                DrivableSource(DiscoveryKind.Mdns, discoveries = mdns.drivenFeed()),
                DrivableSource(DiscoveryKind.Multipeer, discoveries = multipeer.drivenFeed()),
            ),
            backgroundScope,
            onSourceFailure = { source, cause -> failures += source to cause },
        )
        runCurrent()

        mdns.emit(Signal.Item(InMemoryTag("alice")))
        runCurrent()
        assertEquals(setOf("alice"), keysOf(roster.value), "precondition: the mDNS feed was live")

        mdns.emit(Signal.Fail(CancellationException("the source's own withTimeout expired")))
        runCurrent()

        multipeer.emit(Signal.Item(InMemoryTag("bob")))
        runCurrent()

        assertAll(
            { assertTrue("bob" in keysOf(roster.value), "sibling source kept feeding the roster") },
            // The half that is NOT green-either-way: the accident above absorbed the minted
            // cancellation in silence. The fix has to recognise it as this feed's failure.
            { assertEquals(1, failures.size, "the minted cancellation was reported as a feed failure") },
            // `firstOrNull`, not `single`: on an empty list `single` throws NoSuchElementException,
            // which escapes assertAll and replaces the diagnosis with a stack trace about a list.
            { assertEquals(DiscoveryKind.Mdns, failures.firstOrNull()?.first?.kind, "which feed died") },
        )
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
        val failures = mutableListOf<Pair<PeerDiscoverySource, Throwable>>()
        val foldScope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val roster = discoveryRoster(
            listOf(DrivableSource(DiscoveryKind.Mdns, discoveries = mdns.drivenFeed())),
            foldScope,
            onSourceFailure = { source, cause -> failures += source to cause },
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
            // A guard that mistook OUR cancellation for a feed failure would report one here.
            { assertTrue(failures.isEmpty(), "our own cancellation is not a feed failure") },
        )
    }

    /**
     * `:kuilt-core` is logger-free by contract, so `onSourceFailure` is the only signal a dead feed
     * can produce — and a healthy source must never trip it.
     */
    @Test
    fun aFailedFeedIsReportedWithItsSourceAndThrowable() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val mdns = MutableSharedFlow<Signal<Tag>>(extraBufferCapacity = 16)
        val multipeer = MutableSharedFlow<Signal<Tag>>(extraBufferCapacity = 16)
        val mdnsSource = DrivableSource(DiscoveryKind.Mdns, discoveries = mdns.drivenFeed())
        val multipeerSource = DrivableSource(DiscoveryKind.Multipeer, discoveries = multipeer.drivenFeed())
        val failures = mutableListOf<Pair<PeerDiscoverySource, Throwable>>()
        discoveryRoster(
            listOf(mdnsSource, multipeerSource),
            backgroundScope,
            onSourceFailure = { source, cause -> failures += source to cause },
        )
        runCurrent()

        multipeer.emit(Signal.Item(InMemoryTag("bob")))
        runCurrent()
        assertTrue(failures.isEmpty(), "precondition: a healthy source reports nothing")

        val cause = IllegalStateException("jmdns IO error")
        mdns.emit(Signal.Fail(cause))
        runCurrent()

        assertAll(
            { assertEquals(1, failures.size) },
            { assertEquals(mdnsSource, failures.firstOrNull()?.first, "the failing source, by identity") },
            { assertEquals(cause, failures.firstOrNull()?.second, "the throwable itself, not a summary") },
        )
    }

    /**
     * A consumer's logger must not be able to do what the isolation exists to prevent. If the
     * reporting hook's own throw escaped, it would fail the merged flow and freeze the roster —
     * reintroducing #1904 through the very callback that reports it.
     */
    @Test
    fun aThrowingOnSourceFailureCannotKillTheFold() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val mdns = MutableSharedFlow<Signal<Tag>>(extraBufferCapacity = 16)
        val multipeer = MutableSharedFlow<Signal<Tag>>(extraBufferCapacity = 16)
        var reported = 0
        val roster = discoveryRoster(
            listOf(
                DrivableSource(DiscoveryKind.Mdns, discoveries = mdns.drivenFeed()),
                DrivableSource(DiscoveryKind.Multipeer, discoveries = multipeer.drivenFeed()),
            ),
            backgroundScope,
            onSourceFailure = { _, _ ->
                reported++
                throw IllegalStateException("the consumer's logger blew up")
            },
        )
        runCurrent()

        multipeer.emit(Signal.Item(InMemoryTag("bob")))
        runCurrent()
        assertEquals(setOf("bob"), keysOf(roster.value), "precondition: the fold is live")

        mdns.emit(Signal.Fail(IllegalStateException("jmdns IO error")))
        runCurrent()
        assertEquals(1, reported, "precondition: the throwing hook actually ran")

        multipeer.emit(Signal.Item(InMemoryTag("carol")))
        runCurrent()

        assertTrue("carol" in keysOf(roster.value), "the fold survived the hook's own throw")
    }
}
