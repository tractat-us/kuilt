@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.deal

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import org.kotlincrypto.hash.sha2.SHA256
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamCollapsedException
import us.tractat.kuilt.core.raceCollapse

private val logger = KotlinLogging.logger("us.tractat.kuilt.deal.FairRandom")

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
 * If the seam collapses mid-round — a transport tear (the seam reaches the terminal
 * [us.tractat.kuilt.core.SeamState.Torn]) OR a membership drain (a required participant
 * leaves the live peer set while the seam stays `Woven`) — the missing commit/reveal
 * never arrives. [roll] detects both via [us.tractat.kuilt.core.raceCollapse] and throws
 * [SeamCollapsedException] rather than stalling, so no outer timeout is required. (A peer
 * that stays connected but simply withholds its reveal is a distinct, application-level
 * concern — see "Abort resistance" above — and still requires game-layer forfeit handling.
 * A peer that sends a *malformed* reveal falls into that same case **as far as the protocol is
 * concerned**: the frame is dropped, so the round cannot tell it from one that never arrived.
 * Operationally the two are no longer identical — the drop is logged and names its sender, once
 * at `warn` per participant and at `debug` thereafter (#2666) — but that is a diagnostic, not a
 * signal a caller can act on. See [FairRandomMessage.Reveal].)
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
     * @throws SeamCollapsedException if the seam tears or a required participant drains
     *   from the live peer set mid-round (the missing commit/reveal will never arrive).
     * @throws CancellationException if the coroutine is cancelled.
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

        // One warn per participant that sends a malformed frame, then debug (#2666). The collector
        // sees every frame on the seam, from any peer, with no consensus or rate limit in front of
        // it, so an unconditional warn would hand a hostile peer a log amplifier for as long as the
        // round lasts. A non-participant never enters the set, so it is bounded by `peers` — the
        // rate limit cannot itself become the unbounded thing. Confined to the single collector
        // coroutine below: `Flow.collect` is sequential, so the lambda never runs concurrently with
        // itself and this needs no lock (it is a local, not shared state reached from elsewhere).
        val warnedMalformed = mutableSetOf<PeerId>()

        val collectorJob = launch {
            seam.incoming.collect { swatch ->
                val sender = swatch.sender ?: return@collect
                val msg = try {
                    swatch.decode(Cbor, FairRandomMessage.serializer())
                } catch (e: CancellationException) {
                    throw e
                } catch (refusal: IllegalArgumentException) {
                    // Two refusals arrive here, and one arm covers both because
                    // SerializationException IS an IllegalArgumentException: bytes CBOR cannot
                    // parse, and bytes it can parse into values FairRandomMessage.Reveal will not
                    // hold (its init { require } throws a plain IllegalArgumentException — kotlinx
                    // does not wrap a constructor throw). Widened from SerializationException when
                    // the width check moved onto the wire type (#2650): an escaping throw here
                    // would end this collector and leave the round permanently deaf with no tear
                    // to observe, which is strictly worse than dropping the frame (#1819's shape).
                    //
                    // No currentCoroutineContext().ensureActive() here, and the arm is narrow
                    // enough for that to be a fact rather than a judgement: CancellationException
                    // is an IllegalStateException, a *sibling* of IllegalArgumentException, so
                    // neither our own cancel nor a callee-minted one can reach this arm at all.
                    // The explicit rethrow arm above it says the same thing a second time.
                    //
                    // Interpolated, not attached: a malformed frame from a peer is routine on an
                    // open fabric, its type and message are the whole diagnosis, and the trace
                    // under it is the same CBOR framework frames every time (the WarpOtlpBridge /
                    // HeddleControlPlane convention).
                    if (sender in peers && warnedMalformed.add(sender)) {
                        logger.warn { malformedFrame(myId, sender, refusal) }
                    } else {
                        logger.debug { malformedFrame(myId, sender, refusal) }
                    }
                    return@collect
                }
                when (msg) {
                    is FairRandomMessage.Commit -> commits.send(sender to msg)
                    is FairRandomMessage.Reveal -> reveals.send(sender to msg)
                }
            }
        }

        try {
            // Both phases block on channel.receive() fed by seam.incoming. If the seam collapses
            // mid-round — a transport tear OR a required participant draining from the live peer set
            // without a tear — no further message ever arrives and the receive() would hang forever.
            // raceCollapse turns either collapse into a bounded SeamCollapsedException throw. abortWhen
            // fires when ANY required participant leaves the live set (not just size < 2), covering the
            // N-peer case where the drop still leaves ≥ 2 live peers.
            seam.raceCollapse(abortWhen = { live -> peers.any { it !in live } }) {
                val allCommits = broadcastAndCollectCommits(myId, myCommit, commits)
                val allReveals = broadcastAndCollectReveals(myId, secret, nonce, allCommits, reveals)
                deriveSeed(allReveals)
            }
        } finally {
            // Cancel the collector; the enclosing coroutineScope joins it on exit. Do NOT close() the
            // channels: they are UNLIMITED (no suspended senders to release), and closing them races the
            // still-cancelling collector's send() — on a multi-threaded dispatcher a straggler frame
            // arriving during teardown throws ClosedSendChannelException, failing roll() AFTER the seed was
            // derived (the #1465 class). The receivers have already returned, so the channels need no close.
            collectorJob.cancel()
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
            // No width check here: `msg` is a FairRandomMessage.Reveal, and the type cannot hold a
            // wrong-width secret or nonce (#2650). A frame that tried was refused by the decode
            // above and never reached this channel. See FairRandomMessage.Reveal's KDoc for the
            // re-split attack the widths defend against, and for why enforcing it on the type
            // rather than here is the point.
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

    /**
     * The one line a dropped frame leaves behind.
     *
     * Names [sender] because the identity is in scope at the drop site and nothing downstream will
     * ever see this peer's contribution again this round — [awaitAllReveals] cannot report it, since
     * the frame never became a [FairRandomMessage] to attribute. Says what the loss *is*, too: the
     * peer is now indistinguishable from one that withheld, which is the abort case the game layer
     * handles, so a reader knows which lever applies.
     */
    private fun malformedFrame(self: PeerId, sender: PeerId, refusal: Throwable): String =
        "[fairRandom:${self.value}] dropping a malformed frame from peer '${sender.value}' — it " +
            "contributes nothing to this round, so that peer is indistinguishable from one that " +
            "withheld its reveal. Refusal: $refusal"

    private fun verifyCommitment(peer: PeerId, secret: ByteArray, nonce: ByteArray, expectedHash: ByteArray) {
        val actualHash = commitment(secret, nonce)
        if (!actualHash.contentEquals(expectedHash)) throw CommitmentViolation(peer, expectedHash, actualHash)
    }

    private fun deriveSeed(secrets: Map<PeerId, ByteArray>): Long {
        val hashes = secrets.entries
            .sortedBy { (id, _) -> id.value }
            .map { (id, secret) -> sha256(id.value.encodeToByteArray() + secret) }
        val combined = ByteArray(hashes.sumOf { it.size })
        var offset = 0
        for (hash in hashes) {
            hash.copyInto(combined, offset)
            offset += hash.size
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
        val width = FairRandomMessage.Reveal.SECRET_BYTES
        val bytes = secureRandomBytes(width)
        require(bytes.size == width) { "secureRandomBytes returned ${bytes.size} bytes; expected $width" }
        return bytes
    }

    private fun resolveNonce(): ByteArray {
        if (fixedNonce != null) return fixedNonce
        val width = FairRandomMessage.Reveal.NONCE_BYTES
        val bytes = secureRandomBytes(width)
        require(bytes.size == width) { "secureRandomBytes returned ${bytes.size} bytes; expected $width" }
        return bytes
    }

    private fun tamper(secret: ByteArray): ByteArray =
        secret.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }

    internal companion object {
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

    /**
     * Phase-2 message: the revealed secret and nonce.
     *
     * ## Both fields are fixed-width, and a wrong width is REJECTED, never reshaped (#2650)
     *
     * [secret] is always exactly [SECRET_BYTES] bytes and [nonce] exactly [NONCE_BYTES] —
     * enforced here, not merely documented. The check lives in the constructor deliberately:
     * kotlinx-serialization invokes it, so the invariant holds on **every** path, encode and
     * decode alike, and a new consumer of this type cannot forget it.
     *
     * It is load-bearing because the commitment is `SHA-256(secret ‖ nonce)` with **no length
     * delimiter between the halves**. A peer free to choose the widths can commit to one 48-byte
     * string and then present any split of it — the (33, 15) split hashes to exactly the
     * commitment it already published, while contributing a *different* secret to the derived
     * seed. That is a bias applied after every other peer is committed. Fixed widths are what
     * make the preimage unambiguous, and they only work as a pair: pinning one half would leave
     * the other free to move.
     *
     * A quantity could be clamped into range; these cannot. The secret is a seed contribution and
     * the nonce is part of a hash preimage, so a wrong width is proof of a malformed or forged
     * reveal, and padding or truncating it to the declared width would launder that proof into a
     * valid-looking contribution — the forger simply receives whichever in-range value the
     * reshaping picks. The frame is refused instead.
     *
     * ## Why this is not the check in the handler it replaces
     *
     * [FairRandom.awaitAllReveals] used to compare both sizes itself, one call site away from the
     * type. The invariant then held because *that* consumer remembered it: a second reader of
     * [Reveal] — another handler, a log line, a replay tool — inherits nothing, and the next hand
     * to write a reveal path re-derives the omission along with the format. Enforced on the type,
     * there is no path that can hold a malformed [Reveal] at all, so there is nothing left to
     * remember.
     *
     * The visible consequence is that a wrong-width reveal is now **dropped** by
     * [FairRandom.roll]'s frame path rather than converted into a [CommitmentViolation] naming its
     * sender — the type refuses to materialise, so [FairRandom.awaitAllReveals] never sees a peer
     * to accuse. That is the correct disposition and a weaker signal: to the *protocol* a peer who
     * sends one is indistinguishable from a peer who withholds its reveal, which is the abort case
     * documented on [FairRandom] and handled at the game layer. (The `CommitmentViolation` it
     * replaced was itself a fabricated diagnosis — it reported an empty `actualHash`, because on
     * this path the commitment typically *does* verify.)
     *
     * The **attribution** is not lost with it, though it was for a while (#2666): `roll()`'s
     * collector binds `swatch.sender` before it attempts the decode, so the drop site still knows
     * exactly who sent the frame and logs it. What no caller can yet observe is a *structured*
     * signal — a game layer can forfeit a peer that goes quiet, but not one that repeatedly sends
     * garbage. That remains open.
     */
    @Serializable
    internal data class Reveal(val secret: ByteArray, val nonce: ByteArray) : FairRandomMessage() {
        init {
            require(secret.size == SECRET_BYTES) {
                "malformed Reveal: secret is ${secret.size} bytes, expected exactly $SECRET_BYTES"
            }
            require(nonce.size == NONCE_BYTES) {
                "malformed Reveal: nonce is ${nonce.size} bytes, expected exactly $NONCE_BYTES"
            }
        }

        override fun equals(other: Any?): Boolean =
            other is Reveal && secret.contentEquals(other.secret) && nonce.contentEquals(other.nonce)
        override fun hashCode(): Int = 31 * secret.contentHashCode() + nonce.contentHashCode()

        internal companion object {
            /** Width of a revealed secret, in bytes. Both the generator and the check use this. */
            internal const val SECRET_BYTES = 32

            /** Width of a revealed nonce, in bytes. Both the generator and the check use this. */
            internal const val NONCE_BYTES = 16
        }
    }
}
