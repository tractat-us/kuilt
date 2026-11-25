@file:OptIn(
    us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class,
    us.tractat.kuilt.identity.IdentityInternalApi::class,
)

package us.tractat.kuilt.session

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.test.fakeDeviceKey
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.RejectCode
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The host's half of #3215 (identity slice 1.8): every answer names the Hello it answers, and the
 * direct Welcome names the membership it admits.
 *
 * A joiner acts only on a `Welcome` or `Reject` whose `answers` is the nonce of its latest Hello
 * (`ReHelloReconnectTest` pins that side), so a host answer that named no Hello, or the wrong one,
 * would be dropped by every joiner: an admission that never completes, a refusal heard as silence.
 * Each case reads the frame the host actually sent.
 */
class AnswersNameTheirHelloTest {
    private val bob = PeerId("bob")
    private val carol = PeerId("carol")
    private val bobKey: DeviceKey = fakeDeviceKey(1)
    private val carolKey: DeviceKey = fakeDeviceKey(2)
    private val eveKey: DeviceKey = fakeDeviceKey(3)

    private fun nonce(byte: Byte) = ByteArray(AdmitMessage.Hello.JOINER_NONCE_SIZE) { byte }

    /**
     * The direct Welcome names the Hello it answers and a membership; the roster copy another member
     * receives names neither. The rest of the joiner's own burst names its Hello and no membership,
     * so a joiner can drop a late frame from a burst answering an older Hello (#3615). Two
     * admissions are two memberships.
     */
    @Test
    fun theDirectWelcomeNamesItsHelloAndAMembership() = runTest {
        val host = rawHost(joiners = setOf(bob, carol))
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)

        val toBob = host.sentTo(bob).welcomesFor(bob).single()
        val toCarol = host.sentTo(carol).welcomesFor(carol).single()
        val carolAsBobHeardIt = host.sentTo(bob).welcomesFor(carol).single()
        val bobInCarolsBurst = host.sentTo(carol).welcomesFor(bob).single()
        assertAll(
            { assertTrue(nonce(1).contentEquals(toBob.answers), "Bob's Welcome names his Hello") },
            { assertTrue(nonce(2).contentEquals(toCarol.answers), "Carol's Welcome names hers") },
            { assertNotNull(toBob.membership, "Bob's Welcome names his membership") },
            { assertNotEquals(toBob.membership, toCarol.membership, "two admissions, two memberships") },
            { assertNull(carolAsBobHeardIt.answers, "the roster copy names no Hello") },
            { assertNull(carolAsBobHeardIt.membership, "the roster copy names no membership") },
            { assertTrue(nonce(2).contentEquals(bobInCarolsBurst.answers), "Carol's burst names her Hello on its Welcome for Bob") },
            { assertNull(bobInCarolsBurst.membership, "and names no membership there") },
        )
        host.room.leave()
    }

    /**
     * The host holds one membership id per current member (#3215): a `Goodbye` drops the member's
     * id with the member, and the host's own `leave` drops them all, so a fabric that mints a fresh
     * peer id per connection cannot grow the map. (An expiry's pruning is pinned in
     * `TransportCloseWindowTest`.)
     */
    @Test
    fun aMembershipIdGoesWithItsMember() = runTest {
        val host = rawHost(joiners = setOf(bob, carol))
        host.admit(bob, bobKey, nonce = 12)
        host.admit(carol, carolKey, nonce = 13)
        val room = host.room as SeamRoom
        val whileBothAdmitted = room.heldMembershipCount()
        host.say(bob, AdmitMessage.Goodbye)
        val afterGoodbye = room.heldMembershipCount()
        val bobAfterGoodbye = host.member(bob)
        host.room.leave()
        assertAll(
            { assertEquals(2, whileBothAdmitted, "rig: one id per admitted member") },
            { assertNull(bobAfterGoodbye, "rig: Bob's Goodbye ended his membership") },
            { assertEquals(1, afterGoodbye, "a Goodbye drops the member's id") },
            { assertEquals(0, room.heldMembershipCount(), "the host's leave drops every id") },
        )
    }

    /**
     * Refusals at the Hello gate name the Hello too (#3616 item 3), so a joiner the gate refuses
     * hears why rather than waiting out its deadline: a version this host cannot speak, and a v7
     * Hello missing its key.
     */
    @Test
    fun aRefusalAtTheHelloGateNamesTheHello() = runTest {
        val host = rawHost(joiners = setOf(bob, carol))
        host.say(bob, host.hello("bob", bobKey.id, nonce = 4).copy(protocolVersion = 6))
        host.say(carol, host.hello("carol", carolKey.id, nonce = 5).copy(key = null))

        val toBob = host.rejectsTo(bob).single()
        val toCarol = host.rejectsTo(carol).single()
        assertAll(
            { assertEquals(RejectCode.ProtocolMismatch, toBob.code, "rig: the version gate refused Bob") },
            { assertTrue(nonce(4).contentEquals(toBob.answers), "the version refusal names Bob's Hello") },
            { assertEquals(RejectCode.ProtocolMismatch, toCarol.code, "rig: the keyless gate refused Carol") },
            { assertTrue(nonce(5).contentEquals(toCarol.answers), "the keyless refusal names Carol's Hello") },
        )
        host.room.leave()
    }

    /** A proof signed by the wrong key is refused naming the Hello the challenge answered. */
    @Test
    fun aBadProofRefusalNamesTheHello() = runTest {
        val host = rawHost(joiners = setOf(bob))
        host.say(bob, host.hello("bob", bobKey.id, nonce = 6))
        val challenge = host.challengesTo(bob).single()
        host.say(bob, host.proof(challenge, eveKey, bob))

        val reject = host.rejectsTo(bob).single()
        assertAll(
            { assertEquals(RejectCode.BadProof, reject.code, "rig: the proof was refused") },
            { assertTrue(nonce(6).contentEquals(reject.answers), "the refusal names the Hello") },
        )
        host.room.leave()
    }

    /**
     * Another device claims Bob's id while his seat is connected (#3616 item 1). The refusal names the
     * impostor's Hello, not any Hello of Bob's, so Bob's room — which the `Reject` reaches, since it
     * goes to the id claimed — does not act on it.
     */
    @Test
    fun aKeyConflictNamesTheImpostorsHello() = runTest {
        val host = rawHost(joiners = setOf(bob))
        host.admit(bob, bobKey, nonce = 7)
        host.admit(bob, eveKey, nonce = 8)

        val reject = host.rejectsTo(bob).single()
        assertAll(
            { assertEquals(RejectCode.KeyConflict, reject.code, "rig: the impostor was refused") },
            { assertTrue(nonce(8).contentEquals(reject.answers), "the refusal names the impostor's Hello") },
            { assertEquals(bobKey.id, host.member(bob)?.key, "Bob keeps his seat") },
        )
        host.room.leave()
    }

    /**
     * A takeover under a new id is a new membership (#3215): the joiner under the new id is told an id
     * the old seat never had, and its Welcome names its own Hello.
     */
    @Test
    fun aTakeoverUnderANewIdIsANewMembership() = runTest {
        val bobAgain = PeerId("bob-2")
        val host = rawHost(joiners = setOf(bob, bobAgain, carol))
        host.admit(bob, bobKey, nonce = 9)
        host.admit(carol, carolKey, nonce = 10)
        val held = host.sentTo(bob).welcomesFor(bob).single().membership
        host.partition(bob, alive = setOf(carol))

        host.admit(bobAgain, bobKey, nonce = 11)

        val taken = host.sentTo(bobAgain).welcomesFor(bobAgain).single()
        assertAll(
            { assertNull(host.member(bob), "rig: the old seat was taken over") },
            { assertEquals(bobKey.id, host.member(bobAgain)?.key, "rig: by the same key under the new id") },
            { assertTrue(nonce(11).contentEquals(taken.answers), "the Welcome names the new id's Hello") },
            { assertNotNull(taken.membership) },
            { assertNotEquals(held, taken.membership, "a takeover is a new membership") },
        )
        host.room.leave()
    }
}
