package us.tractat.kuilt.gossip

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Tests for the live, self-healing [GossipView] manager (Phase 2b, #671).
 *
 * **Virtual time**: every timer (recompute jitter, per-neighbour heartbeat) runs
 * on the [runTest] scheduler; the injected [clock] reads `testScheduler.currentTime`
 * so detector silence advances with virtual time. Time is driven with bounded
 * [advanceTimeBy]/[runCurrent] — never `advanceUntilIdle` (the heartbeat timers
 * re-arm forever and would spin).
 *
 * **Determinism**: a seeded [Random] drives both jitter and neighbour selection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GossipViewTest {
    // Fast timings keep loss-detection within a handful of virtual seconds.
    private val config =
        HeartbeatConfig(
            interval = 1.seconds,
            timeout = 2.seconds,
            reconnectWindow = 2.seconds,
        )

    private fun peers(n: Int): Set<PeerId> = (1..n).map { PeerId("peer-$it") }.toSet()

    private fun pong(from: PeerId): Swatch =
        Swatch(
            payload = HeartbeatPartitionDetector.PONG_PREFIX.encodeToByteArray(),
            sender = from,
            sequence = 1,
        )

    private class Harness(
        val self: PeerId,
        val seam: FakeSeam,
        val raw: MutableSharedFlow<Swatch>,
        val roster: MutableStateFlow<Set<PeerId>>,
        val view: GossipView,
    )

    private fun TestScope.harness(
        members: Set<PeerId>,
        seed: Int,
        spareCount: Int = GossipView.DEFAULT_SPARE_COUNT,
    ): Harness {
        val self = PeerId("self")
        val roster = members + self
        val seam = FakeSeam(selfId = self, initialPeers = roster)
        val raw = MutableSharedFlow<Swatch>(extraBufferCapacity = 256)
        val rosterFlow = MutableStateFlow(roster)
        val view =
            GossipView(
                selfId = self,
                seam = seam,
                roster = rosterFlow,
                rawIncoming = raw.asSharedFlow(),
                random = Random(seed),
                clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
                config = config,
                spareCount = spareCount,
            )
        return Harness(self, seam, raw, rosterFlow, view)
    }

    /** Advances past the max jitter window so the first recompute lands. */
    private fun TestScope.settle() {
        advanceTimeBy(GossipView.DEFAULT_JITTER.endInclusive.inWholeMilliseconds + 1)
        runCurrent()
    }

    // ── Derivation ─────────────────────────────────────────────────────────────

    @Test
    fun derivesActiveViewAfterJitter() =
        runTest {
            val members = peers(10)
            val h = harness(members, seed = 42)
            h.view.start(backgroundScope)

            runCurrent()
            assertTrue(h.view.active.value.isEmpty(), "view stays empty until jitter elapses")

            settle()

            val k = recommendedActiveViewSize(h.roster.value.size)
            assertAll(
                { assertEquals(k, h.view.active.value.size, "active view has exactly k neighbours") },
                { assertFalse(h.self in h.view.active.value, "active view excludes self") },
                { assertTrue(h.view.active.value.all { it in members }, "neighbours come from the roster") },
                { assertTrue(h.view.spares.value.all { it in members }, "spares come from the roster") },
                {
                    assertTrue(
                        h.view.active.value.intersect(h.view.spares.value.toSet()).isEmpty(),
                        "active and spares are disjoint",
                    )
                },
            )
        }

    @Test
    fun deterministicForSameSeed() =
        runTest {
            val members = peers(12)
            val a = harness(members, seed = 7)
            val b = harness(members, seed = 7)
            a.view.start(backgroundScope)
            b.view.start(backgroundScope)
            settle()

            assertEquals(a.view.active.value, b.view.active.value, "same seed + roster ⇒ same active view")
            assertEquals(a.view.spares.value, b.view.spares.value, "same seed + roster ⇒ same spares")
        }

    // ── Reactive healing ───────────────────────────────────────────────────────

    @Test
    fun promotesSpareWhenNeighbourGoesSilent() =
        runTest {
            val members = peers(8)
            val h = harness(members, seed = 3, spareCount = 2)
            h.view.start(backgroundScope)
            settle()

            val before = h.view.active.value
            val expectedPromotion = h.view.spares.value.first()
            val victim = before.first()
            val healthy = before - victim

            // Keep every active neighbour but the victim alive across the loss window:
            // deliver a pong from each currently-active peer (recomputed every round so a
            // freshly-promoted spare stays alive too) once per heartbeat interval.
            repeat(6) {
                (h.view.active.value - victim).forEach { h.raw.tryEmit(pong(it)) }
                runCurrent()
                advanceTimeBy(config.interval.inWholeMilliseconds)
                runCurrent()
            }

            val after = h.view.active.value
            assertAll(
                { assertFalse(victim in after, "the silent neighbour is dropped") },
                { assertTrue(expectedPromotion in after, "the next spare is promoted into the active view") },
                { assertEquals(before.size, after.size, "the active view stays at k after healing") },
                { assertTrue(healthy.all { it in after }, "healthy neighbours are retained, not reshuffled") },
            )
        }

    @Test
    fun lostNeighbourIsNotReselectedWhileStillInRoster() =
        runTest {
            val members = peers(8)
            val h = harness(members, seed = 11, spareCount = 2)
            h.view.start(backgroundScope)
            settle()

            val victim = h.view.active.value.first()

            repeat(6) {
                (h.view.active.value - victim).forEach { h.raw.tryEmit(pong(it)) }
                runCurrent()
                advanceTimeBy(config.interval.inWholeMilliseconds)
                runCurrent()
            }
            assertFalse(victim in h.view.active.value, "the failed neighbour is dropped")

            // A later roster recompute (the victim is still rostered) must not re-admit it.
            val extra = PeerId("late-joiner")
            h.seam.addPeer(extra)
            h.roster.value = h.roster.value + extra
            settle()

            assertFalse(victim in h.view.active.value, "a failed-but-rostered peer is not re-selected")
        }

    // ── Churn ──────────────────────────────────────────────────────────────────

    @Test
    fun retainsHealthyNeighboursAcrossRosterGrowth() =
        runTest {
            val members = peers(8)
            val h = harness(members, seed = 5)
            h.view.start(backgroundScope)
            settle()
            val before = h.view.active.value

            // Grow the roster; a single membership change must not reshuffle the overlay.
            val grown = h.roster.value + peers(4).map { PeerId("${it.value}-b") }.toSet()
            h.roster.value = grown
            settle()

            assertTrue(
                before.all { it in h.view.active.value },
                "healthy neighbours survive a roster change (churn-minimising recompute)",
            )
        }

    // ── ActiveViewPolicy ───────────────────────────────────────────────────────

    @Test
    fun fullFanoutSelectsEveryOtherPeerAsActive() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val self = PeerId("hub")
            val roster = MutableStateFlow(setOf(self, PeerId("a"), PeerId("b"), PeerId("c")))
            val view =
                GossipView(
                    selfId = self,
                    seam = FakeSeam(self),
                    roster = roster,
                    rawIncoming = MutableSharedFlow<Swatch>().asSharedFlow(),
                    random = Random(1L),
                    clock = { Instant.fromEpochMilliseconds(0) },
                    activeViewPolicy = ActiveViewPolicy.FullFanout,
                )
            view.start(backgroundScope)
            advanceTimeBy(250) // past DEFAULT_JITTER max (200ms recompute window)
            runCurrent()
            assertEquals(setOf(PeerId("a"), PeerId("b"), PeerId("c")), view.active.value)
            assertEquals(emptyList(), view.spares.value)
        }
}
