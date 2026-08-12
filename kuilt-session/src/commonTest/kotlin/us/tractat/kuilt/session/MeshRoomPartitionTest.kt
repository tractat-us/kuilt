package us.tractat.kuilt.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.election.ElectionOutcome
import us.tractat.kuilt.session.election.SeamElectionLobby
import us.tractat.kuilt.session.election.electHost
import us.tractat.kuilt.test.Direction
import us.tractat.kuilt.test.FaultyLoom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Reproduction for #1618: on a **>2-peer mesh** built the way the app builds it
 * (`electLobby` → `adopt` → [SeamRoom]), does the host emit
 * [MembershipEvent.Partitioned] / [MembershipEvent.WindowOpened] when one member
 * drops off the network (silent Wi-Fi loss — the peer lingers in `peers`, only
 * pong-silence catches it)?
 *
 * The 2-peer [us.tractat.kuilt.conformance.RoomConformanceSuite] partition test and
 * [AdoptTest] never exercise this surface together, yet it is exactly where the app runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshRoomPartitionTest {

    private val fastHeartbeat = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 200.milliseconds,
        reconnectWindow = 500.milliseconds,
    )

    @Test
    fun `host emits Partitioned and WindowOpened when a mesh member silently drops`() =
        runTest {
            // Advancing clock kept in lock-step with virtual time (the detector measures
            // silence as clock() - lastSeen, so a frozen clock never times out).
            var nowMs = 0L
            val clock = { Instant.fromEpochMilliseconds(nowMs) }
            fun tick() {
                nowMs += 100L
                advanceTimeBy(100L)
                runCurrent()
            }

            val faulty = FaultyLoom(InMemoryLoom(), backgroundScope)
            fun factory() = SeamRoomFactory(
                loom = faulty,
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastHeartbeat,
            )
            fun lobby(seam: Seam) = SeamElectionLobby(
                seam = seam,
                factory = factory(),
                scope = backgroundScope,
                clock = clock,
                roomKey = null,
            )

            // Weave a 3-peer mesh (links[0..2] in weave order = peer-1..peer-3 on InMemoryLoom).
            val s1 = faulty.weave(Rendezvous.New(Pattern("g")))
            val s2 = faulty.weave(Rendezvous.Existing(InMemoryTag("g")))
            val s3 = faulty.weave(Rendezvous.Existing(InMemoryTag("g")))
            val lobbies = listOf(s1, s2, s3).map { lobby(it) }

            // Everyone sees the full roster and elects the same lowest-id host (peer-1 = s1).
            val electedHost = lobbies[0].host.first { lobbies[0].peers.value.size == 3 }
            lobbies.forEach { it.host.first { _ -> it.peers.value.size == 3 } }

            val hostLobby = lobbies.first { it.selfId == electedHost }
            val memberLobbies = lobbies.filter { it.selfId != electedHost }

            val memberRooms = memberLobbies.map { async { it.awaitRoom(memberName = it.selfId.value) } }
            val hostRoom = hostLobby.start(memberName = "Host")
            memberRooms.map { assertIs<ElectionOutcome.Adopted>(it.await()).room }

            // Host admitted both members → detectors are running for each.
            hostRoom.roster.first { it.size == 2 }

            // Pick one member to drop. Its FaultySeam is the link whose selfId is that member.
            val victim = memberLobbies.first()
            val victimLink = faulty.links.first { it.selfId == victim.selfId }

            // Arm the collector before the drop.
            val partitioned = async {
                hostRoom.events.filterIsInstance<MembershipEvent.Partitioned>()
                    .first { it.peerId == victim.selfId }
            }
            val windowOpened = async {
                hostRoom.events.filterIsInstance<MembershipEvent.WindowOpened>()
                    .first { it.peerId == victim.selfId }
            }

            // Silent Wi-Fi loss: drop the victim's frames both ways. Peers/state unchanged
            // (FaultySeam proxies them straight through) — only pong-silence can catch it.
            victimLink.partition(Direction.Both)

            // Advance past the heartbeat timeout (200 ms) with margin.
            repeat(5) { tick() }

            val p = partitioned.await()
            assertEquals(victim.selfId, p.peerId, "host must mark the dropped member Partitioned")
            val w = windowOpened.await()
            assertIs<MembershipEvent.WindowOpened>(w)
            assertEquals(victim.selfId, w.peerId, "host must open a reconnect window for the dropped member")

            lobbies.forEach { it.leave() }
        }
}
