package us.tractat.kuilt.conformance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.Direction
import us.tractat.kuilt.core.FaultProfile
import us.tractat.kuilt.core.FaultyLoom
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.session.LeaveReason
import us.tractat.kuilt.session.Liveness
import us.tractat.kuilt.session.Member
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.session.RoomFactory
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.SessionRole
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.partition.ResumeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Reusable contract test suite for [RoomFactory] implementations.
 *
 * Subclass and implement [newHarness] to bind any [RoomFactory] under test.
 * Every [Test] encodes a required invariant of the Room lifecycle state machine.
 *
 * Lives in `commonMain` of `:kuilt-conformance` (not a module's `commonTest`)
 * so every [RoomFactory] adapter can subclass it from its own test source set.
 *
 * **Virtual time convention:** all partition tests advance in 100 ms steps using
 * [fastHeartbeatConfig] (interval=100ms, timeout=200ms, reconnectWindow=500ms):
 *  - 4 × 100 ms → [MembershipEvent.Partitioned] fires.
 *  - 9 × 100 ms → past reconnect window → PeerLost / [MembershipEvent.HostLost].
 *
 * **Scope contract:** [newHarness] receives the test's [CoroutineScope] (typically
 * `backgroundScope` from [runTest]) so [FaultyLoom] and [SeamRoomFactory] are
 * correctly structured under the test's virtual-time scheduler.
 *
 * **Fault injection:** tests that require partition behaviour check
 * [RoomHarness.faultyLoom] — when null the test is skipped so subclasses backed
 * by fabrics that do not support fault injection still pass the suite.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public abstract class RoomConformanceSuite {

    /**
     * Fast heartbeat config shared by all tests so virtual-time advancement is cheap.
     * Advancing 4 × 100 ms triggers [MembershipEvent.Partitioned];
     * advancing 9 × 100 ms exhausts the reconnect window (PeerLost / HostLost).
     */
    public val fastHeartbeatConfig: HeartbeatConfig = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 200.milliseconds,
        reconnectWindow = 500.milliseconds,
    )

    /**
     * A harness that bundles host and joiner [RoomFactory]s plus optional
     * [FaultyLoom] for partition tests.
     *
     * [faultyLoom] wraps the same [InMemoryLoom] that both factories use.
     * [setFaultProfileOnAll][FaultyLoom.setFaultProfileOnAll] partitions all links atomically.
     * When null the suite treats this fabric as non-faultable and skips partition tests.
     *
     * Per-side fault injection: access individual [FaultySeam] instances via
     * [faultyLoom.links][FaultyLoom.links] — index 0 is the host's seam (created by
     * the first `host()` call), index 1 is the joiner's seam (created by `join()`).
     *
     * [clock] and [advanceClock] are shared across the harness so the injected
     * clock stays in sync with virtual-time advancement.
     */
    public data class RoomHarness(
        val hostFactory: RoomFactory,
        val joinerFactory: RoomFactory,
        val faultyLoom: FaultyLoom?,
        val clock: () -> Instant,
        val advanceClock: (Long) -> Unit,
    )

    /**
     * Provide a fresh [RoomHarness] for one test, using [scope] as the coroutine
     * scope for background loops ([SeamRoomFactory], [FaultyLoom]).
     *
     * The default implementation returns an [InMemoryLoom]-backed harness wrapped in
     * a [FaultyLoom] so partition tests work out of the box. Subclasses backed by
     * a different fabric should override this method.
     */
    public open fun newHarness(scope: CoroutineScope): RoomHarness = defaultHarness(scope)

    // ── (1) host → role = Host, selfId is non-blank ──────────────────────────

    @Test
    public fun hostFactoryAssignsHostRole(): TestResult =
        runTest {
            val h = newHarness(backgroundScope)
            val room = h.hostFactory.host(Pattern("Alice"))
            assertEquals(SessionRole.Host, room.role.value, "host() must produce SessionRole.Host")
            assertTrue(room.selfId.value.isNotBlank(), "selfId must be non-blank")
            room.leave()
        }

    // ── (2) join → role = Joiner; both peers admitted ────────────────────────

    @Test
    public fun joinFactoryAssignsJoinerRole(): TestResult =
        runTest {
            val h = newHarness(backgroundScope)
            h.hostFactory.host(Pattern("Alice"))
            val joiner = h.joinerFactory.join(InMemoryTag("Bob"))
            assertEquals(SessionRole.Joiner, joiner.role.value, "join() must produce SessionRole.Joiner")
            joiner.leave()
        }

    // ── (3) roster is empty before any admit handshake ───────────────────────

    @Test
    public fun rosterIsEmptyBeforeAnyHandshake(): TestResult =
        runTest {
            val h = newHarness(backgroundScope)
            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            assertEquals(
                emptySet<Member>(),
                hostRoom.roster.value,
                "roster must be empty before any peer completes the admit handshake",
            )
            hostRoom.leave()
        }

    // ── (4) broadcast → RoomFrame tagged with admitted-member sender ─────────

    @Test
    public fun broadcastDeliversRoomFrameTaggedWithSender(): TestResult =
        runTest {
            val h = newHarness(backgroundScope)
            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Bob"))

            hostRoom.roster.first { it.size == 1 }
            joinerRoom.roster.first { it.isNotEmpty() }

            val payload = byteArrayOf(1, 2, 3)
            val frameDeferred = async { joinerRoom.incoming.first() }
            hostRoom.broadcast(payload)

            val frame = frameDeferred.await()
            assertEquals(hostRoom.selfId, frame.sender, "frame sender must be the host's selfId")
            assertTrue(payload.contentEquals(frame.toByteArray()), "frame payload must match")

            joinerRoom.leave()
            hostRoom.leave()
        }

    // ── (5) leave(Normal) → Left event; roster shrinks ──────────────────────

    @Test
    public fun leaveNormalFiresLeftEventAndShrinksRoster(): TestResult =
        runTest {
            val h = newHarness(backgroundScope)
            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Bob"))

            hostRoom.roster.first { it.size == 1 }

            val leftDeferred = async {
                hostRoom.events.filterIsInstance<MembershipEvent.Left>().first()
            }

            joinerRoom.leave(LeaveReason.Normal)

            val event = leftDeferred.await()
            assertIs<MembershipEvent.Left>(event)
            assertEquals(0, hostRoom.roster.value.size, "roster must shrink after Leave")

            hostRoom.leave()
        }

    // ── (6) round-trip: join → leave → rejoin; fresh session ────────────────

    @Test
    public fun rejoinAfterLeaveWorks(): TestResult =
        runTest {
            val h = newHarness(backgroundScope)
            val hostRoom = h.hostFactory.host(Pattern("Alice"))

            val firstJoiner = h.joinerFactory.join(InMemoryTag("Bob"))
            hostRoom.roster.first { it.size == 1 }
            firstJoiner.leave()
            hostRoom.roster.first { it.isEmpty() }

            val secondJoiner = h.joinerFactory.join(InMemoryTag("Bob"))
            val rosterAfterRejoin = hostRoom.roster.first { it.size == 1 }

            assertEquals(1, rosterAfterRejoin.size, "roster must contain the rejoiner")
            assertEquals("Bob", rosterAfterRejoin.first().identity.displayName, "rejoiner display name must match")

            secondJoiner.leave()
            hostRoom.leave()
        }

    // ── (7) Partitioned / Recovered fire on liveness transitions ────────────

    /**
     * Faults only the host's [FaultySeam] ([FaultyLoom.links][0]) with [FaultProfile.DropAll]
     * in both directions. The joiner's seam ([FaultyLoom.links][1]) stays Healthy, mirroring
     * [us.tractat.kuilt.session.PartitionRoleTest]'s proven partition/recovery pattern.
     *
     * With only the host's seam faulted, neither side can exchange ping/pong:
     * - Host can't send pings (outbound dropped).
     * - Joiner's pings to host are dropped at host's inbound.
     * Both detectors fire [MembershipEvent.Partitioned] within the timeout.
     *
     * After healing, both sides exchange ping/pong again. Both detectors fire
     * [MembershipEvent.Recovered] before the reconnect window expires.
     *
     * **Tick pattern** (mirrored from [us.tractat.kuilt.session.PartitionRoleTest]):
     * 1. 4 ticks → [MembershipEvent.Partitioned] fires.
     * 2. Heal host seam.
     * 3. 1 tick (allow ping/pong exchange to update [lastSeenEpochMs]).
     * 4. Start recovered collector to avoid missing the event on the hot flow.
     * 5. 5 ticks → [MembershipEvent.Recovered] fires.
     */
    @Test
    public fun partitionedAndRecoveredFireOnLivenessTransitions(): TestResult =
        runTest {
            val h = newHarness(backgroundScope)
            val faultyLoom = h.faultyLoom ?: return@runTest

            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Bob"))
            hostRoom.roster.first { it.size == 1 }

            // After host() and join(), links[0] = host's seam, links[1] = joiner's seam.
            val hostSeam = faultyLoom.links[0]

            val partitionedDeferred = async {
                hostRoom.events.filterIsInstance<MembershipEvent.Partitioned>().first()
            }
            // Fault only the host's seam (both directions) — joiner seam stays Healthy.
            hostSeam.setFaultProfile(FaultProfile.DropAll(Direction.Both))
            // Advance past heartbeat timeout (200 ms) — 4 steps gives margin.
            repeat(4) { h.advanceClock(100L); advanceTimeBy(100L) }

            assertIs<MembershipEvent.Partitioned>(partitionedDeferred.await())
            assertEquals(Liveness.Partitioned, hostRoom.roster.value.first().liveness)

            // Heal the host seam then advance one tick for ping/pong exchange.
            hostSeam.heal()
            h.advanceClock(100L); advanceTimeBy(100L)

            // Start the recovered collector AFTER one pong exchange but BEFORE the next
            // poll cycle where PeerRecovered fires — mirrors PartitionRoleTest exactly.
            val recoveredDeferred = async {
                hostRoom.events.filterIsInstance<MembershipEvent.Recovered>().first()
            }
            repeat(5) { h.advanceClock(100L); advanceTimeBy(100L) }

            val recovered = recoveredDeferred.await()
            assertIs<MembershipEvent.Recovered>(recovered)
            assertEquals(Liveness.Connected, hostRoom.roster.value.first().liveness)

            joinerRoom.leave()
            hostRoom.leave()
        }

    // ── (8) Resumed fires on Room.resume(token) within the window ───────────

    @Test
    public fun resumeWithinWindowFiresResumed(): TestResult =
        runTest {
            val h = newHarness(backgroundScope)
            val faultyLoom = h.faultyLoom ?: return@runTest

            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Bob"))

            hostRoom.roster.first { it.size == 1 }
            joinerRoom.roster.first { it.isNotEmpty() }

            val token = joinerRoom.resumeToken ?: return@runTest

            faultyLoom.setFaultProfileOnAll(FaultProfile.DropAll(Direction.Both))
            repeat(4) { h.advanceClock(100L); advanceTimeBy(100L) }

            val hostResumed = async { hostRoom.events.filterIsInstance<MembershipEvent.Resumed>().first() }
            val joinerResumed = async { joinerRoom.events.filterIsInstance<MembershipEvent.Resumed>().first() }

            faultyLoom.setFaultProfileOnAll(FaultProfile.Healthy)
            advanceTimeBy(50L)

            val result = joinerRoom.resume(token)
            assertIs<ResumeResult.Success>(result)
            assertIs<MembershipEvent.Resumed>(hostResumed.await())
            assertIs<MembershipEvent.Resumed>(joinerResumed.await())

            hostRoom.leave()
            joinerRoom.leave()
        }

    // ── (9) HostLost is terminal — broadcast after HostLost is a no-op ──────

    @Test
    public fun hostLostIsTerminalBroadcastIsNoOp(): TestResult =
        runTest {
            val h = newHarness(backgroundScope)
            val faultyLoom = h.faultyLoom ?: return@runTest

            h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Bob"))
            joinerRoom.roster.first { it.isNotEmpty() }

            val hostLostDeferred = async {
                joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first()
            }
            faultyLoom.setFaultProfileOnAll(FaultProfile.DropAll(Direction.Both))
            repeat(9) { h.advanceClock(100L); advanceTimeBy(100L) }

            assertIs<MembershipEvent.HostLost>(hostLostDeferred.await())

            joinerRoom.broadcast("after-host-lost".encodeToByteArray())
        }

    // ── (10) Left member no longer receives broadcast frames ─────────────────

    @Test
    public fun memberThatLeftNoLongerReceivesFrames(): TestResult =
        runTest {
            val h = newHarness(backgroundScope)
            val hostRoom = h.hostFactory.host(Pattern("Alice"))
            val joinerRoom = h.joinerFactory.join(InMemoryTag("Bob"))

            hostRoom.roster.first { it.size == 1 }
            joinerRoom.roster.first { it.isNotEmpty() }

            joinerRoom.leave(LeaveReason.Normal)
            hostRoom.roster.first { it.isEmpty() }

            var received = false
            val collectJob = launch { joinerRoom.incoming.collect { received = true } }

            hostRoom.broadcast("after-leave".encodeToByteArray())
            advanceTimeBy(100L)
            collectJob.cancel()

            assertFalse(received, "a member that has Left must not receive broadcast frames")

            hostRoom.leave()
        }

    // ── Default harness ───────────────────────────────────────────────────────

    private fun defaultHarness(scope: CoroutineScope): RoomHarness {
        var clockMs = 0L
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
        val innerLoom = InMemoryLoom()
        val faultyLoom = FaultyLoom(innerLoom, scope)
        val factory = SeamRoomFactory(
            loom = faultyLoom,
            scope = scope,
            clock = clock,
            heartbeatConfig = fastHeartbeatConfig,
        )
        return RoomHarness(
            hostFactory = factory,
            joinerFactory = factory,
            faultyLoom = faultyLoom,
            clock = clock,
            advanceClock = { ms -> clockMs += ms },
        )
    }
}
