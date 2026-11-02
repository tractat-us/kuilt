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
     * [TapAdmitMessage.Proof] carries the [admissionTag] for this exchange while [token] is valid.
     */
    class Verifier(val token: LogTapJoinToken, val clock: Clock, val random: Random) : GateRole

    /**
     * Pulling side: answer each [TapAdmitMessage.Challenge] with a fresh nonce from [random] and the
     * [admissionTag] for this exchange. [random] must be secure in production, as
     * [us.tractat.kuilt.otel.tap.LogTapAdmission.Present] says.
     */
    class Prover(val code: String, val random: Random) : GateRole
}

/**
 * A [Seam] decorator that runs the tap's token-gated admission handshake, so an
 * unauthorized peer never reaches the replicator riding above it on the offering side.
 *
 * On the **verifier** (offering) side, an unverified peer is invisible in both
 * directions: it is absent from [peers], its frames never reach [incoming], and
 * [broadcast]/[sendTo] never deliver to it — so the replicator can neither be seen by
 * nor leak log state to a peer that has not proven the join code. Admission frames the
 * gate itself sends use the inner seam directly, bypassing that filter. On the
 * **prover** (pulling) side the gate is transparent apart from answering challenges and
 * stripping admit frames from what the replicator sees. It authenticates nobody, so on a fabric
 * where joiners reach each other, the puller's replicator exchanges state with any peer that turns
 * up, including what it has already pulled from the device.
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
 * so the token's TTL bounds it. See "Entropy ↔ TTL" on [LogTapJoinToken].
 *
 * ## A proof never waits on the collector
 *
 * Answering everyone means a stranger decides how many frames head its way, and a stranger who stops
 * reading fills its own buffer until the next [Seam.sendTo] suspends, as that call is entitled to.
 * The sole collector of `inner.incoming` must not be the coroutine suspended there, because every
 * other peer's frames arrive through it. So a proof goes out in a job of its own, and the collector
 * moves on. It runs undispatched, so an accepted send finishes in place and only one that truly
 * suspends leaves anything behind.
 *
 * One challenger holds at most one such job. A newer challenge cancels the send still parked for that
 * peer, so a flood costs one job and one frame rather than one per challenge, and a peer back on a
 * fresh link is answered there rather than behind a send to the link it left.
 *
 * A verifier's sends stay on their collectors, deliberately. It owes a peer one challenge and at most
 * one reject per connection, which is not enough to fill anything, and it answers nothing else until
 * the peer is admitted.
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
 * wears both ids, which `aRelayWearingBothIdsAcrossTwoSessionsIsStillAdmitted` pins. Closing that takes
 * a key exchange the code authenticates, which is a different protocol.
 *
 * ## Admission belongs to an id, not to a connection
 *
 * [verified] holds ids, and an id leaves it only when [peers] shows that peer going. So wherever a
 * fabric carries a second connection under an admitted id without moving the roster, that connection
 * is admitted too, with no relay and no code. It can read what the device sends that id and send as
 * that id. By reading the source and not by running it, Multipeer does this, stamping
 * [Swatch.sender] from a display name, and so does a hub with no attestation, replacing the route.
 * The same stamp lets a stranger send a bad proof as a peer and use up that peer's challenge, which
 * locks the peer out until the roster next moves. `anAdmittedIdStaysAdmittedWhoeverIsWearingIt` and
 * `aForgedProofStampedWithAPeersIdUsesUpThatPeersChallenge` pin the gate's half. #3079 tracks the rest.
 *
 * Scope-owning: collectors of `inner.incoming` and, on a verifier, of `inner.peers` run in [scope];
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

    // Verifier-only: peers that have proven the code. Always includes selfId (peers invariant).
    private val verified = MutableStateFlow(setOf(inner.selfId))

    // Prover-only, and touched only by the sole `inner.incoming` collector: the proof still parked for
    // each challenger, if any.
    private val parkedProofs = mutableMapOf<PeerId, Job>()

    // Replication frames surfaced to the replicator. Buffered so the sole relay collector
    // never blocks; a fresh Seam has no subscriber for a brief construction window.
    private val relayed = MutableSharedFlow<Swatch>(extraBufferCapacity = 256)

    override val peers: StateFlow<Set<PeerId>>
        get() = when (role) {
            is GateRole.Verifier -> verified.asStateFlow()
            is GateRole.Prover -> inner.peers
        }

    override val incoming: Flow<Swatch> get() = relayed.asSharedFlow()

    init {
        if (role is GateRole.Verifier) {
            scope.launch {
                var known = setOf(inner.selfId)
                inner.peers.collect { current ->
                    // Drop admission state for peers that disconnected, so a same-PeerId
                    // reconnect must re-prove and pendingNonces cannot grow without bound.
                    val departed = known - current
                    if (departed.isNotEmpty()) prune(departed)
                    for (peer in current) if (peer != inner.selfId) maybeChallenge(peer)
                    known = current
                }
            }
        }
        // Sole collector of inner.incoming (single-collection contract): handle admit frames,
        // relay only surfaced peers' replication frames onward.
        scope.launch { inner.incoming.collect { handleFrame(it) } }
    }

    private fun prune(departed: Set<PeerId>) {
        lock.withLock {
            for (peer in departed) pendingNonces.remove(peer)
            if (verified.value.any { it in departed }) verified.value = verified.value - departed
        }
    }

    override suspend fun broadcast(payload: ByteArray) {
        when (role) {
            is GateRole.Prover -> inner.broadcast(payload)
            // Fan out only to verified peers — never a plain inner.broadcast, which would
            // reach an unverified peer.
            is GateRole.Verifier -> {
                val targets = lock.withLock { verified.value - inner.selfId }
                for (peer in targets) runCatchingCancellable { inner.sendTo(peer, payload) }
                    .onFailure { logger.debug { "gated broadcast to $peer failed: ${it.message}" } }
            }
        }
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        // Ahead of the admission gate: `selfId` is never in `verified`, so the Verifier arm would
        // classify a self-send as an unadmitted peer and DROP it with a debug line — the refusal
        // the Seam contract owes the caller (#2428) never reaching the inner seam that carries it.
        require(peer != selfId) { "Cannot send to self — use broadcast if you intend to loop back" }
        when (role) {
            is GateRole.Prover -> inner.sendTo(peer, payload)
            is GateRole.Verifier -> {
                val allowed = lock.withLock { peer in verified.value }
                if (allowed) inner.sendTo(peer, payload)
                else logger.debug { "gated sendTo dropped: $peer is not admitted" }
            }
        }
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
                val surfaced = when (role) {
                    is GateRole.Verifier -> lock.withLock { sender in verified.value }
                    is GateRole.Prover -> true
                }
                if (surfaced) relayed.emit(swatch)
            }
            is TapAdmitMessage.Challenge -> if (role is GateRole.Prover) respondToChallenge(sender, admit)
            is TapAdmitMessage.Proof -> if (role is GateRole.Verifier) verify(sender, admit)
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
        parkedProofs.remove(challenger)?.cancel()
        val send = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatchingCancellable { inner.sendTo(challenger, frame) }
                .onFailure { logger.debug { "proof send to $challenger failed: ${it.message}" } }
        }
        parkedProofs.values.removeAll { it.isCompleted }
        if (!send.isCompleted) parkedProofs[challenger] = send
    }

    private suspend fun verify(peer: PeerId, proof: TapAdmitMessage.Proof) {
        val verifier = role as GateRole.Verifier
        val admitted = lock.withLock {
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
            // fresh challenge.
            pendingNonces.remove(peer)
            if (ok) verified.value = verified.value + peer
            ok
        }
        if (admitted) {
            logger.debug { "admitted $peer to the log tap" }
        } else {
            runCatchingCancellable {
                inner.sendTo(peer, TapAdmitMessage.encode(TapAdmitMessage.Reject("invalid or expired join code")))
            }.onFailure { logger.debug { "reject send to $peer failed: ${it.message}" } }
        }
    }
}

/** Wrap [this] with the token gate for the given [role]; the gate's collectors run in [scope]. */
internal fun Seam.tokenGated(role: GateRole, scope: CoroutineScope): Seam = TokenGatedSeam(this, role, scope)
