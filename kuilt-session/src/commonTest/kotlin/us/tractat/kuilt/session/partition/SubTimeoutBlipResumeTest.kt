@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session.partition

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.FailureReason
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.SessionRole
import us.tractat.kuilt.test.Direction
import us.tractat.kuilt.test.FaultySeam
import us.tractat.kuilt.test.FlakyLifecycleSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** One step of the harness: 100 ms of virtual time and 100 ms on the injected clock, in lockstep. */
private const val TICK_MS = 100L

/**
 * The joiner-side grace before its own fabric evicts the host — the harness analogue of
 * `NwSeam.DEFAULT_WOVEN_PATH_GRACE` (10 s in production). Frames have already stopped flowing;
 * the seam has not re-formed yet.
 */
private const val GRACE_TICKS = 4

/**
 * How long the path is actually down. **Strictly between [GRACE_TICKS] and the host's
 * `HeartbeatConfig.timeout`** — that ordering *is* the bug (see the class KDoc). On device the
 * same window is 10 s < outage < 15 s, i.e. an outage of roughly 12–13 s.
 */
private const val OUTAGE_TICKS = 6

/** Ticks spent settling the admit handshake and letting both detectors latch "peer was seen". */
private const val SETTLE_TICKS = 3

/**
 * Long enough that the **unfixed** joiner has burned its whole `reconnectWindow` and gone
 * terminal by the end of the test, so a regression fails on an assertion rather than on a
 * five-second `runTest` timeout.
 */
private const val OBSERVE_TICKS = 45

/**
 * #1637 — a blip shorter than the host's liveness timeout must resume, not go terminal.
 *
 * ## The failure loop
 *
 * The joiner's device path drops. Its fabric holds the seam for a grace period, then evicts the
 * host and re-forms; the joiner's detector reports `TransportClosed` and the
 * [JoinerResumeMachine] starts a reconnect episode. **The host's link never closed** — only the
 * joiner's side tore — so the host's own detector never fired and it has no reconnect window
 * open. It answers the joiner's `Resume` with `ResumeWindowNotYetOpen`, which is *retryable*.
 *
 * That answer is self-sustaining: `HeartbeatPartitionDetector.collectIncoming` treats **any**
 * inbound frame as proof of liveness, so each retry refreshes the host's `lastSeen` and its
 * silence can never reach `HeartbeatConfig.timeout`. No window will ever open. Before the fix the
 * joiner retried until its `reconnectWindow` elapsed and died `HostLost(Refused)` on a link that
 * was, by then, perfectly healthy.
 *
 * ## Why the ordering below is load-bearing
 *
 * Three durations, and only their **order** matters:
 *
 * | Harness | Device | Meaning |
 * |---|---|---|
 * | [GRACE_TICKS] = 400 ms | `NwSeam.DEFAULT_WOVEN_PATH_GRACE` = 10 s | the joiner evicts the host |
 * | [OUTAGE_TICKS] = 600 ms | ~12 s | how long the path is down |
 * | `HeartbeatConfig.timeout` = 1000 ms | 15 s | the host gives up on a silent peer |
 *
 * `grace < outage` — otherwise the fabric never evicts the host and the joiner never enters
 * `attemptReconnect` at all (an 8 s device outage cannot reproduce #1637 for exactly this
 * reason). `outage < hostTimeout` — otherwise the host *does* notice, opens a window, and the
 * ordinary resume path handles it. Only the sandwich reproduces the bug.
 *
 * ## Harness shape
 *
 * The two-layer seam is what makes the grace representable. The inner [FaultySeam] stops frames
 * in both directions while leaving the seam `Woven` with the host still in `peers` — the
 * *path-lost, still-in-grace* state. The outer [FlakyLifecycleSeam]'s `enterWeaving()` then
 * models grace expiry: the host leaves `peers` and the seam re-forms `Woven → Weaving → Woven`,
 * which is the `NwSeam` shape (it does not latch `Torn`) and the only shape that reaches
 * `attemptReconnect`. The host's own seam is untouched throughout — that is the whole point.
 *
 * The clock **advances in lockstep with virtual time**: unlike the frozen-clock harnesses in this
 * package, this test needs the host's silence and the joiner's dwell to be measurable.
 *
 * This is session-layer plumbing only. #1637 also wants a two-phone validation of the real
 * drop-and-return; see the plan's Task 4.
 */
class SubTimeoutBlipResumeTest {

    private val fastConfig = HeartbeatConfig(
        interval = 100.milliseconds,
        // The host's liveness timeout — strictly longer than OUTAGE_TICKS, and the dwell the fix
        // keys on.
        timeout = 1000.milliseconds,
        reconnectWindow = 4.seconds,
    )

    @Test
    fun `a blip shorter than the host timeout completes as a local no-op resume`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            var nowMs = 0L
            val clock = { Instant.fromEpochMilliseconds(nowMs) }
            fun tick(count: Int = 1) = repeat(count) {
                nowMs += TICK_MS
                advanceTimeBy(TICK_MS)
                runCurrent()
            }

            val h = blipHarness(clock, fastConfig)
            val recovered = backgroundScope.async {
                h.joinerRoom.events.filterIsInstance<MembershipEvent.Recovered>().first()
            }
            val hostLost = backgroundScope.async {
                h.joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first()
            }

            tick(SETTLE_TICKS)
            h.dropPath()
            tick(GRACE_TICKS)
            h.expireGrace()
            tick(OUTAGE_TICKS - GRACE_TICKS)
            h.restorePath()
            tick(OBSERVE_TICKS)

            assertAll(
                {
                    assertTrue(
                        recovered.isCompleted,
                        "a blip the host never observed must close its own arc with Recovered(hostId)",
                    )
                },
                {
                    assertFalse(
                        hostLost.isCompleted,
                        "a blip shorter than the host's timeout must not go terminal",
                    )
                },
            )
        }

    /**
     * **Guard 1 — the no-op path must not rescue a genuine host loss.**
     *
     * The path never returns, so the seam never re-forms, `reweaveFn()` keeps yielding something
     * that is not `Woven`, and no resume is ever *sent*. No reject is recorded, the dwell never
     * starts, and the reconnect window expires terminal — [FailureReason.WindowExpired], which is
     * also the proof that no refusal was involved.
     */
    @Test
    fun `an outage that never returns still ends terminal`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            var nowMs = 0L
            val clock = { Instant.fromEpochMilliseconds(nowMs) }
            fun tick(count: Int = 1) = repeat(count) {
                nowMs += TICK_MS
                advanceTimeBy(TICK_MS)
                runCurrent()
            }

            val h = blipHarness(clock, fastConfig)
            val recovered = backgroundScope.async {
                h.joinerRoom.events.filterIsInstance<MembershipEvent.Recovered>().first()
            }
            val hostLost = backgroundScope.async {
                h.joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first()
            }

            tick(SETTLE_TICKS)
            h.dropPath()
            tick(GRACE_TICKS)
            h.expireGrace() // …and the path never comes back.
            tick(OBSERVE_TICKS)

            assertAll(
                {
                    assertTrue(
                        hostLost.isCompleted,
                        "an outage that outlasts the reconnect window must still go terminal",
                    )
                },
                {
                    assertEquals(
                        FailureReason.WindowExpired,
                        hostLost.takeIf { it.isCompleted }?.getCompleted()?.reason,
                        "a loss with no refusal at all expires the window rather than being refused",
                    )
                },
                {
                    assertFalse(
                        recovered.isCompleted,
                        "the no-op path must not rescue a host that is genuinely gone",
                    )
                },
            )
        }

    /**
     * **Guard 2 — the dwell must not pre-empt a real resume.**
     *
     * The same sub-timeout blip, except that partway through the joiner's retries the **host's**
     * own side of the link drops too. That is the #1572 shape: the host now genuinely notices, opens
     * a reconnect window, and a later retry lands on it. The episode must then end in a real
     * [MembershipEvent.Resumed] — the host acked it — and **not** in the no-op path's
     * [MembershipEvent.Recovered], which would mean the dwell fired while a window was on its way.
     *
     * The host's drop is placed inside the dwell (it starts ~200 ms after the first reject, against
     * a 1000 ms dwell) precisely so a shortened dwell would be caught here.
     */
    @Test
    fun `a drop the host also observes ends in a real resume`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            var nowMs = 0L
            val clock = { Instant.fromEpochMilliseconds(nowMs) }
            fun tick(count: Int = 1) = repeat(count) {
                nowMs += TICK_MS
                advanceTimeBy(TICK_MS)
                runCurrent()
            }

            val h = blipHarness(clock, fastConfig)
            val resumed = backgroundScope.async {
                h.joinerRoom.events.filterIsInstance<MembershipEvent.Resumed>().first()
            }
            val recovered = backgroundScope.async {
                h.joinerRoom.events.filterIsInstance<MembershipEvent.Recovered>().first()
            }
            val hostLost = backgroundScope.async {
                h.joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first()
            }

            tick(SETTLE_TICKS)
            h.dropPath()
            tick(GRACE_TICKS)
            h.expireGrace()
            tick(OUTAGE_TICKS - GRACE_TICKS)
            h.restorePath()

            // The joiner is now collecting ResumeWindowNotYetOpen rejects and its dwell is running.
            tick(3)
            h.hostObservesDrop()
            tick(2)
            h.hostLinkReturns()
            tick(OBSERVE_TICKS)

            assertAll(
                {
                    assertTrue(
                        resumed.isCompleted,
                        "a host that did open a window must be resumed against for real",
                    )
                },
                {
                    assertFalse(
                        recovered.isCompleted,
                        "the dwell must not pre-empt a resume the host was about to accept",
                    )
                },
                {
                    assertFalse(
                        hostLost.isCompleted,
                        "a resume the host accepts must not go terminal",
                    )
                },
            )
        }
}

/** The pieces of a #1637 scenario a test drives: two rooms and the joiner's two fault layers. */
private class BlipHarness(
    val hostRoom: Room,
    val joinerRoom: Room,
    private val joinerPath: FaultySeam,
    private val joinerLink: FlakyLifecycleSeam,
    private val hostLink: FlakyLifecycleSeam,
) {
    /** The device path goes away: frames stop both ways, but the seam is still `Woven`. */
    fun dropPath() = joinerPath.partition(Direction.Both)

    /** The fabric's grace expires: the joiner evicts the host and the seam re-forms. */
    fun expireGrace() = joinerLink.enterWeaving()

    /** The path returns: the seam re-forms in place and frames flow again. */
    fun restorePath() {
        joinerLink.recover()
        joinerPath.heal()
    }

    /** The **host** briefly loses its side of the link, so it genuinely opens a reconnect window. */
    fun hostObservesDrop() = hostLink.enterWeaving()

    /** The host's side of the link returns. */
    fun hostLinkReturns() = hostLink.recover()
}

/**
 * Two adopted rooms over one [InMemoryLoom], with the **joiner's** seam wrapped
 * `FlakyLifecycleSeam(FaultySeam(base))` so a test can drop the path and expire the grace
 * independently. `reweave = { joinerLink }` is the self-healing-in-place wiring adopted rooms use
 * for a fabric that re-forms rather than latching `Torn`.
 */
private suspend fun TestScope.blipHarness(
    clock: () -> Instant,
    config: HeartbeatConfig,
): BlipHarness {
    val base = InMemoryLoom()
    val hostLink = FlakyLifecycleSeam(base.weave(Rendezvous.New(Pattern("s"))), backgroundScope)
    val joinerPath = FaultySeam(base.weave(Rendezvous.Existing(InMemoryTag("s"))), backgroundScope)
    val joinerLink = FlakyLifecycleSeam(joinerPath, backgroundScope)
    val factory = SeamRoomFactory(
        loom = base,
        scope = backgroundScope,
        clock = clock,
        heartbeatConfig = config,
    )
    val hostRoom = factory.adopt(hostLink, SessionRole.Host, memberName = "Host")
    val joinerRoom = factory.adopt(
        joinerLink,
        SessionRole.Joiner,
        memberName = "Joiner",
        reweave = { joinerLink },
    )
    hostRoom.roster.first { it.size == 1 }
    joinerRoom.roster.first { it.isNotEmpty() }
    return BlipHarness(hostRoom, joinerRoom, joinerPath, joinerLink, hostLink)
}
