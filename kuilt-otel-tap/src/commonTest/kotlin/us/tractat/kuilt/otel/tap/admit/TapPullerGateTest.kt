@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.otel.tap.admit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The puller's half of the tap's admission gate (#3076): a puller surfaces a peer only once that peer
 * has answered the nonce the puller put in its own proof, and a device gives that answer only to a peer
 * it has admitted.
 *
 * Most of this drives one gate over a [FakeSeam], because the claims are about what the gate does with
 * a frame stamped with a given sender, and a fake stamps what it is told. `GatedTapEndToEndTest` holds
 * the same property through two real replicators.
 *
 * Every refusal has an admission beside it, in the same test and through the same rig. A timeout or an
 * absence is also what a gate that is deadlocked or missing produces (#2740).
 */
class TapPullerGateTest {
    private val t0 = Instant.fromEpochSeconds(1_700_000_000)
    private val clock = object : Clock { override fun now(): Instant = t0 }
    private val code = "CODE1234"
    private val pullerId = PeerId("puller")
    private val deviceId = PeerId("device")
    private val strangerId = PeerId("stranger")
    private val replicationFrame = byteArrayOf(0x01, 0x02, 0x03)

    // ── the puller ─────────────────────────────────────────────────────────────────────────────

    private inner class PullerRig(scope: TestScope, remotes: Set<PeerId>) {
        val seam = FakeSeam(pullerId, remotes + pullerId)
        val gate: Seam = seam.tokenGated(GateRole.Prover(code, Random(PROVER_SEED)), scope.backgroundScope)
        val relayed = mutableListOf<Swatch>()

        init {
            scope.backgroundScope.launch { gate.incoming.collect { relayed += it } }
        }

        /** Have [peer] challenge the puller, and return the proof the puller sent it. */
        suspend fun TestScope.challengedBy(peer: PeerId, seed: Int = 1): TapAdmitMessage.Proof {
            val nonce = ByteString(Random(seed).nextBytes(TapAdmitMessage.Challenge.NONCE_BYTES))
            seam.deliver(peer, TapAdmitMessage.encode(TapAdmitMessage.Challenge(nonce)))
            runCurrent()
            return seam.directed
                .filter { (to, _) -> to == peer }
                .mapNotNull { (_, frame) -> TapAdmitMessage.decode(frame) as? TapAdmitMessage.Proof }
                .last()
        }

        /** Deliver what [from] sends when it knows the code: an answer over the nonce in [puller]'s proof. */
        suspend fun TestScope.answer(from: PeerId, puller: TapAdmitMessage.Proof, usingCode: String = code) {
            seam.deliver(from, answerFrame(from, puller.proverNonce, usingCode))
            runCurrent()
        }

        suspend fun TestScope.frameFrom(peer: PeerId) {
            seam.deliver(peer, replicationFrame)
            runCurrent()
        }
    }

    private fun answerFrame(from: PeerId, pullerNonce: ByteString, usingCode: String = code): ByteArray {
        val proverNonce = ByteString(ByteArray(TapAdmitMessage.Challenge.NONCE_BYTES) { 0x44 })
        val tag = admissionTag(usingCode, pullerId, from, pullerNonce, proverNonce)
        return TapAdmitMessage.encode(TapAdmitMessage.Proof(proverNonce, ByteString(tag)))
    }

    @Test
    fun aPullerSurfacesAPeerOnlyAfterThatPeerAnswersItsNonce() = runTest(UnconfinedTestDispatcher()) {
        val rig = PullerRig(this, setOf(deviceId))
        runCurrent()
        val onTheRoster = rig.gate.peers.value

        with(rig) {
            val proof = challengedBy(deviceId)
            val afterItsChallenge = gate.peers.value
            answer(deviceId, proof)

            assertAll(
                { assertEquals(setOf(pullerId), onTheRoster, "being on the fabric's roster earns nothing") },
                { assertEquals(setOf(pullerId), afterItsChallenge, "a challenge proves nothing about the challenger") },
                { assertEquals(setOf(pullerId, deviceId), gate.peers.value, "the answer is what admits it") },
            )
        }
    }

    @Test
    fun anAnswerUnderTheWrongCodeAdmitsNobody() = runTest(UnconfinedTestDispatcher()) {
        val rig = PullerRig(this, setOf(deviceId))
        with(rig) {
            val proof = challengedBy(deviceId)
            answer(deviceId, proof, usingCode = "WRONGGGG")
            val afterTheWrongCode = gate.peers.value
            answer(deviceId, proof)

            assertAll(
                { assertEquals(setOf(pullerId), afterTheWrongCode, "the wrong code must not admit") },
                { assertTrue(deviceId in gate.peers.value, "control: the right code through the same rig must") },
            )
        }
    }

    /** With no challenge outstanding the puller has no nonce an answer could be over. */
    @Test
    fun anAnswerNobodyAskedForAdmitsNobody() = runTest(UnconfinedTestDispatcher()) {
        val rig = PullerRig(this, setOf(deviceId))
        with(rig) {
            val unasked = ByteString(ByteArray(TapAdmitMessage.Challenge.NONCE_BYTES) { 0x09 })
            seam.deliver(deviceId, answerFrame(deviceId, unasked))
            runCurrent()

            assertEquals(setOf(pullerId), gate.peers.value)
        }
    }

    /**
     * Only a peer that holds the code can get this far with the right id, but anything stamped with that
     * id can send the garbage. So a bad answer must not spend the nonce, or one frame locks the device
     * out, which is the shape `aForgedProofStampedWithAPeersIdUsesUpThatPeersChallenge` pins on the
     * device's side.
     */
    @Test
    fun aBadAnswerDoesNotSpendTheNonceTheDeviceIsAnswering() = runTest(UnconfinedTestDispatcher()) {
        val rig = PullerRig(this, setOf(deviceId))
        with(rig) {
            val proof = challengedBy(deviceId)
            val garbage = TapAdmitMessage.Proof(
                proverNonce = ByteString(ByteArray(TapAdmitMessage.Challenge.NONCE_BYTES)),
                tag = ByteString(ByteArray(TapAdmitMessage.Proof.TAG_BYTES)),
            )
            seam.deliver(deviceId, TapAdmitMessage.encode(garbage))
            runCurrent()
            val afterTheGarbage = gate.peers.value
            answer(deviceId, proof)

            assertAll(
                { assertEquals(setOf(pullerId), afterTheGarbage, "rig: the garbage must not admit") },
                { assertTrue(deviceId in gate.peers.value, "the device's real answer must still land") },
            )
        }
    }

    /** An answer recorded earlier is over a nonce the puller has moved on from. */
    @Test
    fun aRecordedAnswerDoesNotAdmitAgainstAFreshNonce() = runTest(UnconfinedTestDispatcher()) {
        val rig = PullerRig(this, setOf(deviceId))
        with(rig) {
            val earlier = challengedBy(deviceId, seed = 1)
            val later = challengedBy(deviceId, seed = 2)
            answer(deviceId, earlier)
            val afterTheRecordedAnswer = gate.peers.value
            answer(deviceId, later)

            assertAll(
                { assertFalse(earlier.proverNonce == later.proverNonce, "rig: the two nonces must differ") },
                { assertEquals(setOf(pullerId), afterTheRecordedAnswer, "an answer to an old nonce must not admit") },
                { assertTrue(deviceId in gate.peers.value, "control: an answer to the current nonce must") },
            )
        }
    }

    /**
     * The attack a role that both asks and answers opens up, and a reason the ids are in the tag. A
     * stranger holding no code gets the puller to MAC the nonce the puller is waiting on, by
     * sending that nonce as a challenge from a second id, and hands the result back as its own answer.
     *
     * The rig checks the tag is a real one over exactly that nonce. Only the ids can refuse it.
     */
    @Test
    fun aPullersOwnAnswerReflectedThroughASecondPeerDoesNotAdmit() = runTest(UnconfinedTestDispatcher()) {
        val accomplice = PeerId("accomplice")
        val rig = PullerRig(this, setOf(strangerId, accomplice))
        with(rig) {
            val waitingOn = challengedBy(strangerId).proverNonce
            seam.deliver(accomplice, TapAdmitMessage.encode(TapAdmitMessage.Challenge(waitingOn)))
            runCurrent()
            val reflected = seam.directed
                .filter { (to, _) -> to == accomplice }
                .firstNotNullOf { (_, frame) -> TapAdmitMessage.decode(frame) as? TapAdmitMessage.Proof }
            seam.deliver(strangerId, TapAdmitMessage.encode(reflected))
            runCurrent()

            assertAll(
                {
                    assertContentEquals(
                        admissionTag(code, accomplice, pullerId, waitingOn, reflected.proverNonce),
                        reflected.tag.toByteArray(),
                        "rig: the puller must have MAC'd the very nonce it is waiting on",
                    )
                },
                { assertEquals(setOf(pullerId), gate.peers.value, "a reflected answer must not admit") },
            )
        }
    }

    @Test
    fun aFrameFromAnUnadmittedPeerDoesNotReachTheReplicator() = runTest(UnconfinedTestDispatcher()) {
        val rig = PullerRig(this, setOf(deviceId, strangerId))
        with(rig) {
            answer(deviceId, challengedBy(deviceId))
            frameFrom(strangerId)
            val afterTheStranger = relayed.size
            frameFrom(deviceId)

            assertAll(
                { assertEquals(0, afterTheStranger, "a stranger's frame must not reach the replicator") },
                { assertEquals(listOf(deviceId), relayed.map { it.sender }, "control: the admitted peer's frame must") },
            )
        }
    }

    @Test
    fun aPullerSendsNothingToAnUnadmittedPeer() = runTest(UnconfinedTestDispatcher()) {
        val rig = PullerRig(this, setOf(deviceId, strangerId))
        with(rig) {
            gate.broadcast(replicationFrame)
            gate.sendTo(strangerId, replicationFrame)
            gate.sendTo(deviceId, replicationFrame)
            val beforeAdmission = seam.broadcasts.size + seam.directed.size
            answer(deviceId, challengedBy(deviceId))
            gate.broadcast(replicationFrame)
            gate.sendTo(strangerId, replicationFrame)
            gate.sendTo(deviceId, replicationFrame)

            val replicationTo = { peer: PeerId ->
                seam.directed.count { (to, frame) -> to == peer && !TapAdmitMessage.isAdmitFrame(frame) }
            }
            assertAll(
                { assertEquals(0, beforeAdmission, "nothing may leave for a peer that has proved nothing") },
                { assertEquals(0, seam.broadcasts.size, "a broadcast must not use the fabric's own fan-out") },
                { assertEquals(0, replicationTo(strangerId), "the stranger must never get a replication frame") },
                { assertEquals(2, replicationTo(deviceId), "control: the admitted peer gets the broadcast and the send") },
            )
        }
    }

    /** A peer that returns under the same id has a new connection, and the proof was for the old one. */
    @Test
    fun aPeerThatLeavesMustProveAgain() = runTest(UnconfinedTestDispatcher()) {
        val rig = PullerRig(this, setOf(deviceId))
        with(rig) {
            answer(deviceId, challengedBy(deviceId))
            val whileConnected = gate.peers.value
            seam.removePeer(deviceId)
            runCurrent()
            val afterLeaving = gate.peers.value
            seam.addPeer(deviceId)
            runCurrent()
            frameFrom(deviceId)
            val relayedBeforeProving = relayed.size
            answer(deviceId, challengedBy(deviceId, seed = 2))

            assertAll(
                { assertTrue(deviceId in whileConnected, "rig: the peer must have been admitted") },
                { assertFalse(deviceId in afterLeaving, "leaving must end its admission") },
                { assertEquals(0, relayedBeforeProving, "back on the roster earns nothing until it proves again") },
                { assertTrue(deviceId in gate.peers.value, "control: proving again must admit it") },
            )
        }
    }

    /** The nonce goes too, so an answer computed before the peer left is not good after it returns. */
    @Test
    fun aNonceDoesNotOutliveThePeerItWasFor() = runTest(UnconfinedTestDispatcher()) {
        val rig = PullerRig(this, setOf(deviceId))
        with(rig) {
            val proof = challengedBy(deviceId)
            seam.removePeer(deviceId)
            runCurrent()
            seam.addPeer(deviceId)
            runCurrent()
            answer(deviceId, proof)

            assertEquals(setOf(pullerId), gate.peers.value)
        }
    }

    // ── the device ─────────────────────────────────────────────────────────────────────────────

    private fun honestPullerProof(deviceChallenge: TapAdmitMessage.Challenge, usingCode: String = code): TapAdmitMessage.Proof {
        val proverNonce = ByteString(Random(PROVER_SEED).nextBytes(TapAdmitMessage.Challenge.NONCE_BYTES))
        val tag = admissionTag(usingCode, deviceId, pullerId, deviceChallenge.nonce, proverNonce)
        return TapAdmitMessage.Proof(proverNonce, ByteString(tag))
    }

    private fun challengeTo(seam: FakeSeam, peer: PeerId): TapAdmitMessage.Challenge = seam.directed
        .filter { (to, _) -> to == peer }
        .firstNotNullOf { (_, frame) -> TapAdmitMessage.decode(frame) as? TapAdmitMessage.Challenge }

    private fun proofsTo(seam: FakeSeam, peer: PeerId): List<TapAdmitMessage.Proof> = seam.directed
        .filter { (to, _) -> to == peer }
        .mapNotNull { (_, frame) -> TapAdmitMessage.decode(frame) as? TapAdmitMessage.Proof }

    private fun verifier(token: LogTapJoinToken = LogTapJoinToken(code, t0, 5.minutes)) =
        GateRole.Verifier(token, clock, Random(VERIFIER_SEED))

    @Test
    fun aDeviceAnswersTheNonceInAProofItAccepted() = runTest(UnconfinedTestDispatcher()) {
        val seam = FakeSeam(deviceId, setOf(deviceId, pullerId))
        val gate = seam.tokenGated(verifier(), backgroundScope)
        runCurrent()
        val proof = honestPullerProof(challengeTo(seam, pullerId))

        seam.deliver(pullerId, TapAdmitMessage.encode(proof))
        runCurrent()

        val answer = assertNotNull(proofsTo(seam, pullerId).singleOrNull(), "the device must answer exactly once")
        assertAll(
            { assertTrue(pullerId in gate.peers.value, "rig: the puller must have been admitted") },
            {
                assertContentEquals(
                    admissionTag(code, pullerId, deviceId, proof.proverNonce, answer.proverNonce),
                    answer.tag.toByteArray(),
                    "the answer must name the puller as verifier and the device as prover, over the puller's nonce",
                )
            },
        )
    }

    /**
     * Otherwise any peer reaching the device could collect a tag to grind, which today takes reaching a
     * puller. The answer goes only to a peer already past the gate, and a device never answers a bare
     * challenge at all.
     */
    @Test
    fun aDeviceGivesAStrangerNothingToGrind() = runTest(UnconfinedTestDispatcher()) {
        val seam = FakeSeam(deviceId, setOf(deviceId, pullerId, strangerId))
        val gate = seam.tokenGated(verifier(), backgroundScope)
        runCurrent()

        val nonce = ByteString(ByteArray(TapAdmitMessage.Challenge.NONCE_BYTES) { 0x5A })
        seam.deliver(strangerId, TapAdmitMessage.encode(TapAdmitMessage.Challenge(nonce)))
        runCurrent()
        val wrongCode = TapAdmitMessage.Proof(
            proverNonce = ByteString(ByteArray(TapAdmitMessage.Challenge.NONCE_BYTES) { 0x5B }),
            tag = ByteString(ByteArray(TapAdmitMessage.Proof.TAG_BYTES)),
        )
        seam.deliver(strangerId, TapAdmitMessage.encode(wrongCode))
        runCurrent()
        val answeredStranger = proofsTo(seam, strangerId).size
        seam.deliver(pullerId, TapAdmitMessage.encode(honestPullerProof(challengeTo(seam, pullerId))))
        runCurrent()

        assertAll(
            { assertEquals(0, answeredStranger, "no tag may go to a peer that has not proved the code") },
            { assertEquals(1, proofsTo(seam, pullerId).size, "control: the peer that did prove it gets one") },
            { assertTrue(pullerId in gate.peers.value, "rig: and it must be admitted") },
        )
    }

    @Test
    fun anExpiredDeviceAnswersNobody() = runTest(UnconfinedTestDispatcher()) {
        val seam = FakeSeam(deviceId, setOf(deviceId, pullerId))
        val expired = LogTapJoinToken(code, t0 - 10.minutes, 5.minutes)
        val gate = seam.tokenGated(verifier(expired), backgroundScope)
        runCurrent()

        seam.deliver(pullerId, TapAdmitMessage.encode(honestPullerProof(challengeTo(seam, pullerId))))
        runCurrent()

        assertAll(
            { assertEquals(0, proofsTo(seam, pullerId).size, "an expired token must not vouch for the device") },
            { assertFalse(pullerId in gate.peers.value, "and must not admit") },
            {
                assertEquals(
                    1,
                    seam.directed.count { (_, frame) -> TapAdmitMessage.decode(frame) is TapAdmitMessage.Reject },
                    "rig: the proof must have reached the check and been refused",
                )
            },
        )
    }

    /**
     * The device's replicator pushes its whole state the moment it sees a new peer. If that overtook the
     * answer, the puller would drop the backlog as coming from a peer it has not admitted. So the peer
     * comes onto the roster only once the answer is out.
     */
    @Test
    fun aPeerIsNotSurfacedUntilTheDevicesAnswerHasGoneOut() = runTest(UnconfinedTestDispatcher()) {
        val fake = FakeSeam(deviceId, setOf(deviceId, pullerId))
        val seam = ProofHoldingSeam(fake)
        val gate = seam.tokenGated(verifier(), backgroundScope)
        runCurrent()

        fake.deliver(pullerId, TapAdmitMessage.encode(honestPullerProof(challengeTo(fake, pullerId))))
        runCurrent()
        val whileHeld = gate.peers.value
        val heldProofs = seam.held
        seam.release.complete(Unit)
        runCurrent()

        assertAll(
            { assertEquals(1, heldProofs, "rig: the answer must have been parked in the fabric") },
            { assertFalse(pullerId in whileHeld, "the peer must not surface ahead of the answer") },
            { assertTrue(pullerId in gate.peers.value, "and must once it has gone out") },
        )
    }

    @Test
    fun aPeerWhoseAnswerCouldNotBeSentIsNotSurfaced() = runTest(UnconfinedTestDispatcher()) {
        val fake = FakeSeam(deviceId, setOf(deviceId, pullerId))
        val seam = ProofHoldingSeam(fake, failOnRelease = true)
        val gate = seam.tokenGated(verifier(), backgroundScope)
        runCurrent()

        fake.deliver(pullerId, TapAdmitMessage.encode(honestPullerProof(challengeTo(fake, pullerId))))
        runCurrent()
        seam.release.complete(Unit)
        runCurrent()

        assertAll(
            { assertEquals(1, seam.held, "rig: the send must have been attempted") },
            { assertFalse(pullerId in gate.peers.value, "a puller that never got the answer would refuse this device anyway") },
        )
    }

    /** Leaving during the send ends the exchange, so finishing it must not admit whoever comes back. */
    @Test
    fun aPeerThatLeavesWhileItsAnswerIsParkedIsNotAdmittedWhenTheSendFinishes() = runTest(UnconfinedTestDispatcher()) {
        val fake = FakeSeam(deviceId, setOf(deviceId, pullerId))
        val seam = ProofHoldingSeam(fake)
        val gate = seam.tokenGated(verifier(), backgroundScope)
        runCurrent()

        fake.deliver(pullerId, TapAdmitMessage.encode(honestPullerProof(challengeTo(fake, pullerId))))
        runCurrent()
        fake.removePeer(pullerId)
        runCurrent()
        fake.addPeer(pullerId)
        runCurrent()
        val challengesWhileParked = fake.directed.count { (_, frame) -> TapAdmitMessage.decode(frame) is TapAdmitMessage.Challenge }
        seam.release.complete(Unit)
        runCurrent()

        assertAll(
            { assertEquals(1, seam.held, "rig: the answer must have been parked when the peer left") },
            { assertEquals(2, challengesWhileParked, "rig: the returning peer must get a challenge of its own") },
            { assertFalse(pullerId in gate.peers.value, "the old exchange must not admit the new connection") },
        )
    }

    // ── over a real mesh ───────────────────────────────────────────────────────────────────────

    @Test
    fun aDeviceAndAPullerOverAMeshAdmitEachOtherAndNobodyElse() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val deviceGate = loom.host(Pattern("mutual")).tokenGated(verifier(), backgroundScope)
        val stranger = loom.join(InMemoryTag("stranger"))
        val pullerSeam = loom.join(InMemoryTag("puller"))
        val pullerGate = pullerSeam.tokenGated(GateRole.Prover(code, Random(PROVER_SEED)), backgroundScope)
        runCurrent()

        assertAll(
            { assertEquals(setOf(deviceGate.selfId, pullerGate.selfId), deviceGate.peers.value, "the device's roster") },
            { assertEquals(setOf(deviceGate.selfId, pullerGate.selfId), pullerGate.peers.value, "the puller's roster") },
            { assertTrue(stranger.selfId in pullerSeam.peers.value, "rig: the stranger must really be reachable from the puller") },
        )
    }

    /** Puller and device each need the code. A puller holding the wrong one admits nobody. */
    @Test
    fun aPullerWithTheWrongCodeAdmitsTheDeviceNoMoreThanTheDeviceAdmitsIt() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val deviceGate = loom.host(Pattern("mutual")).tokenGated(verifier(), backgroundScope)
        val pullerGate = loom.join(InMemoryTag("puller")).tokenGated(GateRole.Prover("WRONGGGG", Random(PROVER_SEED)), backgroundScope)
        runCurrent()

        assertAll(
            { assertEquals(setOf(deviceGate.selfId), deviceGate.peers.value, "the device must refuse it") },
            { assertEquals(setOf(pullerGate.selfId), pullerGate.peers.value, "and it must not surface the device") },
        )
    }

    /**
     * A known limit (#2740). A role is fixed, and the verifier is the side that starts, so two pullers
     * hold the same code and never find out. The control is the mesh test above, with one peer of each
     * role.
     */
    @Test
    fun twoPullersHoldingTheSameCodeNeverAdmitEachOther() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val first = loom.host(Pattern("mutual")).tokenGated(GateRole.Prover(code, Random(PROVER_SEED)), backgroundScope)
        val second = loom.join(InMemoryTag("second")).tokenGated(GateRole.Prover(code, Random(PROVER_SEED + 1)), backgroundScope)
        runCurrent()

        assertAll(
            { assertEquals(setOf(first.selfId), first.peers.value) },
            { assertEquals(setOf(second.selfId), second.peers.value) },
        )
    }

    // ── what this does not close ───────────────────────────────────────────────────────────────

    /**
     * A known limit (#3076, #3079), and the reason both of them are still open. What a later frame shows
     * is the id stamped on it, so a stranger wearing the device's id gets what the device's proof earned.
     * On Multipeer that is one extra `MCPeerID` named `x#<device-id>`, by reading and not yet by running.
     *
     * If this reds because the gate now ties a frame to something more than its id, delete it and fix
     * "The honest limitations" in `module.md`.
     */
    @Test
    fun aFrameWearingTheAdmittedDevicesIdReachesThePullersReplicator() = runTest(UnconfinedTestDispatcher()) {
        val rig = PullerRig(this, setOf(deviceId, strangerId))
        with(rig) {
            frameFrom(strangerId)
            val underItsOwnId = relayed.size
            answer(deviceId, challengedBy(deviceId))
            frameFrom(deviceId)

            assertAll(
                { assertEquals(0, underItsOwnId, "control: the same sender under its own id must be dropped") },
                { assertEquals(listOf(deviceId), relayed.map { it.sender }, "the id is all a later frame shows") },
            )
        }
    }

    /**
     * A known limit (#3079). A challenge stamped with the device's id replaces the nonce the puller is
     * waiting on, so the device's real answer lands on nothing and the pull never finishes. A stranger
     * under its own id cannot do this: the nonce is held per sender.
     */
    @Test
    fun aChallengeWearingTheDevicesIdReplacesTheNonceTheDeviceIsAnswering() = runTest(UnconfinedTestDispatcher()) {
        val rig = PullerRig(this, setOf(deviceId, strangerId))
        with(rig) {
            val devicesProof = challengedBy(deviceId, seed = 1)
            challengedBy(strangerId, seed = 2)
            answer(deviceId, devicesProof)
            val afterAStrangerUnderItsOwnId = gate.peers.value
            seam.removePeer(deviceId)
            runCurrent()
            seam.addPeer(deviceId)
            runCurrent()

            val nextProof = challengedBy(deviceId, seed = 3)
            challengedBy(deviceId, seed = 4)
            answer(deviceId, nextProof)

            assertAll(
                { assertTrue(deviceId in afterAStrangerUnderItsOwnId, "control: a stranger under its own id must not interfere") },
                { assertFalse(deviceId in gate.peers.value, "wearing the id, it replaces the nonce and the answer misses") },
            )
        }
    }

    /**
     * The gate's own id is always in `verified`, so a frame stamped with it needs no admission unless the
     * gate says so. No fabric hands a seam a frame from itself, but a WebSocket dialler can claim the
     * server's id, and the review of #3100 measured forged logs reaching a puller that way. The control
     * is the same frame from an admitted peer.
     */
    @Test
    fun aFrameStampedWithThePullersOwnIdNeverReachesItsReplicator() = runTest(UnconfinedTestDispatcher()) {
        val rig = PullerRig(this, setOf(deviceId))
        with(rig) {
            frameFrom(pullerId)
            val afterTheSelfStamp = relayed.size
            answer(deviceId, challengedBy(deviceId))
            frameFrom(deviceId)

            assertAll(
                { assertEquals(0, afterTheSelfStamp, "a frame wearing the receiver's own id must be dropped") },
                { assertEquals(listOf(deviceId), relayed.map { it.sender }, "control: an admitted peer's frame must arrive") },
                { assertEquals(setOf(pullerId, deviceId), gate.peers.value, "and the roster is unchanged by the self-stamp") },
            )
        }
    }

    @Test
    fun aFrameStampedWithTheDevicesOwnIdNeverReachesItsReplicator() = runTest(UnconfinedTestDispatcher()) {
        val seam = FakeSeam(deviceId, setOf(deviceId, pullerId))
        val gate = seam.tokenGated(verifier(), backgroundScope)
        val relayed = mutableListOf<Swatch>()
        backgroundScope.launch { gate.incoming.collect { relayed += it } }
        runCurrent()

        seam.deliver(deviceId, replicationFrame)
        runCurrent()
        val afterTheSelfStamp = relayed.size
        seam.deliver(pullerId, TapAdmitMessage.encode(honestPullerProof(challengeTo(seam, pullerId))))
        runCurrent()
        seam.deliver(pullerId, replicationFrame)
        runCurrent()

        assertAll(
            { assertEquals(0, afterTheSelfStamp, "a frame wearing the receiver's own id must be dropped") },
            { assertEquals(listOf(pullerId), relayed.map { it.sender }, "control: the admitted puller's frame must arrive") },
        )
    }

    private companion object {
        const val VERIFIER_SEED = 7
        const val PROVER_SEED = 11
    }
}

/**
 * Parks any [TapAdmitMessage.Proof] this seam is asked to send until [release] completes, then sends it
 * or, with [failOnRelease], fails as a peer that has gone away. A real send suspends the same way; this
 * makes the window one a test can look inside.
 */
private class ProofHoldingSeam(private val inner: Seam, private val failOnRelease: Boolean = false) : Seam by inner {
    val release = CompletableDeferred<Unit>()
    var held = 0
        private set

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        if (TapAdmitMessage.decode(payload) is TapAdmitMessage.Proof) {
            held++
            release.await()
            if (failOnRelease) throw PeerNotConnected(peer)
        }
        inner.sendTo(peer, payload)
    }
}
