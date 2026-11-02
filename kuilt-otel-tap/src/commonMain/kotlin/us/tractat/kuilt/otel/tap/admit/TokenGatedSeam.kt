package us.tractat.kuilt.otel.tap.admit

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.runCatchingCancellable
import kotlin.random.Random
import kotlin.time.Clock

private val logger = KotlinLogging.logger("us.tractat.kuilt.otel.tap.admit.TokenGatedSeam")

/** The gate's side of the admission handshake. */
internal sealed interface GateRole {
    /**
     * Offering side: challenge every joiner with a nonce and admit only those whose
     * [TapAdmitMessage.Proof] carries the [admissionTag] for this exchange while [token] is valid. A
     * peer it admits gets a proof back over the nonce in that peer's own proof.
     */
    class Verifier(val token: LogTapJoinToken, val clock: Clock, val random: Random) : GateRole

    /**
     * Pulling side: answer each [TapAdmitMessage.Challenge] with a fresh nonce from [random] and the
     * [admissionTag] for this exchange, and admit a peer only once it proves [code] over that nonce.
     * [random] must be secure in production, as [us.tractat.kuilt.otel.tap.LogTapAdmission.Present] says.
     */
    class Prover(val code: String, val random: Random) : GateRole
}

/**
 * A [Seam] decorator that runs the tap's token-gated admission handshake, so an unauthorized peer
 * never reaches the replicator riding above it, on either side.
 *
 * An unadmitted peer is invisible in both directions: it is absent from [peers], its frames never
 * reach [incoming], and [broadcast]/[sendTo] never deliver to it. So the replicator can neither be
 * seen by nor leak log state to a peer that has not proven the join code, and it never takes one's
 * frames. Admission frames the gate itself sends use the inner seam directly, bypassing that filter.
 * That includes a frame stamped with the gate's own id: [verified] always holds it, so it is dropped
 * explicitly, and no fabric needs to refuse to deliver a peer its own id for the gate to hold.
 *
 * ## Each side proves the code to the other
 *
 * The verifier (offering) side and the prover (pulling) side differ only in who goes first. The
 * verifier challenges; the prover answers, with a nonce of its own in the answer; and the verifier,
 * once it has admitted the prover, answers that nonce:
 * ```
 * verifier → prover  : Challenge(nV)
 * prover   → verifier: Proof(nP,  tag(verifier = V, prover = P, nV, nP))
 * verifier → prover  : Proof(nV2, tag(verifier = P, prover = V, nP, nV2))
 * ```
 * The prover admits nobody until that last frame. All it learns from it is that the peer stamped with
 * that id holds the code, and held a valid token when it admitted the prover. That is not the same as
 * being the device: anyone holding the code can answer, and the prover has no clock, so a code past
 * its expiry still answers. One shared code cannot say more.
 *
 * The verifier goes first so a stranger gets nothing from it. A verifier answers only a peer it has
 * already admitted, and never a bare challenge, so reaching a device still earns no tag to grind.
 * The prover cannot be as careful, as the next section says.
 *
 * The verifier sends its answer before the peer reaches [peers]. The replicator pushes its whole state
 * the moment a peer appears there, and the prover would drop that as coming from a peer it has not
 * admitted, so the answer has to be ahead of it on the link. An answer that cannot be sent leaves the
 * peer unadmitted, since the prover would refuse this side anyway.
 *
 * The prover keeps its nonce through a bad answer, where a verifier spends its own. Anything stamped
 * with a peer's id can send a bad answer, and spending the nonce would let one frame end that peer's
 * pull. Guessing against a kept nonce gains nothing, because nothing is learned from a miss and the
 * code is far too large to try online.
 *
 * ## The prover answers everyone
 *
 * A prover cannot tell a device from a stranger before answering, because proving who you are is what
 * the answer is for. It used to answer only the first peer to challenge it, which let a stranger who
 * got there first lock the real device out, and bought little: an attacker lurking on the rendezvous
 * is always first. Now every challenger gets an answer good for that challenger alone.
 *
 * The cost is that any peer reaching the prover can collect a tag to grind offline, where before it
 * had to win a race. One tag is all a grind needs and a passive listener on a plaintext wire already
 * has one, and with the prover's nonce in the input the grind cannot start until the tag is in hand,
 * so what bounds it is the code's entropy and the TTL on the device's token. The puller has no clock and
 * enforces no expiry of its own. See "Entropy ↔ TTL" on [LogTapJoinToken].
 *
 * ## A proof never waits on the collector
 *
 * Answering everyone means a stranger decides how many frames head its way, and a stranger who stops
 * reading fills its own buffer until the next [Seam.sendTo] suspends, as that call is entitled to.
 * The sole collector of `inner.incoming` must not be the coroutine suspended there, because every
 * other peer's frames arrive through it. So a proof goes out in a job of its own, and the collector
 * moves on. The job starts undispatched: queued, it could be cancelled by the next challenge before it
 * had run, and two challenges in one go would draw one answer.
 *
 * One challenger holds at most one such job. A newer challenge cancels the send still parked for that
 * peer, so a flood costs one job and one frame rather than one per challenge, and a peer back on a
 * fresh link is answered there rather than behind a send to the link it left.
 *
 * A challenger that leaves gives its job up too, since nothing obliges a seam to wake a send parked
 * for a peer that has gone. Every answer cancels the jobs of peers no longer on the fabric's roster, so
 * the jobs never outnumber the peers there at the last challenge, and leaving wins a stranger nothing it
 * could not hold by staying. Only an answer can add a job, so an answer is the one place the check has
 * to run. The roster collector could free the last jobs sooner, at the price of a second thread into
 * this map and a lock, and it would still miss a peer that came and went between two emissions. A
 * timeout on the send would be a bound in time and not in jobs, and it would drop an answer to a slow
 * honest peer, who gets no other.
 *
 * A verifier's sends stay on their collectors, deliberately. It challenges a peer only when the fabric's
 * roster shows the peer arriving, and a challenge draws at most one reject or one answer, so a
 * connection draws two frames. That is not enough to fill anything, and it answers nothing else until
 * the peer is admitted.
 *
 * Replication has no such bound. The replicator acks each frame in a job of its own, so a peer that
 * stops reading parks one send per frame it sends. Keeping unadmitted frames off the replicator means a
 * stranger is owed no ack, which leaves that cost to a peer that proved the code, or wears the id of
 * one. `aPeerThatProvedTheCodeAndStopsReadingStillParksASendPerFrame` pins it.
 *
 * ## What the peer ids buy
 *
 * [admissionTag] covers the verifier's and the prover's ids as each side reads them off its own seam:
 * [Seam.selfId] for itself and [Swatch.sender] for the other. They agree because that is the seam
 * contract, asserted by `SeamConformanceSuite`, and an honest pair is refused on a fabric that breaks
 * it. The binding stops a relayer who shows up as itself, since the puller's answer then names the
 * relayer and the device expects to be named.
 *
 * No fabric here authenticates a peer id against anything, so the ids are worth what the fabric makes
 * them hard to claim:
 * - **`InMemoryLoom`:** the loom mints every id. A relay on one mesh is simply closed.
 * - **Multipeer, Nearby:** the id is claimed by the peer, as a suffix of its display name or in a
 *   connection hello, and Multipeer accepts any Mac on the LAN. A relayer has to be the puller's id to
 *   the device and the device's id to the puller, which takes more than one identity and takes knowing
 *   both ids. On Multipeer those identities can sit in one session, as extra display names.
 * - **Network.framework:** the same claim, behind TLS keyed from the room key, so a relayer must first
 *   hold the room key.
 * - **WebSocket, TCP, WebRTC:** the id is claimed in the dial or a hello. A one-to-one seam never puts
 *   a relayer between two honest peers on one session, so the relay needs two: answer a device dialling
 *   a spoofed advertisement under the puller's id, and dial the puller under the device's. Then the ids
 *   match and this binding does nothing. A room relayed through a hub is the Multipeer case instead.
 *
 * So the binding closes the relay for a relayer that shows up as itself, and leaves open one that
 * wears both ids, at both ends, which `aRelayWearingBothIdsAcrossTwoSessionsIsStillAdmitted` pins.
 * Closing that takes a key exchange the code authenticates, which is a different protocol.
 *
 * ## Admission belongs to an id, not to a connection
 *
 * [verified] holds ids, and an id leaves it only when the fabric's roster shows that peer going. No
 * frame after the handshake carries a MAC, so the sender stamp is the only thing tying one to the peer
 * that proved the code. So wherever a fabric carries a second connection under an admitted id without
 * moving the roster, that connection is admitted too, with no relay and no code, on both sides. It can
 * send as that id, including logs the device never wrote, and it may read what this side sends that id,
 * depending on which connection the fabric picks. By reading the source and not by running it,
 * Multipeer does this, stamping [Swatch.sender] from a display name and sending to the first session
 * peer wearing the id, and so does a hub with no attestation, replacing the route.
 *
 * The same stamp lets a stranger send a bad proof as a peer and use up that peer's challenge at a
 * verifier, or send a challenge as a peer and replace the nonce a prover is waiting on. A used-up
 * challenge is not reissued, and neither is one that failed to send, so in each case the peer waits
 * until it reconnects, which here means until the roster shows it leave and return. Reissuing on any
 * roster move only ever rescued the first case, by luck, and it gave a stranger who stopped reading a
 * way to fill its own buffer and stall the gate. `anAdmittedIdStaysAdmittedWhoeverIsWearingIt`,
 * `aFrameWearingTheAdmittedDevicesIdReachesThePullersReplicator`,
 * `aForgedProofStampedWithAPeersIdUsesUpThatPeersChallenge` and
 * `aChallengeWearingTheDevicesIdReplacesTheNonceTheDeviceIsAnswering` pin the gate's half. #3079 tracks
 * the fabric's, and #3076 stays open on it.
 *
 * Scope-owning: collectors of `inner.incoming` and `inner.peers` run in [scope];
 * cancel it to stop the gate. State is guarded by a [reentrantLock] with every suspending
 * inner-seam send performed outside the lock, so the gate is correct under a multi-threaded
 * dispatcher.
 */
internal class TokenGatedSeam(
    private val inner: Seam,
    private val role: GateRole,
    private val scope: CoroutineScope,
) : Seam by inner {

    private val lock = reentrantLock()

    // Verifier-only: peers we have challenged and are awaiting a proof from → the nonce we sent.
    private val pendingNonces = mutableMapOf<PeerId, ByteString>()

    // Verifier-only: peers whose proof was good and whose answer is on its way. Leaving takes a peer out
    // of here, which is how a finished send knows the exchange it belongs to is still the current one.
    private val answering = mutableSetOf<PeerId>()

    // Prover-only: the nonce in the latest proof sent to each challenger, which its answer must cover.
    private val answerNonces = mutableMapOf<PeerId, ByteString>()

    // Peers that have proven the code. Always includes selfId (peers invariant).
    private val verified = MutableStateFlow(setOf(inner.selfId))

    // Prover-only, and touched only by the sole `inner.incoming` collector: the latest proof for each
    // challenger, which may have gone out already. Cancelling a finished job does nothing.
    private val parkedProofs = mutableMapOf<PeerId, Job>()

    // Roster peers whose dropped replication frames have been logged already; cleared when they leave.
    private val droppedSenders = mutableSetOf<PeerId>()

    // Replication frames surfaced to the replicator. Buffered so the sole relay collector
    // never blocks; a fresh Seam has no subscriber for a brief construction window.
    private val relayed = MutableSharedFlow<Swatch>(extraBufferCapacity = 256)

    override val peers: StateFlow<Set<PeerId>> get() = verified.asStateFlow()

    override val incoming: Flow<Swatch> get() = relayed.asSharedFlow()

    init {
        scope.launch {
            var known = setOf(inner.selfId)
            inner.peers.collect { current ->
                // Drop admission state for peers that disconnected, so a same-PeerId reconnect must
                // re-prove. `answerNonces` also keeps any sender never seen in `known`, which a stranger
                // can add one entry at a time, so it is bounded by departures only for roster peers.
                val departed = known - current
                if (departed.isNotEmpty()) prune(departed)
                if (role is GateRole.Verifier) {
                    for (peer in current - known) if (peer != inner.selfId) maybeChallenge(peer)
                }
                known = current
            }
        }
        // Sole collector of inner.incoming (single-collection contract): handle admit frames,
        // relay only surfaced peers' replication frames onward.
        scope.launch { inner.incoming.collect { handleFrame(it) } }
    }

    private fun prune(departed: Set<PeerId>) {
        lock.withLock {
            for (peer in departed) {
                pendingNonces.remove(peer)
                answering.remove(peer)
                answerNonces.remove(peer)
                droppedSenders.remove(peer)
            }
            if (verified.value.any { it in departed }) verified.value = verified.value - departed
        }
    }

    override suspend fun broadcast(payload: ByteArray) {
        // Fan out only to verified peers — never a plain inner.broadcast, which would
        // reach an unverified peer.
        val targets = lock.withLock { verified.value - inner.selfId }
        for (peer in targets) runCatchingCancellable { inner.sendTo(peer, payload) }
            .onFailure { logger.debug { "gated broadcast to $peer failed: ${it.message}" } }
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        // Ahead of the admission gate: `selfId` is never in `verified`, so the Verifier arm would
        // classify a self-send as an unadmitted peer and DROP it with a debug line — the refusal
        // the Seam contract owes the caller (#2428) never reaching the inner seam that carries it.
        require(peer != selfId) { "Cannot send to self — use broadcast if you intend to loop back" }
        val allowed = lock.withLock { peer in verified.value }
        if (allowed) inner.sendTo(peer, payload)
        else logger.debug { "gated sendTo dropped: $peer is not admitted" }
    }

    private suspend fun maybeChallenge(peer: PeerId) {
        val verifier = role as GateRole.Verifier
        val nonce = lock.withLock {
            if (peer in pendingNonces || peer in verified.value) return
            ByteString(verifier.random.nextBytes(TapAdmitMessage.Challenge.NONCE_BYTES))
                .also { pendingNonces[peer] = it }
        }
        runCatchingCancellable { inner.sendTo(peer, TapAdmitMessage.encode(TapAdmitMessage.Challenge(nonce))) }
            .onFailure { logger.debug { "challenge send to $peer failed: ${it.message}" } }
    }

    private suspend fun handleFrame(swatch: Swatch) {
        val sender = swatch.sender ?: return
        // `verified` always holds selfId, so without this a frame stamped with the receiver's own id
        // would pass the relay check below with no admission. No honest fabric delivers one.
        if (sender == inner.selfId) return
        val bytes = swatch.toByteArray()
        val admit = TapAdmitMessage.decode(bytes)
        if (admit == null && TapAdmitMessage.isAdmitFrame(bytes)) {
            // Carries the admit prefix but did not decode: a malformed admit frame — a
            // wrong-width Challenge nonce is one (#1820). Drop it rather than falling through to
            // the relay, which would hand the replicator a frame that is not replication.
            logger.debug { "dropping malformed admit frame from $sender" }
            return
        }
        when (admit) {
            null -> {
                // Replication frame — relay only if the sender is surfaced.
                val (surfaced, firstDrop) = lock.withLock {
                    val admitted = sender in verified.value
                    // Once per peer per stay on the roster, so a flood of frames is one line.
                    val first = !admitted && sender in inner.peers.value && droppedSenders.add(sender)
                    admitted to first
                }
                if (surfaced) relayed.emit(swatch)
                else if (firstDrop) {
                    logger.warn { "dropping replication frames from $sender: on the roster but has not proved the code" }
                }
            }
            is TapAdmitMessage.Challenge -> if (role is GateRole.Prover) respondToChallenge(sender, admit)
            is TapAdmitMessage.Proof -> when (role) {
                is GateRole.Verifier -> verify(sender, admit)
                is GateRole.Prover -> checkAnswer(sender, admit)
            }
            is TapAdmitMessage.Reject -> logger.debug { "admission refused by $sender: ${admit.reason}" }
        }
    }

    // Runs only from the sole `inner.incoming` collector, so the draw from `random` is never concurrent.
    private fun respondToChallenge(challenger: PeerId, challenge: TapAdmitMessage.Challenge) {
        val prover = role as GateRole.Prover
        val proverNonce = ByteString(prover.random.nextBytes(TapAdmitMessage.Challenge.NONCE_BYTES))
        val tag = admissionTag(
            code = prover.code,
            verifierId = challenger,
            proverId = inner.selfId,
            challengeNonce = challenge.nonce,
            proverNonce = proverNonce,
        )
        val frame = TapAdmitMessage.encode(TapAdmitMessage.Proof(proverNonce, ByteString(tag)))
        lock.withLock { answerNonces[challenger] = proverNonce }
        parkedProofs[challenger]?.cancel()
        parkedProofs[challenger] = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatchingCancellable { inner.sendTo(challenger, frame) }
                .onFailure { logger.debug { "proof send to $challenger failed: ${it.message}" } }
        }
        for (peer in parkedProofs.keys - inner.peers.value) parkedProofs.remove(peer)?.cancel()
    }

    /** The prover's half: admit [peer] if [answer] proves the code over the nonce this side last sent it. */
    private fun checkAnswer(peer: PeerId, answer: TapAdmitMessage.Proof) {
        val prover = role as GateRole.Prover
        val admitted = lock.withLock {
            val nonce = answerNonces[peer] ?: return
            val expected = admissionTag(
                code = prover.code,
                verifierId = inner.selfId,
                proverId = peer,
                challengeNonce = nonce,
                proverNonce = answer.proverNonce,
            )
            val ok = constantTimeEquals(expected, answer.tag.toByteArray())
            if (ok) {
                answerNonces.remove(peer)
                verified.value = verified.value + peer
            }
            ok
        }
        logger.debug { if (admitted) "admitted $peer as a peer holding the code" else "bad answer from $peer" }
    }

    private suspend fun verify(peer: PeerId, proof: TapAdmitMessage.Proof) {
        val verifier = role as GateRole.Verifier
        val answerNonce = lock.withLock {
            val nonce = pendingNonces[peer] ?: return
            val expected = admissionTag(
                code = verifier.token.code,
                verifierId = inner.selfId,
                proverId = peer,
                challengeNonce = nonce,
                proverNonce = proof.proverNonce,
            )
            // Constant-time compare AND validity check — both under the same decision.
            val ok = constantTimeEquals(expected, proof.tag.toByteArray()) && verifier.token.isValid(verifier.clock.now())
            // Consume the nonce whichever way it went: on success the peer is admitted; on a
            // reject the entry is cleared so it cannot linger or be reused, and a retry needs a
            // fresh challenge, which takes a reconnect.
            pendingNonces.remove(peer)
            if (ok) {
                answering += peer
                ByteString(verifier.random.nextBytes(TapAdmitMessage.Challenge.NONCE_BYTES))
            } else {
                null
            }
        }
        if (answerNonce == null) {
            runCatchingCancellable {
                inner.sendTo(peer, TapAdmitMessage.encode(TapAdmitMessage.Reject("invalid or expired join code")))
            }.onFailure { logger.debug { "reject send to $peer failed: ${it.message}" } }
            return
        }
        val tag = admissionTag(
            code = verifier.token.code,
            verifierId = peer,
            proverId = inner.selfId,
            challengeNonce = proof.proverNonce,
            proverNonce = answerNonce,
        )
        val sent = runCatchingCancellable {
            inner.sendTo(peer, TapAdmitMessage.encode(TapAdmitMessage.Proof(answerNonce, ByteString(tag))))
        }.onFailure { logger.debug { "answer send to $peer failed: ${it.message}" } }.isSuccess
        lock.withLock {
            // Absent if the peer left during the send: that exchange is over, whoever is back under this id.
            if (answering.remove(peer) && sent) verified.value = verified.value + peer
        }
        logger.debug { if (sent) "admitted $peer to the log tap" else "not admitting $peer: it never got the answer" }
    }
}

/** Wrap [this] with the token gate for the given [role]; the gate's collectors run in [scope]. */
internal fun Seam.tokenGated(role: GateRole, scope: CoroutineScope): Seam = TokenGatedSeam(this, role, scope)
