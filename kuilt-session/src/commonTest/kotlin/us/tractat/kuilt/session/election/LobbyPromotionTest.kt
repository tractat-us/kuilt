package us.tractat.kuilt.session.election

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.SessionRole
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * #1483 — a member promoted to host mid-election must learn it was **promoted**, not that the lobby
 * collapsed. The old signal threw `LobbyTornException(Unreachable)` ("co-electors permanently gone")
 * while the seam was Woven and the co-members were right there, and its KDoc'd recovery
 * (re-run `electLobby`) weaves a *fresh* seam that strands them.
 *
 * Four properties, one per test:
 * 1. promotion with co-members present → [ElectionOutcome.BecameHost];
 * 2. a genuine collapse (drain to `{self}`, or a transport tear) still → [ElectionOutcome.Torn];
 * 3. the **weave-in transient** (`host == self` before a lower peer propagates) produces neither —
 *    the lobby simply carries on as a member;
 * 4. the documented recovery — `start()` on the **same** lobby — actually works, and the co-member
 *    parked in its original `awaitRoom` on the same seam acks it.
 */
class LobbyPromotionTest {
    private fun lobby(seam: Seam, loom: InMemoryLoom, scope: kotlinx.coroutines.CoroutineScope) =
        SeamElectionLobby(
            seam = seam,
            factory = SeamRoomFactory(loom, scope, clock = { Instant.fromEpochMilliseconds(0L) }),
            scope = scope,
            clock = { Instant.fromEpochMilliseconds(0L) },
            roomKey = null,
        )

    // ── 1. Promotion ──────────────────────────────────────────────────────────

    @Test
    fun `a member promoted to host mid-election yields BecameHost, not Torn`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            // 3-peer lobby: peer-a is the elected host; self (peer-m) and peer-z are members.
            val self = PeerId("peer-m")
            val hostId = PeerId("peer-a")
            val other = PeerId("peer-z")
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, hostId, other))
            val l = lobby(seam, InMemoryLoom(), backgroundScope)
            assertEquals(hostId, l.host.first()) // self is a member → awaitRoom is the right call

            val outcome = awaitRoomIn(l, "Member")
            runCurrent()

            // The elected host leaves. self is now the lowest id — but peer-z is right there, the
            // seam is Woven, and nothing has collapsed. "co-electors permanently gone" is a lie.
            seam.removePeer(hostId)

            val result = outcome.await()
            assertAll(
                { assertIs<ElectionOutcome.BecameHost>(result) },
                { assertEquals(setOf(self, other), seam.peers.value) },
                { assertEquals(SeamState.Woven, l.state.value) },
                { assertEquals(self, l.host.value) },
            )
        }

    // ── 2. Genuine collapse ───────────────────────────────────────────────────

    @Test
    fun `a membership drain to self alone still yields Torn`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            // 2-peer lobby: when the host leaves there is nobody left to host FOR. That is the
            // #1466 drain, and it stays Torn — self is trivially the lowest id of a set of one.
            val self = PeerId("peer-z")
            val hostId = PeerId("peer-a")
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, hostId))
            val l = lobby(seam, InMemoryLoom(), backgroundScope)
            assertEquals(hostId, l.host.first())

            val outcome = awaitRoomIn(l, "Member")
            runCurrent()
            seam.removePeer(hostId)

            val result = outcome.await()
            assertAll(
                { assertIs<ElectionOutcome.Torn>(result) },
                { assertEquals(CloseReason.Unreachable, (result as ElectionOutcome.Torn).reason) },
                { assertEquals(setOf(self), seam.peers.value) },
            )
        }

    @Test
    fun `a transport tear with the roster intact still yields Torn`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            // The other collapse dimension: `peers` never shrinks (self is NOT promoted) but the
            // seam latches Torn. Proves Torn is not reachable only through the drain path.
            val self = PeerId("peer-z")
            val hostId = PeerId("peer-a")
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, hostId))
            val l = lobby(seam, InMemoryLoom(), backgroundScope)
            assertEquals(hostId, l.host.first())

            val outcome = awaitRoomIn(l, "Member")
            runCurrent()
            seam.tear(CloseReason.RemoteRequested)

            val result = outcome.await()
            assertAll(
                { assertIs<ElectionOutcome.Torn>(result) },
                { assertEquals(CloseReason.RemoteRequested, (result as ElectionOutcome.Torn).reason) },
                { assertEquals(setOf(self, hostId), seam.peers.value) },
            )
        }

    // ── 3. The weave-in transient ─────────────────────────────────────────────

    @Test
    fun `the weave-in transient does not produce BecameHost`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            // self is TRANSIENTLY the elected host: only a HIGHER-id peer has woven in so far, so
            // `electHost({self, peer-z}) == self`. The real host (peer-a, lower) has not propagated
            // yet. A naive `host.first { it == selfId }` fires right here and sends the caller into
            // a start() it must never run. The roster is non-trivial throughout, so a roster-size
            // check alone cannot tell this apart from the promotion above — only the "have I ever
            // observed a host that was not me?" latch can.
            val self = PeerId("peer-m")
            val other = PeerId("peer-z")
            val late = PeerId("peer-a") // lower than self; arrives after awaitRoom is already parked
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, other))
            val l = lobby(seam, InMemoryLoom(), backgroundScope)
            assertEquals(self, l.host.first()) // the transient: self IS the elected host, briefly

            val outcome = awaitRoomIn(l, "Member")
            runCurrent()
            assertFalse(outcome.isCompleted, "awaitRoom resolved on the weave-in transient")

            // The real host propagates. host flips to peer-a; the lobby must simply carry on.
            seam.addPeer(late)
            runCurrent()
            assertEquals(late, l.host.value)
            assertFalse(outcome.isCompleted, "awaitRoom resolved when a lower peer wove in")

            // Still a live, participating member: it acks peer-a's Freeze.
            seam.deliver(
                late,
                LobbyMessage.encode(
                    LobbyMessage.Freeze(late.value, setOf(late.value, self.value, other.value), 1L),
                ),
            )
            runCurrent()
            assertTrue(
                seam.broadcasts.any { LobbyMessage.decode(it) is LobbyMessage.FreezeAck },
                "the member never acked the real host's Freeze — the transient took it out of the lobby",
            )
            assertFalse(outcome.isCompleted, "awaitRoom resolved before the Commit")
        }

    // ── 4. The recovery ───────────────────────────────────────────────────────

    @Test
    fun `start on the same lobby is the recovery, and the parked co-member acks it`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val loom = InMemoryLoom()
            val s1 = loom.weave(Rendezvous.New(Pattern("g")))
            val s2 = loom.weave(Rendezvous.Existing(InMemoryTag("g")))
            val s3 = loom.weave(Rendezvous.Existing(InMemoryTag("g")))
            val lobbies = listOf(s1, s2, s3).map { lobby(it, loom, backgroundScope) }
            val electedHost = lobbies[0].host.first { lobbies[0].peers.value.size == 3 }
            lobbies.forEach { l -> l.host.first { l.peers.value.size == 3 } }

            val hostLobby = lobbies.first { it.selfId == electedHost }
            val members = lobbies.filter { it.selfId != electedHost }.sortedBy { it.selfId.value }
            val promoted = members[0] // next-lowest id → becomes host when the host walks out
            val parked = members[1]

            val promotedOutcome = async { promoted.awaitRoom(memberName = "Promoted") }
            val parkedOutcome = async { parked.awaitRoom(memberName = "Parked") }
            runCurrent()

            // The elected host walks out of the lobby. It does NOT tear the mesh — the two members
            // are still woven to each other on the SAME seam.
            hostLobby.leave()

            assertIs<ElectionOutcome.BecameHost>(promotedOutcome.await())

            // The recovery: start() on the SAME lobby. The co-member is still parked in its original
            // awaitRoom on the same seam, so it acks the freeze immediately — no re-weave, nothing
            // stranded. (Re-running electLobby would weave a fresh seam the parked member never sees.)
            val promotedRoom = promoted.start(memberName = "Promoted")
            val parkedRoom = assertIs<ElectionOutcome.Adopted>(parkedOutcome.await()).room

            promotedRoom.roster.first { it.size == 1 }
            parkedRoom.roster.first { it.size == 1 }
            assertAll(
                { assertEquals(SessionRole.Host, promotedRoom.role.value) },
                { assertEquals(SessionRole.Joiner, parkedRoom.role.value) },
            )
            parkedRoom.leave()
            promotedRoom.leave()
        }

    /**
     * Launch [ElectionLobby.awaitRoom] in a caught driver so a thrown exception is delivered through
     * the returned [CompletableDeferred] rather than propagating to (and cancelling) the test scope —
     * the old API threw where the new one returns, so a red must be readable, not a scope kill.
     */
    private fun TestScope.awaitRoomIn(
        lobby: ElectionLobby,
        memberName: String,
    ): CompletableDeferred<ElectionOutcome> {
        val outcome = CompletableDeferred<ElectionOutcome>()
        backgroundScope.launch {
            try {
                outcome.complete(lobby.awaitRoom(memberName = memberName))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                outcome.completeExceptionally(e)
            }
        }
        return outcome
    }
}
