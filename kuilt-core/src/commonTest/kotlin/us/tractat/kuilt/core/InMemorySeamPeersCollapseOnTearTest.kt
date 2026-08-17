@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The [Seam.peers] collapse obligation for the **reference fabric**, [InMemoryLoom] (#1849).
 *
 * `Seam.peers` requires a `Torn` seam's roster to be exactly `{ selfId }`, published **before, or
 * atomically with**, the terminal `Torn` latch (#1816). `SeamConformanceSuite.peersCollapseToSelfIdWhenTorn`
 * (bound here by `InMemoryLoomConformanceTest`, and inherited by `GossipSeamConformanceTest`, whose
 * base is an `InMemoryLoom`) asserts the terminal **value**. Two further properties it structurally
 * cannot reach get their own probes here, and both are the reason the obvious fix for #1849 is wrong.
 *
 * ### 1. Ordering — invisible to a dispatched collector
 * `peers` is a conflating `StateFlow`, so a collector resumed after `close()` has returned always
 * reads the settled value and passes against any implementation. [theCollapsedRosterIsAlreadyPublishedWhenTornBecomesObservable]
 * therefore collects `state` on an [UnconfinedTestDispatcher], so it resumes **inline** inside the
 * `_state.value = Torn` write; what it reads from `peers` is the value at exactly the instant `Torn`
 * became observable. Same shape, and same reason, as `CompositeCloseCollapseOrderTest`.
 *
 * ### 2. Distinct-until-changed after the tear — the property that rejects the mapped-view fix
 * #1849's body proposed `MappedStateFlow(factory.peers) { if (closed) setOf(selfId) else it }`.
 * `MappedStateFlow`'s own KDoc requires `transform` to be **injective** on the source's distinct
 * values, and this one is not once `closed`: every distinct registry value maps onto the same
 * `setOf(selfId)`. That is not a paperwork objection. [InMemoryLoom]'s registry is **shared**, so it
 * keeps moving after one seam tears — other peers weave and leave — and a mapped view would forward
 * each of those changes as a **duplicate** `setOf(selfId)`, breaking `StateFlow`'s
 * distinct-until-changed contract for exactly the consumers watching a tear.
 * [aTornSeamPublishesItsCollapsedRosterOnceAndNeverAgain] is what makes that concrete: once torn the
 * roster is *terminal*, so the view latches instead of mapping, and post-tear registry churn reaches
 * no collector at all.
 *
 * ### 3. The boundary between following and latching
 * Latching removes the duplicate *within* each phase but not at the seam between them: if the last
 * roster published while live already equals the collapsed one, an unconditional terminal emission
 * republishes it. [aLoneSeamDoesNotRepublishARosterThatAlreadyEqualsTheCollapsedOne] and
 * [aSeamWhoseJoinerLeftFirstDoesNotRepublishTheCollapsedRoster] pin that case, which every
 * connected-pair test above is structurally blind to.
 *
 * Terminal-value coverage ([aTornSeamAdvertisesExactlyItsOwnId]) is duplicated from the TCK
 * deliberately: `:kuilt-core` has no test dependency on `:kuilt-conformance`, so without it this
 * module's own red would name only the two derived properties and not the headline one.
 */
class InMemorySeamPeersCollapseOnTearTest {

    @Test
    fun aTornSeamAdvertisesExactlyItsOwnId() = runTest {
        val loom = InMemoryLoom()
        val host = loom.host(Pattern("host"))
        val joiner = loom.join(InMemoryTag("join"))

        // A roster worth collapsing — otherwise the assertion cannot tell a correct collapse from a
        // seam that never had a remote peer to lose.
        val before = host.peers.first { it.size == 2 }
        assertTrue(joiner.selfId in before, "precondition: the host must have seen the joiner before the tear")

        host.close()

        val peers = host.peers.value
        assertAll(
            {
                assertEquals(
                    emptySet(),
                    peers - host.selfId,
                    "a Torn seam must advertise NO reachable remote peer (Seam.peers): a torn fabric can " +
                        "reach nobody, and a decorator folding this seam reads what is left here as still " +
                        "reachable until the member is detached",
                )
            },
            {
                assertTrue(
                    host.selfId in peers,
                    "a Torn seam's collapsed roster is { selfId }, not empty — a seam that drops selfId has " +
                        "collapsed too far (got ${peers.map { it.value }})",
                )
            },
        )

        joiner.close()
    }

    @Test
    fun theCollapsedRosterIsAlreadyPublishedWhenTornBecomesObservable() = runTest {
        val loom = InMemoryLoom()
        val host = loom.host(Pattern("host"))
        val joiner = loom.join(InMemoryTag("join"))
        val before = host.peers.first { it.size == 2 }

        val peersWhenTornBecameVisible = CompletableDeferred<Set<PeerId>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            host.state.first { it is SeamState.Torn }
            peersWhenTornBecameVisible.complete(host.peers.value)
        }
        runCurrent()

        host.close()

        assertAll(
            { assertTrue(joiner.selfId in before, "precondition: the host must have seen the joiner before the tear") },
            {
                assertTrue(
                    peersWhenTornBecameVisible.isCompleted,
                    "the probe must have observed the terminal Torn — close() did not latch it",
                )
            },
            {
                assertEquals(
                    setOf(host.selfId),
                    peersWhenTornBecameVisible.getCompleted(),
                    "peers must ALREADY be collapsed at the instant Torn becomes observable (Seam.peers, " +
                        "#1816): a consumer woken by the terminal state must not be able to read the " +
                        "pre-close roster",
                )
            },
        )

        joiner.close()
    }

    @Test
    fun aTornSeamPublishesItsCollapsedRosterOnceAndNeverAgain() = runTest {
        val loom = InMemoryLoom()
        val host = loom.host(Pattern("host"))
        val joiner = loom.join(InMemoryTag("join"))
        host.peers.first { it.size == 2 }

        val seen = mutableListOf<Set<PeerId>>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            host.peers.collect { seen += it }
        }
        runCurrent()
        val beforeClose = seen.toList()

        host.close()
        runCurrent()
        val atCollapse = seen.size

        // The registry is SHARED, so it keeps moving after our seam has torn. Each of these is a
        // distinct `factory.peers` value that a mapped (rather than latching) view would forward to
        // this collector as another `setOf(selfId)`.
        val late = loom.join(InMemoryTag("late"))
        runCurrent()
        late.close()
        runCurrent()
        joiner.close()
        runCurrent()

        assertAll(
            {
                assertTrue(
                    beforeClose.isNotEmpty() && beforeClose.all { it.size == 2 },
                    "precondition: the collector must have been attached to a two-peer roster before the " +
                        "tear (got ${beforeClose.map { s -> s.map { it.value } }})",
                )
            },
            {
                assertEquals(
                    setOf(host.selfId),
                    seen.lastOrNull(),
                    "a collector attached before the tear must be handed the collapsed roster",
                )
            },
            {
                assertEquals(
                    1,
                    seen.count { it == setOf(host.selfId) },
                    "the collapsed roster is TERMINAL, so it is published exactly once: a view that keeps " +
                        "mapping a still-changing shared registry through a constant transform re-emits it " +
                        "on every later registry change, breaking StateFlow's distinct-until-changed " +
                        "contract (got ${seen.map { s -> s.map { it.value } }})",
                )
            },
            {
                assertEquals(
                    atCollapse,
                    seen.size,
                    "nothing may reach a torn seam's peers collector after the collapse (got " +
                        "${seen.map { s -> s.map { it.value } }})",
                )
            },
        )

        collector.cancel()
    }

    // ── The boundary between the two phases ──────────────────────────────────────────────────────
    //
    // The three tests above all build a CONNECTED PAIR, so the last live roster is `{host, joiner}`
    // and can never equal the collapsed `{ selfId }`. That makes them structurally blind to the one
    // case where following-then-latching can still emit a duplicate: when the roster the seam was
    // *already* publishing is the collapsed one. Then the collapse changes nothing, and StateFlow's
    // distinct-until-changed contract says nothing may be published at all.
    //
    // Two independent ways to reach it, because they rig it differently: a seam that never had a
    // remote (the registry never moves), and a seam whose remote left first (the registry moves and
    // comes back). Only the second proves the view is comparing against what it last *emitted*
    // rather than just special-casing a roster that never changed.

    @Test
    fun aLoneSeamDoesNotRepublishARosterThatAlreadyEqualsTheCollapsedOne() = runTest {
        val loom = InMemoryLoom()
        val host = loom.host(Pattern("host"))

        val seen = mutableListOf<Set<PeerId>>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            host.peers.collect { seen += it }
        }
        runCurrent()

        host.close()
        runCurrent()

        assertAll(
            {
                assertEquals(
                    listOf(setOf(host.selfId)),
                    seen.toList(),
                    "a lone seam already publishes { selfId }, so tearing it changes NOTHING — republishing " +
                        "the same value breaks the distinct-until-changed contract this view exists to keep " +
                        "(got ${seen.map { s -> s.map { it.value } }})",
                )
            },
            {
                assertEquals(
                    setOf(host.selfId),
                    host.peers.value,
                    "the terminal value is unchanged by the emission being elided",
                )
            },
        )

        collector.cancel()
    }

    @Test
    fun aSeamWhoseJoinerLeftFirstDoesNotRepublishTheCollapsedRoster() = runTest {
        val loom = InMemoryLoom()
        val host = loom.host(Pattern("host"))
        val joiner = loom.join(InMemoryTag("join"))
        host.peers.first { it.size == 2 }

        val seen = mutableListOf<Set<PeerId>>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            host.peers.collect { seen += it }
        }
        runCurrent()

        // The remote leaves BEFORE we tear, so the host's last live roster is already { selfId } —
        // reached by real registry motion rather than by never having moved.
        joiner.close()
        runCurrent()
        val beforeClose = seen.toList()

        host.close()
        runCurrent()

        assertAll(
            {
                assertEquals(
                    listOf(setOf(host.selfId, joiner.selfId), setOf(host.selfId)),
                    beforeClose,
                    "precondition: the collector must have watched the roster move from the pair back down " +
                        "to { selfId } (got ${beforeClose.map { s -> s.map { it.value } }})",
                )
            },
            {
                assertEquals(
                    beforeClose,
                    seen.toList(),
                    "the collapse published nothing, because the seam was ALREADY publishing { selfId }: " +
                        "the terminal emission must be compared against the last value actually emitted, not " +
                        "made unconditionally (got ${seen.map { s -> s.map { it.value } }})",
                )
            },
        )

        collector.cancel()
    }
}
