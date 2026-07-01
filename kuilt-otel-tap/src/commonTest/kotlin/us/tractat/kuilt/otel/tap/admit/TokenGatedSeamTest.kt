@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.otel.tap.admit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import kotlin.random.Random
import kotlin.test.Test
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
     * Replay/forgery defense: a proof bound to a *foreign* nonce (not the one the verifier
     * issued) is rejected, even though it is a valid HMAC of the correct code. This is the
     * property that stops a captured proof from being replayed against a fresh challenge —
     * the verifier checks the tag against the nonce **it** sent, and each challenge is fresh.
     */
    @Test
    fun proofBoundToAForeignNonceIsRejected() = runTest(UnconfinedTestDispatcher()) {
        val token = LogTapJoinToken.issue(Random(1), clockAt(t0), ttl = 5.minutes)
        val loom = InMemoryLoom()
        val hostSeam = loom.host(Pattern("gate-test"))
        val joinerSeam = loom.join(InMemoryTag("attacker"))
        // Host is a real verifier; the "attacker" joiner is raw (not a Prover) so we can forge.
        val hostGate = hostSeam.tokenGated(GateRole.Verifier(token, clockAt(t0), Random(7)), backgroundScope)

        // Answer the challenge with a proof over a DIFFERENT nonce than the one issued.
        backgroundScope.launch {
            joinerSeam.incoming.collect { swatch ->
                val challenge = TapAdmitMessage.decode(swatch.toByteArray()) as? TapAdmitMessage.Challenge ?: return@collect
                // Correct code, wrong nonce — a stale/replayed proof.
                val foreignNonce = ByteArray(16) { 0x5A }
                check(!foreignNonce.contentEquals(challenge.nonce.toByteArray())) { "nonce collision in test" }
                val tag = ByteString(hmacSha256(token.code.encodeToByteArray(), foreignNonce))
                swatch.sender?.let { joinerSeam.sendTo(it, TapAdmitMessage.encode(TapAdmitMessage.Proof(tag))) }
            }
        }

        val surfaced = withTimeoutOrNull(1.minutes) {
            hostGate.peers.first { peers -> peers.any { it != hostGate.selfId } }
        }
        assertNull(surfaced, "a proof over a foreign nonce is refused")
    }

    /**
     * Offline-harvest hardening (#1052): in the role-inverted topology the prover hosts the
     * session, so any joiner can send it a [TapAdmitMessage.Challenge] purely to harvest
     * `HMAC(code, nonce)` for an offline crack of the join code (bounded only by the token
     * TTL on the plaintext wire). The prover binds to the first host it answers and must NOT
     * answer a challenge from any other sender thereafter.
     */
    @Test
    fun proverAnswersOnlyItsBoundHost() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val proverSeam = loom.host(Pattern("prover-hosts"))
        val hostSeam = loom.join(InMemoryTag("host")) // the legitimate verifier device
        val attackerSeam = loom.join(InMemoryTag("attacker"))
        val proverGate = proverSeam.tokenGated(GateRole.Prover("CODE1234"), backgroundScope)
        val proverId = proverGate.selfId

        // The legitimate host challenges first; the prover binds to it and answers.
        hostSeam.sendTo(proverId, TapAdmitMessage.encode(TapAdmitMessage.Challenge(ByteString(ByteArray(16) { 1 }))))
        val hostProof = withTimeoutOrNull(1.minutes) {
            hostSeam.incoming.first { TapAdmitMessage.decode(it.toByteArray()) is TapAdmitMessage.Proof }
        }
        assertTrue(hostProof != null, "prover answers the host it first binds to")

        // An attacker now challenges the hosting prover; it must be ignored (different sender).
        attackerSeam.sendTo(proverId, TapAdmitMessage.encode(TapAdmitMessage.Challenge(ByteString(ByteArray(16) { 2 }))))
        val attackerProof = withTimeoutOrNull(1.minutes) {
            attackerSeam.incoming.first { TapAdmitMessage.decode(it.toByteArray()) is TapAdmitMessage.Proof }
        }
        assertNull(attackerProof, "prover must not answer a challenge from an unexpected sender")
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
        val joinerGate = joinerSeam.tokenGated(GateRole.Prover(presentedCode), scope)
        return hostGate to joinerGate
    }
}
