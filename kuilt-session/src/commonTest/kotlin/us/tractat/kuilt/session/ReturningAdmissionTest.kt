@file:OptIn(
    us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class,
    us.tractat.kuilt.identity.IdentityInternalApi::class,
)

package us.tractat.kuilt.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.DeviceKeyStore
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.Grant
import us.tractat.kuilt.identity.GrantScope
import us.tractat.kuilt.identity.KeyScheme
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
import us.tractat.kuilt.session.admit.RejectCode
import us.tractat.kuilt.session.admit.ReturnKind
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.RoomId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    private companion object {
        /** A host key that is not this room's host's. */
        const val ANOTHER_HOST = 7
    }

    private val bob = PeerId("bob")
    private val bobAgain = PeerId("bob-again")
    private val carol = PeerId("carol")
    private val eve = PeerId("eve")
    private val hostTab = PeerId("host-tab")

    private val bobKey: DeviceKey = fakeDeviceKey(1)
    private val carolKey: DeviceKey = fakeDeviceKey(2)
    private val eveKey: DeviceKey = fakeDeviceKey(3)

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

    /**
     * The host's own store, whose scheme counts every pass signature it is asked to check ([checks]),
     * and throws on one, while [failing]: a platform provider that cannot run. Every other check — a
     * joiner's proof — goes through uncounted.
     *
     * The count is what pins the host's cap on signature checks and its cheap-field filter, which no
     * verdict shows: a pass checked and found bad, and a pass set aside unchecked, both leave the
     * device vetted.
     */
    private class FailsPassChecks(private val inner: DeviceKeyStore) : DeviceKeyStore by inner {
        var failing = false
        var failed = 0
        var checks = 0

        override val keyScheme: KeyScheme
            get() {
                val scheme = inner.keyScheme
                return object : KeyScheme by scheme {
                    override suspend fun verify(key: DidKey, message: ByteArray, signature: ByteArray): Boolean {
                        if ("kuilt/grant" in message.decodeToString()) {
                            checks++
                            if (failing) {
                                failed++
                                error("the platform provider failed")
                            }
                        }
                        return scheme.verify(key, message, signature)
                    }
                }
            }
    }

    /** This pass with its signature's last byte changed: the same fields, signed by nobody. */
    private fun Grant.withForgedSignature(): Grant =
        assertNotNull(Grant.decode(wire.also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }), "a forged signature still decodes")

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
     * A device that has played many rooms with this host holds a pass for each: its store keeps one
     * per issuer and scope, never drops one that ran out, and puts a new room's pass last. Coming back
     * here, it must still be let in on this room's pass. The joiner presents only the passes good for
     * the room its challenger named, so the Proof does not grow with every room it has played.
     */
    @Test
    fun aDeviceThatPlayedManyRoomsReturnsOnThisRoomsPass() = runTest {
        val board = Switchboard(setOf(KEY_SEAT_HOST, bob))
        val counted = Counted()
        val host = boardRoom(board, KEY_SEAT_HOST, fakeKeyStore(HOST_DEVICE), SessionRole.Host, admission = counted.admission)
        val bobStore = fakeKeyStore(1)
        val hostSigner = fakeDeviceKey(HOST_DEVICE)
        val now = testScheduler.currentTime
        (1..5).forEach { n ->
            bobStore.keepGrant(Grant.issue(hostSigner, bobKey.id, GrantScope.Room("other-room-$n"), issuedAt = now, notAfter = now + 1.hours.inWholeMilliseconds))
        }
        val firstBob = boardRoom(board, bob, bobStore, SessionRole.Joiner)
        testScheduler.runCurrent()
        val held = bobStore.grants().map { it.scope }
        firstBob.leave()
        testScheduler.runCurrent()
        val vettedBefore = counted.asked.size

        val proofsBefore = board.sent(from = bob, to = KEY_SEAT_HOST).filterIsInstance<AdmitMessage.Proof>().size
        val hostLog = EventLog(host, backgroundScope)
        val mark = hostLog.mark()
        val secondBob = boardRoom(board, bob, bobStore, SessionRole.Joiner)
        testScheduler.runCurrent()
        val proof = board.sent(from = bob, to = KEY_SEAT_HOST).filterIsInstance<AdmitMessage.Proof>().drop(proofsBefore).single()

        assertAll(
            { assertEquals(6, held.size, "rig: Bob holds five other rooms' passes and this one's: $held") },
            { assertEquals<GrantScope>(GrantScope.Room(KEY_SEAT_ROOM.value), held.last(), "rig: this room's pass is held last") },
            { assertEquals(1, vettedBefore, "rig: Bob was vetted once, when he first joined") },
            {
                assertEquals(
                    listOf<GrantScope>(GrantScope.Room(KEY_SEAT_ROOM.value)),
                    proof.grants.map { assertNotNull(Grant.decode(it)).scope },
                    "Bob presents only the pass good in this room",
                )
            },
            { assertEquals(vettedBefore, counted.asked.size, "he comes back unvetted") },
            {
                assertEquals(
                    listOf(ReturningMark(previousId = bob)),
                    hostLog.since(mark).joins().filter { it.member.id == bob }.map { it.returning },
                    "and is marked returning",
                )
            },
        )
        secondBob.leave()
        host.leave()
    }

    /**
     * The host's half of the same: a Proof crowded with passes from this host for other rooms, or run
     * out, or for another device, or from another host, ahead of the good one. The host sets aside
     * every pass whose issuer, subject, scope or end already rules it out, before checking any
     * signature, so the good one is reached however many come first.
     */
    @Test
    fun aGoodPassBehindManyThatCannotApplyIsStillFound() = runTest {
        val counted = Counted()
        val hostStore = FailsPassChecks(fakeKeyStore(HOST_DEVICE))
        val host = rawHost(joiners = setOf(bob), store = hostStore, admission = counted.admission)
        host.admit(bob, bobKey, nonce = 1)
        val good = host.passesTo(bob).single()
        host.say(bob, AdmitMessage.Goodbye)
        val hostSigner = fakeDeviceKey(HOST_DEVICE)
        val now = testScheduler.currentTime
        val otherRooms = (1..6).map { n ->
            Grant.issue(hostSigner, bobKey.id, GrantScope.Room("other-room-$n"), issuedAt = now, notAfter = now + 1.hours.inWholeMilliseconds)
        }
        val runOut = Grant.issue(hostSigner, bobKey.id, GrantScope.AnyRoomOfIssuer, issuedAt = now - 2, notAfter = now - 1)
        val anotherDevices = Grant.issue(hostSigner, carolKey.id, GrantScope.AnyRoomOfIssuer, issuedAt = now, notAfter = now + 1.hours.inWholeMilliseconds)
        // As many as the cap, so the good pass is reached only if every one is set aside unchecked: a
        // signature check on another host's pass stops at its issuer, so only the cap can see it counted.
        val anotherHosts = (1..4).map {
            Grant.issue(fakeDeviceKey(ANOTHER_HOST), bobKey.id, GrantScope.AnyRoomOfIssuer, issuedAt = now, notAfter = now + 1.hours.inWholeMilliseconds)
        }

        val anotherHostSigned = anotherHosts.all { it.isSignedByIssuer(hostStore.keyScheme) }
        val checksBefore = hostStore.checks
        host.admit(bob, bobKey, nonce = 2, grants = otherRooms + runOut + anotherDevices + anotherHosts + good)

        assertAll(
            { assertTrue(anotherHosts.none { it.issuer == host.hostKey } && anotherHostSigned, "rig: four genuine passes, from another host") },
            { assertEquals(1, counted.asked.size, "the good pass is found behind twelve that cannot apply") },
            { assertEquals(ReturnKind.Returning, host.directWelcomesTo(bob).last().returnKind) },
            {
                assertEquals(
                    1,
                    hostStore.checks - checksBefore,
                    "the twelve are set aside on their fields — room, end, subject and issuer: only the good pass's signature is checked",
                )
            },
        )
        host.room.leave()
    }

    /**
     * [revokeALostDeviceSample], run: Bob's pass is void the moment his device is revoked, so his
     * return on it is vetted as a newcomer's. The cutoff is one past the clock, so even a pass issued in
     * that same millisecond is void.
     */
    @Test
    fun revokeALostDeviceSampleVoidsThePassTheDeviceHolds() = runTest {
        val counted = Counted()
        val host = rawHost(joiners = setOf(bob), admission = counted.admission)
        host.admit(bob, bobKey, nonce = 1)
        val pass = host.passesTo(bob).single()
        host.say(bob, AdmitMessage.Goodbye)
        val clock = virtualClock()

        revokeALostDeviceSample(counted.admission, bobKey.id, clock)
        host.admit(bob, bobKey, nonce = 2, grants = listOf(pass))

        assertAll(
            { assertEquals(pass.issuedAt, clock().toEpochMilliseconds(), "rig: the pass was issued in the same millisecond as the revocation") },
            { assertEquals(clock().toEpochMilliseconds() + 1, counted.revocations.notBefore(bobKey.id), "the cutoff is one past the clock") },
            { assertEquals(listOf(bobKey.id, bobKey.id), counted.keys(), "Bob's return on the voided pass is vetted") },
            { assertNull(host.directWelcomesTo(bob).last().returnKind, "an ordinary admission, not a return") },
        )
        host.room.leave()
    }

    /**
     * Anyone sent a challenge may send a Proof, so the host checks at most four pass signatures from
     * one, however many passes it carries. Six passes that the cheap fields cannot rule out — this
     * host's, for this device and this room, not run out — with signatures nobody made cost four
     * checks, and the device is vetted as a newcomer.
     */
    @Test
    fun atMostFourPassSignaturesAreCheckedPerProof() = runTest {
        val counted = Counted()
        val hostStore = FailsPassChecks(fakeKeyStore(HOST_DEVICE))
        val host = rawHost(joiners = setOf(bob), store = hostStore, admission = counted.admission)
        val hostSigner = fakeDeviceKey(HOST_DEVICE)
        val now = testScheduler.currentTime
        val forged = (1..6).map {
            Grant.issue(hostSigner, bobKey.id, GrantScope.Room(KEY_SEAT_ROOM.value), issuedAt = now, notAfter = now + 1.hours.inWholeMilliseconds)
                .withForgedSignature()
        }

        val checksBefore = hostStore.checks
        host.admit(bob, bobKey, nonce = 1, grants = forged)
        val checks = hostStore.checks - checksBefore
        val signedByTheHost = forged.count { it.isSignedByIssuer(hostStore.keyScheme) }

        assertAll(
            { assertEquals(6, forged.map { it.id.toList() }.toSet().size, "rig: six distinct passes") },
            {
                assertTrue(
                    forged.all { it.issuer == host.hostKey && it.subject == bobKey.id && it.scope == GrantScope.Room(KEY_SEAT_ROOM.value) && it.notAfter > now },
                    "rig: no field rules any of them out, so each would cost a check",
                )
            },
            { assertEquals(0, signedByTheHost, "rig: none is signed by the host") },
            { assertEquals(4, checks, "the host checks four signatures, not six") },
            { assertEquals(listOf(bobKey.id), counted.keys(), "none is good: an ordinary admission") },
        )
        host.room.leave()
    }

    /**
     * A host that cannot run a pass's signature check — its platform provider fails — has not found
     * the pass bad: it says so with a retryable `Reject(HostFailed)` naming the Hello, as it does for
     * a proof it cannot check, rather than send a device inside its pass to the policy. An undecodable
     * pass is no fault of the host's, and is set aside.
     */
    @Test
    fun aPassTheHostCannotCheckIsAHostFailureNotAVetting() = runTest {
        val counted = Counted()
        val hostStore = FailsPassChecks(fakeKeyStore(HOST_DEVICE))
        val host = rawHost(joiners = setOf(bob), store = hostStore, admission = counted.admission)
        host.admit(bob, bobKey, nonce = 1)
        val pass = host.passesTo(bob).single()
        host.say(bob, AdmitMessage.Goodbye)

        hostStore.failing = true
        host.admit(bob, bobKey, nonce = 2, grants = listOf(pass))
        val refused = host.rejectsTo(bob)
        val checksFailed = hostStore.failed
        val vettedAfterFailure = counted.asked.size
        hostStore.failing = false
        host.admit(bob, bobKey, nonce = 3, grants = listOf(pass))

        assertAll(
            { assertEquals(1, checksFailed, "rig: the pass's check failed once") },
            { assertEquals(listOf(RejectCode.HostFailed), refused.map { it.code }, "the host says it failed") },
            { assertTrue(refused.singleOrNull()?.code?.retryable == true, "retryably") },
            { assertTrue(refused.singleOrNull()?.answers?.contentEquals(host.hello("bob", bobKey.id, 2).joinerNonce) == true, "naming the Hello") },
            { assertEquals(1, vettedAfterFailure, "the policy was not asked about the Hello whose pass could not be checked") },
            { assertTrue(host.member(bob) != null && counted.asked.size == 1, "the retry, once the provider runs, comes back on the pass unvetted") },
        )
        host.room.leave()
    }

    /** An undecodable pass is set aside, and the device decided as if it presented none. */
    @Test
    fun anUndecodablePassIsIgnored() = runTest {
        val counted = Counted()
        val host = rawHost(joiners = setOf(bob), admission = counted.admission)
        val challenge = run {
            host.say(bob, host.hello("bob", bobKey.id, 1))
            host.challengesTo(bob).last()
        }
        val proof = host.proof(challenge, bobKey, bob)
        host.say(bob, AdmitMessage.Proof(proof.statement, listOf(byteArrayOf(1, 2, 3))))

        assertAll(
            { assertTrue(host.rejectsTo(bob).isEmpty(), "no refusal") },
            { assertEquals(listOf(bobKey.id), counted.keys(), "an ordinary admission") },
        )
        host.room.leave()
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

    // ── A seat released for a device that holds a good pass (rules 6–7) ──────

    /**
     * Eve's device claims Bob's id while Bob's seat is held partitioned, and Eve holds a good pass of
     * her own from an earlier visit. Bob's seat is released for her (Decided 18), and the key decided
     * again without it holds a good pass, so she comes back on it: the policy is not asked, and she is
     * marked as her own device returning (Decided 23, the design's row 6, its Returning arm).
     */
    @Test
    fun aDifferentDeviceOnAPartitionedIdReturnsOnItsOwnPass() = runTest {
        val counted = Counted()
        val host = rawHost(joiners = setOf(bob, carol, eve), admission = counted.admission)
        host.admit(eve, eveKey, nonce = 1)
        val evesPass = host.passesTo(eve).single()
        host.say(eve, AdmitMessage.Goodbye)
        host.admit(bob, bobKey, nonce = 2)
        host.admit(carol, carolKey, nonce = 3)
        host.partition(bob, alive = setOf(carol))
        val vettedBefore = counted.keys()

        val mark = host.events.mark()
        host.admit(bob, eveKey, nonce = 4, grants = listOf(evesPass))

        assertAll(
            { assertEquals(listOf(eveKey.id, bobKey.id, carolKey.id), vettedBefore, "rig: each device was vetted once") },
            { assertTrue(host.member(eve) == null, "rig: Eve's own seat was gone") },
            { assertEquals(vettedBefore, counted.keys(), "Eve comes back on her pass: the policy is not asked") },
            {
                assertEquals(
                    listOf("Left(bob, ${LeaveReason.PartitionExpired})", "Joined(bob)"),
                    host.events.edgesSince(mark).map { it.describe() },
                    "Bob's seat is released, and Eve joins under the id",
                )
            },
            { assertEquals(listOf(ReturningMark(previousId = eve)), host.events.since(mark).joins().map { it.returning }, "marked as Eve's device returning") },
            { assertEquals(ReturnKind.Returning, host.directWelcomesTo(bob).last().returnKind) },
            { assertEquals(eveKey.id, host.member(bob)?.key, "the id is Eve's now") },
        )
        host.room.leave()
    }

    /**
     * A revoked device's held seat is released at its own Hello, and the device is decided again
     * without it (Decided 14). When it holds a pass issued at or after the cutoff — here the one its
     * second process was given on an ordinary admission after the revocation — it comes back on that
     * pass, unvetted (Amendment M): pass revocation reads the pass's `issuedAt`, seat revocation the
     * seat's admission.
     */
    @Test
    fun aRevokedSeatIsReleasedForAPassIssuedAfterTheCutoff() = runTest {
        val counted = Counted()
        val host = rawHost(joiners = setOf(bob, bobAgain, carol), admission = counted.admission)
        val admittedAt = testScheduler.currentTime
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)
        host.partition(bob, alive = setOf(carol))
        val cutoff = testScheduler.currentTime
        counted.revocations.revoke(bobKey.id, notBefore = cutoff)
        host.admit(bobAgain, bobKey, nonce = 3)
        val laterPass = host.passesTo(bobAgain).single()
        host.say(bobAgain, AdmitMessage.Goodbye)
        val heldWhenBobReturns = host.member(bob)?.liveness
        val vettedBefore = counted.keys()

        val mark = host.events.mark()
        host.admit(bob, bobKey, nonce = 4, grants = listOf(laterPass))

        assertAll(
            { assertTrue(admittedAt < cutoff, "rig: Bob's seat was admitted before the cutoff") },
            { assertTrue(laterPass.issuedAt >= cutoff, "rig: the pass Bob presents was issued at or after it") },
            { assertIs<Liveness.Partitioned>(heldWhenBobReturns, "rig: Bob's revoked seat was still held") },
            { assertEquals(listOf(bobKey.id, carolKey.id, bobKey.id), vettedBefore, "rig: the second process was vetted, after the revocation") },
            { assertEquals(vettedBefore, counted.keys(), "on the later pass Bob comes back unvetted") },
            {
                assertEquals(
                    listOf("Left(bob, ${LeaveReason.PartitionExpired})", "Joined(bob)"),
                    host.events.edgesSince(mark).map { it.describe() },
                    "the revoked seat is released, and Bob joins as a new membership",
                )
            },
            { assertEquals(ReturnKind.Returning, host.directWelcomesTo(bob).last().returnKind, "marked as a return") },
        )
        host.room.leave()
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

    /**
     * A pass is checked when its Proof arrives, and an exchange can then wait on the window controller
     * before it decides. A revocation landing in that wait still voids the pass: the decision reads
     * the pass's revocation again, under the lock that decides. Bob's seat, revoked with it, is
     * released, and Bob is vetted as a newcomer rather than let back in on a pass that is void by then.
     */
    @Test
    fun aRevocationWhileTheExchangeWaitsVoidsThePass() = runTest {
        val counted = Counted()
        val gate = CompletableDeferred<Unit>()
        val host = rawHost(
            joiners = setOf(bob, carol),
            hold = ObservedHold(verdict = { ResumeResult.WindowClosed }, gate = gate),
            admission = counted.admission,
        )
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)
        val pass = host.passesTo(bob).single()
        host.partition(bob, alive = setOf(carol))

        host.admit(bob, bobKey, nonce = 3, grants = listOf(pass))
        val askingWhileRevoked = host.hold.calls.size
        counted.revocations.revoke(bobKey.id, notBefore = testScheduler.currentTime + 1)
        gate.complete(Unit)
        testScheduler.runCurrent()

        assertAll(
            { assertEquals(1, askingWhileRevoked, "rig: the exchange was waiting on the controller when the revocation landed") },
            { assertEquals(listOf(bobKey.id, carolKey.id, bobKey.id), counted.keys(), "Bob is vetted: his pass was void by the time it was decided") },
            { assertNull(host.directWelcomesTo(bob).last().returnKind, "an ordinary admission, not a return") },
            { assertTrue(host.member(bob) != null, "and the open policy admits him") },
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
        val vettedAtFirst = counted.keys()
        val pass = host.passesTo(hostTab).single()
        host.say(hostTab, AdmitMessage.Goodbye)

        host.admit(hostTab, hostDevice, nonce = 2, grants = listOf(pass))

        assertAll(
            { assertEquals(listOf(host.hostKey), vettedAtFirst, "rig: the first tab, on the host's key, was vetted as an ordinary admission") },
            { assertEquals(vettedAtFirst, counted.keys(), "its return on the pass is not vetted") },
            { assertEquals(ReturnKind.Returning, host.directWelcomesTo(hostTab).last().returnKind, "its return on the pass is marked") },
            { assertTrue(host.member(hostTab) != null, "and seated") },
        )
        host.room.leave()
    }
}
