package us.tractat.kuilt.session.test

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.session.Liveness
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.session.SessionRole
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FakeRoomFactoryTest {
    // ── FakeRoomFactory ───────────────────────────────────────────────────────

    @Test
    fun `host seeds selfId from pattern displayName and role is Host`() = runTest {
        val factory = FakeRoomFactory()
        val room = factory.host(Pattern("alice")) as FakeRoom
        assertAll(
            { assertEquals(PeerId("alice"), room.selfId) },
            { assertEquals(SessionRole.Host, room.role.value) },
        )
    }

    @Test
    fun `join seeds selfId from tag displayName and role is Joiner`() = runTest {
        val factory = FakeRoomFactory()
        val tag = simpleTag("bob")
        val room = factory.join(tag) as FakeRoom
        assertAll(
            { assertEquals(PeerId("bob"), room.selfId) },
            { assertEquals(SessionRole.Joiner, room.role.value) },
        )
    }

    // ── fakeRoomPair ──────────────────────────────────────────────────────────

    @Test
    fun `fakeRoomPair seeds each side with the other as a Connected member`() = runTest {
        val (host, joiner) = fakeRoomPair(PeerId("host"), PeerId("joiner"))
        // Drain the Joined events emitted by seedRoster
        host.events.first()
        joiner.events.first()
        val hostRosterMember = host.roster.value.single()
        val joinerRosterMember = joiner.roster.value.single()
        assertAll(
            { assertEquals(PeerId("joiner"), hostRosterMember.id) },
            { assertEquals(Liveness.Connected, hostRosterMember.liveness) },
            { assertEquals(PeerId("host"), joinerRosterMember.id) },
            { assertEquals(Liveness.Connected, joinerRosterMember.liveness) },
        )
    }

    @Test
    fun `broadcast from host is delivered into joiner incoming`() = runTest {
        val (host, joiner) = fakeRoomPair(PeerId("host"), PeerId("joiner"))
        val received = async { joiner.incoming.first() }
        host.broadcast(byteArrayOf(1, 2, 3))
        val frame = received.await()
        assertAll(
            { assertEquals(PeerId("host"), frame.sender) },
            { assertContentEquals(byteArrayOf(1, 2, 3), frame.toByteArray()) },
        )
    }

    @Test
    fun `broadcast from joiner is delivered into host incoming`() = runTest {
        val (host, joiner) = fakeRoomPair(PeerId("host"), PeerId("joiner"))
        val received = async { host.incoming.first() }
        joiner.broadcast(byteArrayOf(9, 8, 7))
        val frame = received.await()
        assertAll(
            { assertEquals(PeerId("joiner"), frame.sender) },
            { assertContentEquals(byteArrayOf(9, 8, 7), frame.toByteArray()) },
        )
    }

    @Test
    fun `broadcast does not echo back to sender`() = runTest {
        val (host, joiner) = fakeRoomPair(PeerId("host"), PeerId("joiner"))
        val joinerReceived = async { joiner.incoming.first() }
        host.broadcast(byteArrayOf(42))
        joinerReceived.await()
        assertEquals(1, host.broadcasts.size)
    }

    @Test
    fun `multiple broadcasts across pair arrive in order`() = runTest {
        val (host, joiner) = fakeRoomPair(PeerId("host"), PeerId("joiner"))
        val frames = async { joiner.incoming.take(3).toList() }
        host.broadcast(byteArrayOf(1))
        host.broadcast(byteArrayOf(2))
        host.broadcast(byteArrayOf(3))
        val received = frames.await()
        assertAll(
            { assertContentEquals(byteArrayOf(1), received[0].toByteArray()) },
            { assertContentEquals(byteArrayOf(2), received[1].toByteArray()) },
            { assertContentEquals(byteArrayOf(3), received[2].toByteArray()) },
        )
    }

    @Test
    fun `fakeRoomPair emits Joined events when seeding roster`() = runTest {
        val (host, joiner) = fakeRoomPair(PeerId("host"), PeerId("joiner"))
        val hostJoined = assertIs<MembershipEvent.Joined>(host.events.first())
        val joinerJoined = assertIs<MembershipEvent.Joined>(joiner.events.first())
        assertAll(
            { assertEquals(PeerId("joiner"), hostJoined.member.id) },
            { assertEquals(PeerId("host"), joinerJoined.member.id) },
        )
    }

    @Test
    fun `fakeRoomPair uses default IDs when called with no args`() = runTest {
        val (host, joiner) = fakeRoomPair()
        assertAll(
            { assertEquals(PeerId("host"), host.selfId) },
            { assertEquals(PeerId("joiner"), joiner.selfId) },
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun simpleTag(name: String) = object : us.tractat.kuilt.core.Tag {
        override val displayName: String = name
        override val peerKey: String = name
    }
}

