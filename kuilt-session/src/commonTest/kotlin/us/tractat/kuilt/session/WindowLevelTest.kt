@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.MuxClientLoom
import us.tractat.kuilt.core.MuxServerLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.RoomAuthorizer
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.hubMesh
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.partition.RoomId
import us.tractat.kuilt.test.FaultySeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The reconnect window as a **level**, emitted inline for both roles (#1724, #1618 Drop B/Q2).
 *
 * A partitioned member's seat is held open until some deadline. That deadline used to exist only
 * on [MembershipEvent.WindowOpened] — a `replay = 0` event — and only on paths that emitted one:
 *
 * - the **host** got it via `reconnectController.onPeerUnresponsive` → `scope.launch { openWindow }`
 *   → an emit onto the controller's own `replay = 0` [kotlinx.coroutines.flow.SharedFlow], which
 *   discards the value outright if `runReconnectEventLoop` has not yet subscribed (#1618 Drop B);
 * - the **joiner** got nothing at all, because `reconnectController` is null on a joiner, so the
 *   call was a no-op against null (#1724).
 *
 * `markPartitioned` is role-agnostic, so one inline emission there serves both — and the deadline
 * it emits is the same `expiresAt` that sets [Liveness.Partitioned] and feeds the host's
 * `AdmitMessage.Paused` fan-out, so no two observers can disagree about it.
 *
 * All timings are virtual; the injected clock reads [TestScope.testScheduler] so the detector's
 * `silenceMs = clock() - lastSeen` arithmetic advances in lockstep with `advanceTimeBy`.
 */
class WindowLevelTest {

    /** Sub-second detection so a whole partition episode fits in a fraction of a second. */
    private val fastConfig = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 200.milliseconds,
        reconnectWindow = 500.milliseconds,
    )

    /**
     * A deadline past this instant can only have come from a host window measured in tens of
     * seconds; every locally-estimated window in these tests is a few seconds at most. It is the
     * discriminator that lets an authority test assert *whose* number the roster is holding without
     * having to predict the exact virtual instant a detector fired.
     */
    private val authorityThreshold = Instant.fromEpochMilliseconds(10.seconds.inWholeMilliseconds)

    // ── Host side (#1618 Drop B) ──────────────────────────────────────────────

    /**
     * The host's own events must carry the window, with no async hop that can lose it.
     *
     * **Characterization**, not a regression: the controller does emit for the host today, just
     * across a `scope.launch` + `replay = 0` [kotlinx.coroutines.flow.SharedFlow] hop that a test
     * is unlikely to lose deterministically. What this pins is the pairing — the event's
     * `expiresAt` and the roster level's `windowExpiresAt` are one number, so moving the emission
     * inline cannot silently change what a consumer counts down to.
     */
    @Test
    fun hostEmitsWindowOpenedForAnUnresponsiveJoiner() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val pair = hostFaultedPair()
            val windows = mutableListOf<MembershipEvent.WindowOpened>()
            backgroundScope.launch {
                pair.host.events
                    .filterIsInstance<MembershipEvent.WindowOpened>()
                    .collect { windows += it }
            }
            testScheduler.runCurrent()

            pair.faultedLink.partition()
            testScheduler.advanceTimeBy(fastConfig.timeout + fastConfig.interval * 2)
            testScheduler.runCurrent()

            val level = assertIs<Liveness.Partitioned>(
                pair.host.roster.value.first { it.id == pair.joinerId }.liveness,
                "sanity: the host must have detected the joiner's silence",
            )
            assertAll(
                {
                    assertEquals(
                        1,
                        windows.size,
                        "the host must see exactly one WindowOpened for the silent joiner — observed $windows",
                    )
                },
                { assertEquals(pair.joinerId, windows.firstOrNull()?.peerId) },
                {
                    assertEquals(
                        level.windowExpiresAt,
                        windows.firstOrNull()?.expiresAt,
                        "the emitted deadline and the roster level's deadline must be the same number",
                    )
                },
            )
        }

    /**
     * #1618 Q2, the structural claim: a collector that subscribes only **after** the partition
     * still reads the deadline — off [Room.roster], with no event ever collected.
     *
     * Driven on the **joiner** lane, where the claim actually has teeth. `Room.events` is not
     * `replay = 0` (it carries a bounded best-effort tail, #692), so on the host lane a late
     * subscriber usually *does* get a replayed `WindowOpened` — but "usually, if it is still in the
     * last 64 events" is not a guarantee, and the tail can only replay something that was emitted in
     * the first place. On a joiner nothing was: `reconnectController` is null, so no window was ever
     * announced and neither the stream nor its replay cache could supply one. The
     * [StateFlow][kotlinx.coroutines.flow.StateFlow] level needs neither.
     *
     * Two assertions, in order of strength: the level carries `since + reconnectWindow` with no
     * collector involved at all, and the late subscriber's replayed announcement — now that one
     * exists — agrees with it exactly, so a consumer keying on either cannot be misled.
     */
    @Test
    fun aLateSubscriberStillReadsTheDeadlineOffRoster() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val pair = joinerFaultedPair()

            // Deliberately NO events collector before or during the partition.
            pair.faultedLink.partition()
            testScheduler.advanceTimeBy(fastConfig.timeout + fastConfig.interval * 2)
            testScheduler.runCurrent()

            val level = assertIs<Liveness.Partitioned>(
                pair.joiner.roster.value.first { it.id == pair.hostId }.liveness,
                "sanity: the joiner must have detected its host's silence",
            )

            val late = mutableListOf<MembershipEvent>()
            backgroundScope.launch { pair.joiner.events.collect { late += it } }
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        level.since + fastConfig.reconnectWindow,
                        level.windowExpiresAt,
                        "the level's deadline must be first-detection plus the configured window, " +
                            "readable with no collector ever attached",
                    )
                },
                {
                    assertEquals(
                        listOf(level.windowExpiresAt),
                        late.filterIsInstance<MembershipEvent.WindowOpened>()
                            .filter { it.peerId == pair.hostId }
                            .map { it.expiresAt },
                        "the joiner must have announced its window exactly once, and the replayed " +
                            "announcement must carry the same deadline the level does — a late " +
                            "subscriber keying on the event and one keying on the roster cannot be " +
                            "allowed to count down to different instants — observed $late",
                    )
                },
            )
        }

    // ── Joiner side (#1724) ───────────────────────────────────────────────────

    /**
     * #1724: a joiner whose host goes silent by heartbeat `Timeout` gets a window, not a bare
     * `Partitioned`.
     *
     * The silence is injected with [FaultySeam.partition], which drops frames while leaving the
     * seam `Woven` and the host in `peers` — so the detector reports
     * [us.tractat.kuilt.liveness.PartitionEvent.Reason.Timeout] and the room routes it to
     * `markPartitioned`. A transport-`TransportClosed` tear would route to the resume machine
     * instead and never exercise this defect at all.
     */
    @Test
    fun joinerHostTimeoutOpensAWindowWithADeadline() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val pair = joinerFaultedPair()
            val partitioned = mutableListOf<MembershipEvent.Partitioned>()
            val windows = mutableListOf<MembershipEvent.WindowOpened>()
            backgroundScope.launch {
                pair.joiner.events.collect { event ->
                    when (event) {
                        is MembershipEvent.Partitioned -> if (event.peerId == pair.hostId) partitioned += event
                        is MembershipEvent.WindowOpened -> if (event.peerId == pair.hostId) windows += event
                        else -> Unit
                    }
                }
            }
            testScheduler.runCurrent()

            pair.faultedLink.partition()
            testScheduler.advanceTimeBy(fastConfig.timeout + fastConfig.interval * 2)
            testScheduler.runCurrent()

            val level = assertIs<Liveness.Partitioned>(
                pair.joiner.roster.value.first { it.id == pair.hostId }.liveness,
                "sanity: the joiner must have detected its host's silence",
            )
            assertAll(
                {
                    assertEquals(
                        ReconnectReason.LinkTimeout,
                        partitioned.firstOrNull()?.reason,
                        "this must be the silent-Timeout lane; a TransportClosed tear routes to the " +
                            "resume machine and never reaches markPartitioned — observed $partitioned",
                    )
                },
                {
                    assertEquals(
                        1,
                        windows.size,
                        "#1724: a joiner's reconnectController is null, so onPeerUnresponsive was a " +
                            "no-op against null and no window was ever announced — observed $windows",
                    )
                },
                {
                    assertEquals(
                        level.windowExpiresAt,
                        windows.firstOrNull()?.expiresAt,
                        "the announced deadline must be the one on the level",
                    )
                },
            )
        }

    // ── Authority: whose deadline wins ────────────────────────────────────────

    /**
     * A member's local estimate for **another** member must be corrected by the host's
     * authoritative `Paused` — without emitting a duplicate `Partitioned`.
     *
     * The bystander detects first (fast timeout, 5 s window), so it writes a local guess; the host
     * detects later (slow timeout, 30 s window) and fans out the real deadline. Only the host's
     * number can put the roster deadline past [authorityThreshold], so the assertion identifies
     * *whose* value survived without predicting the instant either detector fired.
     */
    @Test
    fun hostPausedRefinesALocallyEstimatedDeadline() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val mesh = mesh(
                hostConfig = fastConfig.copy(timeout = 700.milliseconds, reconnectWindow = 30.seconds),
                joinerConfig = fastConfig.copy(reconnectWindow = 5.seconds),
            )
            val partitioned = mutableListOf<MembershipEvent.Partitioned>()
            backgroundScope.launch {
                mesh.bystander.events
                    .filterIsInstance<MembershipEvent.Partitioned>()
                    .collect { if (it.peerId == mesh.droppedId) partitioned += it }
            }
            testScheduler.runCurrent()

            mesh.droppedLink.partition()

            // Past the bystander's own 200 ms timeout but short of the host's 700 ms one: the only
            // deadline in the roster so far is the bystander's own estimate.
            testScheduler.advanceTimeBy(400.milliseconds)
            testScheduler.runCurrent()
            val estimated = mesh.bystanderLevel().windowExpiresAt

            // Now let the host detect and fan out its authoritative Paused.
            testScheduler.advanceTimeBy(600.milliseconds)
            testScheduler.runCurrent()
            val refined = mesh.bystanderLevel().windowExpiresAt

            assertAll(
                {
                    assertTrue(
                        estimated < authorityThreshold,
                        "sanity: before the host spoke, the deadline is the bystander's own 5 s " +
                            "estimate — observed $estimated",
                    )
                },
                {
                    assertTrue(
                        refined > authorityThreshold,
                        "the host's authoritative 30 s window must replace the local estimate; " +
                            "handlePaused returning early on an already-partitioned member pins the " +
                            "guess forever (#1724) — observed $refined",
                    )
                },
                {
                    assertEquals(
                        1,
                        partitioned.size,
                        "refining the deadline must not re-announce the partition — observed $partitioned",
                    )
                },
            )
        }

    /**
     * The refinement is **announced**, not merely written to the level.
     *
     * Same timeline as [hostPausedRefinesALocallyEstimatedDeadline], asserting the half that test
     * cannot see. `handlePaused` used to return before both emissions on an already-partitioned
     * member, so the authoritative deadline moved the roster **silently**: the last
     * [MembershipEvent.WindowOpened] a consumer heard still named the bystander's own 5 s estimate
     * while the seat was held to the host's 30 s one, and a consumer counting down to the event's
     * deadline dropped the "reconnecting" seat ~25 s early. That is exactly the defect #1724 fixes on
     * the other lanes; the roster being right does not make the event's lie acceptable.
     *
     * The final assertion is the one that matters: whichever surface a consumer keys on — the last
     * announcement or the level — it reads the same number.
     */
    @Test
    fun hostPausedAnnouncesTheRefinedDeadline() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val mesh = mesh(
                hostConfig = fastConfig.copy(timeout = 700.milliseconds, reconnectWindow = 30.seconds),
                joinerConfig = fastConfig.copy(reconnectWindow = 5.seconds),
            )
            val windows = mutableListOf<MembershipEvent.WindowOpened>()
            val partitioned = mutableListOf<MembershipEvent.Partitioned>()
            backgroundScope.launch {
                mesh.bystander.events.collect { event ->
                    when (event) {
                        is MembershipEvent.WindowOpened -> if (event.peerId == mesh.droppedId) windows += event
                        is MembershipEvent.Partitioned -> if (event.peerId == mesh.droppedId) partitioned += event
                        else -> Unit
                    }
                }
            }
            testScheduler.runCurrent()

            mesh.droppedLink.partition()

            // Past the bystander's own 200 ms timeout but short of the host's 700 ms one: the only
            // announcement so far is the bystander's own 5 s estimate.
            testScheduler.advanceTimeBy(400.milliseconds)
            testScheduler.runCurrent()
            val estimated = windows.map { it.expiresAt }

            // Now let the host detect and fan out its authoritative 30 s Paused.
            testScheduler.advanceTimeBy(600.milliseconds)
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        1,
                        estimated.size,
                        "sanity: before the host spoke the bystander announced its own estimate " +
                            "exactly once — observed $estimated",
                    )
                },
                {
                    assertTrue(
                        estimated.all { it < authorityThreshold },
                        "sanity: that first announcement is the local 5 s estimate — observed $estimated",
                    )
                },
                {
                    assertEquals(
                        2,
                        windows.size,
                        "the host's authoritative deadline must be ANNOUNCED, not only written to " +
                            "the level; handlePaused returning early on an already-partitioned " +
                            "member leaves the estimate as the last thing a consumer heard " +
                            "(#1724) — observed $windows",
                    )
                },
                {
                    assertTrue(
                        windows.last().expiresAt > authorityThreshold,
                        "…and the re-announcement must carry the host's 30 s deadline, not repeat " +
                            "the estimate — observed $windows",
                    )
                },
                {
                    assertEquals(
                        mesh.bystanderLevel().windowExpiresAt,
                        windows.last().expiresAt,
                        "the last announcement and the roster level must be the same number, so a " +
                            "consumer keying on either counts down to the same instant",
                    )
                },
                {
                    assertEquals(
                        1,
                        partitioned.size,
                        "…while re-announcing the window must not re-announce the partition — " +
                            "observed $partitioned",
                    )
                },
            )
        }

    /**
     * The reverse order: `Paused` arrives **before** local detection. A member's own detector
     * firing afterwards must not overwrite the host's authoritative deadline with a local estimate.
     *
     * Also the only coverage of [Liveness.Partitioned.since] **stability**: `since` means "when
     * this partition was first detected", so it must survive a re-detection unchanged — that is
     * what keeps the level agreeing with the single [MembershipEvent.Partitioned] actually emitted.
     * Nothing else asserts it, and it is exactly the sort of thing a later reader "simplifies" back
     * to `since = at`.
     *
     * The final assertion is a vacuity guard, not decoration: the bystander's own detector matures
     * from Unresponsive to `PeerLost` on its 2 s window and evicts the peer. Without it, a test in
     * which the local detector never fired at all would pass for the wrong reason.
     */
    @Test
    fun localDetectionDoesNotClobberAnEarlierHostPausedDeadline() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val mesh = mesh(
                hostConfig = fastConfig.copy(reconnectWindow = 30.seconds),
                joinerConfig = fastConfig.copy(timeout = 900.milliseconds, reconnectWindow = 2.seconds),
            )
            mesh.droppedLink.partition()

            // Past the host's 200 ms timeout, well short of the bystander's 900 ms one.
            testScheduler.advanceTimeBy(400.milliseconds)
            testScheduler.runCurrent()
            val fromHost = mesh.bystanderLevel()

            // Past the bystander's own timeout: its detector now fires for an already-partitioned peer.
            testScheduler.advanceTimeBy(800.milliseconds)
            testScheduler.runCurrent()
            val afterLocalDetection = mesh.bystanderLevel()

            assertAll(
                {
                    assertTrue(
                        fromHost.windowExpiresAt > authorityThreshold,
                        "sanity: the bystander learned the host's 30 s window first — observed $fromHost",
                    )
                },
                {
                    assertEquals(
                        fromHost.windowExpiresAt,
                        afterLocalDetection.windowExpiresAt,
                        "a local estimate must not clobber the host's authoritative deadline (F4)",
                    )
                },
                {
                    assertEquals(
                        fromHost.since,
                        afterLocalDetection.since,
                        "`since` is first-detection, so a re-detection must not drift it forward — " +
                            "the level would then disagree with the single Partitioned event emitted",
                    )
                },
            )

            // Vacuity guard: let the bystander's own 2 s window mature to PeerLost. Only a detector
            // that really did transition to Unresponsive above can reach this eviction.
            testScheduler.advanceTimeBy(3.seconds)
            testScheduler.runCurrent()
            assertEquals(
                emptyList(),
                mesh.bystander.roster.value.filter { it.id == mesh.droppedId },
                "sanity: the bystander's own detector must have been live over this peer — its " +
                    "2 s window should have matured to PeerLost and evicted the seat",
            )
        }

    /**
     * Re-detection of an **already-partitioned** peer must re-announce the window.
     *
     * The emission sits deliberately outside the `!wasPartitioned` gate that suppresses a duplicate
     * `Partitioned`: a consumer that missed (or was created after) the first announcement must
     * still be told the deadline, and a re-arm must never leave one counting down to a deadline
     * that has moved.
     *
     * **Divergence from the brief, deliberate.** The brief asked for two events with the *later*
     * deadline second, driven by "a detector link-close after a Timeout episode". Neither half is
     * reachable in `SeamRoom`: the detector's link-close branch waits on `rawIncoming`, a
     * `MutableSharedFlow` that never completes, and its `TransportClosed` branch is unreachable for
     * the whole window because `awaitRecoveryOrLoss` owns the heartbeat loop until the peer either
     * recovers (clearing `Partitioned`) or is lost. The reachable double-fire is a **non-host**
     * member hearing the host's `Paused` and then its own detector — and there the second deadline
     * is deliberately *equal*, because a non-host preserves the authoritative value rather than
     * re-arming (see [localDetectionDoesNotClobberAnEarlierHostPausedDeadline]). Two events with a
     * non-regressing deadline is the assertion the reachable path supports.
     */
    @Test
    fun reDetectionReEmitsWindowOpenedWithoutRegressingTheDeadline() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val mesh = mesh(
                hostConfig = fastConfig.copy(reconnectWindow = 30.seconds),
                joinerConfig = fastConfig.copy(timeout = 900.milliseconds, reconnectWindow = 2.seconds),
            )
            val windows = mutableListOf<MembershipEvent.WindowOpened>()
            val partitioned = mutableListOf<MembershipEvent.Partitioned>()
            backgroundScope.launch {
                mesh.bystander.events.collect { event ->
                    when (event) {
                        is MembershipEvent.WindowOpened -> if (event.peerId == mesh.droppedId) windows += event
                        is MembershipEvent.Partitioned -> if (event.peerId == mesh.droppedId) partitioned += event
                        else -> Unit
                    }
                }
            }
            testScheduler.runCurrent()

            mesh.droppedLink.partition()

            // First announcement: the host's Paused reaches the bystander.
            testScheduler.advanceTimeBy(400.milliseconds)
            testScheduler.runCurrent()
            val firstWindowCount = windows.size

            // Second: the bystander's own detector fires for a peer already Partitioned.
            testScheduler.advanceTimeBy(800.milliseconds)
            testScheduler.runCurrent()

            assertAll(
                { assertEquals(1, firstWindowCount, "sanity: the host's Paused announces once — observed $windows") },
                {
                    assertEquals(
                        2,
                        windows.size,
                        "re-detecting an already-partitioned peer must re-announce the window; gating " +
                            "the emission on !wasPartitioned drops it silently — observed $windows",
                    )
                },
                {
                    assertEquals(
                        windows.firstOrNull()?.expiresAt,
                        windows.lastOrNull()?.expiresAt,
                        "the re-announcement must not regress the deadline to a local estimate",
                    )
                },
                {
                    assertEquals(
                        1,
                        partitioned.size,
                        "…while the partition itself is still announced exactly once — observed $partitioned",
                    )
                },
            )
        }

    // ── Joiner ↔ its own host: the transport-tear / resume lane (#1723) ────────

    /**
     * #1723: `roster` and `events` must not contradict each other. A joiner partitioned from its
     * host must show that host [Liveness.Partitioned] **in the roster**, carrying the same deadline
     * the announcement did.
     *
     * This is the transport-tear lane, not the heartbeat-`Timeout` lane
     * [joinerHostTimeoutOpensAWindowWithADeadline] covers: a torn base routes to the resume machine,
     * whose `onReconnectStarted` callback emitted [MembershipEvent.Partitioned] +
     * [MembershipEvent.WindowOpened] and mutated **no roster state at all** — so `events` said the
     * host was partitioned while `roster` still reported it [Liveness.Connected], and a subscriber
     * arriving after the edge could not recover the state from either surface.
     *
     * The re-weave is gated so the reconnect is observed **in flight**: a successful resume clears
     * the level again (see [aResumedJoinerShowsItsHostConnectedAgain]), so an ungated harness would
     * race the assertion against the resume it is not testing.
     */
    @Test
    fun joinerShowsItsHostPartitionedInTheRoster() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val h = resumableJoiner()
            val windows = mutableListOf<MembershipEvent.WindowOpened>()
            backgroundScope.launch {
                h.joiner.events
                    .filterIsInstance<MembershipEvent.WindowOpened>()
                    .collect { if (it.peerId == h.hostId) windows += it }
            }
            testScheduler.runCurrent()

            h.tearTransport()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(fastConfig.interval)
            testScheduler.runCurrent()

            val level = assertIs<Liveness.Partitioned>(
                h.hostLiveness(),
                "#1723: onReconnectStarted announced the partition and mutated no roster state, so " +
                    "the joiner's roster still reported its host Connected",
            )
            assertAll(
                {
                    assertEquals(
                        listOf(level.windowExpiresAt),
                        windows.map { it.expiresAt },
                        "the joiner must have announced exactly one window for its host, and the " +
                            "level must carry that same deadline — a consumer keying on the roster " +
                            "and one keying on the event cannot count down to different instants — " +
                            "observed $windows",
                    )
                },
                {
                    assertEquals(
                        level.since + fastConfig.reconnectWindow,
                        level.windowExpiresAt,
                        "the level's deadline is the budget the resume machine actually enforces: " +
                            "first detection plus the configured reconnect window",
                    )
                },
            )
        }

    /**
     * The clear side. Without it the level is worse than no level: a host pinned
     * [Liveness.Partitioned] in the joiner's roster renders a permanent "reconnecting…" over a
     * session that is in fact live again.
     *
     * Asserted on the **roster**, not on [MembershipEvent.Resumed] — a `Resumed` edge over a level
     * that never cleared is the same #1723 contradiction, in the other direction.
     *
     * The mid-flight assertion is a vacuity guard, not decoration: without it a run in which the
     * level was never set at all would pass for the wrong reason.
     *
     * The clearing path is [SeamRoom.handleResumeAck] and nothing else — the detector the resume
     * restarts is fresh, so it never fires the `PeerRecovered` that [SeamRoom.markRecovered] needs.
     * An episode that completes **without** a `ResumeAck` therefore leaves this level pinned; see
     * #1637, whose no-op-resume path must clear it explicitly.
     */
    @Test
    fun aResumedJoinerShowsItsHostConnectedAgain() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val h = resumableJoiner()

            h.tearTransport()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(fastConfig.interval)
            testScheduler.runCurrent()
            val midFlight = h.hostLiveness()

            // Let the re-weave through; the resume completes well inside the 500 ms window.
            h.releaseReweave()
            repeat(3) {
                testScheduler.advanceTimeBy(fastConfig.interval)
                testScheduler.runCurrent()
            }

            assertAll(
                {
                    assertIs<Liveness.Partitioned>(
                        midFlight,
                        "sanity: the host must have been Partitioned mid-reconnect, else this test " +
                            "never exercises the clear at all",
                    )
                },
                {
                    assertEquals(
                        Liveness.Connected,
                        h.hostLiveness(),
                        "a resumed joiner must clear its host's level, or every consumer renders " +
                            "\"reconnecting…\" forever over a live session",
                    )
                },
            )
        }

    /**
     * A tear that follows a heartbeat `Timeout` moves the **deadline** and must not move
     * [Liveness.Partitioned.since].
     *
     * The reachable double-detection, and the real-hardware ordering: heartbeats stop while the seam
     * is still `Woven`, so the detector reports `Timeout` and [SeamRoom.markPartitioned] writes
     * `Partitioned(since = t1, windowExpiresAt = t1 + w)`; the socket then actually closes and the
     * torn watcher hands the **already-partitioned** host to the resume machine, which reports
     * `onReconnectStarted(host, t2, t2 + w)`.
     *
     * The two halves pull in opposite directions, which is the point:
     * - `since` must stay `t1` — its documented contract is *first* detection, so it keeps agreeing
     *   with the first [MembershipEvent.Partitioned] a consumer actually heard;
     * - `windowExpiresAt` must become `t2 + w`, because that is the budget the resume machine now
     *   enforces, and it must equal the deadline just announced or a consumer counts down to a
     *   deadline nobody holds.
     *
     * Writing `Partitioned(since = at, windowExpiresAt = windowDeadline)` unconditionally satisfies
     * the second half and breaks the first; leaving the level alone satisfies the first and breaks
     * the second.
     */
    @Test
    fun aTearAfterATimeoutMovesTheDeadlineButNotFirstDetection() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val h = faultedResumableJoiner()
            val windows = mutableListOf<MembershipEvent.WindowOpened>()
            val partitions = mutableListOf<MembershipEvent.Partitioned>()
            backgroundScope.launch {
                h.joiner.events
                    .filterIsInstance<MembershipEvent.WindowOpened>()
                    .collect { if (it.peerId == h.hostId) windows += it }
            }
            backgroundScope.launch {
                h.joiner.events
                    .filterIsInstance<MembershipEvent.Partitioned>()
                    .collect { if (it.peerId == h.hostId) partitions += it }
            }
            testScheduler.runCurrent()

            // Silence the host WITHOUT tearing: the seam stays Woven and the host stays in `peers`,
            // so the detector reports Timeout and the room routes it to markPartitioned.
            assertNotNull(h.faultedLink, "this harness must expose a faultable link").partition()
            testScheduler.advanceTimeBy(fastConfig.timeout + fastConfig.interval)
            testScheduler.runCurrent()
            val fromTimeout = assertIs<Liveness.Partitioned>(
                h.hostLiveness(),
                "sanity: the heartbeat Timeout must have partitioned the host first — this test is " +
                    "about what a LATER tear does to an already-partitioned host",
            )

            // Now the socket really closes. The gate is never released: this asserts the state at
            // onReconnectStarted, and a FaultySeam cannot carry a healed generation anyway (its
            // inbound pump completes with the torn generation).
            h.tearTransport()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(fastConfig.interval)
            testScheduler.runCurrent()
            val fromTear = assertIs<Liveness.Partitioned>(
                h.hostLiveness(),
                "the host must still be Partitioned after the tear",
            )

            assertAll(
                {
                    assertEquals(
                        fromTimeout.since,
                        fromTear.since,
                        "`since` is FIRST detection: a tear following a Timeout must not drift it " +
                            "forward, or the level stops agreeing with the Partitioned event the " +
                            "consumer already heard",
                    )
                },
                {
                    assertTrue(
                        fromTear.windowExpiresAt > fromTimeout.windowExpiresAt,
                        "the deadline DID move — the resume machine's budget runs from the tear, " +
                            "not from first detection — observed $fromTimeout then $fromTear",
                    )
                },
                {
                    assertEquals(
                        fromTear.windowExpiresAt,
                        windows.lastOrNull()?.expiresAt,
                        "…and the moved deadline must be the one just announced; a level that moves " +
                            "silently leaves the last WindowOpened permanently false — observed $windows",
                    )
                },
                {
                    assertEquals(
                        1,
                        partitions.size,
                        "the PARTITION is announced once, on first detection — one outage is one " +
                            "Partitioned, however many times we notice it. A consumer treating this " +
                            "as an edge (countdown, disconnect log, metric) double-counts otherwise, " +
                            "and Liveness.Partitioned.since's contract says it agrees with the single " +
                            "Partitioned emitted — which requires there to be one. Observed $partitions",
                    )
                },
                {
                    assertEquals(
                        fromTear.since,
                        partitions.firstOrNull()?.at,
                        "…and that one event is the FIRST detection, so the level's `since` is " +
                            "exactly the instant the consumer was told about — observed $partitions",
                    )
                },
            )
        }

    // ── Harnesses ─────────────────────────────────────────────────────────────

    private fun TestScope.virtualClock(): () -> Instant =
        { Instant.fromEpochMilliseconds(testScheduler.currentTime) }

    /** A host whose own link is faultable, plus the joiner it watches. */
    private class HostFaultedPair(val host: Room, val faultedLink: FaultySeam, val joinerId: PeerId)

    private suspend fun TestScope.hostFaultedPair(): HostFaultedPair {
        val loom = InMemoryLoom()
        val factory = SeamRoomFactory(loom, backgroundScope, virtualClock(), fastConfig)
        // Fault the HOST's link so its own detector times out on the joiner. `adopt` with
        // SessionRole.Host still mints a RoomId, so the reconnect controller exists — the
        // characterization test above depends on that.
        val faultedLink = FaultySeam(loom.host(Pattern("Host")), backgroundScope)
        val hostRoom = factory.adopt(faultedLink, SessionRole.Host)
        val joinerRoom = factory.join(InMemoryTag("Joiner"))
        hostRoom.roster.first { it.size == 1 }
        joinerRoom.roster.first { it.size == 1 }
        return HostFaultedPair(hostRoom, faultedLink, joinerRoom.selfId)
    }

    /** A joiner whose own link is faultable, so its **host** goes silent by Timeout. */
    private class JoinerFaultedPair(val joiner: Room, val faultedLink: FaultySeam, val hostId: PeerId)

    private suspend fun TestScope.joinerFaultedPair(): JoinerFaultedPair {
        val loom = InMemoryLoom()
        val factory = SeamRoomFactory(loom, backgroundScope, virtualClock(), fastConfig)
        val hostRoom = factory.host(Pattern("Host"))
        val faultedLink = FaultySeam(loom.join(InMemoryTag("Joiner")), backgroundScope)
        val joinerRoom = factory.adopt(faultedLink, SessionRole.Joiner)
        hostRoom.roster.first { it.size == 1 }
        joinerRoom.roster.first { it.size == 1 }
        return JoinerFaultedPair(joinerRoom, faultedLink, hostRoom.selfId)
    }

    /**
     * A three-peer mesh: a host, a joiner whose link is faulted mid-test, and a [bystander] joiner
     * that holds an opinion about the dropped peer from **both** sources — its own detector (the
     * mesh gives it a route) and the host's authoritative fan-out. Host and joiner rooms take
     * independent [HeartbeatConfig]s so a test can choose which source speaks first.
     */
    private class Mesh(val bystander: Room, val droppedLink: FaultySeam, val droppedId: PeerId) {
        /** The bystander's current level for the dropped peer; fails if it is not partitioned. */
        fun bystanderLevel(): Liveness.Partitioned = assertIs<Liveness.Partitioned>(
            bystander.roster.value.first { it.id == droppedId }.liveness,
            "the bystander must hold the dropped peer as Partitioned at this point",
        )
    }

    /**
     * A joiner over a **resumable** base, so a transport tear routes to the resume machine
     * (`onReconnectStarted`) rather than to `markPartitioned` — the lane the [joinerFaultedPair]
     * harness above cannot reach, because a `SeamRoomFactory` joiner over a plain
     * [InMemoryLoom] has no re-weave target and goes straight to terminal.
     */
    private class ResumableJoiner(
        val joiner: SeamRoom,
        val hostId: PeerId,
        /** Non-null only for [faultedResumableJoiner]; see that factory for why. */
        val faultedLink: FaultySeam?,
        private val muxClient: MuxClientLoom,
        private val reweaveGate: CompletableDeferred<Unit>,
    ) {
        /** Drop the single shared socket out from under the joiner (a real socket close analog). */
        suspend fun tearTransport(): Unit = muxClient.closeBase()

        /** Let the gated re-weave proceed, so the reconnect can complete its resume. */
        fun releaseReweave() {
            reweaveGate.complete(Unit)
        }

        /** The joiner's current level for its host; fails if the host left the roster entirely. */
        fun hostLiveness(): Liveness = joiner.roster.value.first { it.id == hostId }.liveness
    }

    private val nameOf: (Rendezvous) -> String = { rv ->
        when (rv) {
            is Rendezvous.New -> rv.pattern.sessionName
            is Rendezvous.Existing -> rv.tag.sessionName
        }
    }

    /**
     * A resumable joiner whose re-weave is **gated**, so a test can observe the reconnect in flight
     * and then release it to a successful resume.
     */
    private suspend fun TestScope.resumableJoiner(): ResumableJoiner = buildResumableJoiner(faulty = false)

    /**
     * The same, with the joiner's live seam additionally wrapped in a [FaultySeam] so heartbeats can
     * be dropped **without** tearing — the only way to put an already-`Partitioned` host in front of
     * `onReconnectStarted`.
     *
     * **The resume cannot succeed through this wrapper**, so a test using it must never release the
     * gate: [FaultySeam] pumps its delegate's `incoming` into a one-shot spool, and a
     * [MuxClientLoom] channel's `incoming` is per-generation — it completes at that generation's
     * `Torn`, closing the spool for good, so a healed base never reaches the room again.
     */
    private suspend fun TestScope.faultedResumableJoiner(): ResumableJoiner = buildResumableJoiner(faulty = true)

    private suspend fun TestScope.buildResumableJoiner(faulty: Boolean): ResumableJoiner {
        val dispatcher = coroutineContext[ContinuationInterceptor]!!
        val clock = virtualClock()
        val source = InMemoryConnectionSource()
        val serverLoom = MuxServerLoom(
            source = source,
            scope = backgroundScope,
            selfId = PeerId("server"),
            authorizer = RoomAuthorizer.AllowAll,
            dispatcher = dispatcher,
            random = Random(13L),
        )
        val hostRoom = SeamRoom(
            seam = serverLoom.host(Pattern("table-7")),
            role = SessionRole.Host,
            memberName = "table-7",
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = fastConfig,
            roomId = RoomId("room-1"),
        ).also { it.start() }

        val clientId = PeerId("client")
        var seed = 1
        val base = object : Loom {
            override suspend fun weave(rendezvous: Rendezvous): Seam {
                val (serverConn, clientConn) = connectionPair()
                source.offer(serverConn)
                return hubMesh(clientId, listOf(clientConn), dispatcher, Random((seed++).toLong()))
            }
        }
        val muxClient = MuxClientLoom(base, Rendezvous.New(Pattern("base")), backgroundScope, nameOf)
        val tag = InMemoryTag("table-7")
        val channel = muxClient.join(tag)
        val faultedLink = if (faulty) FaultySeam(channel, backgroundScope) else null
        val gate = CompletableDeferred<Unit>()
        val joinerRoom = SeamRoom(
            seam = faultedLink ?: channel,
            role = SessionRole.Joiner,
            memberName = "client",
            scope = backgroundScope,
            clock = clock,
            heartbeatConfig = fastConfig,
            roomId = null,
            reweave = { gate.await(); muxClient.join(tag) },
        ).also { it.start() }

        hostRoom.roster.first { it.size == 1 }
        joinerRoom.roster.first { it.isNotEmpty() }
        return ResumableJoiner(joinerRoom, hostRoom.selfId, faultedLink, muxClient, gate)
    }

    private suspend fun TestScope.mesh(hostConfig: HeartbeatConfig, joinerConfig: HeartbeatConfig): Mesh {
        val loom = InMemoryLoom()
        val clock = virtualClock()
        val hostFactory = SeamRoomFactory(loom, backgroundScope, clock, hostConfig)
        val joinerFactory = SeamRoomFactory(loom, backgroundScope, clock, joinerConfig)

        val hostRoom = hostFactory.host(Pattern("Host"))
        val droppedLink = FaultySeam(loom.join(InMemoryTag("Dropped")), backgroundScope)
        val droppedRoom = joinerFactory.adopt(droppedLink, SessionRole.Joiner)
        val bystander = joinerFactory.join(InMemoryTag("Bystander"))

        hostRoom.roster.first { it.size == 2 }
        droppedRoom.roster.first { it.size == 2 }
        bystander.roster.first { it.size == 2 }

        return Mesh(bystander, droppedLink, droppedRoom.selfId)
    }
}
