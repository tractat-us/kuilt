@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.partition.JoinerReconnectController
import us.tractat.kuilt.session.partition.JoinerReconnectEvent
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.ResumeToken
import us.tractat.kuilt.test.FaultySeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Host-authoritative admit fan-outs reach the wire in the order the host raised them (#1781).
 *
 * ### What was wrong
 *
 * `fanOutToOtherMembers` used to launch its own coroutine per call, so two fan-outs raised close
 * together had **no ordering relationship at all** — either could reach [Seam.sendTo] first. Two of
 * the resulting inversions corrupt a remote roster:
 *
 * - `Paused(estimate)` (first detection) overtaken by `Paused(refined)` (the enforcing controller's
 *   deadline) moves a remote member's deadline **backwards**, so it counts an indefinitely-held seat
 *   down to a few seconds and drops it while the host still holds it;
 * - `Unpaused` overtaken by its own `Paused` leaves a **recovered** member pinned
 *   [Liveness.Partitioned] in that remote roster **forever** — `handleUnpaused` no-ops for a member
 *   it does not currently hold as partitioned, and neither frame carries episode identity.
 *
 * ### Why these tests can see it and the rest of the suite cannot
 *
 * [StandardTestDispatcher] dispatches launches FIFO, so two `scope.launch`es that each run
 * straight through to completion *do* reach the wire in creation order — which is why every existing
 * presence test passes on the broken code. The distinguishing property is what happens when the
 * **first** send is slow: two independent launches run *concurrently*, so a later fan-out overtakes a
 * stalled earlier one, while one FIFO writer cannot start item *N+1* until item *N* is fully sent.
 *
 * [StallingSeam] injects exactly that: the first admit-presence frame the host sends stalls in
 * [Seam.sendTo] for a fixed span of **virtual** time. That is deterministic under
 * [StandardTestDispatcher] — no real threads, no probabilistic interleaving — and both tests below
 * fail on the pre-fix code and pass on the fixed code.
 *
 * ### What these tests do *not* cover
 *
 * They do not reproduce the *original* trigger, which needs genuine parallelism: two launches racing
 * to `sendTo` on a multi-threaded dispatcher with neither one stalling. That interleaving cannot be
 * constructed deterministically under virtual time, and a real-threaded probe would be a
 * timing-dependent test with no gate to hide behind in this module. The stall is a **deterministic
 * stand-in**: it exercises the same missing invariant (fan-out *N+1* must not reach the wire before
 * fan-out *N*) through the one interleaving virtual time can be made to produce. A fix that ordered
 * only the non-stalled case would not pass these, and a fix that reintroduced a per-call launch would
 * fail them immediately.
 *
 * They also do not cover the *other* half of #1781 — a controller's local
 * [JoinerReconnectEvent.WindowOpened] for partition episode *N* landing after episode *N+1* opened.
 * That reordering happens before `refineWindow` is reached rather than on the wire after it, and
 * closing it needs episode identity on the event (a public-API change); see `refineWindow`'s KDoc.
 */
class AdmitFanOutOrderingTest {

    /** Host-side detection: fast, so a whole partition→recovery arc fits in ~1 s of virtual time. */
    private val hostConfig = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 300.milliseconds,
        reconnectWindow = 10.seconds,
    )

    /**
     * Joiner-side detection: far longer than any advancement budget here, so the bystander can never
     * reach a conclusion about the dropped peer on its own. Everything it holds came from the host's
     * fan-out — the star property [StarTopologyPresenceFanoutTest] documents at length.
     */
    private val joinerConfig = HeartbeatConfig(
        interval = 10.seconds,
        timeout = 60.seconds,
        reconnectWindow = 60.seconds,
    )

    /**
     * The refinement must not be overtaken by the estimate it refines.
     *
     * An injected hold policy (#1614) makes the two deadlines distinguishable: `markPartitioned`
     * announces the room's [HeartbeatConfig] estimate, then `refineWindow` announces the policy's
     * sentinel. With the estimate's send stalled, the pre-fix code delivered the sentinel first and
     * the estimate second, and the bystander — which accepts a host's deadline last-writer-wins,
     * correctly, since a host may legitimately shorten a window — latched the **estimate**. Its seat
     * countdown then expired while the host still held the seat.
     */
    @Test
    fun `a stalled Paused estimate cannot be overtaken by the refinement that supersedes it`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val star = star(
                stall = 2.seconds,
                hostReconnectController = { SentinelHoldPolicy(SENTINEL_EXPIRES_AT) },
            )

            star.droppedLink.partition()
            // Past the host's detection timeout (so both fan-outs are raised) and past the stall (so
            // both have reached the wire), yet far short of the 10 s reconnect window, so the seat is
            // still held everywhere when we look.
            testScheduler.advanceTimeBy(3.seconds)
            testScheduler.runCurrent()

            val level = assertIs<Liveness.Partitioned>(
                star.bystander.roster.value.first { it.id == star.droppedId }.liveness,
                "sanity: the bystander must hold the dropped peer as Partitioned",
            )
            assertAll(
                {
                    assertEquals(
                        2,
                        star.hostSeam.pausedExpiries.size,
                        "sanity: exactly two Paused frames — the estimate and the policy's refinement " +
                            "— must have reached the wire; observed ${star.hostSeam.pausedExpiries}",
                    )
                },
                {
                    assertEquals(
                        SENTINEL_EXPIRES_AT,
                        star.hostSeam.pausedExpiries.lastOrNull(),
                        "the estimate must reach the wire BEFORE the refinement that supersedes it, so " +
                            "the refinement is last; reversed, the remote latches the estimate and " +
                            "drops a held seat early — observed ${star.hostSeam.pausedExpiries}",
                    )
                },
                {
                    assertNotEquals(
                        SENTINEL_EXPIRES_AT,
                        star.hostSeam.pausedExpiries.firstOrNull(),
                        "sanity: the first frame really is the HeartbeatConfig estimate, so the " +
                            "assertion above is about order and not a single send",
                    )
                },
                {
                    assertEquals(
                        Instant.fromEpochMilliseconds(SENTINEL_EXPIRES_AT),
                        level.windowExpiresAt,
                        "…so the bystander ends up holding the injected policy's deadline, not the " +
                            "estimate — recorded sends were ${star.hostSeam.pausedExpiries}",
                    )
                },
            )
        }

    /**
     * The unbounded sibling: an `Unpaused` must not be overtaken by the `Paused` it releases.
     *
     * `handleUnpaused` deliberately no-ops for a member it does not currently hold as partitioned, so
     * an inverted pair is not merely a transient wrong value — the `Unpaused` is *discarded*, the late
     * `Paused` then applies, and the remote holds a **recovered** member as [Liveness.Partitioned] for
     * the rest of the room's life. Nothing re-announces it: recovery fires once.
     */
    @Test
    fun `a stalled Paused cannot be overtaken by the Unpaused that releases it`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val star = star(stall = 2.seconds)

            star.droppedLink.partition()
            // Past the host's detection timeout: Paused is raised and its send stalls.
            testScheduler.advanceTimeBy(hostConfig.timeout + hostConfig.interval * 2)
            testScheduler.runCurrent()
            // Heal well inside the stall, so Unpaused is raised while Paused is still in flight.
            star.droppedLink.heal()
            testScheduler.advanceTimeBy(hostConfig.interval * 4)
            testScheduler.runCurrent()
            // Past the stall, so both frames have reached the wire and been applied.
            testScheduler.advanceTimeBy(3.seconds)
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        listOf("Paused", "Unpaused"),
                        star.hostSeam.presenceFrames,
                        "Unpaused must not overtake the Paused it releases — reversed, the remote " +
                            "discards it and the late Paused pins a recovered member forever",
                    )
                },
                {
                    assertEquals(
                        Liveness.Connected,
                        star.bystander.roster.value.first { it.id == star.droppedId }.liveness,
                        "…so the recovered peer reads Connected in the bystander's roster, not " +
                            "Partitioned — recorded sends were ${star.hostSeam.presenceFrames}",
                    )
                },
                {
                    assertEquals(
                        Liveness.Connected,
                        star.host.roster.value.first { it.id == star.droppedId }.liveness,
                        "sanity: the host itself saw the peer recover",
                    )
                },
            )
        }

    /**
     * `propagateFarewell` shares the queue too — a `Farewell` cannot overtake the `Paused` for the
     * same peer. A FIFO half the announcements bypass is not a FIFO, and this is the assertion that
     * notices if `propagateFarewell` ever grows its own `scope.launch` back.
     *
     * End state is the same either way (a `Paused` for an already-evicted peer is dropped on receipt),
     * so this pins the ordering itself rather than a corrupted roster.
     */
    @Test
    fun `a Farewell cannot overtake the Paused for the same peer`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val star = star(stall = 2.seconds, reconnectWindow = 500.milliseconds)

            star.droppedLink.partition()
            // Past detection (Paused, stalled) and past the 500 ms window (expiry → Farewell), then
            // past the stall so both have reached the wire.
            testScheduler.advanceTimeBy(4.seconds)
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        listOf("Paused", "Farewell"),
                        star.hostSeam.presenceFrames,
                        "the seat's pause must reach the wire before its expiry",
                    )
                },
                {
                    assertEquals(
                        emptyList(),
                        star.bystander.roster.value.filter { it.id == star.droppedId },
                        "sanity: the expired peer is gone from the bystander's roster",
                    )
                },
            )
        }

    // ── Harness ───────────────────────────────────────────────────────────────

    /**
     * A [Seam] decorator that stalls the host's **first** admit-presence frame in [Seam.sendTo] and
     * records the order every such frame reaches the delegate.
     *
     * "Presence frame" means [AdmitMessage.Paused] / [AdmitMessage.Unpaused] / [AdmitMessage.Farewell]
     * — the fan-outs under test. Everything else (heartbeats, the admit handshake, application frames)
     * passes through untouched, so stalling here cannot stall detection itself.
     *
     * The stall is [delay], so it consumes **virtual** time only: the host's fan-out sits inside
     * `sendTo` for exactly [stall] of scheduler time, deterministically, with no wall clock involved.
     * Recording happens *after* the stall and immediately before delegating, so the list is the order
     * frames actually reached the fabric — not the order they were raised.
     */
    private class StallingSeam(
        private val delegate: Seam,
        private val stall: Duration,
    ) : Seam {
        private val recorded = mutableListOf<AdmitMessage>()
        private var stalled = false

        /** Presence frames in the order they reached the fabric, by [AdmitMessage] subclass name. */
        val presenceFrames: List<String> get() = recorded.map { it::class.simpleName ?: "?" }

        /** `expiresAt` of every [AdmitMessage.Paused] that reached the fabric, in wire order. */
        val pausedExpiries: List<Long>
            get() = recorded.filterIsInstance<AdmitMessage.Paused>().map { it.expiresAt }

        override val selfId: PeerId get() = delegate.selfId
        override val peers: StateFlow<Set<PeerId>> get() = delegate.peers
        override val state: StateFlow<SeamState> get() = delegate.state
        override val plies: StateFlow<Map<PlyId, SeamState>> get() = delegate.plies
        override val capability: StateFlow<TransportCapability> get() = delegate.capability
        override val incoming: Flow<Swatch> get() = delegate.incoming

        override suspend fun broadcast(payload: ByteArray): Unit = delegate.broadcast(payload)

        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            val presence = AdmitMessage.decode(payload)?.takeIf { it.isPresence() }
            if (presence == null) {
                delegate.sendTo(peer, payload)
                return
            }
            // Only the FIRST presence frame stalls. A per-frame stall would delay every fan-out
            // equally and so preserve order under either implementation — the point is to stall an
            // EARLIER send while a LATER one is free to overtake it.
            if (!stalled) {
                stalled = true
                delay(stall)
            }
            recorded += presence
            delegate.sendTo(peer, payload)
        }

        override suspend fun close(reason: CloseReason): Unit = delegate.close(reason)

        private fun AdmitMessage.isPresence(): Boolean =
            this is AdmitMessage.Paused || this is AdmitMessage.Unpaused || this is AdmitMessage.Farewell
    }

    /**
     * A hold policy whose deadline is a fixed sentinel the default fixed-window controller could never
     * compute — the #1614 shape reduced to the one property under test. `replay` is non-zero for the
     * same reason [StarTopologyPresenceFanoutTest]'s copy is: the room's reconnect-event loop is a
     * separate coroutine, so a `replay = 0` emission could be discarded before it subscribes.
     */
    private class SentinelHoldPolicy(private val expiresAt: Long) : JoinerReconnectController {
        private val _events = MutableSharedFlow<JoinerReconnectEvent>(replay = 16, extraBufferCapacity = 16)
        override val events: SharedFlow<JoinerReconnectEvent> = _events.asSharedFlow()

        override fun onPeerUnresponsive(peerId: PeerId, at: Long) {
            _events.tryEmit(JoinerReconnectEvent.WindowOpened(peerId, expiresAt = expiresAt))
        }

        override suspend fun tryResume(token: ResumeToken, at: Long): ResumeResult =
            ResumeResult.WindowNotYetOpen

        override fun expire(peerId: PeerId, at: Long) = Unit
    }

    /** A three-peer star whose host sends through [hostSeam], so its fan-out order is observable. */
    private class Star(
        val host: Room,
        val hostSeam: StallingSeam,
        val droppedLink: FaultySeam,
        val droppedId: PeerId,
        val bystander: Room,
    )

    /**
     * Builds the star: a host whose seam is wrapped in a [StallingSeam], a joiner whose link is
     * faulted mid-test, and a bystander that must learn everything from the host's fan-out.
     *
     * The host is created with `adopt` rather than `host` so the wrapper can sit between the room and
     * the loom-woven seam; `adopt(_, Host)` mints the same host-side `roomId` that `host()` does.
     */
    private suspend fun TestScope.star(
        stall: Duration,
        reconnectWindow: Duration? = null,
        hostReconnectController: (() -> JoinerReconnectController)? = null,
    ): Star {
        val loom = InMemoryLoom()
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        val hostFactory = SeamRoomFactory(
            loom,
            backgroundScope,
            clock,
            reconnectWindow?.let { hostConfig.copy(reconnectWindow = it) } ?: hostConfig,
            reconnectControllerFactory = hostReconnectController?.let { build -> { _, _, _ -> build() } },
        )
        val joinerFactory = SeamRoomFactory(loom, backgroundScope, clock, joinerConfig)

        val hostSeam = StallingSeam(loom.host(Pattern("Host")), stall)
        val hostRoom = hostFactory.adopt(hostSeam, SessionRole.Host)
        val droppedLink = FaultySeam(loom.join(InMemoryTag("Dropped")), backgroundScope)
        val droppedRoom = joinerFactory.adopt(droppedLink, SessionRole.Joiner)
        val bystanderRoom = joinerFactory.join(InMemoryTag("Bystander"))

        hostRoom.roster.first { it.size == 2 }
        droppedRoom.roster.first { it.size == 2 }
        bystanderRoom.roster.first { it.size == 2 }

        return Star(hostRoom, hostSeam, droppedLink, droppedRoom.selfId, bystanderRoom)
    }

    private companion object {
        /**
         * A deadline no default controller could produce: the host's window is 10 s from a virtual
         * clock starting at 0, so 987654 ms can only have come from the injected policy.
         */
        const val SENTINEL_EXPIRES_AT = 987_654L
    }
}
