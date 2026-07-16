package us.tractat.kuilt.session.election

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.SessionRole
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class SeamElectionLobbyTest {
    private fun factory(loom: InMemoryLoom, scope: CoroutineScope) =
        SeamRoomFactory(loom, scope, clock = { kotlin.time.Instant.fromEpochMilliseconds(0L) })

    private fun lobby(seam: Seam, loom: InMemoryLoom, scope: CoroutineScope) =
        SeamElectionLobby(seam = seam, factory = factory(loom, scope), scope = scope,
            clock = { kotlin.time.Instant.fromEpochMilliseconds(0L) }, roomKey = null)

    @Test
    fun `all peers elect the same lowest-id host`() =
        runTest {
            val loom = InMemoryLoom()
            val s1 = loom.weave(Rendezvous.New(Pattern("g")))
            val s2 = loom.weave(Rendezvous.Existing(InMemoryTag("g")))
            val l1 = lobby(s1, loom, backgroundScope)
            val l2 = lobby(s2, loom, backgroundScope)

            // Both see 2 peers; both elect min(peers). Peer ids are "peer-1","peer-2" (InMemoryLoom).
            val h1 = l1.host.first()
            val h2 = l2.host.first { l2.peers.value.size == 2 }
            assertAll(
                { assertEquals(h1, h2) },
                { assertEquals(electHost(l1.peers.value), h1) },
                { assertEquals(2, l1.peers.value.size) },
            )
            l1.leave(); l2.leave()
        }

    @Test
    fun `host updates when a lower-id peer joins`() =
        runTest {
            val loom = InMemoryLoom()
            val s1 = loom.weave(Rendezvous.New(Pattern("g")))
            val l1 = lobby(s1, loom, backgroundScope)
            assertEquals(s1.selfId, l1.host.first()) // alone → self is host

            val s2 = loom.weave(Rendezvous.Existing(InMemoryTag("g")))
            val l2 = lobby(s2, loom, backgroundScope)
            // host is now min of both; assert both agree once the second peer is visible.
            val settled = l1.host.first { l1.peers.value.size == 2 }
            assertEquals(electHost(setOf(s1.selfId, s2.selfId)), settled)
            l1.leave(); l2.leave()
        }

    @Test
    fun `host start and member awaitRoom form a session with correct roles`() =
        runTest {
            val loom = InMemoryLoom()
            val s1 = loom.weave(Rendezvous.New(Pattern("g")))
            val s2 = loom.weave(Rendezvous.Existing(InMemoryTag("g")))
            val l1 = lobby(s1, loom, backgroundScope)
            val l2 = lobby(s2, loom, backgroundScope)

            // Wait until both see the full 2-peer roster and agree on the host.
            val electedHost = l1.host.first { l1.peers.value.size == 2 }
            l2.host.first { l2.peers.value.size == 2 }

            // The elected host calls start(); the other calls awaitRoom(). Determine which is which.
            val hostLobby = if (l1.selfId == electedHost) l1 else l2
            val memberLobby = if (l1.selfId == electedHost) l2 else l1

            val memberRoomDeferred = async { memberLobby.awaitRoom(memberName = "Member") }
            val hostRoom = hostLobby.start(memberName = "Host")
            val memberRoom = memberRoomDeferred.await()

            // Both rooms complete their admit handshake: one member each.
            hostRoom.roster.first { it.size == 1 }
            memberRoom.roster.first { it.size == 1 }

            assertAll(
                { assertEquals(SessionRole.Host, hostRoom.role.value) },
                { assertEquals(SessionRole.Joiner, memberRoom.role.value) },
            )
            memberRoom.leave(); hostRoom.leave()
        }

    @Test
    fun `three-peer freeze round admits both members with correct roles`() =
        runTest {
            val loom = InMemoryLoom()
            val s1 = loom.weave(Rendezvous.New(Pattern("g")))
            val s2 = loom.weave(Rendezvous.Existing(InMemoryTag("g")))
            val s3 = loom.weave(Rendezvous.Existing(InMemoryTag("g")))
            val lobbies = listOf(s1, s2, s3).map { lobby(it, loom, backgroundScope) }

            // All three see the full 3-peer roster and elect the same lowest-id host.
            val electedHost = lobbies[0].host.first { lobbies[0].peers.value.size == 3 }
            lobbies.forEach { it.host.first { _ -> it.peers.value.size == 3 } }

            val hostLobby = lobbies.first { it.selfId == electedHost }
            val memberLobbies = lobbies.filter { it.selfId != electedHost }

            // Both members await concurrently; the host runs the freeze round. The Commit must reach
            // BOTH members (the multi-member path the single-member test cannot exercise).
            val memberRooms = memberLobbies.map { async { it.awaitRoom(memberName = it.selfId.value) } }
            val hostRoom = hostLobby.start(memberName = "Host")
            val rooms = memberRooms.map { it.await() }

            // Host admits both members; each member sees host + the other member.
            hostRoom.roster.first { it.size == 2 }
            rooms.forEach { it.roster.first { r -> r.size == 2 } }

            assertAll(
                { assertEquals(SessionRole.Host, hostRoom.role.value) },
                { assertEquals(SessionRole.Joiner, rooms[0].role.value) },
                { assertEquals(SessionRole.Joiner, rooms[1].role.value) },
            )
            rooms.forEach { it.leave() }
            hostRoom.leave()
        }

    @Test
    fun `start from a non-host peer throws`() =
        runTest {
            val loom = InMemoryLoom()
            val s1 = loom.weave(Rendezvous.New(Pattern("g")))
            val s2 = loom.weave(Rendezvous.Existing(InMemoryTag("g")))
            val l1 = lobby(s1, loom, backgroundScope)
            val l2 = lobby(s2, loom, backgroundScope)

            val electedHost = l1.host.first { l1.peers.value.size == 2 }
            l2.host.first { l2.peers.value.size == 2 }
            val nonHost = if (l1.selfId == electedHost) l2 else l1
            assertFailsWith<NotElectedHostException> { nonHost.start() }
            l1.leave(); l2.leave()
        }

    @Test
    fun `member awaitRoom throws LobbyTornException when the seam tears mid-2PC`() =
        runTest {
            // self is the member (higher id); "peer-a" is the elected host (lower id).
            val self = PeerId("peer-b")
            val hostId = PeerId("peer-a")
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, hostId))
            val l = lobby(seam, InMemoryLoom(), backgroundScope)
            assertEquals(hostId, l.host.first()) // self is a member, so awaitRoom is the right call

            // Capture awaitRoom's outcome via a caught launch so a thrown exception is delivered
            // through [room] rather than propagating to (and cancelling) the test scope.
            val room = CompletableDeferred<Room>()
            val driver = launch {
                try {
                    room.complete(l.awaitRoom(memberName = "Member"))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    room.completeExceptionally(e)
                }
            }
            runCurrent() // let awaitRoom subscribe to lobbyMessages before we deliver the Freeze

            // Drive to mid-2PC: deliver the host's Freeze; the member acks and awaits the Commit.
            seam.deliver(hostId, LobbyMessage.encode(LobbyMessage.Freeze(hostId.value, setOf(hostId.value, self.value), 1L)))
            runCurrent()

            // The co-elector vanishes mid-handshake: a 2-peer peerMesh latches Torn when its last link
            // drops. awaitRoom must surface that terminally, not suspend past the commit timeout forever.
            seam.removePeer(hostId)
            seam.tear(CloseReason.Unreachable)

            assertFailsWith<LobbyTornException> { withTimeout(5.seconds) { room.await() } }
            driver.cancel()
        }

    @Test
    fun `host start throws LobbyTornException when the seam tears mid-2PC`() =
        runTest {
            // self is the elected host (lower id); "peer-z" is the awaited member (higher id).
            val self = PeerId("peer-a")
            val memberId = PeerId("peer-z")
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, memberId))
            val l = lobby(seam, InMemoryLoom(), backgroundScope)
            assertEquals(self, l.host.first()) // self is the elected host, so start is the right call

            val room = CompletableDeferred<Room>()
            val driver = launch {
                try {
                    room.complete(l.start(memberName = "Host"))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    room.completeExceptionally(e)
                }
            }
            runCurrent() // let start broadcast the Freeze and await the member's FreezeAck

            // The only member vanishes mid-freeze-round: the seam tears rather than the host silently
            // committing a solo room (matchmaking has no game with one peer).
            seam.removePeer(memberId)
            seam.tear(CloseReason.Unreachable)

            assertFailsWith<LobbyTornException> { withTimeout(5.seconds) { room.await() } }
            driver.cancel()
        }

    @Test
    fun `member awaitRoom throws LobbyTornException when the host leaves without a seam tear`() =
        runTest {
            // The hardware failure (#1466): membership drains but the seam stays Woven — peers drops
            // to {self} and host.value recomputes to self, yet state never latches Torn. FakeSeam.
            // removePeer models exactly this (drops _peers, leaves state Woven); NO tear() call.
            val self = PeerId("peer-z")
            val hostId = PeerId("peer-a")
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, hostId))
            val l = lobby(seam, InMemoryLoom(), backgroundScope)
            assertEquals(hostId, l.host.first()) // self is a member

            val room = CompletableDeferred<Room>()
            val driver = launch {
                try {
                    room.complete(l.awaitRoom(memberName = "Member"))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    room.completeExceptionally(e)
                }
            }
            runCurrent() // awaitRoom subscribes and suspends awaiting the host's Freeze

            // Host leaves the peer set; seam stays Woven (no tear). host.value → self.
            seam.removePeer(hostId)

            assertFailsWith<LobbyTornException> { withTimeout(5.seconds) { room.await() } }
            driver.cancel()
        }

    @Test
    fun `host start throws LobbyTornException when the only member leaves without a seam tear`() =
        runTest {
            // Host-side membership drain: the member vanishes but the seam stays Woven. The host must
            // surface a retryable signal, not silently commit a solo room (the #1468 host bug, which a
            // transport-tear test masks because nw publishes peers→{self} before any Torn latch — here
            // there is no Torn at all).
            val self = PeerId("peer-a")
            val memberId = PeerId("peer-z")
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, memberId))
            val l = lobby(seam, InMemoryLoom(), backgroundScope)
            assertEquals(self, l.host.first()) // self is the elected host

            val room = CompletableDeferred<Room>()
            val driver = launch {
                try {
                    room.complete(l.start(memberName = "Host"))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    room.completeExceptionally(e)
                }
            }
            runCurrent() // start broadcasts the Freeze and awaits the member's FreezeAck

            seam.removePeer(memberId) // member leaves; seam stays Woven (no tear)

            assertFailsWith<LobbyTornException> { withTimeout(5.seconds) { room.await() } }
            driver.cancel()
        }

    @Test
    fun `lone host starts immediately with an empty roster`() =
        runTest {
            val loom = InMemoryLoom()
            val s1 = loom.weave(Rendezvous.New(Pattern("g")))
            val l1 = lobby(s1, loom, backgroundScope)
            val room = l1.start(memberName = "Solo") // no members → immediate commit
            assertEquals(SessionRole.Host, room.role.value)
            room.leave()
        }
}
