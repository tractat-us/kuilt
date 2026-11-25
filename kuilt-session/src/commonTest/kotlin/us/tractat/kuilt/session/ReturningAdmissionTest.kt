@file:OptIn(
    us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class,
    us.tractat.kuilt.identity.IdentityInternalApi::class,
)

package us.tractat.kuilt.session

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.Grant
import us.tractat.kuilt.identity.GrantScope
import us.tractat.kuilt.identity.RevocationList
import us.tractat.kuilt.identity.test.fakeDeviceKey
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.session.admit.AdmissionDecision
import us.tractat.kuilt.session.admit.AdmissionPolicy
import us.tractat.kuilt.session.admit.AdmissionRequest
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.GrantReach
import us.tractat.kuilt.session.admit.GrantTerms
import us.tractat.kuilt.session.admit.HostAdmission
import us.tractat.kuilt.session.admit.ReturnKind
import us.tractat.kuilt.session.partition.RoomId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * A device whose seat is gone, but which still holds a good pass from this host, comes back without
 * the host's admission policy being asked (identity Task 2.6, #3222; Decided 1's middle tier).
 *
 * Every test counts the policy's calls, since that count is the whole difference between a return
 * on a pass (0) and an ordinary admission (1): a host that ignored the pass, or trusted a bad one,
 * reds on it. Each refusal case also asserts its rig — the pass it presents is real, and would have
 * been good but for the one thing the case changes.
 */
class ReturningAdmissionTest {
    private val bob = PeerId("bob")
    private val bobAgain = PeerId("bob-again")
    private val carol = PeerId("carol")
    private val hostTab = PeerId("host-tab")

    private val bobKey: DeviceKey = fakeDeviceKey(1)
    private val carolKey: DeviceKey = fakeDeviceKey(2)

    /** A policy that counts the requests it is asked about and admits each on [terms]. */
    private class Counted(private val terms: (AdmissionRequest) -> GrantTerms = { GrantTerms(1.hours) }) {
        val asked = mutableListOf<AdmissionRequest>()
        val revocations: RevocationList = RevocationList.inMemory()
        val admission = HostAdmission(
            policy = AdmissionPolicy { request ->
                asked += request
                AdmissionDecision.Admit(terms(request))
            },
            revocations = revocations,
        )

        fun keys(): List<DidKey> = asked.map { it.key }
    }

    private fun RawHost.directWelcomesTo(peer: PeerId): List<AdmitMessage.Welcome> =
        sentTo(peer).welcomesFor(peer).filter { it.membership != null }

    private fun List<MembershipEvent>.joins(): List<MembershipEvent.Joined> = filterIsInstance<MembershipEvent.Joined>()

    // ── A pass lets the device back in ───────────────────────────────────────

    /**
     * Bob leaves cleanly and comes back under a new id with the pass he was given: the policy is not
     * asked, the host emits `Joined(bob-again, ReturningMark(bob))`, the direct Welcome and Carol's
     * roster copy are both marked [ReturnKind.Returning] and name Bob's old id, and the new pass keeps
     * the term the old one had.
     */
    @Test
    fun returnInsideGrantSkipsPolicyAndIsMarked() = runTest {
        val counted = Counted()
        val host = rawHost(joiners = setOf(bob, bobAgain, carol), admission = counted.admission)
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)
        val pass = host.passesTo(bob).last()
        host.say(bob, AdmitMessage.Goodbye)
        val vettedBefore = counted.keys()

        val mark = host.events.mark()
        val toCarol = host.sentTo(carol).size
        host.admit(bobAgain, bobKey, nonce = 3, grants = listOf(pass))

        val direct = host.directWelcomesTo(bobAgain).single()
        val copy = host.sentTo(carol).drop(toCarol).welcomesFor(bobAgain).single()
        val reissued = assertNotNull(direct.grant?.let(Grant::decode), "the return is issued a fresh pass")
        assertAll(
            { assertEquals(listOf(bobKey.id, carolKey.id), vettedBefore, "rig: Bob and Carol were each vetted once") },
            { assertTrue(host.member(bob) == null, "rig: Bob's seat was gone when he came back") },
            { assertEquals(vettedBefore, counted.keys(), "a return on a good pass never asks the policy") },
            {
                assertEquals(
                    listOf(MembershipEvent.Joined(assertNotNull(host.member(bobAgain)), ReturningMark(previousId = bob))),
                    host.events.since(mark).joins(),
                    "the host marks the join as Bob's device returning",
                )
            },
            { assertEquals(ReturnKind.Returning, direct.returnKind, "the direct Welcome is marked") },
            { assertEquals(bob.value, direct.previousPeerId, "and names the id Bob last held") },
            { assertEquals(ReturnKind.Returning, copy.returnKind, "Carol's roster copy is marked too") },
            { assertEquals(bob.value, copy.previousPeerId) },
            { assertNull(copy.grant, "Carol is not handed Bob's pass") },
            { assertEquals(pass.notAfter - pass.issuedAt, reissued.notAfter - reissued.issuedAt, "the fresh pass keeps the term Bob presented") },
            { assertEquals(bobKey.id, host.member(bobAgain)?.key) },
        )
        host.room.leave()
    }

    /**
     * The same, end to end: a real joiner presents the pass it kept, and a real bystander reads the
     * mark off the host's Welcome. Bob's rebuilt process reuses his peer id, so the mark names it.
     */
    @Test
    fun aRealDevicePresentsItsPassAndABystanderSeesTheMark() = runTest {
        val board = Switchboard(setOf(KEY_SEAT_HOST, bob, carol))
        val counted = Counted()
        val host = boardRoom(board, KEY_SEAT_HOST, fakeKeyStore(HOST_DEVICE), SessionRole.Host, admission = counted.admission)
        val bobStore = fakeKeyStore(1)
        val firstBob = boardRoom(board, bob, bobStore, SessionRole.Joiner)
        val carolRoom = boardRoom(board, carol, fakeKeyStore(2), SessionRole.Joiner)
        testScheduler.runCurrent()
        val carolLog = EventLog(carolRoom, backgroundScope)
        val keptBefore = bobStore.grants()
        firstBob.leave()
        testScheduler.runCurrent()
        val gone = host.roster.value.none { it.id == bob }
        val vettedBefore = counted.asked.size

        val mark = carolLog.mark()
        val secondBob = boardRoom(board, bob, bobStore, SessionRole.Joiner)
        testScheduler.runCurrent()

        val proofs = board.sent(from = bob, to = KEY_SEAT_HOST).filterIsInstance<AdmitMessage.Proof>()
        assertAll(
            { assertEquals(2, vettedBefore, "rig: Bob and Carol were each vetted once") },
            { assertEquals(1, keptBefore.size, "rig: Bob kept the pass his Welcome carried") },
            { assertTrue(gone, "rig: Bob's seat was gone before he came back") },
            { assertEquals(keptBefore.map { it.wire.toList() }, proofs.last().grants.map { it.toList() }, "Bob's Proof presents the pass he kept") },
            { assertEquals(vettedBefore, counted.asked.size, "the host let him back in without asking") },
            { assertTrue(host.roster.value.any { it.id == bob }, "Bob is a member again") },
            {
                assertEquals(
                    listOf(ReturningMark(previousId = bob)),
                    carolLog.since(mark).joins().filter { it.member.id == bob }.map { it.returning },
                    "Carol hears Bob's device return",
                )
            },
        )
        secondBob.leave()
        carolRoom.leave()
        host.leave()
    }

    /**
     * A friend's pass — no expiry, good in any room of this host — is carried forward on its return,
     * although the policy would now hand a stranger's one-hour, this-room pass: the policy is not asked,
     * and the return keeps the terms it presented (plan decision on carried terms; Decided 2).
     */
    @Test
    fun friendsNoExpiryAnyRoomGrantIsCarriedForward() = runTest {
        val friend = GrantTerms(Duration.INFINITE, GrantReach.AnyRoomOfIssuer)
        var firstCall = true
        val counted = Counted { if (firstCall) friend.also { firstCall = false } else GrantTerms(1.hours) }
        val host = rawHost(joiners = setOf(bob, bobAgain), admission = counted.admission)
        host.admit(bob, bobKey, nonce = 1)
        val pass = host.passesTo(bob).single()
        host.say(bob, AdmitMessage.Goodbye)

        host.admit(bobAgain, bobKey, nonce = 2, grants = listOf(pass))

        val reissued = host.passesTo(bobAgain).single()
        assertAll(
            { assertEquals(Grant.NO_EXPIRY, pass.notAfter, "rig: Bob's first pass never expires") },
            { assertEquals(1, counted.asked.size, "the policy was asked once, at Bob's first admission") },
            { assertEquals(Grant.NO_EXPIRY, reissued.notAfter, "the returning pass still never expires") },
            { assertEquals<GrantScope>(GrantScope.AnyRoomOfIssuer, reissued.scope, "and is still good in any room") },
            { assertTrue(!(host.room as SeamRoom).renewsPassForTest(bobAgain), "a pass that never expires is never renewed") },
        )
        host.room.leave()
    }

    // ── What the host remembers of a departed member ─────────────────────────

    /**
     * The host remembers a departed member's key and peer id until the last pass it gave that
     * membership runs out, and no longer (spec *Membership and the wire*). Counted with the room's
     * own probe, since nothing else can see the record once the pass that could use it is gone.
     */
    @Test
    fun departedRecordIsForgottenAtGrantExpiry() = runTest {
        val term = 10.minutes
        val host = rawHost(joiners = setOf(bob), admission = Counted { GrantTerms(term) }.admission)
        val room = host.room as SeamRoom
        host.admit(bob, bobKey, nonce = 1)
        host.advanceKeeping(setOf(bob), 3.minutes)
        val pass = host.passesTo(bob).last()
        val beforeLeaving = room.departedRecordCountForTest()
        host.say(bob, AdmitMessage.Goodbye)
        val afterLeaving = room.departedRecordCountForTest()
        testScheduler.advanceTimeBy(pass.notAfter - testScheduler.currentTime - 1)
        testScheduler.runCurrent()
        val justBefore = room.departedRecordCountForTest()
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        val atExpiry = room.departedRecordCountForTest()

        assertAll(
            { assertEquals(0, beforeLeaving, "rig: a present member is not a departed one") },
            { assertEquals(1, afterLeaving, "Bob's departure is remembered") },
            { assertEquals(1, justBefore, "while his last pass is good") },
            { assertEquals(0, atExpiry, "and forgotten the instant it runs out") },
        )
        host.room.leave()
    }

    /**
     * A host that restarts over the same key store keeps trusting its own passes: one good in any of
     * its rooms comes back unvetted whatever the new room's id, and one for this room only comes back
     * unvetted only where the app passed the same stable room id. A minted id is fresh per process,
     * so a this-room pass is for another room there, and ordinary admission follows. The restarted
     * host remembers no departure, so the mark names no previous id.
     */
    @Test
    fun anyRoomGrantSurvivesHostRestartRoomGrantNeedsStableRoomId() = runTest {
        val hostStore = fakeKeyStore(HOST_DEVICE)

        suspend fun passFrom(terms: GrantTerms): Grant {
            val first = rawHost(joiners = setOf(bob), store = hostStore, admission = Counted { terms }.admission)
            first.admit(bob, bobKey, nonce = 1)
            val pass = first.passesTo(bob).single()
            first.room.leave()
            return pass
        }

        suspend fun returnTo(roomId: RoomId?, pass: Grant): Pair<Int, MembershipEvent.Joined?> {
            val counted = Counted()
            val restarted = rawHost(joiners = setOf(bob), store = hostStore, admission = counted.admission, roomId = roomId)
            restarted.admit(bob, bobKey, nonce = 2, grants = listOf(pass))
            val joined = restarted.events.since(0).joins().singleOrNull { it.member.id == bob }
            restarted.room.leave()
            return counted.asked.size to joined
        }

        val roomPass = passFrom(GrantTerms(1.hours))
        val anyRoomPass = passFrom(GrantTerms(1.hours, GrantReach.AnyRoomOfIssuer))
        val (roomStable, roomStableJoin) = returnTo(KEY_SEAT_ROOM, roomPass)
        val (roomMinted, roomMintedJoin) = returnTo(null, roomPass)
        val (anyMinted, anyMintedJoin) = returnTo(null, anyRoomPass)

        assertAll(
            { assertEquals<GrantScope>(GrantScope.Room(KEY_SEAT_ROOM.value), roomPass.scope, "rig: a this-room pass") },
            { assertEquals<GrantScope>(GrantScope.AnyRoomOfIssuer, anyRoomPass.scope, "rig: an any-room pass") },
            { assertEquals(0, roomStable, "a this-room pass, same stable id: unvetted") },
            { assertEquals(ReturningMark(previousId = null), roomStableJoin?.returning, "a restarted host remembers no previous id") },
            { assertEquals(1, roomMinted, "a this-room pass, minted id: vetted") },
            { assertNull(roomMintedJoin?.returning, "and not marked") },
            { assertEquals(0, anyMinted, "an any-room pass, minted id: unvetted") },
            { assertEquals(ReturningMark(previousId = null), anyMintedJoin?.returning) },
        )
    }

    // ── A pass is good only with the host that issued it (Decided 11) ────────

    /**
     * After a lobby re-elects, the new host is another device, with another key. A device's passes
     * name the old host as issuer, so the joiner presents none to the new one — it presents only those
     * its challenger issued — and the new host vets it. Modelled here as two hosts with distinct key
     * stores, the one property re-election changes for a returning device.
     */
    @Test
    fun newHostAfterLobbyReElectionTreatsGrantAsUntrusted() = runTest {
        val board = Switchboard(setOf(KEY_SEAT_HOST, bob))
        val bobStore = fakeKeyStore(1)
        val oldHost = boardRoom(board, KEY_SEAT_HOST, fakeKeyStore(HOST_DEVICE), SessionRole.Host)
        val firstBob = boardRoom(board, bob, bobStore, SessionRole.Joiner)
        testScheduler.runCurrent()
        val kept = bobStore.grants()
        firstBob.leave()
        oldHost.leave()
        testScheduler.runCurrent()

        val counted = Counted()
        val newHost = boardRoom(board, KEY_SEAT_HOST, fakeKeyStore(7), SessionRole.Host, admission = counted.admission)
        val proofsBefore = board.sent(from = bob, to = KEY_SEAT_HOST).filterIsInstance<AdmitMessage.Proof>().size
        val secondBob = boardRoom(board, bob, bobStore, SessionRole.Joiner)
        testScheduler.runCurrent()
        val proof = board.sent(from = bob, to = KEY_SEAT_HOST).filterIsInstance<AdmitMessage.Proof>().drop(proofsBefore).single()

        assertAll(
            { assertEquals(1, kept.size, "rig: Bob holds the old host's pass") },
            { assertTrue(proof.grants.isEmpty(), "Bob presents no pass the new host did not issue") },
            { assertEquals(listOf(bobKey.id), counted.keys(), "the new host vets Bob as a newcomer") },
            { assertTrue(newHost.roster.value.any { it.id == bob }, "and admits him") },
        )
        secondBob.leave()
        newHost.leave()
    }

    /**
     * Each cluster server has its own key, so a pass from one is `UntrustedIssuer` at another, even
     * when it is presented anyway: the second server vets the device, and does not mark it.
     */
    @Test
    fun grantFromAnotherServersKeyIsUntrusted() = runTest {
        val serverA = rawHost(joiners = setOf(bob), store = fakeKeyStore(HOST_DEVICE), admission = Counted().admission)
        serverA.admit(bob, bobKey, nonce = 1)
        val passFromA = serverA.passesTo(bob).single()
        serverA.room.leave()

        val counted = Counted()
        val serverB = rawHost(joiners = setOf(bob), store = fakeKeyStore(7), admission = counted.admission)
        serverB.admit(bob, bobKey, nonce = 2, grants = listOf(passFromA))

        assertAll(
            { assertEquals(serverA.hostKey, passFromA.issuer, "rig: the pass is server A's") },
            { assertTrue(serverA.hostKey != serverB.hostKey, "rig: the servers' keys differ") },
            { assertEquals(listOf(bobKey.id), counted.keys(), "server B vets Bob") },
            { assertNull(serverB.directWelcomesTo(bob).single().returnKind, "an ordinary admission, not marked") },
        )
        serverB.room.leave()
    }

    // ── What a pass cannot do ────────────────────────────────────────────────

    /**
     * A device revoked after its pass was issued comes back through the policy (Decided 14, 27): the
     * pass is `Revoked`. The fresh pass that admission hands it is dated after the revocation, so on
     * the return after that it comes back unvetted — revoking voids passes, it is not a ban.
     */
    @Test
    fun aPassIssuedBeforeARevocationIsVetted() = runTest {
        val counted = Counted()
        val host = rawHost(joiners = setOf(bob), admission = counted.admission)
        host.admit(bob, bobKey, nonce = 1)
        val oldPass = host.passesTo(bob).single()
        host.say(bob, AdmitMessage.Goodbye)
        testScheduler.advanceTimeBy(1.minutes)
        counted.revocations.revoke(bobKey.id, notBefore = testScheduler.currentTime)
        testScheduler.advanceTimeBy(1.minutes)

        host.admit(bob, bobKey, nonce = 2, grants = listOf(oldPass))
        val vettedOnRevokedPass = counted.asked.size
        val newPass = host.passesTo(bob).last()
        host.say(bob, AdmitMessage.Goodbye)
        host.admit(bob, bobKey, nonce = 3, grants = listOf(newPass))

        assertAll(
            { assertTrue(oldPass.issuedAt < testScheduler.currentTime - 1.minutes.inWholeMilliseconds, "rig: the old pass predates the revocation") },
            { assertEquals(2, vettedOnRevokedPass, "a pass issued before the revocation is vetted") },
            { assertTrue(newPass.issuedAt > oldPass.issuedAt, "rig: admission issued a newer pass") },
            { assertEquals(2, counted.asked.size, "the pass issued after it lets Bob back unvetted") },
            { assertEquals(ReturnKind.Returning, host.directWelcomesTo(bob).last().returnKind) },
        )
        host.room.leave()
    }

    /** A pass that has run out is vetted: one millisecond before its end it was not. */
    @Test
    fun anExpiredPassIsVetted() = runTest {
        val term = 1.minutes
        val counted = Counted { GrantTerms(term) }
        val host = rawHost(joiners = setOf(bob), admission = counted.admission)
        host.admit(bob, bobKey, nonce = 1)
        val pass = host.passesTo(bob).single()
        host.say(bob, AdmitMessage.Goodbye)
        testScheduler.advanceTimeBy(term)
        testScheduler.runCurrent()

        host.admit(bob, bobKey, nonce = 2, grants = listOf(pass))

        assertAll(
            { assertEquals(pass.notAfter, testScheduler.currentTime, "rig: the pass ran out this instant") },
            { assertEquals(2, counted.asked.size, "an expired pass is vetted") },
            { assertNull(host.directWelcomesTo(bob).last().returnKind) },
        )
        host.room.leave()
    }

    /**
     * A pass never overrides a held seat (rule order, *The key → seat table*): a partitioned seat is
     * restored, and a device whose seats are all connected gets an additional seat, which the policy
     * vets as it would without the pass (plan Amendment C).
     */
    @Test
    fun aPassNeverOverridesAHeldSeat() = runTest {
        val counted = Counted()
        val host = rawHost(joiners = setOf(bob, bobAgain, carol), admission = counted.admission)
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)
        val pass = host.passesTo(bob).single()

        host.admit(bobAgain, bobKey, nonce = 3, grants = listOf(pass))
        val asAdditional = host.directWelcomesTo(bobAgain).single().returnKind
        val vettedForTheTab = counted.asked.size
        host.say(bobAgain, AdmitMessage.Goodbye)
        host.partition(bob, alive = setOf(carol))
        host.admit(bob, bobKey, nonce = 4, grants = listOf(pass))

        assertAll(
            { assertNull(asAdditional, "a second live tab is an additional seat, not a return") },
            { assertEquals(3, vettedForTheTab, "which the policy vets, pass or no pass") },
            { assertEquals(ReturnKind.Restored, host.directWelcomesTo(bob).last().returnKind, "a partitioned seat is restored") },
            { assertEquals(3, counted.asked.size, "and a restore never asks") },
        )
        host.room.leave()
    }

    /**
     * The host's own key, presented by its first tab, is decided by rules 6–7 (plan Amendment J): an
     * ordinary admission the first time, and, once that tab has gone, a return on the pass it was
     * given — no call to the policy.
     */
    @Test
    fun theHostsFirstTabReturnsOnItsPassUnvetted() = runTest {
        val counted = Counted()
        val hostDevice = fakeDeviceKey(HOST_DEVICE)
        val host = rawHost(joiners = setOf(hostTab), admission = counted.admission)
        host.admit(hostTab, hostDevice, nonce = 1)
        val pass = host.passesTo(hostTab).single()
        host.say(hostTab, AdmitMessage.Goodbye)

        host.admit(hostTab, hostDevice, nonce = 2, grants = listOf(pass))

        assertAll(
            { assertEquals(listOf(host.hostKey), counted.keys(), "rig: the first tab, on the host's key, was vetted once") },
            { assertEquals(ReturnKind.Returning, host.directWelcomesTo(hostTab).last().returnKind, "its return on the pass is marked") },
            { assertTrue(host.member(hostTab) != null, "and seated") },
        )
        host.room.leave()
    }
}
