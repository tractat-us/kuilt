@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.admit.AdmitMessage
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Host-authoritative **presence** fan-out on a star/host-relayed topology (#1557).
 *
 * ### How the star is modelled
 *
 * The defining property of a star is that a joiner has **no liveness edge against another
 * joiner** — only the host watches every member. These tests reproduce that with two
 * [HeartbeatConfig]s over one shared [InMemoryLoom]: the **host** room runs [hostConfig]
 * (sub-second detection), while the **joiner** rooms run [joinerConfig], whose timeout and
 * reconnect window are an order of magnitude longer than the whole test's virtual-time
 * budget. A joiner therefore cannot reach any conclusion about another joiner on its own
 * within the window under test, exactly as it cannot in a real star. Anything a joiner
 * observes about a *peer* joiner in these tests came from the host's authoritative fan-out.
 *
 * The dropped link is injected with [FaultySeam.partition] (frames silently dropped in both
 * directions) rather than a seam close, so the underlying peer set is untouched — a silent
 * partition, not a transport tear, which is the case the reconnect window exists for.
 */
class StarTopologyPresenceFanoutTest {

    /** Host-side detection: fast, so the whole partition→expiry arc fits in ~1.5 s of virtual time. */
    private val hostConfig = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 300.milliseconds,
        reconnectWindow = 1.seconds,
    )

    /**
     * Joiner-side detection: deliberately far longer than any test's advancement budget, so a
     * joiner's own detector can never be the thing that produces an observation about a peer
     * joiner. This is what makes the star's "no joiner↔joiner heartbeat" property explicit.
     */
    private val joinerConfig = HeartbeatConfig(
        interval = 10.seconds,
        timeout = 60.seconds,
        reconnectWindow = 60.seconds,
    )

    /** Enough virtual time for the host to detect the partition and let the window expire. */
    private val expiryBudget = 2.seconds

    /**
     * A member whose reconnect window expires must be evicted from **every** member's roster,
     * not just the host's.
     *
     * `propagateFarewell` had exactly one call site — the `Goodbye` handler — so window expiry
     * propagated nothing. The documented fallback ("a lost Farewell degrades to that member's
     * heartbeat-window eviction") only holds on a mesh where every member heartbeats every
     * other; in a star a joiner has no heartbeat against another joiner, so the expired peer
     * sat in its roster forever.
     */
    @Test
    fun `a non-host member evicts a peer whose reconnect window expired`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val star = star()
            val observed = mutableListOf<MembershipEvent.Left>()
            backgroundScope.launch {
                star.bystander.events
                    .filterIsInstance<MembershipEvent.Left>()
                    .collect { if (it.peerId == star.droppedId) observed += it }
            }
            testScheduler.runCurrent()

            star.droppedLink.partition()
            testScheduler.advanceTimeBy(expiryBudget)
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        emptyList(),
                        star.host.roster.value.filter { it.id == star.droppedId },
                        "sanity: the host's own reconnect window must have expired the seat",
                    )
                },
                {
                    assertEquals(
                        1,
                        observed.size,
                        "a peer whose reconnect window expired must be evicted from a non-host " +
                            "member's roster too — observed $observed",
                    )
                },
                {
                    assertEquals(
                        LeaveReason.PartitionExpired,
                        observed.firstOrNull()?.reason,
                        "an expired seat is not a clean leave — the propagated eviction must " +
                            "keep the PartitionExpired reason",
                    )
                },
            )
            assertEquals(
                emptyList(),
                star.bystander.roster.value.filter { it.id == star.droppedId },
                "the expired peer must be gone from the bystander's roster",
            )
        }

    /**
     * A paused peer must be visible as paused to **every** member, not just the host.
     *
     * The host detects the drop locally and holds the seat; the bystander has no heartbeat
     * edge to the dropped joiner, so without the host's [us.tractat.kuilt.session.admit.AdmitMessage.Paused]
     * fan-out it sees the peer as `Connected` right up until it vanishes.
     */
    @Test
    fun `a non-host member sees Partitioned and WindowOpened for a paused peer`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val star = star()
            val partitioned = mutableListOf<MembershipEvent.Partitioned>()
            val windows = mutableListOf<MembershipEvent.WindowOpened>()
            backgroundScope.launch {
                star.bystander.events.collect { event ->
                    when (event) {
                        is MembershipEvent.Partitioned -> if (event.peerId == star.droppedId) partitioned += event
                        is MembershipEvent.WindowOpened -> if (event.peerId == star.droppedId) windows += event
                        else -> Unit
                    }
                }
            }
            testScheduler.runCurrent()

            star.droppedLink.partition()
            // Past the host's detection timeout, but well short of its reconnect window, so the
            // seat is still held when we look.
            testScheduler.advanceTimeBy(hostConfig.timeout + hostConfig.interval * 2)
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        1,
                        partitioned.size,
                        "a non-host member must see the paused peer as Partitioned — observed $partitioned",
                    )
                },
                {
                    assertEquals(
                        1,
                        windows.size,
                        "…and must see the reconnect window that holds its seat — observed $windows",
                    )
                },
                {
                    assertIs<Liveness.Partitioned>(
                        star.bystander.roster.value.first { it.id == star.droppedId }.liveness,
                        "the roster entry must read Partitioned, not Connected",
                    )
                },
            )
        }

    /**
     * Mesh idempotency: a peer that detects the drop with its **own** detector *and* receives
     * the host's fan-out for the same peer must emit one [MembershipEvent.Partitioned], not two.
     *
     * Unlike the star tests above, every room here runs [hostConfig], so the bystander's own
     * detector fires inside the window — the double-source case the fan-out introduces.
     */
    @Test
    fun `a mesh member that both detects locally and receives Paused emits once`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val loom = InMemoryLoom()
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
            val factory = SeamRoomFactory(loom, backgroundScope, clock, hostConfig)

            val hostRoom = factory.host(Pattern("Host"))
            val droppedLink = FaultySeam(loom.join(InMemoryTag("Dropped")), backgroundScope)
            val droppedRoom = factory.adopt(droppedLink, SessionRole.Joiner)
            val bystander = factory.join(InMemoryTag("Bystander"))

            hostRoom.roster.first { it.size == 2 }
            droppedRoom.roster.first { it.size == 2 }
            bystander.roster.first { it.size == 2 }

            val droppedId = droppedRoom.selfId
            val partitioned = mutableListOf<MembershipEvent.Partitioned>()
            backgroundScope.launch {
                bystander.events
                    .filterIsInstance<MembershipEvent.Partitioned>()
                    .collect { if (it.peerId == droppedId) partitioned += it }
            }
            testScheduler.runCurrent()

            droppedLink.partition()
            // Long enough for both sources to have fired: the bystander's own detector and the
            // host's Paused fan-out. Still short of the reconnect window, so no eviction yet.
            testScheduler.advanceTimeBy(hostConfig.timeout + hostConfig.interval * 4)
            testScheduler.runCurrent()

            assertEquals(
                1,
                partitioned.size,
                "local detection and the host's Paused fan-out must coalesce into one " +
                    "Partitioned event — observed $partitioned",
            )
        }

    /**
     * An **injected** hold policy's deadline must reach remote members too, not just the host's own
     * roster and events.
     *
     * `propagatePaused` used to have a single call site — `markPartitioned`, carrying the room's
     * [HeartbeatConfig]-derived estimate. A host running a custom [JoinerReconnectController] (#1614)
     * — the whole point of which is a deadline unrelated to `at + reconnectWindow` — therefore
     * corrected its *own* level and events and sent nothing, so every other member's roster held
     * `at + reconnectWindow` forever with no way to learn better. That is the same
     * roster-contradicts-roster failure #1724 fixes locally, one hop out.
     *
     * The star makes the assertion clean: the bystander has no heartbeat edge to the dropped joiner
     * (see the class KDoc), so **every** deadline it holds arrived from the host. Two announcements
     * are expected and ordered — the host's inline estimate, then the policy's authoritative
     * sentinel, which the default controller could never produce.
     */
    @Test
    fun `an injected hold policy's deadline reaches a non-host member`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val sentinel = Instant.fromEpochMilliseconds(SENTINEL_EXPIRES_AT)
            val star = star(hostReconnectController = { SentinelHoldPolicy(SENTINEL_EXPIRES_AT) })
            val windows = mutableListOf<MembershipEvent.WindowOpened>()
            backgroundScope.launch {
                star.bystander.events
                    .filterIsInstance<MembershipEvent.WindowOpened>()
                    .collect { if (it.peerId == star.droppedId) windows += it }
            }
            testScheduler.runCurrent()

            star.droppedLink.partition()
            // Past the host's detection timeout, well short of its 1 s reconnect window, so the
            // dropped seat is still in every roster when we look.
            testScheduler.advanceTimeBy(hostConfig.timeout + hostConfig.interval * 2)
            testScheduler.runCurrent()

            val level = assertIs<Liveness.Partitioned>(
                star.bystander.roster.value.first { it.id == star.droppedId }.liveness,
                "sanity: the bystander must hold the dropped peer as Partitioned",
            )
            assertAll(
                {
                    assertEquals(
                        2,
                        windows.size,
                        "the bystander must hear the host's inline estimate and then the injected " +
                            "policy's refined deadline — observed $windows",
                    )
                },
                {
                    assertEquals(
                        sentinel,
                        windows.lastOrNull()?.expiresAt,
                        "refineWindow must re-fan the authoritative Paused; without it an injected " +
                            "policy's deadline never leaves the host — observed $windows",
                    )
                },
                {
                    assertTrue(
                        windows.firstOrNull()?.expiresAt?.let { it != sentinel } ?: false,
                        "sanity: the first announcement is the host's HeartbeatConfig estimate, so " +
                            "the second really is a refinement — observed $windows",
                    )
                },
                {
                    assertEquals(
                        sentinel,
                        level.windowExpiresAt,
                        "…and the remote roster must agree with the remote announcement",
                    )
                },
            )
        }

    /**
     * A member that gets back in by **resuming** must read as recovered to every other member (#2908).
     *
     * This is the order that used to strand it: the `Resume` frame reaches the host before any ping
     * has come back, so `handleReconnectResumed` is what restores `Connected`. That path set the level
     * and told nobody. By the time the detector's `PeerRecovered` arrived the member was already
     * `Connected`, so `markRecovered`, the only other sender of `Unpaused`, did nothing either, and a
     * star's other members held a pause whose deadline had passed for the rest of the session.
     *
     * [partitionUntilPaused] parks the clock between host pings so the order is forced rather than
     * hoped for, and the assertions taken before the clock moves prove it was reached.
     */
    @Test
    fun `a resume that beats the detector still tells the other members`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val star = star()
            val seen = star.watchEvents(this)
            val token = star.partitionUntilPaused(this)

            star.droppedLink.heal()
            val resume = async { star.dropped.resume(token) }
            testScheduler.runCurrent()

            assertAll(
                { assertIs<ResumeResult.Success>(resume.getCompleted(), "rig: the resume landed at this instant") },
                { assertEquals(1, seen.host.count { it is MembershipEvent.Resumed }, "rig: the host resumed it") },
                { assertEquals(0, seen.host.count { it is MembershipEvent.Recovered }, "rig: no pong has come back") },
            )

            // Long enough for the detector's own PeerRecovered, so a second Unpaused would show up here.
            // A window left armed would not: handleWindowExpired refuses to expire a Connected member.
            testScheduler.advanceTimeBy(expiryBudget)
            testScheduler.runCurrent()

            star.assertOneRecoveryReachedBystander(seen, hostEvents = listOf(MembershipEvent.Resumed::class))
        }

    /**
     * The other arrival order: the detector hears a pong first, `markRecovered` sends the `Unpaused`,
     * and the `Resume` frame lands on a member that is already `Connected`. The controller still
     * answers `Success`, because a healed window stays resumable (#2556). The resume path must add
     * nothing to what the bystander already heard, so this is the arm that catches a double send: the
     * wire count sees it, where [MembershipEvent.Recovered] alone could not, since `handleUnpaused`
     * drops a repeat without trace.
     */
    @Test
    fun `a resume that follows the detector does not announce it twice`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val star = star()
            val seen = star.watchEvents(this)
            val token = star.partitionUntilPaused(this)

            // One interval for the detector's next ping to draw a pong, one for its next poll to see it.
            star.droppedLink.heal()
            testScheduler.advanceTimeBy(hostConfig.interval * 2)
            testScheduler.runCurrent()

            assertAll(
                { assertEquals(1, seen.host.count { it is MembershipEvent.Recovered }, "rig: the pong came first") },
                { assertEquals(1, star.unpausedSeenByBystander(), "rig: markRecovered announced it") },
            )

            val resume = async { star.dropped.resume(token) }
            testScheduler.runCurrent()
            assertIs<ResumeResult.Success>(resume.getCompleted(), "rig: a healed window still answers Success")

            testScheduler.advanceTimeBy(expiryBudget)
            testScheduler.runCurrent()

            star.assertOneRecoveryReachedBystander(
                seen,
                hostEvents = listOf(MembershipEvent.Recovered::class, MembershipEvent.Resumed::class),
            )
        }

    /** Control for the two arms above: the same drop and heal with no resume at all. */
    @Test
    fun `a member that recovers without resuming tells the other members once`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val star = star()
            val seen = star.watchEvents(this)
            star.partitionUntilPaused(this)

            star.droppedLink.heal()
            testScheduler.advanceTimeBy(expiryBudget)
            testScheduler.runCurrent()

            star.assertOneRecoveryReachedBystander(seen, hostEvents = listOf(MembershipEvent.Recovered::class))
        }

    // ── Harness ───────────────────────────────────────────────────────────────

    /** The recovery-shaped events each side emitted about the dropped member, in order. */
    private class SeenEvents(
        val host: MutableList<MembershipEvent> = mutableListOf(),
        val bystander: MutableList<MembershipEvent> = mutableListOf(),
    )

    private fun Star.watchEvents(test: kotlinx.coroutines.test.TestScope): SeenEvents {
        val seen = SeenEvents()
        fun collectInto(room: Room, into: MutableList<MembershipEvent>) = test.backgroundScope.launch {
            room.events.collect { event ->
                when (event) {
                    is MembershipEvent.Recovered -> if (event.peerId == droppedId) into += event
                    is MembershipEvent.Resumed -> if (event.peerId == droppedId) into += event
                    else -> Unit
                }
            }
        }
        collectInto(host, seen.host)
        collectInto(bystander, seen.bystander)
        test.testScheduler.runCurrent()
        return seen
    }

    /**
     * Drops the link until the bystander holds the member as paused, and leaves the clock **between**
     * the host detector's polls, which fall on multiples of [HeartbeatConfig.interval]: a frame sent
     * at this instant reaches the host before the detector can conclude anything from it.
     */
    private fun Star.partitionUntilPaused(test: kotlinx.coroutines.test.TestScope): ResumeToken {
        val token = assertNotNull(dropped.resumeToken, "the joiner must hold a resume token after admit")
        droppedLink.partition()
        test.testScheduler.advanceTimeBy(hostConfig.timeout + hostConfig.interval * 2 + hostConfig.interval / 2)
        test.testScheduler.runCurrent()
        assertIs<Liveness.Partitioned>(
            bystander.roster.value.first { it.id == droppedId }.liveness,
            "rig: the bystander must hold the dropped member as paused before it comes back",
        )
        return token
    }

    private fun Star.unpausedSeenByBystander(): Int =
        bystanderWire.frames.count { it is AdmitMessage.Unpaused && it.peerId == droppedId.value }

    /**
     * The whole contract, stated once for every arm. The wire count is what tells a missed or doubled
     * `Unpaused` apart. [MembershipEvent.Resumed] is specified for the host and the resuming member
     * only, so a bystander must hear [MembershipEvent.Recovered] instead.
     */
    private fun Star.assertOneRecoveryReachedBystander(
        seen: SeenEvents,
        hostEvents: List<kotlin.reflect.KClass<out MembershipEvent>>,
    ) = assertAll(
        { assertEquals(hostEvents, seen.host.map { it::class }, "the host's own events, in order") },
        { assertEquals(1, unpausedSeenByBystander(), "exactly one Unpaused must reach the other member") },
        {
            assertEquals(
                listOf<kotlin.reflect.KClass<out MembershipEvent>>(MembershipEvent.Recovered::class),
                seen.bystander.map { it::class },
                "the other member must hear Recovered, once, and never Resumed",
            )
        },
        {
            assertEquals(
                Liveness.Connected,
                bystander.roster.value.first { it.id == droppedId }.liveness,
                "the other member's roster must read Connected",
            )
        },
        {
            assertEquals(
                Liveness.Connected,
                host.roster.value.first { it.id == droppedId }.liveness,
                "the host's roster must read Connected",
            )
        },
    )

    /** Records every admit frame reaching a member, which its roster and events cannot show a repeat of. */
    private class AdmitWireTap(private val delegate: Seam) : Seam by delegate {
        val frames = mutableListOf<AdmitMessage>()

        override val incoming: Flow<Swatch> = delegate.incoming.onEach { swatch ->
            AdmitMessage.decode(swatch.toByteArray())?.let { frames += it }
        }
    }

    /**
     * A [JoinerReconnectController] whose hold policy is "until [expiresAt], whatever the room's
     * [HeartbeatConfig] says" — the shape of an unbounded/predicate hold (#1614), reduced to the one
     * property under test: a deadline the default fixed-window controller could never compute.
     *
     * `replay` is deliberately non-zero: the room's reconnect-event loop is a separate coroutine, so
     * a `replay = 0` emission from `onPeerUnresponsive` could be discarded before it subscribes
     * (#1618 Drop B) and the test would pass or fail on scheduling luck.
     */
    private class SentinelHoldPolicy(private val expiresAt: Long) : JoinerReconnectController {
        private val _events = MutableSharedFlow<JoinerReconnectEvent>(replay = 16, extraBufferCapacity = 16)
        override val events: SharedFlow<JoinerReconnectEvent> = _events.asSharedFlow()

        override fun onPeerUnresponsive(peerId: us.tractat.kuilt.core.PeerId, at: Long) {
            _events.tryEmit(
                JoinerReconnectEvent.WindowOpened(peerId, expiresAt = expiresAt, detectedAt = at),
            )
        }

        override suspend fun tryResume(token: ResumeToken, at: Long): ResumeResult.HostVerdict =
            ResumeResult.WindowNotYetOpen

        // Honest rather than a stub: this policy owns no timer and never emits a `WindowExpired`,
        // so it already satisfies `onPeerRecovered`'s only obligation — emit no expiry for the
        // episode this closes — by doing nothing. A fake that *did* arm one would cancel it here.
        override fun onPeerRecovered(peerId: us.tractat.kuilt.core.PeerId, at: Long) = Unit

        override fun expire(peerId: us.tractat.kuilt.core.PeerId, at: Long) = Unit
    }

    /**
     * A three-peer star: a host, a [droppedLink] joiner whose link is faulted mid-test, and a
     * [bystander] joiner that must learn about it from the host alone.
     */
    private class Star(
        val host: Room,
        val droppedLink: FaultySeam,
        val dropped: Room,
        val droppedId: PeerId,
        val bystander: Room,
        val bystanderWire: AdmitWireTap,
    )

    /**
     * [hostReconnectController] overrides the **host's** hold policy (#1614); null keeps the default
     * fixed-window controller, which is what every test but the injection one wants.
     */
    private suspend fun kotlinx.coroutines.test.TestScope.star(
        hostReconnectController: (() -> JoinerReconnectController)? = null,
    ): Star {
        val loom = InMemoryLoom()
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        val hostFactory = SeamRoomFactory(
            loom,
            backgroundScope,
            clock,
            hostConfig,
            reconnectControllerFactory = hostReconnectController?.let { build -> { _, _, _ -> build() } },
        )
        val joinerFactory = SeamRoomFactory(loom, backgroundScope, clock, joinerConfig)

        val hostRoom = hostFactory.host(Pattern("Host"))
        val droppedLink = FaultySeam(loom.join(InMemoryTag("Dropped")), backgroundScope)
        val droppedRoom = joinerFactory.adopt(droppedLink, SessionRole.Joiner)
        val bystanderWire = AdmitWireTap(loom.join(InMemoryTag("Bystander")))
        val bystanderRoom = joinerFactory.adopt(bystanderWire, SessionRole.Joiner)

        hostRoom.roster.first { it.size == 2 }
        droppedRoom.roster.first { it.size == 2 }
        bystanderRoom.roster.first { it.size == 2 }

        return Star(hostRoom, droppedLink, droppedRoom, droppedRoom.selfId, bystanderRoom, bystanderWire)
    }

    private companion object {
        /**
         * A deadline no default controller could produce here: the host's window is 1 s from a
         * virtual clock starting at 0, so 123456 ms can only have come from the injected policy.
         */
        const val SENTINEL_EXPIRES_AT = 123_456L
    }
}
