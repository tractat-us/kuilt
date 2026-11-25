@file:OptIn(
    us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class,
    us.tractat.kuilt.identity.IdentityInternalApi::class,
)

package us.tractat.kuilt.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.identity.AdmissionProof
import us.tractat.kuilt.identity.Challenge
import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.DeviceKeyStore
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.RevocationList
import us.tractat.kuilt.identity.test.FakeKeyOperation
import us.tractat.kuilt.identity.test.FakeKeyScheme
import us.tractat.kuilt.identity.test.fakeDeviceKey
import us.tractat.kuilt.identity.test.fakeDidKey
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.session.admit.AdmissionPolicy
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.HostAdmission
import us.tractat.kuilt.session.admit.ProtocolVersion
import us.tractat.kuilt.session.admit.RejectCode
import us.tractat.kuilt.session.partition.RoomId
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Admission requires a verified proof of the device key (#3211): `Hello → Challenge → Proof →
 * Welcome/Reject`. The host admits a joiner only once it has answered the host's signed challenge with
 * a proof signed by the key its Hello named; the joiner admits a host only once that host has signed a
 * challenge under the key its intro names.
 *
 * Most tests drive one side by hand over a [FakeSeam], so each can say exactly which frame arrived and
 * count it. Every test asserts its rig fired: the challenge it answers was sent, the proof it judges
 * was handled. Every device has its own fake key, and every clock is the test scheduler's, so a
 * deadline really passes when the test advances time.
 */
class KeyAdmissionTest {
    private val hostId = PeerId("host")
    private val bob = PeerId("bob")
    private val eve = PeerId("eve")

    /** Bob's and Eve's keys, signed with by hand. Numbered far from the rooms' stores. */
    private val bobKey: DeviceKey = fakeDeviceKey(200)
    private val eveKey: DeviceKey = fakeDeviceKey(201)

    private fun TestScope.clock(): () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }

    /** A host room over a [FakeSeam] that already sees [joiners]. */
    private suspend fun TestScope.hostRoom(
        store: DeviceKeyStore = fakeKeyStore(0),
        joiners: Set<PeerId> = setOf(bob, eve),
    ): Pair<FakeSeam, Room> {
        val seam = FakeSeam(selfId = hostId, initialPeers = setOf(hostId) + joiners)
        val room = SeamRoomFactory(InMemoryLoom(), backgroundScope, clock(), store, admitTimeout = ADMIT_TIMEOUT)
            .adopt(seam, AdoptRole.Host(HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory()), roomId = ROOM), memberName = "Host")
        runCurrent()
        return seam to room
    }

    private fun hello(name: String, key: DidKey, nonce: Byte, deviceId: String? = null) = AdmitMessage.Hello(
        displayName = name,
        sessionId = "session-$name",
        deviceId = deviceId,
        protocolVersion = ProtocolVersion.CURRENT,
        key = key,
        joinerNonce = ByteArray(AdmitMessage.Hello.JOINER_NONCE_SIZE) { nonce },
    )

    private suspend fun TestScope.say(seam: FakeSeam, from: PeerId, message: AdmitMessage) {
        seam.deliver(from, AdmitMessage.encode(message))
        runCurrent()
    }

    /** Every admit frame the room sent to [peer], decoded, in order. */
    private fun FakeSeam.sentTo(peer: PeerId): List<AdmitMessage> =
        directed.filter { it.first == peer }.mapNotNull { AdmitMessage.decode(it.second) }

    private fun FakeSeam.challengesTo(peer: PeerId): List<Challenge> =
        sentTo(peer).filterIsInstance<AdmitMessage.Challenge>().map { assertNotNull(Challenge.decode(it.statement)) }

    private fun FakeSeam.rejectsTo(peer: PeerId): List<AdmitMessage.Reject> = sentTo(peer).filterIsInstance<AdmitMessage.Reject>()

    /** The proof [joiner] signs to answer [challenge], with any field overridden. */
    private suspend fun proof(
        challenge: Challenge,
        joiner: DeviceKey,
        hostKey: DidKey = challenge.hostKey,
        hostPeerId: String = hostId.value,
        joinerPeerId: String = challenge.joinerPeerId,
        deviceId: String? = null,
        roomId: String = challenge.roomId,
    ): AdmitMessage.Proof = AdmitMessage.Proof(
        AdmissionProof.sign(joiner, roomId, hostKey, hostPeerId, joinerPeerId, deviceId, challenge.hostNonce).wire,
    )

    @Test
    fun honestJoinerIsAdmittedWithItsKey() = runTest {
        val loom = InMemoryLoom()
        val hostStore = fakeKeyStore(0)
        val joinerStore = fakeKeyStore(1)
        val hostSeam = AdmitTap(loom.weave(Rendezvous.New(Pattern("room"))))
        val joinerSeam = AdmitTap(loom.weave(Rendezvous.Existing(InMemoryTag("room"))))
        val host = SeamRoomFactory(loom, backgroundScope, clock(), hostStore).adopt(hostSeam, AdoptRole.Host(HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory())), memberName = "Host")
        val joiner = SeamRoomFactory(loom, backgroundScope, clock(), joinerStore).adopt(joinerSeam, AdoptRole.Joiner, memberName = "Bob")

        val joinerOnHost = host.roster.first { it.size == 1 }.single()
        val hostOnJoiner = joiner.roster.first { it.isNotEmpty() }.single()
        val (hostKey, joinerKey) = hostStore.deviceKey().id to joinerStore.deviceKey().id

        assertAll(
            { assertEquals(1, joinerSeam.received<AdmitMessage.Challenge>(), "rig: the joiner received one challenge") },
            { assertEquals(1, hostSeam.received<AdmitMessage.Proof>(), "rig: the host received one proof") },
            { assertEquals(joinerKey, joinerOnHost.key, "the host's entry for the joiner carries the proven key") },
            { assertEquals(hostKey, hostOnJoiner.key, "the joiner's entry for the host carries the challenge's key") },
        )
        joiner.leave()
        host.leave()
    }

    @Test
    fun forgedProofIsRejectedBadProof() = runTest {
        val (seam, room) = hostRoom()
        say(seam, bob, hello("Bob", bobKey.id, nonce = 1))
        val challenge = seam.challengesTo(bob).single()

        // Everything right except the signer: Eve's key signs a proof for Bob's Hello.
        say(seam, bob, proof(challenge, joiner = eveKey))

        assertAll(
            { assertEquals(1, seam.challengesTo(bob).size, "rig: the host challenged Bob once") },
            { assertEquals(listOf(RejectCode.BadProof), seam.rejectsTo(bob).map { it.code }, "the proof was judged and refused") },
            { assertTrue(room.roster.value.isEmpty(), "a forged proof admits nobody") },
        )
        room.leave()
    }

    @Test
    fun proofReplayedUnderAnotherNonceIsDroppedNotRefused() = runTest {
        val (seam, room) = hostRoom()
        // Attempt 1: Bob is admitted honestly, and the proof he sent is captured.
        say(seam, bob, hello("Bob", bobKey.id, nonce = 1))
        val first = seam.challengesTo(bob).single()
        val captured = proof(first, joiner = bobKey)
        say(seam, bob, captured)
        assertEquals(setOf(bob), room.roster.value.map { it.id }.toSet(), "rig: attempt 1 admitted Bob")
        say(seam, bob, AdmitMessage.Goodbye)
        assertTrue(room.roster.value.isEmpty(), "rig: Bob left")

        // Attempt 2: a new challenge, answered first with attempt 1's proof, then honestly.
        say(seam, bob, hello("Bob", bobKey.id, nonce = 2))
        val second = seam.challengesTo(bob).last()
        say(seam, bob, captured)
        val afterReplay = room.roster.value.map { it.id }.toSet()
        val rejectsAfterReplay = seam.rejectsTo(bob).map { it.code }
        say(seam, bob, proof(second, joiner = bobKey))

        // #3159 Task 1.7: a proof for an older challenge is what a joiner that said Hello again sends
        // in good faith, so it is dropped, never refused — a BadProof would end that joiner — and the
        // open challenge stays open for the honest answer.
        assertAll(
            { assertEquals(2, seam.challengesTo(bob).size, "rig: two challenges") },
            { assertTrue(!first.hostNonce.contentEquals(second.hostNonce), "rig: each challenge has its own nonce") },
            { assertEquals(emptyList(), rejectsAfterReplay, "the replayed proof is dropped, not refused") },
            { assertTrue(afterReplay.isEmpty(), "a replayed proof admits nobody") },
            { assertEquals(emptyList(), seam.rejectsTo(bob).map { it.code }, "nothing is refused at all") },
            { assertEquals(setOf(bob), room.roster.value.map { it.id }.toSet(), "the honest answer to the open challenge still admits") },
        )
        room.leave()
    }

    @Test
    fun proofForAnotherHostIsRejected() = runTest {
        val (seam, room) = hostRoom()
        // Each proof is right in every field but one: Bob's names another host's key, Eve's another
        // host's peer id. One field each, so each check is the only one that can refuse it.
        say(seam, bob, hello("Bob", bobKey.id, nonce = 1))
        say(seam, bob, proof(seam.challengesTo(bob).single(), joiner = bobKey, hostKey = fakeDidKey(202)))
        say(seam, eve, hello("Eve", eveKey.id, nonce = 2))
        say(seam, eve, proof(seam.challengesTo(eve).single(), joiner = eveKey, hostPeerId = "other-host"))

        assertAll(
            { assertEquals(1, seam.challengesTo(bob).size, "rig: the host challenged Bob once") },
            { assertEquals(1, seam.challengesTo(eve).size, "rig: the host challenged Eve once") },
            { assertEquals(listOf(RejectCode.BadProof), seam.rejectsTo(bob).map { it.code }, "another host's key") },
            { assertEquals(listOf(RejectCode.BadProof), seam.rejectsTo(eve).map { it.code }, "another host's peer id") },
            { assertTrue(room.roster.value.isEmpty()) },
        )
        room.leave()
    }

    /**
     * A proof right in every field but the room: the joiner names another room than the one this host
     * serves. The room id is what stops a proof made for one room admitting its device to another
     * room of the same host, which shares the host's key and peer id.
     */
    @Test
    fun proofForAnotherRoomIsRejected() = runTest {
        val (seam, room) = hostRoom()
        say(seam, bob, hello("Bob", bobKey.id, nonce = 1))
        val challenge = seam.challengesTo(bob).single()
        say(seam, bob, proof(challenge, joiner = bobKey, roomId = "another-room"))

        assertAll(
            { assertEquals(ROOM.value, challenge.roomId, "rig: the challenge names this host's room") },
            { assertEquals(1, seam.challengesTo(bob).size, "rig: the host challenged Bob once") },
            { assertEquals(listOf(RejectCode.BadProof), seam.rejectsTo(bob).map { it.code }, "another room's proof is refused") },
            { assertTrue(room.roster.value.isEmpty()) },
        )
        room.leave()
    }

    /**
     * A flat loom with three peers: the real host, the joiner, and a co-joiner that answers the
     * joiner's broadcast Hello with a challenge of its own. The host's signing is held until the joiner
     * has answered the co-joiner, so the stray challenge provably lands first.
     */
    @Test
    fun coJoinersChallengeDoesNotStarveTheRealOne() = runTest {
        val loom = InMemoryLoom()
        val strayAnswered = CompletableDeferred<Unit>()
        val hostScheme = FakeKeyScheme(Random(0)) { op -> if (op is FakeKeyOperation.Sign) strayAnswered.await() }
        val hostStore = fakeKeyStore(0, hostScheme)
        val hostSeam = loom.weave(Rendezvous.New(Pattern("room")))
        val coJoiner = loom.weave(Rendezvous.Existing(InMemoryTag("room")))
        val joinerSeam = AdmitTap(loom.weave(Rendezvous.Existing(InMemoryTag("room"))))

        val proofsToCoJoiner = mutableListOf<AdmitMessage.Proof>()
        backgroundScope.launch {
            coJoiner.incoming.collect { swatch ->
                when (val msg = AdmitMessage.decode(swatch.toByteArray())) {
                    is AdmitMessage.Hello -> {
                        val sender = swatch.sender ?: return@collect
                        val stray = Challenge.sign(
                            fakeDeviceKey(203), "stray-room", coJoiner.selfId.value, sender.value,
                            assertNotNull(msg.joinerNonce), ByteArray(Challenge.NONCE_SIZE) { 9 },
                        )
                        coJoiner.sendTo(sender, AdmitMessage.encode(AdmitMessage.Challenge(stray.wire)))
                    }
                    is AdmitMessage.Proof -> {
                        proofsToCoJoiner += msg
                        strayAnswered.complete(Unit)
                    }
                    else -> Unit
                }
            }
        }

        val host = SeamRoomFactory(loom, backgroundScope, clock(), hostStore).adopt(hostSeam, AdoptRole.Host(HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory())), memberName = "Host")
        val joiner = SeamRoomFactory(loom, backgroundScope, clock(), fakeKeyStore(1))
            .adopt(joinerSeam, AdoptRole.Joiner, memberName = "Bob")

        val hostOnJoiner = joiner.roster.first { it.isNotEmpty() }.single()
        host.roster.first { it.size == 1 }
        val hostKey = hostStore.deviceKey().id

        assertAll(
            { assertEquals(1, proofsToCoJoiner.size, "rig: the joiner answered the co-joiner's challenge") },
            { assertEquals(2, joinerSeam.received<AdmitMessage.Challenge>(), "rig: the joiner received both challenges") },
            { assertEquals(host.selfId, hostOnJoiner.id, "the real host admitted the joiner") },
            { assertEquals(hostKey, hostOnJoiner.key) },
        )
        joiner.leave()
        host.leave()
        coJoiner.close()
    }

    /** Eve floods Hellos in Bob's name; they overwrite only Eve's own pending challenge. */
    @Test
    fun bystanderHelloFloodOverwritesOnlyItsOwnPending() = runTest {
        val (seam, room) = hostRoom()
        say(seam, bob, hello("Bob", bobKey.id, nonce = 1))
        val bobsChallenge = seam.challengesTo(bob).single()
        repeat(FLOOD) { say(seam, eve, hello("Bob", bobKey.id, nonce = (it + 2).toByte())) }

        say(seam, bob, proof(bobsChallenge, joiner = bobKey))

        assertAll(
            { assertEquals(FLOOD, seam.challengesTo(eve).size, "rig: every flooded Hello reached the host") },
            { assertEquals(setOf(bob), room.roster.value.map { it.id }.toSet(), "Bob's pending proof still admits him") },
            { assertEquals(bobKey.id, room.roster.value.single().key) },
            { assertTrue(seam.rejectsTo(bob).isEmpty()) },
        )
        room.leave()
    }

    @Test
    fun pendingIsDroppedOnPeerLost() = runTest {
        val (seam, room) = hostRoom()
        say(seam, bob, hello("Bob", bobKey.id, nonce = 1))
        val challenge = seam.challengesTo(bob).single()

        seam.removePeer(bob)
        runCurrent()
        seam.addPeer(bob)
        say(seam, bob, proof(challenge, joiner = bobKey))

        assertAll(
            { assertEquals(1, seam.challengesTo(bob).size, "rig: the host challenged Bob") },
            { assertTrue(room.roster.value.isEmpty(), "a challenge is forgotten when its sender leaves the seam") },
            { assertTrue(seam.rejectsTo(bob).isEmpty(), "a proof with nothing pending is dropped, not refused") },
        )
        room.leave()
    }

    @Test
    fun pendingIsDroppedAfterAdmitTimeout() = runTest {
        val (seam, room) = hostRoom()
        // Control: answered just inside the deadline, the proof admits — so the clock is the one that moves.
        say(seam, bob, hello("Bob", bobKey.id, nonce = 1))
        advanceTimeBy(ADMIT_TIMEOUT - 1.milliseconds)
        say(seam, bob, proof(seam.challengesTo(bob).single(), joiner = bobKey))
        assertEquals(setOf(bob), room.roster.value.map { it.id }.toSet(), "rig: inside the deadline the proof admits")

        say(seam, eve, hello("Eve", eveKey.id, nonce = 2))
        advanceTimeBy(ADMIT_TIMEOUT + 1.milliseconds)
        say(seam, eve, proof(seam.challengesTo(eve).single(), joiner = eveKey))

        assertAll(
            { assertEquals(1, seam.challengesTo(eve).size, "rig: the host challenged Eve") },
            { assertTrue(room.roster.value.none { it.id == eve }, "a proof after the deadline admits nobody") },
            { assertTrue(seam.rejectsTo(eve).isEmpty(), "and is dropped, not refused") },
        )
        room.leave()
    }

    @Test
    fun seatIsReReadAtProofTimeNotChallengeTime() = runTest {
        val (seam, room) = hostRoom()
        say(seam, bob, hello("Bob", bobKey.id, nonce = 1))
        val challenge = seam.challengesTo(bob).single()
        say(seam, bob, AdmitMessage.Goodbye)
        say(seam, bob, proof(challenge, joiner = bobKey))

        assertAll(
            { assertEquals(1, seam.challengesTo(bob).size, "rig: the host challenged Bob") },
            { assertTrue(room.roster.value.isEmpty(), "a Goodbye between Challenge and Proof leaves no ghost") },
            { assertTrue(seam.sentTo(bob).none { it is AdmitMessage.Welcome }, "and no Welcome") },
        )
        room.leave()
    }

    /** The same gap, narrower: the Goodbye lands while the host is checking the proof's signature. */
    @Test
    fun goodbyeDuringTheProofCheckLeavesNoGhost() = runTest {
        val verifying = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scheme = FakeKeyScheme(Random(0)) { op ->
            if (op is FakeKeyOperation.Verify) {
                verifying.complete(Unit)
                release.await()
            }
        }
        val (seam, room) = hostRoom(fakeKeyStore(0, scheme))
        say(seam, bob, hello("Bob", bobKey.id, nonce = 1))
        say(seam, bob, proof(seam.challengesTo(bob).single(), joiner = bobKey))
        assertTrue(verifying.isCompleted, "rig: the host is checking the proof")

        say(seam, bob, AdmitMessage.Goodbye)
        release.complete(Unit)
        runCurrent()

        assertAll(
            { assertTrue(room.roster.value.isEmpty(), "a Goodbye during the check leaves no ghost") },
            { assertTrue(seam.sentTo(bob).none { it is AdmitMessage.Welcome }) },
        )
        room.leave()
    }

    /** A duplicate proof for a challenge already being decided is dropped, never refused. */
    @Test
    fun duplicateProofIsDroppedSilently() = runTest {
        val release = CompletableDeferred<Unit>()
        val scheme = FakeKeyScheme(Random(0)) { op -> if (op is FakeKeyOperation.Verify) release.await() }
        val (seam, room) = hostRoom(fakeKeyStore(0, scheme))
        say(seam, bob, hello("Bob", bobKey.id, nonce = 1))
        val answer = proof(seam.challengesTo(bob).single(), joiner = bobKey)
        say(seam, bob, answer)
        say(seam, bob, answer)
        release.complete(Unit)
        runCurrent()

        assertAll(
            { assertEquals(setOf(bob), room.roster.value.map { it.id }.toSet(), "the first proof admits Bob") },
            { assertTrue(seam.rejectsTo(bob).isEmpty(), "the duplicate is not refused") },
            { assertEquals(1, seam.sentTo(bob).count { it is AdmitMessage.Welcome && it.assignedPeerId == bob.value }) },
        )
        room.leave()
    }

    /**
     * A proof right in every field but the joiner's peer id: Bob signs a proof naming Carol. The
     * signature is Bob's and good, so only the host's joiner-peer check can refuse it, and the
     * refusal's reason names that check.
     */
    @Test
    fun proofNamingAnotherJoinerIsRejected() = runTest {
        val (seam, room) = hostRoom()
        say(seam, bob, hello("Bob", bobKey.id, nonce = 1))
        val challenge = seam.challengesTo(bob).single()
        say(seam, bob, proof(challenge, joiner = bobKey, joinerPeerId = "carol"))

        assertAll(
            { assertEquals(bob.value, challenge.joinerPeerId, "rig: the challenge names Bob") },
            { assertEquals(listOf(RejectCode.BadProof), seam.rejectsTo(bob).map { it.code }, "another joiner's proof is refused") },
            { assertTrue(seam.rejectsTo(bob).singleOrNull()?.reason.orEmpty().contains("WrongJoinerPeer"), "by the joiner-peer check") },
            { assertTrue(room.roster.value.isEmpty()) },
        )
        room.leave()
    }

    /**
     * The relay attack, end to end. Mallory self-asserts Bob's id at the real host and poses as a host
     * to Bob under her own id. She forwards Bob's Hello to the host, the host's challenge to Bob, and
     * Bob's proof back. The challenge is honest — it names Bob, echoes his nonce and carries the host's
     * signature — so Bob answers it; but his proof names the fabric sender he heard it from, Mallory,
     * and the host refuses a proof naming any host but itself.
     *
     * The defence holds while Mallory cannot also present the host's peer id to Bob. A relay that
     * can, on Bob's own link over an unauthenticated fabric, is the case the design leaves to channel
     * binding.
     */
    @Test
    fun relayedChallengeAdmitsNobody() = runTest {
        val mallory = PeerId("mallory")
        // The host sees Bob — really Mallory under Bob's id. Bob sees Mallory.
        val (hostSeam, host) = hostRoom(joiners = setOf(bob))
        val bobSeam = FakeSeam(selfId = bob, initialPeers = setOf(bob, mallory))
        val joiner = SeamRoomFactory(InMemoryLoom(), backgroundScope, clock(), fakeKeyStore(1), admitTimeout = ADMIT_TIMEOUT)
            .adopt(bobSeam, AdoptRole.Joiner, memberName = "Bob")
        runCurrent()

        val bobsHello = bobSeam.broadcasts.mapNotNull { AdmitMessage.decode(it) }.filterIsInstance<AdmitMessage.Hello>().single()
        say(hostSeam, bob, bobsHello)
        val challenge = hostSeam.sentTo(bob).filterIsInstance<AdmitMessage.Challenge>().single()
        say(bobSeam, mallory, challenge)
        val relayedProof = bobSeam.sentTo(mallory).filterIsInstance<AdmitMessage.Proof>().single()
        say(hostSeam, bob, relayedProof)

        val signed = assertNotNull(AdmissionProof.decode(relayedProof.statement))
        assertAll(
            { assertEquals(hostId.value, assertNotNull(Challenge.decode(challenge.statement)).hostPeerId, "rig: the host's challenge names itself") },
            { assertEquals(mallory.value, signed.hostPeerId, "rig: Bob answered, naming the sender he heard it from") },
            { assertEquals(listOf(RejectCode.BadProof), hostSeam.rejectsTo(bob).map { it.code }, "the relayed proof is refused") },
            { assertTrue(hostSeam.rejectsTo(bob).singleOrNull()?.reason.orEmpty().contains("WrongHostPeer"), "by the host-peer check") },
            { assertTrue(host.roster.value.isEmpty(), "Mallory is not admitted on Bob's key") },
        )
        joiner.leave()
        host.leave()
    }

    /** The joiner's own device id is signed into the proof; a proof claiming another is refused. */
    @Test
    fun proofSigningAnotherDeviceIdIsRejected() = runTest {
        val (seam, room) = hostRoom()
        say(seam, bob, hello("Bob", bobKey.id, nonce = 1, deviceId = "bob-phone"))
        say(seam, bob, proof(seam.challengesTo(bob).single(), joiner = bobKey, deviceId = "someone-else"))

        assertAll(
            { assertEquals(listOf(RejectCode.BadProof), seam.rejectsTo(bob).map { it.code }) },
            { assertTrue(room.roster.value.isEmpty()) },
        )
        room.leave()
    }

    /** A host that cannot sign its challenge refuses the joiner with a Reject, and keeps running. */
    @Test
    fun challengeSigningFailureIsARejectNotACrash() = runTest {
        var failSigning = true
        val scheme = FakeKeyScheme(Random(0)) { op ->
            if (op is FakeKeyOperation.Sign && failSigning) throw IllegalStateException("provider down")
        }
        val (seam, room) = hostRoom(fakeKeyStore(0, scheme))
        say(seam, bob, hello("Bob", bobKey.id, nonce = 1))
        val refused = seam.rejectsTo(bob)

        failSigning = false
        say(seam, eve, hello("Eve", eveKey.id, nonce = 2))
        say(seam, eve, proof(seam.challengesTo(eve).single(), joiner = eveKey))

        assertAll(
            { assertEquals(listOf(RejectCode.HostFailed), refused.map { it.code }, "the joiner hears the host failed") },
            { assertTrue(RejectCode.HostFailed.retryable, "the host's own failure is retryable") },
            { assertTrue(seam.challengesTo(bob).isEmpty(), "no challenge went out") },
            { assertEquals(setOf(eve), room.roster.value.map { it.id }.toSet(), "the room still admits") },
        )
        room.leave()
    }

    /**
     * A host that cannot check a proof — its provider fails on verify — refuses the joiner with a
     * retryable `HostFailed`, never `BadProof`: the joiner's proof was never judged. The room keeps
     * running, and admits the next honest proof once verify works again.
     */
    @Test
    fun proofCheckFailureIsARetryableRejectNotACrash() = runTest {
        var failVerify = true
        var verifyCalls = 0
        val scheme = FakeKeyScheme(Random(0)) { op ->
            if (op is FakeKeyOperation.Verify) {
                verifyCalls++
                if (failVerify) throw IllegalStateException("provider down")
            }
        }
        val (seam, room) = hostRoom(fakeKeyStore(0, scheme))
        say(seam, bob, hello("Bob", bobKey.id, nonce = 1))
        say(seam, bob, proof(seam.challengesTo(bob).single(), joiner = bobKey))
        val refused = seam.rejectsTo(bob)
        val callsWhileFailing = verifyCalls

        failVerify = false
        say(seam, eve, hello("Eve", eveKey.id, nonce = 2))
        say(seam, eve, proof(seam.challengesTo(eve).single(), joiner = eveKey))

        assertAll(
            { assertEquals(1, callsWhileFailing, "rig: the host tried to verify Bob's proof once") },
            { assertEquals(listOf(RejectCode.HostFailed), refused.map { it.code }, "the joiner hears the host failed") },
            { assertTrue(refused.singleOrNull()?.code?.retryable == true, "and may try again") },
            { assertTrue(room.roster.value.none { it.id == bob }, "an unchecked proof admits nobody") },
            { assertEquals(setOf(eve), room.roster.value.map { it.id }.toSet(), "the room still admits") },
        )
        room.leave()
    }

    /** Amendment B: a joiner on the host's own device key is not refused; it gets a seat of its own. */
    @Test
    fun joinerPresentingTheHostsKeyIsAdmitted() = runTest {
        val loom = InMemoryLoom()
        val shared = fakeKeyStore(0)
        val host = SeamRoomFactory(loom, backgroundScope, clock(), shared).host(Pattern("room"), memberName = "Host", admission = HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory()))
        val tab = SeamRoomFactory(loom, backgroundScope, clock(), shared).join(InMemoryTag("room"), memberName = "Tab")

        val tabOnHost = host.roster.first { it.size == 1 }.single()
        val sharedKey = shared.deviceKey().id
        assertAll(
            { assertEquals(tab.selfId, tabOnHost.id) },
            { assertEquals(sharedKey, tabOnHost.key, "rig: the joiner presented the host's own key") },
        )
        tab.leave()
        host.leave()
    }

    // ── Joiner side ──────────────────────────────────────────────────────────

    /** A joiner room over a [FakeSeam] whose only remote is [hostId]; returns its Hello too. */
    private suspend fun TestScope.joinerRoom(
        deviceId: String? = null,
        peers: Set<PeerId> = setOf(hostId),
    ): Triple<FakeSeam, Room, AdmitMessage.Hello> {
        val seam = FakeSeam(selfId = bob, initialPeers = setOf(bob) + peers)
        val room = SeamRoomFactory(InMemoryLoom(), backgroundScope, clock(), fakeKeyStore(1), admitTimeout = ADMIT_TIMEOUT)
            .adopt(seam, AdoptRole.Joiner, memberName = "Bob", deviceId = deviceId)
        runCurrent()
        val hello = seam.broadcasts.mapNotNull { AdmitMessage.decode(it) }.filterIsInstance<AdmitMessage.Hello>().single()
        return Triple(seam, room, hello)
    }

    private suspend fun challengeFor(hello: AdmitMessage.Hello, host: DeviceKey, joinerNonce: ByteArray? = hello.joinerNonce) =
        AdmitMessage.Challenge(
            Challenge.sign(host, ROOM.value, hostId.value, bob.value, assertNotNull(joinerNonce), ByteArray(Challenge.NONCE_SIZE) { 7 }).wire,
        )

    private fun FakeSeam.proofsTo(peer: PeerId): List<AdmissionProof> =
        sentTo(peer).filterIsInstance<AdmitMessage.Proof>().map { assertNotNull(AdmissionProof.decode(it.statement)) }

    @Test
    fun joinerAnswersAChallengeEchoingItsNonce() = runTest {
        val (seam, room, hello) = joinerRoom(deviceId = "bob-phone")
        val hostKey = fakeDeviceKey(204)
        say(seam, hostId, challengeFor(hello, hostKey))

        val answered = seam.proofsTo(hostId).single()
        val signedByBob = answered.isSignedBy(fakeDidKey(1), FakeKeyScheme(Random(0)))
        assertAll(
            { assertEquals(hostId.value, answered.hostPeerId, "the proof names the challenge's fabric sender") },
            { assertEquals(hostKey.id, answered.hostKey) },
            { assertEquals(bob.value, answered.joinerPeerId) },
            { assertEquals("bob-phone", answered.deviceId, "the proof signs the app's device id") },
            { assertTrue(signedByBob, "signed by the joiner's own key") },
        )
        room.leave()
    }

    @Test
    fun joinerIgnoresAChallengeThatDoesNotEchoItsNonceOrIsForged() = runTest {
        val (seam, room, hello) = joinerRoom()
        val stale = ByteArray(AdmitMessage.Hello.JOINER_NONCE_SIZE) { 5 }
        say(seam, hostId, challengeFor(hello, fakeDeviceKey(204), joinerNonce = stale))
        val forged = Challenge.decode(challengeFor(hello, fakeDeviceKey(204)).statement)
        val tampered = assertNotNull(forged).wire.also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        say(seam, hostId, AdmitMessage.Challenge(tampered))
        val answeredBadOnes = seam.proofsTo(hostId).size

        // Control: the same joiner, listening all along, answers the honest challenge at once.
        say(seam, hostId, challengeFor(hello, fakeDeviceKey(204)))

        assertAll(
            { assertTrue(!stale.contentEquals(hello.joinerNonce), "rig: the stale nonce is not the Hello's") },
            { assertEquals(0, answeredBadOnes, "neither the stale nor the tampered challenge is answered") },
            { assertEquals(1, seam.proofsTo(hostId).size, "rig: an honest challenge is answered") },
        )
        room.leave()
    }

    /**
     * A challenge from [eve] that says it is the host's: signed by a real key, naming Bob and echoing
     * his nonce, but carrying `hostPeerId = host`. Bob's proof names Eve — the sender he heard it
     * from — never the id the challenge claims, so it is no use to Eve at the real host.
     */
    @Test
    fun joinerProofNamesTheChallengesSenderNotItsClaim() = runTest {
        val (seam, room, hello) = joinerRoom(peers = setOf(hostId, eve))
        say(seam, eve, challengeFor(hello, fakeDeviceKey(204)))

        val claimed = assertNotNull(Challenge.decode(challengeFor(hello, fakeDeviceKey(204)).statement)).hostPeerId
        val answered = seam.proofsTo(eve).single()
        assertAll(
            { assertEquals(hostId.value, claimed, "rig: the challenge claims to be the host's") },
            { assertTrue(seam.proofsTo(hostId).isEmpty(), "rig: the proof went back to Eve only") },
            { assertEquals(eve.value, answered.hostPeerId, "the proof names the fabric sender") },
        )
        room.leave()
    }

    /** A challenge naming another joiner is not Bob's to answer, however good its signature. */
    @Test
    fun joinerIgnoresAChallengeNamingAnotherJoiner() = runTest {
        val (seam, room, hello) = joinerRoom()
        val forCarol = AdmitMessage.Challenge(
            Challenge.sign(
                fakeDeviceKey(204), ROOM.value, hostId.value, "carol",
                assertNotNull(hello.joinerNonce), ByteArray(Challenge.NONCE_SIZE) { 7 },
            ).wire,
        )
        say(seam, hostId, forCarol)
        val answeredCarols = seam.proofsTo(hostId).size

        // Control: the same challenge naming Bob is answered.
        say(seam, hostId, challengeFor(hello, fakeDeviceKey(204)))

        assertAll(
            { assertEquals(0, answeredCarols, "a challenge naming Carol is ignored") },
            { assertEquals(1, seam.proofsTo(hostId).size, "rig: the same challenge naming Bob is answered") },
        )
        room.leave()
    }

    @Test
    fun joinerAcceptsOnlyAHostIntroCarryingTheProvenKey() = runTest {
        val (seam, room, hello) = joinerRoom()
        val hostKey = fakeDeviceKey(204)

        // Before any challenge: a Welcome from the host's peer id is not from a proven host.
        say(seam, hostId, hostIntro(fakeDidKey(204)))
        assertTrue(room.roster.value.isEmpty(), "an unproven sender's Welcome is ignored")

        say(seam, hostId, challengeFor(hello, hostKey))
        assertEquals(1, seam.proofsTo(hostId).size, "rig: the joiner answered the challenge")

        // An intro naming another key than the challenge proved is ignored; the proven one is accepted.
        say(seam, hostId, hostIntro(fakeDidKey(205)))
        assertTrue(room.roster.value.isEmpty(), "an intro naming another key is ignored")
        say(seam, hostId, hostIntro(hostKey.id))

        assertEquals(listOf(hostKey.id), room.roster.value.map { it.key })
        room.leave()
    }

    private fun hostIntro(key: DidKey) = AdmitMessage.Welcome(
        assignedPeerId = hostId.value,
        displayName = "Host",
        sessionId = hostId.value,
        key = key,
    )

    private companion object {
        val ROOM = RoomId("room-1")
        val ADMIT_TIMEOUT = 10.seconds
        const val FLOOD = 100
    }
}

/**
 * A [Seam] that counts the admit frames it delivers, by type: the receiver-side witness that a
 * frame a test relies on really arrived.
 */
internal class AdmitTap(private val inner: Seam) : Seam by inner {
    private val counts = mutableMapOf<String, Int>()

    override val incoming: Flow<Swatch> = inner.incoming.onEach { swatch ->
        val msg = AdmitMessage.decode(swatch.toByteArray()) ?: return@onEach
        val name = msg::class.simpleName ?: return@onEach
        counts[name] = (counts[name] ?: 0) + 1
    }

    inline fun <reified T : AdmitMessage> received(): Int = count(T::class.simpleName)

    fun count(name: String?): Int = counts[name] ?: 0
}
