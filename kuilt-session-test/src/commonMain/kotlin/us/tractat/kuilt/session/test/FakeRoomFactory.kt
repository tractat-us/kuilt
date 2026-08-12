package us.tractat.kuilt.session.test

import kotlinx.atomicfu.atomic
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.session.Liveness
import us.tractat.kuilt.session.Member
import us.tractat.kuilt.session.MemberIdentity
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.RoomFactory
import us.tractat.kuilt.session.RoomFrame
import us.tractat.kuilt.session.SessionRole
import us.tractat.kuilt.session.partition.RoomId

/**
 * A test double for [RoomFactory] that returns [FakeRoom] instances.
 *
 * [host] seeds the room's [FakeRoom.selfId] from the pattern's session name and
 * sets the role to [SessionRole.Host]. [join] does the same with [SessionRole.Joiner].
 *
 * For wired two-room scenarios, prefer [fakeRoomPair].
 *
 * ```kotlin
 * val factory = FakeRoomFactory()
 * val room = factory.host(Pattern("alice"))
 * // room.selfId == PeerId("alice"), room.role.value == SessionRole.Host
 * ```
 */
public class FakeRoomFactory : RoomFactory {
    /**
     * [roomId] is honoured verbatim when supplied; otherwise a fresh one is minted per call, so two
     * rooms hosted under the same session name never share an identity — the same contract the real
     * factory keeps (#1594).
     */
    override suspend fun host(pattern: Pattern, memberName: String?, roomId: RoomId?): Room =
        FakeRoom(
            selfId = PeerId(pattern.sessionName),
            initialRole = SessionRole.Host,
            initialRoomId = roomId ?: RoomId("${pattern.sessionName}-room-${fakeRoomSequence.getAndIncrement()}"),
        )

    /** A joiner has no room id until it is admitted; drive that with [FakeRoom.setRoomId]. */
    override suspend fun join(tag: Tag, memberName: String?): Room =
        FakeRoom(
            selfId = PeerId(tag.sessionName),
            initialRole = SessionRole.Joiner,
            initialRoomId = null,
        )
}

/**
 * Process-wide room counter behind [FakeRoomFactory.host]'s mint. Atomic because a fake is not
 * exempt from the multi-threaded-dispatcher rule, and process-wide (not per-factory) because two
 * factories in one test are the ordinary case.
 */
private val fakeRoomSequence = atomic(0L)

/**
 * Build a wired pair of [FakeRoom]s whose [FakeRoom.broadcast] calls cross-deliver
 * into the other room's [Room.incoming], matching the behaviour of a real two-peer room.
 *
 * - Each side's roster is seeded with the other as a [Liveness.Connected] [Member].
 * - Both sides report the **same** [Room.roomId] — the pair models a completed handshake, and a
 *   real joiner has learned the host's id by that point.
 * - A [FakeRoom.broadcast] on one side delivers a [RoomFrame] into the other's [Room.incoming].
 * - The broadcast is also recorded in the sender's [FakeRoom.broadcasts] list.
 * - Delivery is synchronous — no separate coroutine substrate required.
 *
 * ```kotlin
 * val (host, joiner) = fakeRoomPair(PeerId("host"), PeerId("joiner"))
 * host.broadcast(byteArrayOf(1, 2, 3))
 * val frame = joiner.incoming.first()   // RoomFrame(sender=PeerId("host"), payload=[1,2,3])
 * ```
 */
public suspend fun fakeRoomPair(
    hostId: PeerId = PeerId("host"),
    joinerId: PeerId = PeerId("joiner"),
): Pair<FakeRoom, FakeRoom> {
    val host = FakeRoom(selfId = hostId, initialRole = SessionRole.Host)
    val joiner = FakeRoom(selfId = joinerId, initialRole = SessionRole.Joiner)
    joiner.setRoomId(host.roomId.value)
    seedRoster(room = host, peerId = joinerId, displayName = joinerId.value)
    seedRoster(room = joiner, peerId = hostId, displayName = hostId.value)
    wireDelivery(sender = host, receiver = joiner)
    wireDelivery(sender = joiner, receiver = host)
    return host to joiner
}

private suspend fun seedRoster(room: FakeRoom, peerId: PeerId, displayName: String) {
    val member = Member(
        id = peerId,
        identity = MemberIdentity(displayName = displayName, sessionId = displayName),
        liveness = Liveness.Connected,
    )
    room.addMember(member)
}

private fun wireDelivery(sender: FakeRoom, receiver: FakeRoom) {
    sender.onBroadcast = { payload ->
        receiver.deliver(from = sender.selfId, payload = payload)
    }
}
