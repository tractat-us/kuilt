@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A wedged recipient must cost **only itself** (#2048).
 *
 * ### What was wrong
 *
 * `SeamRoom`'s two fan-out queues were global FIFOs drained by one serial writer each. Both writers
 * bound every send with a per-recipient budget, which makes a wedged link *finite* — but the queue
 * behind it is shared, so every healthy recipient waits one budget per wedged item ahead of it. On
 * the relay queue the production budget is `HeartbeatConfig.interval` (5 s by default), so a single
 * black-holed spoke throttled the room's whole relayed data plane to ~0.2 fan-outs/second, after
 * which the 64-deep `DROP_OLDEST` buffer began shedding frames aimed at **healthy** spokes. On the
 * membership queue the budget is `reconnectWindow + timeout` — 10.6 s for [relayHeartbeat] — so one
 * wedged bystander delayed every `Paused`/`Unpaused`/`Farewell` to every other bystander by that
 * much.
 *
 * The trigger is ordinary rather than exotic: routing flips to the relay whenever
 * `rosterPeers ⊄ seam.peers`, and on a flat mesh the dominant cause of that is one member sitting in
 * its reconnect window (#1557/#1614). So a single transiently-partitioned peer could route every
 * other peer's traffic through the host for the whole window, on a topology with no star in it.
 *
 * ### Why these tests can see it and the existing ones cannot
 *
 * `StarRelayTest`'s §T14 and §T15 already assert *something* about head-of-line, and both pass on
 * the global-FIFO build. §T14 wedges `joiner-b` while asserting on `joiner-a`, which is **ahead** of
 * the wedge in `admittedById`'s insertion order, so the writer never reaches the black hole before
 * serving it. §T15 allows the healthy spoke five relay budgets, which is exactly the delay a global
 * FIFO imposes — it discriminates the relay budget from the *membership* budget, not one queue from
 * per-recipient queues.
 *
 * These three put the wedge **first** and then either allow no virtual time at all (the delay half)
 * or require a frame the shared buffer would have evicted (the eviction half). Each is red on the
 * global-FIFO build for the head-of-line reason and green once each recipient owns its own queue and
 * writer.
 *
 * ### Ordering is not weakened
 *
 * Per-recipient FIFO — the invariant #1781 actually needs — still holds exactly; see
 * `AdmitFanOutOrderingTest`, which is unchanged and still passes. What is given up is a *global*
 * order across distinct recipients, and nothing depends on it: every roster a bystander holds is
 * derived from the frames **it** received, so no invariant spans two recipients' streams.
 */
class FanOutHeadOfLineTest {

    /**
     * A generous wedge backstop, **not** an assertion — wall-clock over a virtual-time trajectory,
     * so tightening it measures the host machine rather than this code (#1739/#1891). Fast failure
     * comes from the bounded advances inside each test.
     */
    private val backstop = 30.seconds

    /**
     * Virtual time allowed for the membership announcement in [`a membership announcement…`].
     *
     * Comfortably over what the announcement needs (one detector timeout of 600 ms plus a fan-out
     * hop) and five times under the 10.6 s a *single* wedged recipient costs on the shared queue, so
     * the global FIFO fails here rather than merely being slower.
     */
    private val membershipBudget = 2.seconds

    /**
     * Enough virtual time for every queued relay forward to clear its per-recipient budget several
     * times over — used only by the eviction test, where the question is whether the frame still
     * *exists*, not how long it waited.
     */
    private val drainBudget = 20.seconds

    /**
     * The relay half, delay: a forward to a healthy spoke must not wait behind a wedged one **at
     * all**.
     *
     * No virtual time is advanced between the two sends and the assertion, so this measures the
     * queueing rather than the budget: on the shared queue the writer is parked inside the wedged
     * `sendTo` and the healthy forward cannot even be dequeued until its budget expires, while with
     * a queue per recipient the healthy spoke is served at the same virtual instant it was enqueued.
     */
    @Test
    fun `a relay forward to a wedged spoke does not wait behind it at all`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 3, wedge = setOf("joiner-b"))

            // Queued first, and it never completes.
            star.joinerA.sendRelay(RelayDest.One(star.joinerBId), appPayload("plain"))
            star.joinerA.sendRelay(RelayDest.One(star.joinerCId), appPayload("legit"))
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        listOf("legit"),
                        star.joinerC.appFramesFrom(star.joinerAId),
                        "the healthy spoke must be served at the instant its forward was enqueued — " +
                            "on one shared writer it waits a full relay budget behind the black hole, " +
                            "and a room with several wedged spokes waits one budget for each",
                    )
                },
                {
                    // Positive control: the forward really was aimed at the wedged spoke, so the
                    // assertion above is about surviving a black hole and not about an idle room.
                    assertTrue(
                        star.wireFramesTo(star.joinerBId).any { RelayEnvelope.isRelayFrame(it) },
                        "sanity: the host really did try to relay to the wedged spoke",
                    )
                },
                {
                    assertTrue(
                        star.joinerB.appFramesFrom(star.joinerAId).isEmpty(),
                        "sanity: B really is wedged",
                    )
                },
            )
        }

    /**
     * The relay half, eviction: a flood at a wedged spoke must not **evict** a forward already
     * queued for a healthy one.
     *
     * Strictly worse than the delay above, and the failure the 5 s production budget makes reachable:
     * the shared buffer is 64 deep with `DROP_OLDEST`, so once a wedged recipient's backlog fills it,
     * the frames being discarded are whichever are oldest — including one bound for a spoke that is
     * perfectly healthy. The healthy forward is enqueued **first** here precisely so `DROP_OLDEST`
     * picks it.
     *
     * The first send plus [kotlinx.coroutines.test.TestCoroutineScheduler.runCurrent] is what parks
     * the shared writer inside the wedged send before the flood arrives; without it the writer would
     * drain the healthy forward as it went and the flood would never displace anything.
     */
    @Test
    fun `a relay flood at a wedged spoke does not evict a forward queued for a healthy one`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 3, wedge = setOf("joiner-b"))

            // Park the shared writer inside the black hole …
            star.joinerA.sendRelay(RelayDest.One(star.joinerBId), appPayload("plain"))
            testScheduler.runCurrent()
            // … then queue the frame under test, and bury it under more than one buffer's worth.
            star.joinerA.sendRelay(RelayDest.One(star.joinerCId), appPayload("legit"))
            repeat(EVICTION_FLOOD) {
                star.joinerA.sendRelay(RelayDest.One(star.joinerBId), appPayload("plain"))
            }
            testScheduler.advanceTimeBy(drainBudget)
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        listOf("legit"),
                        star.joinerC.appFramesFrom(star.joinerAId),
                        "a healthy spoke's forward must not be evicted by another spoke's backlog — " +
                            "on one shared 64-deep DROP_OLDEST buffer it is the OLDEST item and so " +
                            "the first thing the flood discards",
                    )
                },
                {
                    // Without this the flood's "it overflowed" premise is a comment, not a test:
                    // DROP_OLDEST reports success for the very call that displaces an item.
                    assertTrue(
                        star.host.relayForwardsDropped() > 0,
                        "sanity: the flood really did overflow a bounded buffer — observed " +
                            "${star.host.relayForwardsDropped()} drops",
                    )
                },
                {
                    assertTrue(
                        star.joinerB.appFramesFrom(star.joinerAId).isEmpty(),
                        "sanity: B really is wedged",
                    )
                },
            )
        }

    /**
     * The membership half: a `Paused` must reach a healthy bystander without waiting out a wedged
     * bystander's budget.
     *
     * The wedge is on `joiner-a`, which is admitted **first** and so precedes the healthy bystander
     * in `admittedById`'s insertion order — i.e. in the recipient order one shared writer walks. That
     * ordering is the whole difference from `StarRelayTest`'s §T14, which wedges the *second* spoke
     * and asserts on the first, and therefore passes on a build where the wedge blocks everything
     * behind it.
     *
     * The cost being pinned is not hypothetical: `admitFanOuts`'s budget is
     * `reconnectWindow + timeout`, 10.6 s here and 75 s on the shipped [us.tractat.kuilt.liveness.HeartbeatConfig]
     * defaults, per wedged recipient ahead of you.
     */
    @Test
    fun `a membership announcement to a wedged bystander does not delay one to a healthy bystander`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 3, wedge = setOf("joiner-a"))

            star.partition(star.joinerCId)
            testScheduler.advanceTimeBy(membershipBudget)
            testScheduler.runCurrent()

            assertAll(
                {
                    assertTrue(
                        star.joinerB.sawPartitioned(star.joinerCId),
                        "the healthy bystander must learn C is partitioned within $membershipBudget — " +
                            "behind one wedged recipient on a shared writer it waits a full " +
                            "reconnectWindow + timeout, and on a star it has no detector for C to " +
                            "fall back on",
                    )
                },
                {
                    assertTrue(
                        star.host.sawPartitioned(star.joinerCId),
                        "sanity: the host's own detector really did mature the partition, so there " +
                            "was an announcement to fan out",
                    )
                },
                {
                    assertFalse(
                        star.joinerA.sawPartitioned(star.joinerCId),
                        "sanity: the wedged bystander really is a black hole and really is ahead of " +
                            "the healthy one — without both, the assertion above is vacuous",
                    )
                },
            )
        }

    private companion object {
        /**
         * More than the relay buffer's 64-frame capacity, so the healthy forward queued ahead of it
         * is displaced rather than merely delayed.
         */
        const val EVICTION_FLOOD = 80
    }
}
