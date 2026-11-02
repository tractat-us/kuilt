@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package us.tractat.kuilt.otel.tap.admit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.otel.tap.TapCbor
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The tap's admission gate against somebody actually attacking it (#1865).
 *
 * Every test here plays the attacker on a raw seam, holding no code, over [InMemoryLoom] — a flat
 * mesh, so joiners reach each other the way any Mac on the LAN reaches a Multipeer session. A gate
 * test that only ever runs the honest handshake cannot tell a working gate from one a stranger walks
 * through, and for a long time this gate could be walked through.
 *
 * A refusal here is asserted as a `Reject` frame the attacker received, not as the attacker's absence
 * from a roster: absence is also what a rig that never ran produces. Each test counts its rig's
 * firings for the same reason.
 */
class TapAdmissionAttackTest {
    private val t0 = Instant.fromEpochSeconds(1_700_000_000)
    private val clock = object : Clock { override fun now(): Instant = t0 }
    private val code = "CODE1234"
    private val token = LogTapJoinToken(code, t0, 5.minutes)

    private fun verifier(seed: Int = VERIFIER_SEED) = GateRole.Verifier(token, clock, Random(seed))

    private fun prover() = GateRole.Prover(code, Random(PROVER_SEED))

    // ── the relay ──────────────────────────────────────────────────────────────────────────────

    /**
     * The role-inverted topology `installLogTapJoining` ships: the puller hosts, the device joins.
     *
     * The attacker gets to the puller first, then asks it to answer the device's own challenge and
     * hands the answer back to the device as its own proof.
     */
    @Test
    fun aRelayedTagDoesNotAdmitTheRelayerWhenThePullerHosts() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val pullerGate = loom.host(Pattern("relay")).tokenGated(prover(), backgroundScope)
        val attacker = RelayingAttacker(loom.join(InMemoryTag("attacker")), pullerGate.selfId, backgroundScope)

        attacker.challengeThePuller()
        val deviceGate = loom.join(InMemoryTag("device")).tokenGated(verifier(), backgroundScope)
        runCurrent()

        assertRelayRefused(deviceGate, pullerGate, attacker)
    }

    /**
     * The plain topology `installLogTap` ships: the device hosts, the puller joins.
     *
     * The device's gate is wrapped last, which is how this test forces the attacker to win the race
     * to the puller. Over a real network that race is the attacker's to win or lose; forcing it means
     * the test does not depend on which way it fell.
     */
    @Test
    fun aRelayedTagDoesNotAdmitTheRelayerWhenTheDeviceHosts() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val deviceSeam = loom.host(Pattern("relay"))
        val attackerSeam = loom.join(InMemoryTag("attacker"))
        val pullerGate = loom.join(InMemoryTag("puller")).tokenGated(prover(), backgroundScope)
        val attacker = RelayingAttacker(attackerSeam, pullerGate.selfId, backgroundScope)

        attacker.challengeThePuller()
        val deviceGate = deviceSeam.tokenGated(verifier(), backgroundScope)
        runCurrent()

        assertRelayRefused(deviceGate, pullerGate, attacker)
    }

    private fun assertRelayRefused(deviceGate: Seam, pullerGate: Seam, attacker: RelayingAttacker) {
        assertAll(
            { assertEquals(1, attacker.deviceChallenges.size, "rig: the device must have challenged the attacker") },
            { assertEquals(1, attacker.forwarded.size, "rig: the attacker must have sent the puller's tag to the device") },
            {
                // A garbage frame would be refused too, so this has to be a tag the puller really made.
                val nonce = attacker.deviceChallenges.single().nonce
                val proof = TapAdmitMessage.decode(attacker.forwarded.single()) as TapAdmitMessage.Proof
                val genuine = admissionTag(code, attacker.selfId, pullerGate.selfId, nonce, proof.proverNonce)
                assertContentEquals(genuine, proof.tag.toByteArray(), "rig: what was forwarded must be the puller's real answer")
            },
            { assertFalse(attacker.selfId in deviceGate.peers.value, "the relayer must not be admitted") },
            { assertEquals(listOf(deviceGate.selfId), attacker.rejectedBy, "the device must refuse the relayed tag out loud") },
            { assertTrue(pullerGate.selfId in deviceGate.peers.value, "the honest puller must still be admitted") },
        )
    }

    // ── the harvest ────────────────────────────────────────────────────────────────────────────

    /**
     * The attack as #1865 describes it: choose a nonce, build a table of `code → tag` in advance, and
     * look a harvested tag up. The attacker knows the format, so the table is built with the real
     * [admissionTag], and over its best guess at the one input it cannot choose: the prover nonce from
     * the last answer it saw.
     *
     * The first lookup is the control, and it is the point. Against that earlier answer the table does
     * name the code, so it is a working table and the miss on the next answer is the prover's nonce at
     * work. A table spelled in an older format would miss for the wrong reason, under any gate at all,
     * which is what this test used to be. The control is also the offline grind in miniature: a tag in
     * hand can be tested against guesses, and only the token's lifetime and the size of the code limit
     * that.
     */
    @Test
    fun aTableBuiltFromOneAnswerNamesTheCodeInThatAnswerAndNotInTheNext() = runTest(UnconfinedTestDispatcher()) {
        val chosenNonce = ByteArray(TapAdmitMessage.Challenge.NONCE_BYTES) { 0x5A }
        val loom = InMemoryLoom()
        val pullerGate = loom.host(Pattern("harvest")).tokenGated(prover(), backgroundScope)
        val harvester = Harvester(loom.join(InMemoryTag("attacker")), backgroundScope)

        repeat(2) {
            harvester.challenge(pullerGate.selfId, chosenNonce)
            runCurrent()
        }

        assertEquals(2, harvester.proofs.size, "rig: the puller must have answered both challenges")
        val (earlier, next) = harvester.proofs
        val table = listOf("AAAAAAAA", "BBBBBBBB", code, "CCCCCCCC").associateBy { candidate ->
            admissionTag(
                candidate,
                harvester.seam.selfId,
                pullerGate.selfId,
                ByteString(chosenNonce),
                earlier.proverNonce,
            ).toList()
        }
        assertAll(
            { assertEquals(code, table[earlier.tag.toByteArray().toList()], "control: this table must be able to name the code") },
            { assertNull(table[next.tag.toByteArray().toList()], "a table built in advance must not recognize the next answer") },
        )
    }

    /**
     * The same challenge never draws the same tag twice, so the tag is not a function of anything the
     * attacker knew when building a table.
     */
    @Test
    fun oneChallengeAnsweredTwiceDrawsTwoDifferentTags() = runTest(UnconfinedTestDispatcher()) {
        val chosenNonce = ByteArray(TapAdmitMessage.Challenge.NONCE_BYTES) { 0x5A }
        val loom = InMemoryLoom()
        val pullerGate = loom.host(Pattern("harvest")).tokenGated(prover(), backgroundScope)
        val harvester = Harvester(loom.join(InMemoryTag("attacker")), backgroundScope)

        repeat(2) { harvester.challenge(pullerGate.selfId, chosenNonce) }
        runCurrent()

        assertEquals(2, harvester.proofs.size, "rig: both challenges must have been answered")
        assertFalse(
            harvester.proofs[0].tag.toByteArray().contentEquals(harvester.proofs[1].tag.toByteArray()),
            "a tag the challenger can predict is a tag it can precompute",
        )
    }

    /**
     * A tag harvested earlier, shown to the device later, against the very nonce it was harvested for.
     *
     * A real verifier's nonce should never repeat, so this seeds the device's source to make it repeat:
     * this is the worst the replay can be, and the fix has to hold there rather than lean on the nonce
     * being fresh. The frame is replayed byte for byte, so this does not depend on its shape.
     */
    @Test
    fun aHarvestedTagDoesNotReplayAgainstALaterChallengeForTheSameNonce() = runTest(UnconfinedTestDispatcher()) {
        val predicted = Random(VERIFIER_SEED).nextBytes(TapAdmitMessage.Challenge.NONCE_BYTES)
        val loom = InMemoryLoom()
        val pullerGate = loom.host(Pattern("replay")).tokenGated(prover(), backgroundScope)
        val harvester = Harvester(loom.join(InMemoryTag("attacker")), backgroundScope)

        harvester.challenge(pullerGate.selfId, predicted)
        runCurrent()
        val harvestedFrame = assertNotNull(harvester.proofFrames.singleOrNull(), "rig: the puller must have answered")
        pullerGate.close() // long gone by the time it matters, and the device's first draw is the harvester's

        val deviceGate = loom.join(InMemoryTag("device")).tokenGated(verifier(), backgroundScope)
        runCurrent()
        val deviceChallenge = harvester.challengesFrom(deviceGate.selfId).singleOrNull()
        harvester.seam.sendTo(deviceGate.selfId, harvestedFrame)
        runCurrent()

        assertAll(
            { assertNotNull(deviceChallenge, "rig: the device must have challenged the harvester") },
            {
                assertContentEquals(
                    predicted,
                    deviceChallenge?.nonce?.toByteArray(),
                    "rig: the device must have issued the very nonce the tag was harvested for",
                )
            },
            { assertFalse(harvester.seam.selfId in deviceGate.peers.value, "a replayed tag must not admit") },
            { assertEquals(listOf(deviceGate.selfId), harvester.rejectedBy, "the device must refuse the replay out loud") },
        )
    }

    // ── the stall ──────────────────────────────────────────────────────────────────────────────

    /**
     * A stranger keeps challenging the puller and never reads a thing. Every challenge earns a proof,
     * so the stranger controls how many frames head its way, and its unread buffer fills until the
     * puller's next send to it suspends. That is `Seam.sendTo` doing its job: it suspends until the
     * transport accepts the frame. The gate's job is to not be standing there when it happens, since
     * the same collector carries every other peer's frames.
     */
    @Test
    fun aStrangerWhoStopsReadingDoesNotStallThePuller() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val pullerSeam = SendCountingSeam(loom.host(Pattern("stall")))
        val pullerGate = pullerSeam.tokenGated(prover(), backgroundScope)
        val relayed = mutableListOf<ByteArray>()
        backgroundScope.launch { pullerGate.incoming.collect { relayed += it.toByteArray() } }
        val stranger = loom.join(InMemoryTag("stranger"))

        floodUntilAnAnswerParks(stranger, pullerSeam)

        val deviceSeam = loom.join(InMemoryTag("device"))
        val deviceGate = deviceSeam.tokenGated(verifier(), backgroundScope)
        runCurrent()
        val replicationFrame = byteArrayOf(0x01, 0x02, 0x03)
        deviceSeam.sendTo(pullerGate.selfId, replicationFrame)
        runCurrent()

        assertAll(
            { assertEquals(1, pullerSeam.parkedTo(stranger.selfId), "rig: an answer to the stranger must be parked in the fabric") },
            { assertTrue(pullerSeam.deliveredTo(stranger.selfId) > 0, "rig: the stranger's buffer must have taken answers first") },
            { assertTrue(pullerGate.selfId in deviceGate.peers.value, "the device's challenge must still be answered") },
            { assertContentEquals(replicationFrame, relayed.singleOrNull(), "the device's frames must still reach the replicator") },
        )
    }

    /**
     * The fix's own bound. Taking the send off the collector could have swapped a stall for a job per
     * challenge, which a stranger can run up for free. It also must not strand the newest answer behind
     * an older one, or a peer back on a fresh link would never hear from the puller.
     */
    @Test
    fun aFloodHoldsOneParkedAnswerAndItIsTheNewest() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val pullerSeam = SendCountingSeam(loom.host(Pattern("stall")))
        pullerSeam.tokenGated(prover(), backgroundScope)
        val strangerSeam = loom.join(InMemoryTag("stranger"))

        val sent = floodUntilAnAnswerParks(strangerSeam, pullerSeam)
        val taken = pullerSeam.deliveredTo(strangerSeam.selfId)
        val more = 50
        repeat(more) { i ->
            strangerSeam.sendTo(pullerSeam.selfId, TapAdmitMessage.encode(TapAdmitMessage.Challenge(challengeNonce(sent + i))))
            runCurrent()
        }
        val parkedAfterTheFlood = pullerSeam.parkedTo(strangerSeam.selfId)
        val takenDuringTheFlood = pullerSeam.deliveredTo(strangerSeam.selfId) - taken

        val stranger = Harvester(strangerSeam, backgroundScope) // starts reading, which frees the fabric
        runCurrent()

        val newestNonce = challengeNonce(sent + more - 1)
        val newest = stranger.proofs.last()
        assertAll(
            { assertEquals(1, parkedAfterTheFlood, "the flood must hold one parked answer, however long it runs") },
            { assertEquals(0, takenDuringTheFlood, "rig: the fabric must have taken nothing while the flood ran") },
            { assertEquals(taken + 1, stranger.proofs.size, "what it had taken, plus the one parked answer") },
            {
                assertContentEquals(
                    admissionTag(code, strangerSeam.selfId, pullerSeam.selfId, newestNonce, newest.proverNonce),
                    newest.tag.toByteArray(),
                    "the answer that survives must be to the newest challenge",
                )
            },
        )
    }

    /** Challenge the puller until the fabric stops taking its answers, and return how many that took. */
    private suspend fun TestScope.floodUntilAnAnswerParks(stranger: Seam, puller: SendCountingSeam): Int {
        var sent = 0
        while (puller.parkedTo(stranger.selfId) == 0) {
            check(sent < FLOOD_LIMIT) { "rig: $sent challenges and the fabric still takes every answer" }
            stranger.sendTo(puller.selfId, TapAdmitMessage.encode(TapAdmitMessage.Challenge(challengeNonce(sent))))
            sent++
            runCurrent()
        }
        return sent
    }

    private fun challengeNonce(seed: Int) = ByteString(Random(seed).nextBytes(TapAdmitMessage.Challenge.NONCE_BYTES))

    // ── what this does not close ───────────────────────────────────────────────────────────────

    /**
     * A known limit, pinned so the claim about it is a measurement. Nothing authenticates a peer id, so
     * a relayer on two separate sessions can be the puller's id to the device and the device's id to the
     * puller, and then every id in the tag is the one each side expects. Two [InMemoryLoom]s number their
     * peers the same way, which hands this test exactly those ids; on a real fabric the attacker claims
     * them. See "What the peer ids buy" on [TokenGatedSeam].
     *
     * If this reds because the relay is now refused, the handshake got stronger: delete it and say so.
     */
    @Test
    fun aRelayWearingBothIdsAcrossTwoSessionsIsStillAdmitted() = runTest(UnconfinedTestDispatcher()) {
        val relay = relayAcrossTwoSessions(wearsDevicesIdToPuller = true, wearsPullersIdToDevice = true)
        assertTrue(relay.attackerAdmitted, "this relay is not closed, and the docs must keep saying so")
    }

    /**
     * The same relay wearing one id short. Either id alone refuses a relayer on one session, so the
     * tests above would pass with one of them missing from the tag; these are what need both.
     */
    @Test
    fun aRelayWearingOnlyTheDevicesIdIsRefused() = runTest(UnconfinedTestDispatcher()) {
        val relay = relayAcrossTwoSessions(wearsDevicesIdToPuller = true, wearsPullersIdToDevice = false)
        assertFalse(relay.attackerAdmitted, "the tag names the prover, and the attacker is not the puller at the device")
    }

    @Test
    fun aRelayWearingOnlyThePullersIdIsRefused() = runTest(UnconfinedTestDispatcher()) {
        val relay = relayAcrossTwoSessions(wearsDevicesIdToPuller = false, wearsPullersIdToDevice = true)
        assertFalse(relay.attackerAdmitted, "the tag names the verifier, and the attacker is not the device at the puller")
    }

    private class TwoSessionRelay(val attackerAdmitted: Boolean)

    /**
     * Run a relay across two [InMemoryLoom]s, each of which numbers its peers from `peer-1` in the order
     * they arrive: the puller hosts its own as `peer-1`, and the device joins its own as `peer-2`. The
     * attacker's arrival order on each is what decides which id it wears there, and the rig asserts it.
     */
    private suspend fun TestScope.relayAcrossTwoSessions(
        wearsDevicesIdToPuller: Boolean,
        wearsPullersIdToDevice: Boolean,
    ): TwoSessionRelay {
        val pullerSession = InMemoryLoom()
        val pullerGate = pullerSession.host(Pattern("puller-session")).tokenGated(prover(), backgroundScope)
        if (!wearsDevicesIdToPuller) pullerSession.join(InMemoryTag("bystander"))
        val attackerToPuller = pullerSession.join(InMemoryTag("attacker"))

        val deviceSession = InMemoryLoom()
        val firstOnDeviceSession = deviceSession.host(Pattern("device-session"))
        val deviceSeam = deviceSession.join(InMemoryTag("device"))
        val attackerToDevice = if (wearsPullersIdToDevice) firstOnDeviceSession else deviceSession.join(InMemoryTag("attacker"))

        var forwarded = 0
        backgroundScope.launch {
            attackerToPuller.incoming.collect { swatch ->
                if (TapAdmitMessage.decode(swatch.toByteArray()) is TapAdmitMessage.Proof) {
                    attackerToDevice.sendTo(deviceSeam.selfId, swatch.toByteArray())
                    forwarded++
                }
            }
        }
        backgroundScope.launch {
            attackerToDevice.incoming.collect { swatch ->
                if (TapAdmitMessage.decode(swatch.toByteArray()) is TapAdmitMessage.Challenge) {
                    attackerToPuller.sendTo(pullerGate.selfId, swatch.toByteArray())
                }
            }
        }

        val deviceGate = deviceSeam.tokenGated(verifier(), backgroundScope)
        runCurrent()

        assertAll(
            { assertEquals(1, forwarded, "rig: the attacker must have carried the puller's answer to the device") },
            {
                assertEquals(
                    wearsDevicesIdToPuller,
                    attackerToPuller.selfId == deviceGate.selfId,
                    "rig: whether the attacker wears the device's id to the puller",
                )
            },
            {
                assertEquals(
                    wearsPullersIdToDevice,
                    attackerToDevice.selfId == pullerGate.selfId,
                    "rig: whether the attacker wears the puller's id to the device",
                )
            },
        )
        return TwoSessionRelay(attackerToDevice.selfId in deviceGate.peers.value)
    }

    // ── mixed versions ─────────────────────────────────────────────────────────────────────────

    /** An old puller's answer is correct by the old rules, and that must count for nothing here. */
    @Test
    fun aDeviceDoesNotAdmitAPullerSpeakingTheOldProof() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val deviceGate = loom.host(Pattern("mixed")).tokenGated(verifier(), backgroundScope)
        val oldPuller = Harvester(loom.join(InMemoryTag("old-puller")), backgroundScope)
        runCurrent()

        val challenge = assertNotNull(
            oldPuller.challengesFrom(deviceGate.selfId).singleOrNull(),
            "rig: the device must have challenged the old puller",
        )
        val legacyTag = hmacSha256(code.encodeToByteArray(), challenge.nonce.toByteArray())
        oldPuller.seam.sendTo(deviceGate.selfId, legacyProofFrame(legacyTag))
        runCurrent()

        assertFalse(oldPuller.seam.selfId in deviceGate.peers.value, "a correct old-format proof must not admit")
    }

    /** The other direction: run the old verifier's own check over what a current puller answers. */
    @Test
    fun anOldDeviceDoesNotAdmitACurrentPuller() = runTest(UnconfinedTestDispatcher()) {
        val nonce = ByteArray(TapAdmitMessage.Challenge.NONCE_BYTES) { 0x11 }
        val loom = InMemoryLoom()
        val pullerGate = loom.host(Pattern("mixed")).tokenGated(prover(), backgroundScope)
        val oldDevice = Harvester(loom.join(InMemoryTag("old-device")), backgroundScope)

        oldDevice.challenge(pullerGate.selfId, nonce)
        runCurrent()

        val answer = assertNotNull(oldDevice.proofFrames.singleOrNull(), "rig: the puller must have answered")
        assertAll(
            {
                assertTrue(
                    oldVerifierAccepts(legacyProofFrame(hmacSha256(code.encodeToByteArray(), nonce)), nonce),
                    "control: the old check must accept a proof it was built for, or this test is vacuous",
                )
            },
            { assertFalse(oldVerifierAccepts(answer, nonce), "an old device must not admit a current puller") },
        )
    }

    /** What a pre-#1865 verifier did with a `Proof`: decode one `tag`, and compare it to `HMAC(code, nonce)`. */
    private fun oldVerifierAccepts(frame: ByteArray, nonce: ByteArray): Boolean {
        val decoded = runCatchingCancellable {
            TapCbor.decodeFromByteArray<LegacyAdmitMessage>(frame.copyOfRange(1, frame.size))
        }.getOrNull() as? LegacyAdmitMessage.Proof ?: return false
        return constantTimeEquals(hmacSha256(code.encodeToByteArray(), nonce), decoded.tag)
    }

    private fun legacyProofFrame(tag: ByteArray): ByteArray =
        byteArrayOf(TapAdmitMessage.PREFIX_BYTE) + TapCbor.encodeToByteArray<LegacyAdmitMessage>(LegacyAdmitMessage.Proof(tag))

    /**
     * A peer holding no code that answers the device's challenge by asking the puller. It forwards the
     * puller's frame byte for byte, so it does not need to know what is in one.
     */
    private class RelayingAttacker(private val seam: Seam, private val puller: PeerId, scope: CoroutineScope) {
        val selfId: PeerId = seam.selfId
        val deviceChallenges = mutableListOf<TapAdmitMessage.Challenge>()
        val forwarded = mutableListOf<ByteArray>()
        val rejectedBy = mutableListOf<PeerId>()
        private var awaitingAnswerFor: PeerId? = null

        init {
            scope.launch {
                seam.incoming.collect { swatch ->
                    val sender = swatch.sender ?: return@collect
                    val frame = swatch.toByteArray()
                    when (val message = TapAdmitMessage.decode(frame)) {
                        is TapAdmitMessage.Challenge -> if (sender != puller) {
                            deviceChallenges += message
                            awaitingAnswerFor = sender
                            seam.sendTo(puller, TapAdmitMessage.encode(message))
                        }
                        is TapAdmitMessage.Proof -> awaitingAnswerFor?.let { device ->
                            awaitingAnswerFor = null
                            seam.sendTo(device, frame)
                            forwarded += frame
                        }
                        is TapAdmitMessage.Reject -> rejectedBy += sender
                        null -> Unit
                    }
                }
            }
        }

        /** Any challenge will do: before the fix, this is what made the puller answer nobody else. */
        suspend fun challengeThePuller() {
            val nonce = ByteString(ByteArray(TapAdmitMessage.Challenge.NONCE_BYTES))
            seam.sendTo(puller, TapAdmitMessage.encode(TapAdmitMessage.Challenge(nonce)))
        }
    }

    /** A peer holding no code that keeps every admit frame it is sent. */
    private class Harvester(val seam: Seam, scope: CoroutineScope) {
        val proofFrames = mutableListOf<ByteArray>()
        val proofs get() = proofFrames.map { TapAdmitMessage.decode(it) as TapAdmitMessage.Proof }
        val rejectedBy = mutableListOf<PeerId>()
        private val challenges = mutableListOf<Pair<PeerId, TapAdmitMessage.Challenge>>()

        init {
            scope.launch {
                seam.incoming.collect { swatch ->
                    val sender = swatch.sender ?: return@collect
                    val frame = swatch.toByteArray()
                    when (val message = TapAdmitMessage.decode(frame)) {
                        is TapAdmitMessage.Challenge -> challenges += sender to message
                        is TapAdmitMessage.Proof -> proofFrames += frame
                        is TapAdmitMessage.Reject -> rejectedBy += sender
                        null -> Unit
                    }
                }
            }
        }

        fun challengesFrom(peer: PeerId): List<TapAdmitMessage.Challenge> =
            challenges.filter { it.first == peer }.map { it.second }

        suspend fun challenge(target: PeerId, nonce: ByteArray) {
            seam.sendTo(target, TapAdmitMessage.encode(TapAdmitMessage.Challenge(ByteString(nonce))))
        }
    }

    private companion object {
        const val VERIFIER_SEED = 7
        const val PROVER_SEED = 11
        const val FLOOD_LIMIT = 10_000
    }
}

/**
 * Counts the sends to each peer that the fabric has taken and the ones it is still sitting on, so a
 * test can show a send really did suspend. A stall test without this passes when the buffer never
 * filled.
 */
internal class SendCountingSeam(private val inner: Seam) : Seam by inner {
    private val parked = mutableMapOf<PeerId, Int>()
    private val delivered = mutableMapOf<PeerId, Int>()

    fun parkedTo(peer: PeerId): Int = parked[peer] ?: 0

    fun deliveredTo(peer: PeerId): Int = delivered[peer] ?: 0

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        parked[peer] = parkedTo(peer) + 1
        try {
            inner.sendTo(peer, payload)
            delivered[peer] = deliveredTo(peer) + 1
        } finally {
            parked[peer] = parkedTo(peer) - 1
        }
    }
}

/**
 * The `Proof` frame as it was before #1865: one `tag`, over `HMAC(code, nonce)`. Only this arm is
 * mirrored, since the polymorphic discriminator comes from the `@SerialName` and not from how many
 * arms the hierarchy has.
 */
@Serializable
private sealed interface LegacyAdmitMessage {
    @Serializable
    @SerialName("proof")
    class Proof(val tag: ByteArray) : LegacyAdmitMessage
}
