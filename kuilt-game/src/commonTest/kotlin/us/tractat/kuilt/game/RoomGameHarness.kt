@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.SessionRole
import us.tractat.kuilt.test.FaultyLoom
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** Fast heartbeat so a silent drop is detected within a handful of virtual-time ticks. */
internal val fastHeartbeat = HeartbeatConfig(
    interval = 100.milliseconds,
    timeout = 200.milliseconds,
    reconnectWindow = 500.milliseconds,
)

/**
 * A 3-peer mesh of adopted [Room]s over a [FaultyLoom], mirroring `MeshRoomPartitionTest`: one
 * elected-style [hostRoom] plus two joiner member rooms, all on the caller's scope. The host's
 * roster has settled to both members (detectors running) by the time this returns.
 *
 * [victimId] is the [PeerId] of the first member — drop it via
 * `faulty.links.first { it.selfId == victimId }.partition(Direction.Both)` to simulate a silent
 * Wi-Fi loss that only pong-silence can catch.
 */
internal class MeshRoomFixture(
    val hostRoom: Room,
    val memberRooms: List<Room>,
    val faulty: FaultyLoom,
    val victimId: PeerId,
)

/**
 * Weaves a 3-peer [FaultyLoom] mesh and adopts it into a host [Room] + two joiner rooms, waiting
 * until the host has admitted both members. The rooms run on [scope] (pass `backgroundScope`).
 */
internal suspend fun adopt3PeerMeshRoom(
    scope: CoroutineScope,
    heartbeat: HeartbeatConfig,
    clock: () -> Instant,
): MeshRoomFixture {
    val faulty = FaultyLoom(InMemoryLoom(), scope)
    fun factory() = SeamRoomFactory(
        loom = faulty,
        scope = scope,
        clock = clock,
        heartbeatConfig = heartbeat,
    )

    // links[0..2] in weave order = peer-1..peer-3 on InMemoryLoom.
    val s1 = faulty.weave(Rendezvous.New(Pattern("g")))
    val s2 = faulty.weave(Rendezvous.Existing(InMemoryTag("g")))
    val s3 = faulty.weave(Rendezvous.Existing(InMemoryTag("g")))

    val memberDeferred = listOf(s2, s3).map { seam ->
        scope.async { factory().adopt(seam, SessionRole.Joiner, memberName = seam.selfId.value) }
    }
    val hostRoom = factory().adopt(s1, SessionRole.Host, memberName = "Host")
    val members = memberDeferred.map { it.await() }

    // Host admitted both members → detectors are running for each.
    hostRoom.roster.first { it.size == 2 }

    return MeshRoomFixture(hostRoom, members, faulty, victimId = s2.selfId)
}
