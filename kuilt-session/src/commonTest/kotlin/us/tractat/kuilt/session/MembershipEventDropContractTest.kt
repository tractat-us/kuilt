@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.test.FaultySeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * **The executable contract** a consumer's presence reducer builds against: the exact, ordered
 * [MembershipEvent] stream **both** peers see when one of them silently drops off the network
 * (#1618 — a phone going to airplane mode / losing Wi-Fi).
 *
 * ### Why this exists as a pin, not just another partition test
 *
 * Two-phone hardware validation settled the lane: a real Wi-Fi drop is **silence**, so it arrives
 * as a heartbeat `Timeout` ([ReconnectReason.LinkTimeout]) — *not* a transport close. The seam
 * stays `Woven` and `peers` is untouched; only pong-silence catches it. Every assertion here is
 * injected on that lane ([FaultySeam.partition] drops frames both ways, leaving `state`/`peers`
 * alone), so a downstream reducer written against this file is written against the case that
 * actually happens on a phone.
 *
 * Existing coverage is one-sided: [MeshRoomPartitionTest] / [MeshRoomRecoveryTest] pin the **host**
 * arc, [JoinerHostTimeoutRecoveryTest] pins the **joiner** arc. Neither pins the *pair* — and the
 * pair is what a consumer renders (a seat greys out on one device while a "reconnecting…" banner
 * shows on the other). This test asserts both event streams from one injected drop.
 *
 * ### What is load-bearing
 *
 * The **type + subject-peer sequence** (`hostArc` / `joinerArc` below) is the contract; branch on
 * it. [MembershipEvent.Partitioned.reason] and friends are asserted **separately and secondarily**
 * — they are documentation of today's behavior, and at least one of them is known dishonest
 * (see the `TransportClosed` note on the joiner arc below).
 *
 * ### The pinned arcs
 *
 * ```text
 * sustained drop     host:    Partitioned(joiner) → WindowOpened(joiner) → Left(joiner)
 *                    joiner:  Partitioned(host)   → WindowOpened(host)   → HostLost
 *
 * heals in-window    host:    Partitioned(joiner) → WindowOpened(joiner) → Recovered(joiner)
 *                    joiner:  Partitioned(host)   → WindowOpened(host)   → Recovered(host)
 * ```
 *
 * The arcs are now **symmetric in the window** (#1724): `WindowOpened` is emitted inline by
 * `SeamRoom.markPartitioned`, which is role-agnostic, so a joiner is handed its host's grace
 * deadline instead of having to derive one. It previously came only from the
 * [us.tractat.kuilt.session.partition.JoinerReconnectController], which `SeamRoom` constructs
 * **host-only**, so on this lane the joiner got nothing at all and a joiner-side UI had to compute
 * `Partitioned.at + HeartbeatConfig.reconnectWindow` for itself.
 *
 * One asymmetry remains, pinned deliberately because a consumer that assumes symmetry gets it wrong:
 *
 * 1. **The healed peer emits [MembershipEvent.Recovered], never [MembershipEvent.Resumed].**
 *    `Resumed` is the *resume-handshake* outcome (token presented, `ResumeAck` returned). Nothing
 *    tears on this lane, so nothing resumes — the seam simply starts carrying pongs again and both
 *    detectors report `PeerRecovered`. A reducer that waits for `Resumed` to clear a "reconnecting…"
 *    banner will hang on the exact case the banner exists for.
 */
class MembershipEventDropContractTest {

    /**
     * Fast detection with a **generous** window (2 s) so the heal arc completes comfortably inside
     * it without racing eviction, and the sustained arc still expires within a few advances.
     */
    private val config = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 300.milliseconds,
        reconnectWindow = 2.seconds,
    )

    // ── Harness ──────────────────────────────────────────────────────────────

    /**
     * A host room and a joiner room over one in-memory mesh, with the joiner's link wrapped in a
     * [FaultySeam] so a drop can be injected **without** tearing the seam.
     *
     * [hostEvents] / [joinerEvents] are live transcripts, armed and cleared once the admit
     * handshake has settled — so what they hold is exactly the presence arc under test.
     */
    private class Drop(
        val hostId: PeerId,
        val joinerId: PeerId,
        val joinerLink: FaultySeam,
        val hostEvents: List<MembershipEvent>,
        val joinerEvents: List<MembershipEvent>,
        private val leaveAll: suspend () -> Unit,
    ) {
        private val names get() = mapOf(hostId to "host", joinerId to "joiner")

        /** The host's transcript as a type+subject-peer sequence — the load-bearing shape. */
        val hostArc: List<String> get() = hostEvents.map { it.arc(names) }

        /** The joiner's transcript as a type+subject-peer sequence — the load-bearing shape. */
        val joinerArc: List<String> get() = joinerEvents.map { it.arc(names) }

        suspend fun leave() = leaveAll()
    }

    private suspend fun TestScope.drop(config: HeartbeatConfig): Drop {
        val loom = InMemoryLoom()
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        val factory = SeamRoomFactory(loom, backgroundScope, clock, config)

        val hostRoom = factory.host(Pattern("Table"))
        // Wrap the joiner's live seam so heartbeats can be dropped WITHOUT tearing it: state stays
        // Woven and peers intact, so both detectors fire Timeout, not TransportClosed.
        val joinerLink = FaultySeam(loom.join(InMemoryTag("Table")), backgroundScope)
        val joinerRoom = factory.adopt(joinerLink, SessionRole.Joiner)

        // Rosters exclude self, so one entry each: the host holds the joiner, the joiner the host.
        hostRoom.roster.first { it.size == 1 }
        joinerRoom.roster.first { it.size == 1 }

        val hostEvents = mutableListOf<MembershipEvent>()
        val joinerEvents = mutableListOf<MembershipEvent>()
        backgroundScope.launch { hostRoom.events.collect { hostEvents += it } }
        backgroundScope.launch { joinerRoom.events.collect { joinerEvents += it } }
        testScheduler.runCurrent()
        // Room.events replays, so both collectors have now drained the admit-phase Joined events.
        // Discard them: this pin is about the arc that follows the drop.
        hostEvents.clear()
        joinerEvents.clear()

        return Drop(
            hostId = hostRoom.selfId,
            joinerId = joinerRoom.selfId,
            joinerLink = joinerLink,
            hostEvents = hostEvents,
            joinerEvents = joinerEvents,
            leaveAll = { hostRoom.leave(); joinerRoom.leave() },
        )
    }

    // ── The pinned arcs ──────────────────────────────────────────────────────

    /**
     * **Sustained drop.** The joiner never comes back; the host's held seat expires.
     *
     * Pins the host's `Partitioned → WindowOpened → Left` against the joiner's
     * `Partitioned → WindowOpened → HostLost` — including, mid-flight, that the joiner's window
     * pairs with the host's instead of being absent (#1724).
     */
    @Test
    fun `a sustained silent drop pins both sides of the presence arc`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val d = drop(config)

            // Airplane mode: frames vanish both ways, seam untouched.
            d.joinerLink.partition()

            // Detection only — past the heartbeat timeout, well short of the 2 s window.
            testScheduler.advanceTimeBy(config.timeout + config.interval * 3)
            testScheduler.runCurrent()
            val hostAtDetection = d.hostArc
            val joinerAtDetection = d.joinerArc

            // Expiry — past the reconnect window with margin.
            testScheduler.advanceTimeBy(config.reconnectWindow + config.interval * 4)
            testScheduler.runCurrent()

            val partitionedOnHost = d.hostEvents.filterIsInstance<MembershipEvent.Partitioned>().firstOrNull()
            val windowOnHost = d.hostEvents.filterIsInstance<MembershipEvent.WindowOpened>().firstOrNull()

            assertAll(
                // ── Load-bearing: type + subject-peer, in order ──────────────────
                {
                    assertEquals(
                        listOf("Partitioned(joiner)", "WindowOpened(joiner)"),
                        hostAtDetection,
                        "the host must announce the drop and open the seat's window, in that order",
                    )
                },
                {
                    assertEquals(
                        listOf("Partitioned(host)", "WindowOpened(host)"),
                        joinerAtDetection,
                        "the joiner must announce the drop AND the grace window on the Timeout lane " +
                            "too — markPartitioned is role-agnostic, so it no longer has to derive its " +
                            "own deadline from Partitioned.at + reconnectWindow (#1724)",
                    )
                },
                {
                    assertEquals(
                        listOf("Partitioned(joiner)", "WindowOpened(joiner)", "Left(joiner)"),
                        d.hostArc,
                        "sustained silence must terminate the held seat with Left on the host",
                    )
                },
                {
                    assertEquals(
                        listOf("Partitioned(host)", "WindowOpened(host)", "HostLost"),
                        d.joinerArc,
                        "sustained silence must terminate the joiner with HostLost — and no Left for " +
                            "the host peer",
                    )
                },
                // ── Secondary: reasons. Documented, not the branch surface. ──────
                {
                    assertEquals(
                        ReconnectReason.LinkTimeout,
                        partitionedOnHost?.reason,
                        "a silent drop is LinkTimeout on the host — the hardware-confirmed lane",
                    )
                },
                {
                    assertEquals(
                        ReconnectReason.LinkTimeout,
                        d.joinerEvents.filterIsInstance<MembershipEvent.Partitioned>().firstOrNull()?.reason,
                        "the joiner's own Timeout-lane Partitioned is honest: LinkTimeout. (The dishonest " +
                            "hardcoded TransportClosed of #1635 / Track B-3 lives on JoinerResumeMachine's " +
                            "onReconnectStarted — the transport-close lane — and is NOT reached here.)",
                    )
                },
                {
                    assertEquals(
                        LeaveReason.PartitionExpired,
                        d.hostEvents.filterIsInstance<MembershipEvent.Left>().firstOrNull()?.reason,
                        "an expired seat is not a clean leave",
                    )
                },
                {
                    assertEquals(
                        FailureReason.WindowExpired,
                        d.joinerEvents.filterIsInstance<MembershipEvent.HostLost>().firstOrNull()?.reason,
                        "the joiner's terminal reason is the honest window elapse",
                    )
                },
                // ── The #1712 precedence tag, on the lane a consumer actually meets ──
                {
                    assertIs<FabricAvailability.Unknown>(
                        partitionedOnHost?.localFabric,
                        "an in-memory fabric has no OS path observer, so it cannot say whether OUR " +
                            "own end was up — the tag must read Unknown, never a fabricated Available",
                    )
                },
                {
                    assertIs<FabricAvailability.Unknown>(
                        d.joinerEvents.filterIsInstance<MembershipEvent.HostLost>().firstOrNull()?.localFabric,
                        "…and the same on the joiner's terminal HostLost: a consumer must branch on " +
                            "Unknown as a first-class third answer, since it is what every fabric " +
                            "without a live path observer reports today",
                    )
                },
                // ── The window deadline a host-side UI counts down to ────────────
                {
                    assertEquals(
                        partitionedOnHost?.at?.plus(config.reconnectWindow),
                        windowOnHost?.expiresAt,
                        "WindowOpened.expiresAt must be Partitioned.at + HeartbeatConfig.reconnectWindow",
                    )
                },
            )

            d.leave()
        }

    /**
     * **Transient drop.** The link heals inside the window.
     *
     * Pins that both sides converge on [MembershipEvent.Recovered] — **not**
     * [MembershipEvent.Resumed], which this lane never reaches — and that the host's already-open
     * window expiring afterwards does **not** retroactively evict the recovered member.
     */
    @Test
    fun `a silent drop that heals in-window recovers on both sides and never resumes`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val d = drop(config)

            d.joinerLink.partition()
            testScheduler.advanceTimeBy(config.timeout + config.interval * 3)
            testScheduler.runCurrent()
            val hostAtDetection = d.hostArc
            val joinerAtDetection = d.joinerArc

            // Heal well inside the 2 s window; let ping/pong resume on both edges.
            d.joinerLink.heal()
            testScheduler.advanceTimeBy(config.interval * 6)
            testScheduler.runCurrent()
            val hostAfterHeal = d.hostArc
            val joinerAfterHeal = d.joinerArc

            // The host's window is an independent timer that keeps running after Recovered.
            // Advance past it: the recovered member must NOT be retroactively evicted (#1618 Track C
            // backstop is gated on the member still being Partitioned).
            testScheduler.advanceTimeBy(config.reconnectWindow + config.interval * 4)
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        listOf("Partitioned(joiner)", "WindowOpened(joiner)"),
                        hostAtDetection,
                        "sanity: the host must first see the drop",
                    )
                },
                {
                    assertEquals(
                        listOf("Partitioned(host)", "WindowOpened(host)"),
                        joinerAtDetection,
                        "sanity: the joiner must first see the drop and its grace window",
                    )
                },
                {
                    assertEquals(
                        listOf("Partitioned(joiner)", "WindowOpened(joiner)", "Recovered(joiner)"),
                        hostAfterHeal,
                        "an in-window heal clears the host's seat with Recovered — never Resumed, which " +
                            "belongs to the resume handshake this lane never performs",
                    )
                },
                {
                    assertEquals(
                        listOf("Partitioned(host)", "WindowOpened(host)", "Recovered(host)"),
                        joinerAfterHeal,
                        "an in-window heal clears the joiner's banner with Recovered — never Resumed",
                    )
                },
                {
                    assertEquals(
                        hostAfterHeal,
                        d.hostArc,
                        "the host's window expiring after a recovery must not evict the recovered member",
                    )
                },
                {
                    assertEquals(
                        joinerAfterHeal,
                        d.joinerArc,
                        "a recovered joiner must not fall to HostLost when the original window elapses",
                    )
                },
            )

            d.leave()
        }
}

/**
 * Renders a [MembershipEvent] as `Type(subjectPeer)` — the type-plus-subject shape a consumer
 * branches on, deliberately **excluding** reasons and timestamps so the load-bearing assertions
 * stay independent of the secondary, reason-level ones.
 */
private fun MembershipEvent.arc(names: Map<PeerId, String>): String {
    fun name(id: PeerId) = names[id] ?: id.value
    return when (this) {
        is MembershipEvent.Joined -> "Joined(${name(member.id)})"
        is MembershipEvent.Left -> "Left(${name(peerId)})"
        is MembershipEvent.Partitioned -> "Partitioned(${name(peerId)})"
        is MembershipEvent.Recovered -> "Recovered(${name(peerId)})"
        is MembershipEvent.WindowOpened -> "WindowOpened(${name(peerId)})"
        is MembershipEvent.Resumed -> "Resumed(${name(peerId)})"
        is MembershipEvent.HostLost -> "HostLost"
        is MembershipEvent.AdmissionFailed -> "AdmissionFailed"
        // Self-attributed: the subject is this peer's own end of the fabric, never another peer.
        is MembershipEvent.LocalFabricLost -> "LocalFabricLost"
        is MembershipEvent.LocalFabricRestored -> "LocalFabricRestored"
    }
}
