@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.test

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The [Seam.peers] collapse obligation, for the two `:kuilt-test` fakes **no bound conformance suite
 * reaches**: [FakeSeam] and [FlakyLifecycleSeam].
 *
 * [Seam.peers] requires a `Torn` seam's roster to be **exactly `{ selfId }`**, published **before, or
 * atomically with**, the terminal `Torn` latch. `SeamConformanceSuite.peersCollapseToSelfIdWhenTorn`
 * asserts the value half — but only against the fabrics some subclass actually **binds**, and neither
 * of these is a `Loom`-produced fabric any subclass binds. That is why #1854 found both by survey
 * rather than by a red build, and this file is their standing check.
 *
 * ### Why a *fake* deviating here is worse than a fabric deviating
 * These two are the doubles consumer tests are written against across the whole repo. A consumer that
 * reads `peers` after a tear — `peers.value == setOf(selfId)`, or `selfId in peers` — gets a green
 * against a permissive fake and a red against every conforming production seam. That is the
 * permissive-fake shape of #1816, arriving through the test-support layer instead of the fabric layer.
 *
 * ### Why both halves are asserted separately
 * The two deviations have different causes and different fixes, and the two fakes exhibit **different
 * ones**: [FlakyLifecycleSeam] wrote `emptySet()` — collapsing too far, so it advertised no remote
 * (first half fine) while dropping its own id (second half red); [FakeSeam] wrote nothing at all — the
 * pre-tear roster froze, so it kept advertising a departed remote (first half red) while retaining its
 * own id (second half fine). A single set-equality assertion reports either as one opaque mismatch.
 * Same split, and same wording, as the TCK property and as `CoreSeamPeersCollapseOnTearTest`.
 *
 * ### Why the constructor gets its own section
 * Fixing `tear()` closes the *transition* into the forbidden state; it leaves the *entry* open, because
 * [FakeSeam]'s constructor took `initialState` and `initialPeers` independently. #2432 is that hole, and
 * the section below is its standing check — including its two controls, since a guard that refuses every
 * `Torn` construction, or every roster with a remote, would satisfy a one-sided test while deleting a
 * shape consumers legitimately need.
 *
 * ### Why ordering needs its own probe
 * The *ordering* half is invisible to a dispatched collector: `peers` is a conflating `StateFlow`, so a
 * collector resumed after `tear()` returns always reads the settled value and would pass against any
 * write order. Both ordering probes below therefore collect on an [UnconfinedTestDispatcher], which
 * resumes them **inline** inside the `_state` write — what they read from `peers` is the value at
 * exactly the instant `Torn` became observable. Same shape, and same reason, as
 * `CompositeCloseCollapseOrderTest` and `CoreSeamPeersCollapseOnTearTest` (#1816).
 */
class TestFakePeersCollapseOnTearTest {

    private val self = PeerId("self")
    private val remote = PeerId("remote")

    // ── FakeSeam ──────────────────────────────────────────────────────────────────────────────
    //
    // The fake is constructed with a remote already in `initialPeers`. Without one a frozen roster is
    // structurally undetectable: `{ selfId }` frozen and `{ selfId }` collapsed are the same set, and
    // the property would be green against a `tear()` that touches `_peers` not at all.

    @Test
    fun aTornFakeSeamAdvertisesExactlyItsOwnId() = runTest {
        val seam = FakeSeam(selfId = self, initialPeers = setOf(self, remote))
        assertTrue(remote in seam.peers.value, "precondition: a remote must be in the roster before the tear")

        seam.tear()

        val peers = seam.peers.value
        assertAll(
            {
                assertEquals(
                    emptySet(),
                    peers - self,
                    "a Torn seam must advertise NO reachable remote peer (Seam.peers): a torn fabric can " +
                        "reach nobody, and a consumer test that reads a frozen roster here passes against " +
                        "this fake while failing against every conforming production seam",
                )
            },
            {
                assertTrue(
                    self in peers,
                    "a Torn seam's collapsed roster is { selfId }, not empty — dropping selfId collapses " +
                        "too far (got ${peers.map { it.value }})",
                )
            },
        )
    }

    @Test
    fun theFakeSeamCollapsedRosterIsAlreadyPublishedWhenTornBecomesObservable() = runTest {
        val seam = FakeSeam(selfId = self, initialPeers = setOf(self, remote))

        val peersWhenTornBecameVisible = CompletableDeferred<Set<PeerId>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            seam.state.first { it is SeamState.Torn }
            peersWhenTornBecameVisible.complete(seam.peers.value)
        }
        runCurrent()

        seam.tear()

        assertAll(
            {
                assertTrue(
                    peersWhenTornBecameVisible.isCompleted,
                    "the probe must have observed the terminal Torn — tear() did not latch it",
                )
            },
            {
                assertEquals(
                    setOf(self),
                    peersWhenTornBecameVisible.getCompleted(),
                    "peers must ALREADY be collapsed at the instant Torn becomes observable (Seam.peers): " +
                        "a consumer woken by the terminal state must not read the pre-tear roster",
                )
            },
        )
    }

    // ── FakeSeam: the CONSTRUCTOR is the other entry point into `Torn` (#2432) ─────────────────
    //
    // [FakeSeam.tear] collapses the roster before latching, so the transition can no longer reach the
    // forbidden state. The constructor could still *start* there: `initialState` and `initialPeers`
    // were taken independently, so `Torn` alongside a two-peer roster was constructible — the exact
    // combination #1816 forbids, and the one the `tear()` fix above spent a PR eliminating.
    //
    // Why the constructor hole is as bad as the transition one: a consumer test written against a
    // `Torn`-with-remotes seam passes while describing a situation production can never produce, so
    // the assertion it makes is unfalsifiable in the useful direction. That is the permissive-fake
    // shape arriving one level up.
    //
    // The refusal is loud (`require`) rather than a silent rewrite of the caller's argument: a fake
    // that quietly discards what it was handed teaches a reader the wrong model of the contract.

    @Test
    fun constructingATornFakeSeamWithARemoteInTheRosterIsRefused() {
        val failure = assertFailsWith<IllegalArgumentException> {
            FakeSeam(
                selfId = self,
                initialPeers = setOf(self, remote),
                initialState = SeamState.Torn(CloseReason.Normal),
            )
        }
        assertTrue(
            failure.message.orEmpty().contains("selfId"),
            "the refusal must name the obligation it enforces, not merely fail (got: ${failure.message})",
        )
    }

    /**
     * The positive control. Without it a `require(false)` — refusing *every* `Torn` construction —
     * would satisfy the test above while deleting a shape consumer tests legitimately need.
     */
    @Test
    fun aFakeSeamConstructedTornWithTheCollapsedRosterIsAccepted() {
        val seam = FakeSeam(selfId = self, initialState = SeamState.Torn(CloseReason.RemoteRequested))
        assertAll(
            {
                assertEquals(
                    setOf(self),
                    seam.peers.value,
                    "a seam constructed Torn starts on the collapsed roster the contract requires",
                )
            },
            {
                assertEquals(
                    SeamState.Torn(CloseReason.RemoteRequested),
                    seam.state.value,
                    "the reason handed to the constructor must survive it",
                )
            },
        )
    }

    /**
     * The other control: the guard must key on `Torn`, not on "has a remote". A live seam with peers
     * is the fake's single most common shape, and a guard that over-reached would take it out.
     */
    @Test
    fun aLiveFakeSeamMayStillBeConstructedWithARemoteInTheRoster() {
        val seam = FakeSeam(selfId = self, initialPeers = setOf(self, remote), initialState = SeamState.Weaving)
        assertEquals(setOf(self, remote), seam.peers.value)
    }

    /**
     * The roster is not the only dimension on which a constructed `Torn` seam was unreachable-by-any-
     * real-fabric: [FakeSeam.tear] also closes the spool, because every real seam completes `incoming`
     * on tear. A seam that *starts* `Torn` with an open spool leaves a consumer collecting `incoming`
     * suspended forever on a fabric that can never deliver again — and `deliver` already refuses, so
     * the spool is provably empty.
     *
     * The inner [withTimeoutOrNull] is **virtual** time under `runTest`, so an uncollapsed spool fails
     * this in microseconds of wall clock rather than hanging the suite; the outer backstop is the
     * wedge budget, not an assertion.
     */
    @Test
    fun aFakeSeamConstructedTornHasAlreadyCompletedIncoming() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = FakeSeam(selfId = self, initialState = SeamState.Torn(CloseReason.Normal))

            val collected = withTimeoutOrNull(TEST_WEDGE_BACKSTOP) { seam.incoming.toList() }

            assertEquals(
                emptyList(),
                collected,
                "a seam constructed Torn must have completed incoming already — null here means the flow " +
                    "never terminated, which no real seam's post-tear incoming does",
            )
        }

    // ── FlakyLifecycleSeam ────────────────────────────────────────────────────────────────────
    //
    // The wrapper mirrors its delegate's roster while Woven, so the delegate is given a real second
    // member and the test waits for that mirror before tearing. Sampling `peers` without that wait
    // would tear from a roster that never held more than `{ selfId }` — the same undetectable-freeze
    // problem, one level down.

    /** A [FlakyLifecycleSeam] whose delegate has one real remote member, already mirrored into `peers`. */
    private class Flaky(val seam: FlakyLifecycleSeam, val member: Seam)

    private suspend fun buildFlaky(scope: kotlinx.coroutines.CoroutineScope): Flaky {
        val loom = InMemoryLoom()
        val delegate = loom.host(Pattern("flaky-host"))
        val member = loom.join(InMemoryTag("flaky-member"))
        val seam = FlakyLifecycleSeam(delegate, scope)
        seam.peers.first { member.selfId in it }
        return Flaky(seam, member)
    }

    @Test
    fun aTornFlakyLifecycleSeamAdvertisesExactlyItsOwnId() = runTest {
        val fixture = buildFlaky(backgroundScope)
        val seam = fixture.seam
        assertTrue(
            fixture.member.selfId in seam.peers.value,
            "precondition: the mirrored roster must hold a remote before the tear",
        )

        seam.tear()

        val peers = seam.peers.value
        assertAll(
            {
                assertEquals(
                    emptySet(),
                    peers - seam.selfId,
                    "a Torn seam must advertise NO reachable remote peer (Seam.peers)",
                )
            },
            {
                assertTrue(
                    seam.selfId in peers,
                    "a Torn seam's collapsed roster is { selfId }, not empty — dropping selfId collapses " +
                        "too far, and this class's own enterWeaving() already collapses to setOf(selfId) " +
                        "(got ${peers.map { it.value }})",
                )
            },
        )
        fixture.member.close()
    }

    @Test
    fun theFlakyLifecycleCollapsedRosterIsAlreadyPublishedWhenTornBecomesObservable() = runTest {
        val fixture = buildFlaky(backgroundScope)
        val seam = fixture.seam

        val peersWhenTornBecameVisible = CompletableDeferred<Set<PeerId>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            seam.state.first { it is SeamState.Torn }
            peersWhenTornBecameVisible.complete(seam.peers.value)
        }
        runCurrent()

        seam.tear()

        assertAll(
            {
                assertTrue(
                    peersWhenTornBecameVisible.isCompleted,
                    "the probe must have observed the terminal Torn — tear() did not latch it",
                )
            },
            {
                assertEquals(
                    setOf(seam.selfId),
                    peersWhenTornBecameVisible.getCompleted(),
                    "peers must ALREADY be collapsed at the instant Torn becomes observable (Seam.peers)",
                )
            },
        )
        fixture.member.close()
    }
}
