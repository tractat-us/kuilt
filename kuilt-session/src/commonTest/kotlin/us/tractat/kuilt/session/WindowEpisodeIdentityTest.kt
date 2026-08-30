@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.partition.JoinerReconnectController
import us.tractat.kuilt.session.partition.JoinerReconnectEvent
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.ResumeToken
import us.tractat.kuilt.test.FaultySeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A reconnect deadline must not move **backwards** across a recover→re-partition cycle (#1781).
 *
 * `SeamRoom.refineWindow` accepts a controller's [JoinerReconnectEvent.WindowOpened] whenever the
 * named member is [Liveness.Partitioned] *now*. That covers "the announcement arrived after the
 * member recovered, and it is still recovered". It does **not** cover "recovered, then partitioned
 * again": a controller emits from its own coroutine
 * ([us.tractat.kuilt.session.partition.DefaultJoinerReconnectController.onPeerUnresponsive] does
 * `scope.launch { openWindow(…) }`), so the announcement for partition episode *N* can land after
 * episode *N+1* has opened and replace *N+1*'s deadline with *N*'s. The host then counts the seat
 * down to an instant it is no longer holding it to, and nothing re-asserts the right one.
 *
 * **This is the half [AdmitFanOutOrderingTest] cannot reach.** That reordering happens on the
 * *wire*, after `refineWindow`; #1800's per-recipient FIFO lane closed it. This one happens
 * *before* `refineWindow` is reached — inside the controller and across its `SharedFlow` — so no
 * send-side ordering can see it. The fix is identity, not ordering:
 * [JoinerReconnectEvent.WindowOpened.detectedAt] names the episode, and a mismatched one is dropped.
 *
 * **Why the cheap guard is not enough, and why this test says so.** `if (expiresAt < since) return`
 * reads like it closes this. It does not: the stale episode's deadline is a *window* past its own
 * detection, so with any window longer than the recovery→re-detection gap it still lands after the
 * new episode's [Liveness.Partitioned.since] and the guard waves it through. Both deadlines here are
 * deliberately far past every instant this test reaches ([EPISODE_ONE_DEADLINE] and friends are
 * ~17 minutes out against a ~2.4 s trajectory), which is the 60 s-window-vs-sub-second-gap shape
 * reduced to a fixture: the guard passes on both, so implementing it leaves this test red.
 *
 * **Severity.** Theoretical, not observed: the recovery→re-detection gap is at least one
 * [HeartbeatConfig.timeout], orders of magnitude above coroutine launch latency. The controller fake
 * below announces on the test's command precisely so the interleaving is a decision rather than a
 * race — a real-timing reproduction would be a flake generator, and virtual time cannot produce the
 * genuine multi-threaded launch race at all.
 */
class WindowEpisodeIdentityTest {

    /**
     * Sub-second detection, with a window far longer than anything this test advances.
     *
     * **[HeartbeatConfig.timeout] is five [HeartbeatConfig.interval]s, not two, and that is a
     * correctness property of this fixture.** At `timeout == 2 * interval` the detector sits on a
     * knife edge: a pong that only refreshes `lastSeen` every other poll makes `silenceMs` reach the
     * threshold exactly, so a *healthy* link flaps unresponsive→recovered once per timeout. The first
     * draft of this test ran that config and the host reported five drops for two outages — at which
     * point "episode N" and "episode N+1" name nothing and the stale announcement under test is not
     * stale. The `exactly two drops` assertion below is what caught it and is what keeps it caught.
     */
    private val fastConfig = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 500.milliseconds,
        reconnectWindow = 10.seconds,
    )

    /**
     * A hold policy that **records** each drop it is told about and announces windows only when the
     * test says so — the #1614 injected-controller shape with its one asynchronous hop replaced by
     * an explicit call, so a stale announcement is a decision rather than a race.
     *
     * `events` is a **rendezvous** [MutableSharedFlow] (`replay = 0`, no buffer): [announce]'s
     * `emit` cannot return until the room's `runReconnectEventLoop` has taken the value. That is
     * what makes [announcementsDelivered] a delivery count rather than an emission count — a test
     * that quietly failed to deliver its stale announcement would pass by absence, which is the
     * whole vacuity risk here. [subscriberCount] is asserted as the precondition, because with no
     * subscriber a `SharedFlow` emit succeeds by discarding.
     */
    private class RecordingHoldPolicy : JoinerReconnectController {
        private val _events = MutableSharedFlow<JoinerReconnectEvent>()
        override val events: SharedFlow<JoinerReconnectEvent> = _events.asSharedFlow()

        /** The `at` of every drop reported to this controller, in order — one per episode. */
        val detections: MutableList<Long> = mutableListOf()

        /**
         * The `at` of every **recovery** reported to this controller, in order (#2556).
         *
         * Recorded rather than ignored because it is a fact about the room worth asserting: a real
         * hold policy would cancel its timer here, and a policy that is never told cannot. This
         * fake deliberately does *not* stop announcing afterwards — that is what lets the stale
         * expiry below be delivered on purpose rather than raced for.
         */
        val recoveries: MutableList<Long> = mutableListOf()

        /** Announcements whose `emit` returned, i.e. that the room's collector actually took. */
        var announcementsDelivered: Int = 0
            private set

        /** Expiries whose `emit` returned — counted apart from [announcementsDelivered]. */
        var expiriesDelivered: Int = 0
            private set

        val subscriberCount: Int get() = _events.subscriptionCount.value

        override fun onPeerUnresponsive(peerId: PeerId, at: Long) {
            detections += at
        }

        /** Announce a window for the episode detected at [detectedAt]. Suspends until delivered. */
        suspend fun announce(peerId: PeerId, detectedAt: Long, expiresAt: Long) {
            _events.emit(
                JoinerReconnectEvent.WindowOpened(peerId, expiresAt = expiresAt, detectedAt = detectedAt),
            )
            announcementsDelivered++
        }

        /** Expire the window for the episode detected at [detectedAt]. Suspends until delivered. */
        suspend fun announceExpiry(peerId: PeerId, detectedAt: Long, at: Long) {
            _events.emit(
                JoinerReconnectEvent.WindowExpired(peerId, at = at, detectedAt = detectedAt),
            )
            expiriesDelivered++
        }

        override suspend fun tryResume(token: ResumeToken, at: Long): ResumeResult.HostVerdict =
            ResumeResult.WindowNotYetOpen

        override fun onPeerRecovered(peerId: PeerId, at: Long) {
            recoveries += at
        }

        override fun expire(peerId: PeerId, at: Long) = Unit
    }

    @Test
    fun aStaleEpisodesWindowAnnouncementDoesNotMoveTheNewEpisodesDeadlineBackwards() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val policy = RecordingHoldPolicy()
            val loom = InMemoryLoom()
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
            val hostFactory = SeamRoomFactory(
                loom,
                backgroundScope,
                clock,
                fastConfig,
                reconnectControllerFactory = { _, _, _ -> policy },
            )
            val joinerFactory = SeamRoomFactory(loom, backgroundScope, clock, fastConfig)

            val host = hostFactory.host(Pattern("Host"))
            val droppedLink = FaultySeam(loom.join(InMemoryTag("Dropped")), backgroundScope)
            val droppedRoom = joinerFactory.adopt(droppedLink, SessionRole.Joiner)
            host.roster.first { it.size == 1 }
            droppedRoom.roster.first { it.size == 1 }
            val dropped = droppedRoom.selfId
            testScheduler.runCurrent()

            assertEquals(
                1,
                policy.subscriberCount,
                "rig precondition: the host's reconnect-event loop must be collecting this policy's " +
                    "events before any announcement, or a rendezvous emit succeeds by discarding",
            )

            // ── Partition episode N ───────────────────────────────────────────
            droppedLink.partition()
            testScheduler.advanceTimeBy(DETECTION_BUDGET)
            testScheduler.runCurrent()
            val episodeOne = policy.detections.singleOrNull()
            assertTrue(
                episodeOne != null,
                "rig: the host must have reported exactly ONE drop by now — more than one means the " +
                    "detector is flapping and there is no single episode N to be stale about. " +
                    "Observed ${policy.detections}",
            )

            deliver(policy, dropped, detectedAt = episodeOne, expiresAt = EPISODE_ONE_DEADLINE)
            val afterEpisodeOne = host.windowDeadlineMs(dropped)

            // ── Recovery ──────────────────────────────────────────────────────
            droppedLink.heal()
            testScheduler.advanceTimeBy(RECOVERY_BUDGET)
            testScheduler.runCurrent()
            val recoveredLiveness = host.livenessOf(dropped)

            // ── Partition episode N+1 ─────────────────────────────────────────
            droppedLink.partition()
            testScheduler.advanceTimeBy(DETECTION_BUDGET)
            testScheduler.runCurrent()
            val episodeTwo = policy.detections.getOrNull(1)
            assertTrue(
                episodeTwo != null && policy.detections.size == 2,
                "rig: the host must have reported exactly TWO drops by now — one per outage. A third " +
                    "means the detector flapped, and then the episode this test calls 'N' is not the " +
                    "one the room is holding. Observed ${policy.detections}",
            )

            deliver(policy, dropped, detectedAt = episodeTwo, expiresAt = EPISODE_TWO_DEADLINE)
            val afterEpisodeTwo = host.windowDeadlineMs(dropped)

            // ── Episode N's announcement, delivered LATE ──────────────────────
            deliver(policy, dropped, detectedAt = episodeOne, expiresAt = EPISODE_ONE_DEADLINE)
            val afterStale = host.windowDeadlineMs(dropped)

            // ── Control arm: a CURRENT-episode announcement, same flow, right after ──
            deliver(policy, dropped, detectedAt = episodeTwo, expiresAt = EPISODE_TWO_EXTENDED)
            val afterControl = host.windowDeadlineMs(dropped)

            assertAll(
                {
                    assertEquals(
                        4,
                        policy.announcementsDelivered,
                        "rig: all four announcements must have reached the room's collector — a test " +
                            "that never delivered the stale one passes trivially",
                    )
                },
                {
                    assertNotEquals(
                        episodeOne,
                        episodeTwo,
                        "rig: the two episodes must have distinct detection instants, or there is " +
                            "nothing for episode identity to tell apart",
                    )
                },
                {
                    assertEquals(
                        EPISODE_ONE_DEADLINE,
                        afterEpisodeOne,
                        "sanity: a current-episode announcement must move the deadline (otherwise the " +
                            "assertion below is green because refinement is broken, not because it is guarded)",
                    )
                },
                {
                    assertIs<Liveness.Connected>(
                        recoveredLiveness,
                        "sanity: the peer must have RECOVERED between the two episodes — without that " +
                            "there is one episode and the stale announcement is not stale at all",
                    )
                },
                {
                    assertEquals(
                        EPISODE_TWO_DEADLINE,
                        afterEpisodeTwo,
                        "sanity: the new episode's announcement must have taken effect — " +
                            "detections=${policy.detections}",
                    )
                },
                {
                    assertEquals(
                        EPISODE_TWO_DEADLINE,
                        afterStale,
                        "#1781: episode N's window announcement, delivered after episode N+1 opened, " +
                            "must be DROPPED — it moved the deadline backwards to $EPISODE_ONE_DEADLINE. " +
                            "Note both deadlines sit far past the new episode's `since`, so an " +
                            "`expiresAt < since` guard passes on this input and does not close it",
                    )
                },
                {
                    assertEquals(
                        EPISODE_TWO_EXTENDED,
                        afterControl,
                        "control arm: the very next announcement on the SAME flow, differing only in " +
                            "naming the CURRENT episode, must still be applied — so the assertion above " +
                            "is the episode gate refusing a stale deadline, not the collector being dead",
                    )
                },
            )
        }

    /**
     * The same identity hole one event over, where the action it drives is **irreversible** (#2556).
     *
     * `SeamRoom.handleWindowExpired` fans out an authoritative `Farewell` and evicts the seat. Its
     * only guard used to be *"is this peer partitioned right now?"* — the guard the test above
     * proves insufficient for merely moving a deadline. Under a recover→re-partition cycle it
     * answers **yes** for an expiry belonging to the episode the peer already came back from, so
     * episode *N*'s expiry takes a seat whose episode *N+1* window has not run out. There is no
     * re-admit path behind an admit fan-out, so nothing corrects it afterwards.
     *
     * **Control arm, on the same flow, immediately after.** The stale expiry must be refused and
     * the very next expiry — differing *only* in naming the current episode — must still evict.
     * Without it "the member is still seated" is equally green if the whole arm is dead, which is
     * the shape the `handleWindowExpired` rewrite could plausibly have produced.
     *
     * **The bystander is not scenery.** The eviction this file is about happens on *other* members'
     * rosters, via the `Farewell` fan-out, and the host declines it locally — so a host-only
     * assertion is exactly the one #2556 slipped past. Both rosters are read on both arms.
     */
    @Test
    fun aStaleEpisodesWindowExpiryDoesNotEvictAMemberWhoseCurrentWindowIsStillOpen() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val policy = RecordingHoldPolicy()
            val loom = InMemoryLoom()
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
            val hostFactory = SeamRoomFactory(
                loom,
                backgroundScope,
                clock,
                fastConfig,
                reconnectControllerFactory = { _, _, _ -> policy },
            )
            val joinerFactory = SeamRoomFactory(loom, backgroundScope, clock, fastConfig)

            val host = hostFactory.host(Pattern("Host"))
            val droppedLink = FaultySeam(loom.join(InMemoryTag("Dropped")), backgroundScope)
            val droppedRoom = joinerFactory.adopt(droppedLink, SessionRole.Joiner)
            val bystander = joinerFactory.join(InMemoryTag("Bystander"))
            host.roster.first { it.size == 2 }
            bystander.roster.first { it.size == 2 }
            val dropped = droppedRoom.selfId
            testScheduler.runCurrent()

            // ── Episode N: drop, announce, recover ────────────────────────────
            droppedLink.partition()
            testScheduler.advanceTimeBy(DETECTION_BUDGET)
            testScheduler.runCurrent()
            val episodeOne = policy.detections.singleOrNull()
            assertTrue(
                episodeOne != null,
                "rig: exactly ONE drop must have been reported by now — more means the detector is " +
                    "flapping and there is no single episode N. Observed ${policy.detections}",
            )
            deliver(policy, dropped, detectedAt = episodeOne, expiresAt = EPISODE_ONE_DEADLINE)

            droppedLink.heal()
            testScheduler.advanceTimeBy(RECOVERY_BUDGET)
            testScheduler.runCurrent()
            val recoveredLiveness = host.livenessOf(dropped)

            // ── Episode N+1 ───────────────────────────────────────────────────
            droppedLink.partition()
            testScheduler.advanceTimeBy(DETECTION_BUDGET)
            testScheduler.runCurrent()
            val episodeTwo = policy.detections.getOrNull(1)
            assertTrue(
                episodeTwo != null && policy.detections.size == 2,
                "rig: exactly TWO drops must have been reported by now — one per outage. Observed " +
                    "${policy.detections}",
            )
            deliver(policy, dropped, detectedAt = episodeTwo, expiresAt = EPISODE_TWO_DEADLINE)

            // ── Episode N's EXPIRY, delivered late ────────────────────────────
            deliverExpiry(policy, dropped, detectedAt = episodeOne, at = STALE_EXPIRY_AT)
            val hostHoldsAfterStale = host.holds(dropped)
            val bystanderHoldsAfterStale = bystander.holds(dropped)
            val deadlineAfterStale = host.windowDeadlineMs(dropped)

            // ── Control arm: the CURRENT episode's expiry, same flow ──────────
            deliverExpiry(policy, dropped, detectedAt = episodeTwo, at = CONTROL_EXPIRY_AT)
            val hostHoldsAfterControl = host.holds(dropped)
            val bystanderHoldsAfterControl = bystander.holds(dropped)

            assertAll(
                {
                    assertEquals(
                        2,
                        policy.expiriesDelivered,
                        "rig: both expiries must have reached the room's collector — a test that " +
                            "never delivered the stale one passes trivially",
                    )
                },
                {
                    assertNotEquals(
                        episodeOne,
                        episodeTwo,
                        "rig: the two episodes must have distinct detection instants, or there is " +
                            "nothing for episode identity to tell apart",
                    )
                },
                {
                    assertIs<Liveness.Connected>(
                        recoveredLiveness,
                        "sanity: the peer must have RECOVERED between the two episodes — without that " +
                            "there is one episode and the stale expiry is not stale at all",
                    )
                },
                {
                    // The exact instant is the detector's business (recovery needs a ping, a pong,
                    // and a later poll), so the property asserted is the SHAPE: told once, and told
                    // between the two episodes. Both halves bite — an empty list is the pre-#2556
                    // behaviour, and a recovery reported outside that interval would not be the one
                    // that separates the episodes.
                    val recovery = policy.recoveries.singleOrNull()
                    assertTrue(
                        recovery != null && recovery > episodeOne && recovery < episodeTwo,
                        "#2556: the room must TELL its hold policy the peer came back, exactly once " +
                            "and between the two episodes, so a policy that owns a timer can disarm " +
                            "it — detections=${policy.detections} recoveries=${policy.recoveries}",
                    )
                },
                {
                    assertEquals(
                        true,
                        hostHoldsAfterStale,
                        "#2556: episode N's expiry, delivered after episode N+1 opened, must not evict " +
                            "the member from the host's own roster",
                    )
                },
                {
                    assertEquals(
                        true,
                        bystanderHoldsAfterStale,
                        "#2556: nor may it reach the BYSTANDER as an authoritative Farewell — that is " +
                            "the roster the host's own liveness guard never protected",
                    )
                },
                {
                    assertEquals(
                        EPISODE_TWO_DEADLINE,
                        deadlineAfterStale,
                        "sanity: the member is still held to episode N+1's deadline, i.e. its current " +
                            "window genuinely had NOT run out when the stale expiry was refused",
                    )
                },
                {
                    assertEquals(
                        false,
                        hostHoldsAfterControl,
                        "control arm: the very next expiry on the SAME flow, differing only in naming " +
                            "the CURRENT episode, must still evict — otherwise the assertions above are " +
                            "green because the WindowExpired arm is dead, not because it is guarded",
                    )
                },
                {
                    assertEquals(
                        false,
                        bystanderHoldsAfterControl,
                        "control arm: and the Farewell for a genuine expiry must still reach the " +
                            "bystander (#1557)",
                    )
                },
            )
        }

    // ── Harness ───────────────────────────────────────────────────────────────

    /**
     * Deliver one announcement and prove it landed in the room's collector before returning.
     *
     * The `emit` is launched rather than called inline because it is a rendezvous — it cannot
     * complete until the collector, a different coroutine, takes the value — and `runCurrent()`
     * drains both at the same virtual instant. The completion check is the delivery receipt; the
     * trailing `runCurrent()` additionally lets the collector's body finish before any caller reads
     * the roster.
     */
    private suspend fun TestScope.deliver(
        policy: RecordingHoldPolicy,
        peerId: PeerId,
        detectedAt: Long,
        expiresAt: Long,
    ) {
        assertEquals(1, policy.subscriberCount, "rig: the room must still be collecting the policy's events")
        val emission = backgroundScope.launch { policy.announce(peerId, detectedAt, expiresAt) }
        testScheduler.runCurrent()
        assertTrue(
            emission.isCompleted,
            "rig: the announcement (detectedAt=$detectedAt expiresAt=$expiresAt) was never taken by " +
                "the room's collector — a rendezvous emit only completes once it has been",
        )
        testScheduler.runCurrent()
    }

    /**
     * Deliver one **expiry** and prove it landed in the room's collector before returning — the
     * [deliver] contract, on the sibling event.
     */
    private suspend fun TestScope.deliverExpiry(
        policy: RecordingHoldPolicy,
        peerId: PeerId,
        detectedAt: Long,
        at: Long,
    ) {
        assertEquals(1, policy.subscriberCount, "rig: the room must still be collecting the policy's events")
        val emission = backgroundScope.launch { policy.announceExpiry(peerId, detectedAt, at) }
        testScheduler.runCurrent()
        assertTrue(
            emission.isCompleted,
            "rig: the expiry (detectedAt=$detectedAt at=$at) was never taken by the room's collector " +
                "— a rendezvous emit only completes once it has been",
        )
        testScheduler.runCurrent()
    }

    private fun Room.holds(peerId: PeerId): Boolean = roster.value.any { it.id == peerId }

    private fun Room.livenessOf(peerId: PeerId): Liveness =
        roster.value.first { it.id == peerId }.liveness

    private fun Room.windowDeadlineMs(peerId: PeerId): Long =
        assertIs<Liveness.Partitioned>(
            livenessOf(peerId),
            "the peer must be held Partitioned at this point",
        ).windowExpiresAt.toEpochMilliseconds()

    private companion object {
        /** Past detection ([HeartbeatConfig.timeout]) with margin, far short of the 10 s window. */
        private val DETECTION_BUDGET = 800.milliseconds

        /**
         * Enough polls for the healed link to carry a frame and clear the partition. Recovery needs
         * *evidence*, not elapsed time — the detector must send a ping, receive a pong, and then see
         * the shortened silence on a later poll — so this is several [HeartbeatConfig.interval]s, not
         * one. The `Connected` sanity assertion below is what pins it: too small a budget reds there
         * rather than quietly leaving one long episode for the "stale" announcement to belong to.
         */
        private val RECOVERY_BUDGET = 800.milliseconds

        // Deadlines no window in this test could compute — the virtual clock starts at 0 and the
        // trajectory is ~2.4 s — and every one of them lands FAR past the new episode's `since`.
        // That is deliberate: it is what makes the rejected `expiresAt < since` guard pass here.
        const val EPISODE_ONE_DEADLINE = 1_000_000L
        const val EPISODE_TWO_DEADLINE = 2_000_000L
        const val EPISODE_TWO_EXTENDED = 3_000_000L

        // The `at` of an expiry is only ever logged, never compared — the episode gate reads
        // `detectedAt`. Distinct values so a log line names which of the two arms produced it.
        const val STALE_EXPIRY_AT = 4_000_000L
        const val CONTROL_EXPIRY_AT = 5_000_000L
    }
}
