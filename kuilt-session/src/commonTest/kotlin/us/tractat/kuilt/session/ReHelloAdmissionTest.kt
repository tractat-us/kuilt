@file:OptIn(
    us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class,
    us.tractat.kuilt.identity.IdentityInternalApi::class,
)

package us.tractat.kuilt.session

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.test.fakeDeviceKey
import us.tractat.kuilt.identity.test.fakeDidKey
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The host's half of a joiner saying Hello again (#3159, Task 1.7).
 *
 * Once a reconnect is a fresh Hello, a host sees things it never saw from a joiner before: a proof
 * for a challenge it has already replaced, and a Hello from a peer id whose seat moved to another id
 * of the same device. Neither may end the joiner, and the second must end the process that sent it.
 */
class ReHelloAdmissionTest {
    private val bob = PeerId("bob")
    private val bob1 = PeerId("bob-1")
    private val bob2 = PeerId("bob-2")
    private val bobDevice = fakeDeviceKey(1)

    /**
     * Inherited obligation 1: the joiner said Hello twice — a retry — and answered the first
     * challenge before the second reached it. That proof names a host nonce the host no longer holds
     * for it. It is dropped, not refused (`BadProof` is terminal), and the proof for the current
     * challenge still admits, once.
     */
    @Test
    fun aProofForAnOlderChallengeFromTheSameSenderIsDropped() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val h = rawHost(joiners = setOf(bob))
        val mark = h.events.mark()
        h.say(bob, h.hello("Bob", bobDevice.id, nonce = 1))
        h.say(bob, h.hello("Bob", bobDevice.id, nonce = 2))
        val (older, newer) = h.challengesTo(bob).let { assertEquals(2, it.size, "rig: each Hello drew a challenge"); it[0] to it[1] }
        h.say(bob, h.proof(older, bobDevice, bob))
        val rejectsAfterStale = h.rejectsTo(bob)
        val rosterAfterStale = h.room.roster.value.map { it.id }
        h.say(bob, h.proof(newer, bobDevice, bob))

        assertAll(
            { assertEquals(emptyList(), rejectsAfterStale, "the stale proof is not refused") },
            { assertEquals(emptyList(), rosterAfterStale, "the stale proof admits nobody") },
            { assertEquals(emptyList(), h.rejectsTo(bob), "nothing is refused") },
            { assertEquals(listOf(bob), h.events.since(mark).filterIsInstance<MembershipEvent.Joined>().map { it.member.id }) },
        )
        h.room.leave()
    }

    /**
     * Inherited obligation 2, the cross-path case: the joiner's first Hello went by broadcast and its
     * re-Hello by `sendTo`, and on a fabric whose two paths do not keep order the broadcast arrives
     * last. It overwrites the newer challenge, so the joiner's honest proof answers a challenge the
     * host no longer holds. That proof is dropped, not refused, and the joiner's next Hello admits.
     */
    @Test
    fun aStaleHelloOnTheOtherPathDoesNotRefuseTheNewerProof() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val h = rawHost(joiners = setOf(bob))
        val mark = h.events.mark()
        h.say(bob, h.hello("Bob", bobDevice.id, nonce = 2)) // the re-Hello, by sendTo
        val current = h.challengesTo(bob).single()
        h.say(bob, h.hello("Bob", bobDevice.id, nonce = 1)) // the first Hello's broadcast, late
        assertEquals(2, h.challengesTo(bob).size, "rig: the late Hello replaced the challenge")
        h.say(bob, h.proof(current, bobDevice, bob))
        val rejectsAfter = h.rejectsTo(bob)
        val rosterAfter = h.room.roster.value.map { it.id }
        h.admit(bob, bobDevice, nonce = 3)

        assertAll(
            { assertEquals(emptyList(), rejectsAfter, "an honest proof whose challenge was overwritten is not refused") },
            { assertEquals(emptyList(), rosterAfter, "it admits nobody: its challenge is gone") },
            { assertEquals(emptyList(), h.rejectsTo(bob), "nothing is refused") },
            { assertEquals(listOf(bob), h.events.since(mark).filterIsInstance<MembershipEvent.Joined>().map { it.member.id }) },
        )
        h.room.leave()
    }

    /**
     * Inherited obligation 3: the direct `Farewell(supersededBy)` of Decided 19 usually goes into the
     * dead link it was written for. So the host remembers the superseded id, and when that process's
     * reconnect says Hello again it is answered with the same `Farewell`, not challenged — or the
     * process would be admitted to a second seat of its key and the old membership would be back.
     * A Hello under that id with **another** key is another device, and is challenged as usual.
     */
    @Test
    fun aSupersededIdsHelloIsToldItWasSuperseded() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val h = rawHost(joiners = setOf(bob1, bob2))
        h.admit(bob1, bobDevice, nonce = 1)
        h.partition(bob1, alive = emptySet())
        val mark = h.events.mark()
        h.admit(bob2, bobDevice, nonce = 2)
        assertEquals(
            listOf<MembershipEvent>(MembershipEvent.Left(bob1, LeaveReason.Superseded(bob2))),
            h.events.since(mark).filterIsInstance<MembershipEvent.Left>(),
            "rig: bob-2 superseded bob-1",
        )
        val challengesToBob1 = h.challengesTo(bob1).size
        val sentToBob1 = h.sentTo(bob1).size

        h.say(bob1, h.hello("bob-1", bobDevice.id, nonce = 3))
        val answer = h.sentTo(bob1).drop(sentToBob1)
        h.say(bob1, h.hello("bob-1", fakeDidKey(2), nonce = 4))

        assertAll(
            { assertEquals(listOf<AdmitMessage>(AdmitMessage.Farewell(bob1.value, supersededBy = bob2.value)), answer, "the superseded process is told, not challenged") },
            { assertEquals(challengesToBob1 + 1, h.challengesTo(bob1).size, "another device under that id is challenged as usual") },
            { assertEquals(listOf(bob2), h.room.roster.value.map { it.id }, "one membership, bob-2's") },
        )
        h.room.leave()
    }
}
