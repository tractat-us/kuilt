@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.deal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.kotlincrypto.hash.sha2.SHA256
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam

/**
 * Two-phase commit-reveal protocol for deriving a shared random [Long] seed.
 *
 * All [peers] (including the local peer identified by [seam]'s [Seam.selfId])
 * participate. Once every peer has committed and then revealed, every participant
 * derives the same seed:
 *
 *     seed = first 8 bytes of SHA-256(H(id₁ ‖ secret₁) ‖ … ‖ H(idₙ ‖ secretₙ))
 *
 * Per-contributor inputs are hashed as `SHA-256(peerId.encodeToByteArray() ‖ secret)`
 * before combination, making the seed framing self-describing: the 32-byte hash
 * domain-separates each contributor by identity, preventing length-extension and
 * ambiguous-split attacks. Contributors are sorted by [PeerId] value (lexicographic)
 * before concatenation so the result is deterministic regardless of reveal-arrival
 * order.
 *
 * ## Abort resistance
 *
 * A last-mover peer can observe all other peers' reveals before deciding whether
 * to reveal, allowing it to abort when the outcome is unfavourable. This is a
 * known limitation of the basic commit-reveal protocol. Applications enforce
 * forfeit semantics for abort at the game layer. Full abort-resistance requires
 * threshold signatures or a VRF — out of scope here.
 *
 * A peer that never sends a reveal (or never sends a commit) will cause [roll] to
 * stall until the coroutine is cancelled. Applications should apply an outer timeout.
 * Similarly, a seam that becomes [us.tractat.kuilt.core.FabricState.Torn] during
 * either phase will never deliver the missing message; callers should observe seam
 * state and cancel accordingly.
 *
 * ## Usage
 *
 * ```kotlin
 * val fairRandom = FairRandom(seam, setOf(aliceId, bobId))
 * val seed: Long = fairRandom.roll()   // suspends through both phases
 * ```
 *
 * [FairRandom] is single-use: each [roll] is a fresh protocol run. Create a new
 * instance for each roll if repeated rounds are needed.
 *
 * @param seam the woven seam shared with all [peers].
 * @param peers all participant [PeerId]s, including [seam]'s own [Seam.selfId].
 */
public class FairRandom(
    private val seam: Seam,
    private val peers: Set<PeerId>,
    /** Test-only: if true, reveals a secret that does not match the commitment. */
    internal val tamperedReveal: Boolean = false,
    /** Test-only: fixed secret bytes (skips CSPRNG). Enables deterministic seed checks. */
    internal val fixedSecret: ByteArray? = null,
    /** Test-only: fixed nonce bytes (skips CSPRNG). Enables deterministic seed checks. */
    internal val fixedNonce: ByteArray? = null,
) {
    /**
     * Run one full commit-reveal round and return the agreed seed.
     *
     * Suspends until all [peers] have committed and then revealed. Throws
     * [CommitmentViolation] if any peer reveals a secret that does not match its
     * commitment hash.
     *
     * @throws CommitmentViolation if a peer's reveal does not match its commit.
     * @throws IllegalArgumentException if [seam]'s own identity is not in [peers],
     *   or if [peers] contains fewer than two participants.
     * @throws CancellationException if the coroutine is cancelled (e.g. no-reveal
     *   peer stalls the round — apply an outer timeout).
     */
    public suspend fun roll(): Long = coroutineScope {
        require(seam.selfId in peers) {
            "selfId '${seam.selfId.value}' must be listed in peers"
        }
        require(peers.size >= 2) {
            "At least 2 peers are required; got ${peers.size}"
        }

        val myId = seam.selfId
        val secret = resolveSecret()
        val nonce = resolveNonce()

        val myCommit = commitment(secret, nonce)

        val commits = Channel<Pair<PeerId, FairRandomMessage.Commit>>(Channel.UNLIMITED)
        val reveals = Channel<Pair<PeerId, FairRandomMessage.Reveal>>(Channel.UNLIMITED)

        val collectorJob = launch {
            seam.incoming.collect { swatch ->
                val sender = swatch.sender ?: return@collect
                val msg = try {
                    Cbor.decodeFromByteArray<FairRandomMessage>(swatch.payload)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: SerializationException) {
                    return@collect
                }
                when (msg) {
                    is FairRandomMessage.Commit -> commits.send(sender to msg)
                    is FairRandomMessage.Reveal -> reveals.send(sender to msg)
                }
            }
        }

        try {
            val allCommits = broadcastAndCollectCommits(myId, myCommit, commits)
            val allReveals = broadcastAndCollectReveals(myId, secret, nonce, allCommits, reveals)
            deriveSeed(allReveals)
        } finally {
            collectorJob.cancel()
            commits.close()
            reveals.close()
        }
    }

    private suspend fun broadcastAndCollectCommits(
        myId: PeerId,
        myCommit: ByteArray,
        commits: Channel<Pair<PeerId, FairRandomMessage.Commit>>,
    ): Map<PeerId, ByteArray> {
        seam.broadcast(Cbor.encodeToByteArray<FairRandomMessage>(FairRandomMessage.Commit(myCommit)))
        return awaitAllCommits(myId, myCommit, commits)
    }

    private suspend fun broadcastAndCollectReveals(
        myId: PeerId,
        secret: ByteArray,
        nonce: ByteArray,
        allCommits: Map<PeerId, ByteArray>,
        reveals: Channel<Pair<PeerId, FairRandomMessage.Reveal>>,
    ): Map<PeerId, ByteArray> {
        val revealedSecret = if (tamperedReveal) tamper(secret) else secret
        seam.broadcast(Cbor.encodeToByteArray<FairRandomMessage>(FairRandomMessage.Reveal(revealedSecret, nonce)))
        return awaitAllReveals(myId, secret, nonce, allCommits, reveals)
    }

    private suspend fun awaitAllCommits(
        myId: PeerId,
        myCommit: ByteArray,
        commits: Channel<Pair<PeerId, FairRandomMessage.Commit>>,
    ): Map<PeerId, ByteArray> {
        val result = mutableMapOf(myId to myCommit)
        while (result.keys != peers) {
            val (sender, msg) = commits.receive()
            if (sender !in peers || sender == myId) continue
            // F8: ignore a second commit from an already-committed sender so all honest
            // peers converge on the same committed hash regardless of racing duplicates.
            if (sender in result) continue
            result[sender] = msg.hash
        }
        return result
    }

    private suspend fun awaitAllReveals(
        myId: PeerId,
        mySecret: ByteArray,
        myNonce: ByteArray,
        allCommits: Map<PeerId, ByteArray>,
        reveals: Channel<Pair<PeerId, FairRandomMessage.Reveal>>,
    ): Map<PeerId, ByteArray> {
        val result = mutableMapOf(myId to mySecret)
        while (result.keys != peers) {
            val (sender, msg) = reveals.receive()
            if (sender !in peers || sender == myId) continue
            if (sender in result) continue
            // Reject reveals with wrong field lengths before verifying.
            // The commitment hash is SHA-256(secret ‖ nonce) with no length framing,
            // so without this check a peer could reveal a different (secret', nonce')
            // split that hashes identically yet contributes a different secret to the
            // seed — a post-commit bias attack. Fixed lengths make the preimage
            // unambiguous.
            if (msg.secret.size != SECRET_BYTES || msg.nonce.size != NONCE_BYTES) {
                throw CommitmentViolation(
                    sender,
                    allCommits[sender] ?: ByteArray(0),
                    ByteArray(0),
                )
            }
            val expectedHash = checkNotNull(allCommits[sender]) {
                "Reveal from $sender who did not commit"
            }
            verifyCommitment(sender, msg.secret, msg.nonce, expectedHash)
            result[sender] = msg.secret
        }
        // Self-verify: catches the tamperedReveal test path.
        verifyCommitment(myId, mySecret, myNonce, checkNotNull(allCommits[myId]))
        return result
    }

    private fun verifyCommitment(peer: PeerId, secret: ByteArray, nonce: ByteArray, expectedHash: ByteArray) {
        val actualHash = commitment(secret, nonce)
        if (!actualHash.contentEquals(expectedHash)) throw CommitmentViolation(peer, expectedHash, actualHash)
    }

    private fun deriveSeed(secrets: Map<PeerId, ByteArray>): Long {
        val combined = secrets.entries
            .sortedBy { (id, _) -> id.value }
            .fold(ByteArray(0)) { acc, (id, secret) ->
                acc + sha256(id.value.encodeToByteArray() + secret)
            }
        return sha256(combined).toLong()
    }

    private fun ByteArray.toLong(): Long {
        var result = 0L
        for (i in 0..7) result = (result shl 8) or (this[i].toLong() and 0xFF)
        return result
    }

    private fun commitment(secret: ByteArray, nonce: ByteArray): ByteArray = sha256(secret + nonce)

    private fun resolveSecret(): ByteArray {
        if (fixedSecret != null) return fixedSecret
        val bytes = secureRandomBytes(SECRET_BYTES)
        require(bytes.size == SECRET_BYTES) { "secureRandomBytes returned ${bytes.size} bytes; expected $SECRET_BYTES" }
        return bytes
    }

    private fun resolveNonce(): ByteArray {
        if (fixedNonce != null) return fixedNonce
        val bytes = secureRandomBytes(NONCE_BYTES)
        require(bytes.size == NONCE_BYTES) { "secureRandomBytes returned ${bytes.size} bytes; expected $NONCE_BYTES" }
        return bytes
    }

    private fun tamper(secret: ByteArray): ByteArray =
        secret.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }

    internal companion object {
        internal const val SECRET_BYTES = 32
        internal const val NONCE_BYTES = 16

        internal fun sha256(input: ByteArray): ByteArray = SHA256().digest(input)
    }
}

/**
 * Thrown when a peer's revealed secret does not match its earlier commitment hash.
 *
 * [peer] committed to [expectedHash] but their revealed `(secret, nonce)` hashes
 * to [actualHash].
 */
public class CommitmentViolation(
    public val peer: PeerId,
    public val expectedHash: ByteArray,
    public val actualHash: ByteArray,
) : Exception("Commitment violation by peer '${peer.value}': expected ${expectedHash.hex()}, got ${actualHash.hex()}")

private fun ByteArray.hex(): String = joinToString("") { b ->
    val v = b.toInt() and 0xFF
    HEX_CHARS[v shr 4].toString() + HEX_CHARS[v and 0xF]
}

private val HEX_CHARS = charArrayOf(
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
)

// ── Wire types ────────────────────────────────────────────────────────────────

/** Sealed wire-type envelope; CBOR-encoded on the [Seam]. */
@Serializable
internal sealed class FairRandomMessage {

    /** Phase-1 message: a commitment hash. The sender's identity comes from [Seam]. */
    @Serializable
    internal data class Commit(val hash: ByteArray) : FairRandomMessage() {
        override fun equals(other: Any?): Boolean = other is Commit && hash.contentEquals(other.hash)
        override fun hashCode(): Int = hash.contentHashCode()
    }

    /** Phase-2 message: the revealed secret and nonce. */
    @Serializable
    internal data class Reveal(val secret: ByteArray, val nonce: ByteArray) : FairRandomMessage() {
        override fun equals(other: Any?): Boolean =
            other is Reveal && secret.contentEquals(other.secret) && nonce.contentEquals(other.nonce)
        override fun hashCode(): Int = 31 * secret.contentHashCode() + nonce.contentHashCode()
    }
}
