@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.test

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import kotlin.test.Test
import kotlin.test.assertEquals
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
