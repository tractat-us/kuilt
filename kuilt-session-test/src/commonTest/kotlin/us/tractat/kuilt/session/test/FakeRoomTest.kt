package us.tractat.kuilt.session.test

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.session.LeaveReason
import us.tractat.kuilt.session.Liveness
import us.tractat.kuilt.session.Member
import us.tractat.kuilt.session.MemberIdentity
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.session.SessionRole
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.ResumeToken
import us.tractat.kuilt.session.partition.RoomId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class FakeRoomTest {
    // ── Defaults ─────────────────────────────────────────────────────────────

    @Test
    fun `default selfId is 'self'`() = runTest {
        val room = FakeRoom()
        assertEquals(PeerId("self"), room.selfId)
    }

    @Test
    fun `default role is Host`() = runTest {
        val room = FakeRoom()
        assertEquals(SessionRole.Host, room.role.value)
    }

    @Test
    fun `default roster is empty`() = runTest {
        val room = FakeRoom()
        assertTrue(room.roster.value.isEmpty())
    }

    @Test
    fun `default resumeToken is null`() = runTest {
        val room = FakeRoom()
        assertNull(room.resumeToken)
    }

    @Test
    fun `custom constructor args are respected`() = runTest {
        val token = ResumeToken(PeerId("p"), RoomId("r"), issuedAt = 0L)
        val alice = member(PeerId("alice"))
        val room = FakeRoom(
            selfId = PeerId("alice"),
            initialRole = SessionRole.Joiner,
            initialRoster = setOf(alice),
            initialResumeToken = token,
        )
        assertAll(
            { assertEquals(PeerId("alice"), room.selfId) },
            { assertEquals(SessionRole.Joiner, room.role.value) },
            { assertEquals(setOf(alice), room.roster.value) },
            { assertEquals(token, room.resumeToken) },
        )
    }

    // ── roster mutation ───────────────────────────────────────────────────────

    @Test
    fun `addMember updates roster and emits Joined`() = runTest {
        val room = FakeRoom()
        val alice = member(PeerId("alice"))
        val eventDeferred = async { room.events.first() }
        room.addMember(alice)
        val event = eventDeferred.await()
        assertAll(
            { assertTrue(alice in room.roster.value) },
            { assertEquals(MembershipEvent.Joined(alice), event) },
        )
    }

    @Test
    fun `removeMember updates roster and emits Left`() = runTest {
        val alice = member(PeerId("alice"))
        val room = FakeRoom(initialRoster = setOf(alice))
        val eventDeferred = async { room.events.first() }
        room.removeMember(PeerId("alice"))
        val event = eventDeferred.await()
        assertAll(
            { assertFalse(alice in room.roster.value) },
            { assertIs<MembershipEvent.Left>(event) },
        )
    }

    @Test
    fun `removeMember with explicit reason emits that reason`() = runTest {
        val alice = member(PeerId("alice"))
        val room = FakeRoom(initialRoster = setOf(alice))
        val eventDeferred = async { room.events.first() }
        room.removeMember(PeerId("alice"), LeaveReason.PartitionExpired)
        val event = assertIs<MembershipEvent.Left>(eventDeferred.await())
        assertEquals(LeaveReason.PartitionExpired, event.reason)
    }

    @Test
    fun `removeMember of absent peer is a no-op roster-wise but still emits Left`() = runTest {
        val room = FakeRoom()
        val eventDeferred = async { room.events.first() }
        room.removeMember(PeerId("ghost"))
        val event = eventDeferred.await()
        assertAll(
            { assertTrue(room.roster.value.isEmpty()) },
            { assertIs<MembershipEvent.Left>(event) },
        )
    }

    // ── partition / recover ───────────────────────────────────────────────────

    @Test
    fun `partition flips liveness and emits Partitioned`() = runTest {
        val alice = member(PeerId("alice"))
        val room = FakeRoom(initialRoster = setOf(alice))
        val at = Instant.fromEpochMilliseconds(1000L)
        val eventDeferred = async { room.events.first() }
        room.partition(PeerId("alice"), at)
        val updatedMember = room.roster.value.single { it.id == PeerId("alice") }
        val event = eventDeferred.await()
        assertAll(
            { assertEquals(Liveness.Partitioned, updatedMember.liveness) },
            { assertEquals(MembershipEvent.Partitioned(PeerId("alice"), at), event) },
        )
    }

    @Test
    fun `recover flips liveness back and emits Recovered`() = runTest {
        val alice = member(PeerId("alice"), liveness = Liveness.Partitioned)
        val room = FakeRoom(initialRoster = setOf(alice))
        val at = Instant.fromEpochMilliseconds(2000L)
        val eventDeferred = async { room.events.first() }
        room.recover(PeerId("alice"), at)
        val updatedMember = room.roster.value.single { it.id == PeerId("alice") }
        val event = eventDeferred.await()
        assertAll(
            { assertEquals(Liveness.Connected, updatedMember.liveness) },
            { assertEquals(MembershipEvent.Recovered(PeerId("alice"), at), event) },
        )
    }

    // ── window / resumed / hostLost ───────────────────────────────────────────

    @Test
    fun `openWindow emits WindowOpened`() = runTest {
        val room = FakeRoom()
        val eventDeferred = async { room.events.first() }
        room.openWindow(PeerId("alice"), expiresAt = 9999L)
        assertEquals(
            MembershipEvent.WindowOpened(PeerId("alice"), expiresAt = 9999L),
            eventDeferred.await(),
        )
    }

    @Test
    fun `emitResumed emits Resumed`() = runTest {
        val room = FakeRoom()
        val eventDeferred = async { room.events.first() }
        room.emitResumed(PeerId("alice"))
        assertEquals(MembershipEvent.Resumed(PeerId("alice")), eventDeferred.await())
    }

    @Test
    fun `hostLost emits HostLost and silences subsequent sends`() = runTest {
        val room = FakeRoom()
        val at = Instant.fromEpochMilliseconds(5000L)
        val eventDeferred = async { room.events.first() }
        room.hostLost(at)
        val event = eventDeferred.await()
        room.broadcast(byteArrayOf(1))
        room.sendTo(PeerId("someone"), byteArrayOf(2))
        assertAll(
            { assertEquals(MembershipEvent.HostLost(at), event) },
            { assertTrue(room.broadcasts.isEmpty()) },
            { assertTrue(room.directed.isEmpty()) },
        )
    }

    // ── deliver → incoming ────────────────────────────────────────────────────

    @Test
    fun `deliver pushes a RoomFrame into incoming`() = runTest {
        val room = FakeRoom()
        val received = async { room.incoming.first() }
        room.deliver(PeerId("alice"), byteArrayOf(1, 2, 3))
        val frame = received.await()
        assertAll(
            { assertEquals(PeerId("alice"), frame.sender) },
            { assertContentEquals(byteArrayOf(1, 2, 3), frame.payload) },
        )
    }

    @Test
    fun `frames delivered before collector subscribes are buffered not lost`() = runTest {
        val room = FakeRoom()
        room.deliver(PeerId("bob"), byteArrayOf(42))
        val frame = room.incoming.first()
        assertContentEquals(byteArrayOf(42), frame.payload)
    }

    @Test
    fun `deliver preserves in-order delivery`() = runTest {
        val room = FakeRoom()
        room.deliver(PeerId("alice"), byteArrayOf(1))
        room.deliver(PeerId("alice"), byteArrayOf(2))
        room.deliver(PeerId("alice"), byteArrayOf(3))
        val frames = room.incoming.take(3).toList()
        assertAll(
            { assertContentEquals(byteArrayOf(1), frames[0].payload) },
            { assertContentEquals(byteArrayOf(2), frames[1].payload) },
            { assertContentEquals(byteArrayOf(3), frames[2].payload) },
        )
    }

    // ── broadcast / sendTo recording ─────────────────────────────────────────

    @Test
    fun `broadcast is recorded in broadcasts`() = runTest {
        val room = FakeRoom()
        room.broadcast(byteArrayOf(7))
        assertAll(
            { assertEquals(1, room.broadcasts.size) },
            { assertContentEquals(byteArrayOf(7), room.broadcasts[0]) },
        )
    }

    @Test
    fun `multiple broadcasts are recorded in order`() = runTest {
        val room = FakeRoom()
        room.broadcast(byteArrayOf(1))
        room.broadcast(byteArrayOf(2))
        assertAll(
            { assertEquals(2, room.broadcasts.size) },
            { assertContentEquals(byteArrayOf(1), room.broadcasts[0]) },
            { assertContentEquals(byteArrayOf(2), room.broadcasts[1]) },
        )
    }

    @Test
    fun `sendTo is recorded in directed`() = runTest {
        val room = FakeRoom()
        val bob = PeerId("bob")
        room.sendTo(bob, byteArrayOf(55))
        assertAll(
            { assertEquals(1, room.directed.size) },
            { assertEquals(bob, room.directed[0].first) },
            { assertContentEquals(byteArrayOf(55), room.directed[0].second) },
        )
    }

    // ── resume ────────────────────────────────────────────────────────────────

    @Test
    fun `resume returns Success by default`() = runTest {
        val room = FakeRoom()
        val token = ResumeToken(PeerId("self"), RoomId("r"), issuedAt = 0L)
        assertIs<ResumeResult.Success>(room.resume(token))
    }

    @Test
    fun `resume returns overridden result`() = runTest {
        val room = FakeRoom()
        room.resumeResult = ResumeResult.WindowClosed
        val token = ResumeToken(PeerId("self"), RoomId("r"), issuedAt = 0L)
        assertIs<ResumeResult.WindowClosed>(room.resume(token))
    }

    // ── leave ─────────────────────────────────────────────────────────────────

    @Test
    fun `leave is idempotent`() = runTest {
        val room = FakeRoom()
        room.leave()
        room.leave() // must not throw
    }

    @Test
    fun `broadcast after leave is a silent no-op`() = runTest {
        val room = FakeRoom()
        room.leave()
        room.broadcast(byteArrayOf(1))
        assertTrue(room.broadcasts.isEmpty())
    }

    @Test
    fun `sendTo after leave is a silent no-op`() = runTest {
        val room = FakeRoom()
        room.leave()
        room.sendTo(PeerId("bob"), byteArrayOf(1))
        assertTrue(room.directed.isEmpty())
    }

    // ── role / resumeToken helpers ────────────────────────────────────────────

    @Test
    fun `setRole updates role flow`() = runTest {
        val room = FakeRoom(initialRole = SessionRole.Host)
        room.setRole(SessionRole.Joiner)
        assertEquals(SessionRole.Joiner, room.role.value)
    }

    @Test
    fun `setResumeToken updates resumeToken`() = runTest {
        val room = FakeRoom()
        val token = ResumeToken(PeerId("self"), RoomId("r"), issuedAt = 42L)
        room.setResumeToken(token)
        assertEquals(token, room.resumeToken)
    }

    @Test
    fun `setResumeToken to null clears resumeToken`() = runTest {
        val token = ResumeToken(PeerId("self"), RoomId("r"), issuedAt = 0L)
        val room = FakeRoom(initialResumeToken = token)
        room.setResumeToken(null)
        assertNull(room.resumeToken)
    }

    // ── raw emit ─────────────────────────────────────────────────────────────

    @Test
    fun `emit pushes arbitrary event onto events`() = runTest {
        val room = FakeRoom()
        val alice = member(PeerId("alice"))
        val eventDeferred = async { room.events.first() }
        room.emit(MembershipEvent.Joined(alice))
        assertEquals(MembershipEvent.Joined(alice), eventDeferred.await())
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun member(
        id: PeerId,
        liveness: Liveness = Liveness.Connected,
    ) = Member(
        id = id,
        identity = MemberIdentity(displayName = id.value, sessionId = id.value),
        liveness = liveness,
    )
}

private fun assertAll(vararg assertions: () -> Unit) = assertions.forEach { it() }
