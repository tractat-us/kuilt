@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.otel.tap.admit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The security core: a [TokenGatedSeam] verifier only surfaces a peer that proves
 * knowledge of the join code within the token's validity window. Drives a host/joiner
 * pair over an in-memory [InMemoryLoom] under eager virtual time.
 *
 * The gate coroutines run on `backgroundScope` so their infinite `inner.incoming` /
 * `inner.peers` collectors cancel cleanly at teardown.
 */
class TokenGatedSeamTest {
    private val t0 = Instant.fromEpochSeconds(1_700_000_000)
    private fun clockAt(instant: Instant) = object : Clock { override fun now(): Instant = instant }

    @Test
    fun validCodeSurfacesThePullerToTheHost() = runTest(UnconfinedTestDispatcher()) {
        val token = LogTapJoinToken.issue(Random(1), clockAt(t0), ttl = 5.minutes)
        val (hostGate, _) = gatedPair(token, presentedCode = token.code, verifierClock = clockAt(t0), scope = backgroundScope)
        val verified = hostGate.peers.first { peers -> peers.any { it != hostGate.selfId } }
        assertTrue(verified.size >= 2, "puller with the valid code is admitted")
    }

    @Test
    fun wrongCodeNeverSurfacesThePuller() = runTest(UnconfinedTestDispatcher()) {
        val token = LogTapJoinToken.issue(Random(1), clockAt(t0), ttl = 5.minutes)
        val (hostGate, _) = gatedPair(token, presentedCode = "WRONGGGG", verifierClock = clockAt(t0), scope = backgroundScope)
        val surfaced = withTimeoutOrNull(1.minutes) {
            hostGate.peers.first { peers -> peers.any { it != hostGate.selfId } }
        }
        assertNull(surfaced, "an adversary with the wrong code never enters the verified peer set")
    }

    @Test
    fun expiredTokenIsRefusedEvenWithTheRightCode() = runTest(UnconfinedTestDispatcher()) {
        val token = LogTapJoinToken.issue(Random(1), clockAt(t0), ttl = 5.minutes)
        // Verifier's clock is past the TTL by the time the (correct) proof arrives.
        val (hostGate, _) = gatedPair(token, presentedCode = token.code, verifierClock = clockAt(t0 + 6.minutes), scope = backgroundScope)
        val surfaced = withTimeoutOrNull(1.minutes) {
            hostGate.peers.first { peers -> peers.any { it != hostGate.selfId } }
        }
        assertNull(surfaced, "a correct code presented after expiry is refused")
    }

    /**
     * Replay/forgery defense: a proof over a *foreign* nonce (not the one the verifier issued) is
     * rejected, even though everything else about it is right: the code, both ids, and the prover
     * nonce the attacker sends alongside it. This is the property that stops a captured proof from
     * being replayed against a fresh challenge — the verifier checks the tag against the nonce **it**
     * sent, and each challenge is fresh. The control arm is the same forger using the nonce it was
     * sent, which proves the only thing wrong with the first proof was the nonce.
     */
    @Test
    fun proofBoundToAForeignNonceIsRejected() = runTest(UnconfinedTestDispatcher()) {
        val foreignNonce = ByteArray(TapAdmitMessage.Challenge.NONCE_BYTES) { 0x5A }
        val (hostGate, forgerId) = forgerAgainstAVerifier { foreignNonce }
        assertFalse(forgerId in hostGate.peers.value, "a proof over a foreign nonce is refused")
    }

    /** The control for the test above: the same forged proof over the nonce it was actually sent. */
    @Test
    fun aProofOverTheIssuedNonceFromTheSameRigIsAdmitted() = runTest(UnconfinedTestDispatcher()) {
        val (hostGate, forgerId) = forgerAgainstAVerifier { issued -> issued }
        assertTrue(forgerId in hostGate.peers.value, "the rig's proof must be good but for the nonce")
    }

    /**
     * A verifier, and a raw joiner holding the real code that answers its challenge over whatever
     * nonce [nonceToProve] picks. Returns the verifier's gate and the joiner's id.
     */
    private suspend fun TestScope.forgerAgainstAVerifier(
        nonceToProve: (issued: ByteArray) -> ByteArray,
    ): Pair<Seam, PeerId> {
        val token = LogTapJoinToken.issue(Random(1), clockAt(t0), ttl = 5.minutes)
        val loom = InMemoryLoom()
        val hostSeam = loom.host(Pattern("gate-test"))
        val joinerSeam = loom.join(InMemoryTag("attacker"))
        backgroundScope.launch {
            joinerSeam.incoming.collect { swatch ->
                val challenge = TapAdmitMessage.decode(swatch.toByteArray()) as? TapAdmitMessage.Challenge ?: return@collect
                val verifierId = swatch.sender ?: return@collect
                val proverNonce = ByteString(ByteArray(TapAdmitMessage.Challenge.NONCE_BYTES) { 3 })
                val tag = admissionTag(
                    code = token.code,
                    verifierId = verifierId,
                    proverId = joinerSeam.selfId,
                    challengeNonce = ByteString(nonceToProve(challenge.nonce.toByteArray())),
                    proverNonce = proverNonce,
                )
                joinerSeam.sendTo(verifierId, TapAdmitMessage.encode(TapAdmitMessage.Proof(proverNonce, ByteString(tag))))
            }
        }
        val hostGate = hostSeam.tokenGated(GateRole.Verifier(token, clockAt(t0), Random(7)), backgroundScope)
        runCurrent()
        return hostGate to joinerSeam.selfId
    }

    /**
     * The prover answers whoever asks, and the answer is good for the asker alone. Answering only the
     * first challenger let a stranger lock the real device out (#1865), so the safety moved from *who
     * gets an answer* to *what the answer is good for*.
     */
    @Test
    fun proverAnswersEveryChallengerWithATagNamingThatChallenger() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val proverSeam = loom.host(Pattern("prover-hosts"))
        val deviceSeam = loom.join(InMemoryTag("device"))
        val strangerSeam = loom.join(InMemoryTag("stranger"))
        val proverGate = proverSeam.tokenGated(GateRole.Prover("CODE1234", Random(11)), backgroundScope)
        val nonce = ByteString(ByteArray(TapAdmitMessage.Challenge.NONCE_BYTES) { 1 })

        suspend fun answerTo(challenger: Seam): TapAdmitMessage.Proof {
            challenger.sendTo(proverGate.selfId, TapAdmitMessage.encode(TapAdmitMessage.Challenge(nonce)))
            val frame = withTimeout(1.minutes) {
                challenger.incoming.first { TapAdmitMessage.decode(it.toByteArray()) is TapAdmitMessage.Proof }
            }
            return TapAdmitMessage.decode(frame.toByteArray()) as TapAdmitMessage.Proof
        }

        fun tagNaming(verifier: Seam, proof: TapAdmitMessage.Proof) = ByteString(
            admissionTag("CODE1234", verifier.selfId, proverGate.selfId, nonce, proof.proverNonce),
        )

        // The stranger asks first, which is the order that used to shut the device out.
        val toStranger = answerTo(strangerSeam)
        val toDevice = answerTo(deviceSeam)

        assertAll(
            { assertEquals(tagNaming(strangerSeam, toStranger), toStranger.tag, "the stranger's answer names the stranger") },
            { assertEquals(tagNaming(deviceSeam, toDevice), toDevice.tag, "the device's answer names the device") },
            { assertNotEquals(tagNaming(deviceSeam, toStranger), toStranger.tag, "the stranger's answer is no use at the device") },
        )
    }

    /** Each answer's nonce comes from the source the prover was given, a fresh draw every time. */
    @Test
    fun proverDrawsAFreshNonceFromItsSourceForEachAnswer() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val proverSeam = loom.host(Pattern("prover-hosts"))
        val challengerSeam = loom.join(InMemoryTag("challenger"))
        val proverGate = proverSeam.tokenGated(GateRole.Prover("CODE1234", Random(11)), backgroundScope)
        val answers = mutableListOf<TapAdmitMessage.Proof>()
        backgroundScope.launch {
            challengerSeam.incoming.collect { swatch ->
                (TapAdmitMessage.decode(swatch.toByteArray()) as? TapAdmitMessage.Proof)?.let { answers += it }
            }
        }
        val frame = TapAdmitMessage.encode(TapAdmitMessage.Challenge(ByteString(ByteArray(TapAdmitMessage.Challenge.NONCE_BYTES))))

        repeat(2) { challengerSeam.sendTo(proverGate.selfId, frame) }
        runCurrent()

        val source = Random(11)
        assertEquals(
            List(2) { ByteString(source.nextBytes(TapAdmitMessage.Challenge.NONCE_BYTES)) },
            answers.map { it.proverNonce },
            "each answer must carry the next draw from the prover's own source",
        )
    }

    /**
     * Fixed-width nonce enforcement, at the live prover (#1820). The prover MACs `Challenge.nonce`
     * verbatim, so a peer that can pick the width picks how many bytes of the MAC input exist. The
     * malformed challenge must draw **no proof**, and the prover must still answer the next one.
     */
    @Test
    fun proverIgnoresAChallengeWhoseNonceIsTheWrongWidth() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val proverSeam = loom.host(Pattern("prover-hosts"))
        val attackerSeam = loom.join(InMemoryTag("attacker"))
        val hostSeam = loom.join(InMemoryTag("host")) // the legitimate verifier device
        val proverGate = proverSeam.tokenGated(GateRole.Prover("CODE1234", Random(11)), backgroundScope)
        val proverId = proverGate.selfId

        attackerSeam.sendTo(proverId, challengeFrameWithNonce(ByteArray(0)))
        val harvested = withTimeoutOrNull(1.minutes) {
            attackerSeam.incoming.first { TapAdmitMessage.decode(it.toByteArray()) is TapAdmitMessage.Proof }
        }
        assertNull(harvested, "a wrong-width nonce must never be MAC'd with the join code")

        val nonce = ByteString(ByteArray(TapAdmitMessage.Challenge.NONCE_BYTES) { 1 })
        hostSeam.sendTo(proverId, TapAdmitMessage.encode(TapAdmitMessage.Challenge(nonce)))
        val hostProof = withTimeoutOrNull(1.minutes) {
            hostSeam.incoming.first { TapAdmitMessage.decode(it.toByteArray()) is TapAdmitMessage.Proof }
        }
        assertTrue(hostProof != null, "a rejected challenge must not lock out the legitimate host")
    }

    /**
     * `Seam.sendTo` refuses `sendTo(selfId)` with `IllegalArgumentException` (#2428), and the gate has
     * to carry that ahead of its admission check rather than delegating it.
     *
     * `selfId` is never in `verified`, so the Verifier arm would classify a self-send as an
     * unadmitted peer and **drop** it with a debug line — a silent success from the caller's view,
     * and the inner seam that does carry the refusal is never reached. Asserted on the verifier
     * *because* that is the dropping arm; the prover arm forwards and would inherit it either way.
     */
    @Test
    fun selfSendIsRefusedByTheVerifierRatherThanDroppedAsUnadmitted() = runTest(UnconfinedTestDispatcher()) {
        val token = LogTapJoinToken.issue(Random(1), clockAt(t0), ttl = 5.minutes)
        val (hostGate, _) = gatedPair(token, presentedCode = token.code, verifierClock = clockAt(t0), scope = backgroundScope)

        assertTrue(
            hostGate.selfId !in hostGate.peers.value.filter { it != hostGate.selfId }.toSet(),
            "precondition: selfId is never an ADMITTED peer, which is why the gate would drop it",
        )
        assertFailsWith<IllegalArgumentException>(
            "the verifier must REFUSE a self-send, not drop it as an unadmitted peer",
        ) {
            hostGate.sendTo(hostGate.selfId, byteArrayOf(1))
        }
    }

    /**
     * Wire an in-memory host/joiner [Seam] pair, wrap the host as a [GateRole.Verifier] and the
     * joiner as a [GateRole.Prover] presenting [presentedCode], and return both gated seams.
     */
    private suspend fun gatedPair(
        token: LogTapJoinToken,
        presentedCode: String,
        verifierClock: Clock,
        scope: CoroutineScope,
    ): Pair<Seam, Seam> {
        val loom = InMemoryLoom()
        val hostSeam = loom.host(Pattern("gate-test"))
        val joinerSeam = loom.join(InMemoryTag("puller"))
        val hostGate = hostSeam.tokenGated(GateRole.Verifier(token, verifierClock, Random(7)), scope)
        val joinerGate = joinerSeam.tokenGated(GateRole.Prover(presentedCode, Random(11)), scope)
        return hostGate to joinerGate
    }
}
