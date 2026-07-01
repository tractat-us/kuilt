package us.tractat.kuilt.otel.tap.admit

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
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
     * [TapAdmitMessage.Proof] matches `HMAC(token.code, nonce)` while [token] is valid.
     */
    class Verifier(val token: LogTapJoinToken, val clock: Clock, val random: Random) : GateRole

    /** Pulling side: answer a [TapAdmitMessage.Challenge] with `HMAC(code, nonce)`. */
    class Prover(val code: String) : GateRole
}

/**
 * A [Seam] decorator that runs the tap's token-gated admission handshake, so an
 * unauthorized peer never reaches the replicator riding above it.
 *
 * On the **verifier** (offering) side, an unverified peer is invisible in both
 * directions: it is absent from [peers], its frames never reach [incoming], and
 * [broadcast]/[sendTo] never deliver to it — so the replicator can neither be seen by
 * nor leak log state to a peer that has not proven the join code. Admission frames the
 * gate itself sends use the inner seam directly, bypassing that filter. On the
 * **prover** (pulling) side the gate is transparent apart from answering challenges and
 * stripping admit frames from what the replicator sees. A prover binds to the first host
 * it answers and ignores challenges from any other sender — so, in the role-inverted
 * topology where the prover hosts the session, a stray joiner cannot farm
 * `HMAC(code, nonce)` tags for an offline crack.
 *
 * Scope-owning: two collectors (of `inner.incoming` and of `inner.peers`) run in [scope];
 * cancel it to stop the gate. State is guarded by a [reentrantLock] with every suspending
 * inner-seam send performed outside the lock, so the gate is correct under a multi-threaded
 * dispatcher.
 */
internal class TokenGatedSeam(
    private val inner: Seam,
    private val role: GateRole,
    scope: CoroutineScope,
) : Seam by inner {

    private val lock = reentrantLock()

    // Verifier-only: peers we have challenged and are awaiting a proof from → the nonce we sent.
    private val pendingNonces = mutableMapOf<PeerId, ByteString>()

    // Verifier-only: peers that have proven the code. Always includes selfId (peers invariant).
    private val verified = MutableStateFlow(setOf(inner.selfId))

    // Prover-only: the peer we bound to on the first challenge we answered. We answer only
    // challenges from this sender thereafter — in the role-inverted topology the prover hosts
    // the session, so any joiner could otherwise send a Challenge purely to harvest
    // HMAC(code, nonce) for an offline crack of the code. Cleared when the bound host departs
    // so a legitimate reconnect (the token is reusable) can re-bind. See LogTapJoinToken.
    private var boundHost: PeerId? = null

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
        when (role) {
            is GateRole.Verifier -> scope.launch {
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
            is GateRole.Prover -> scope.launch {
                var known = setOf(inner.selfId)
                inner.peers.collect { current ->
                    // Release the challenge binding when the bound host departs, so a
                    // legitimate reconnect can re-bind rather than being locked out.
                    val departed = known - current
                    if (departed.isNotEmpty()) lock.withLock { if (boundHost in departed) boundHost = null }
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
            ByteString(verifier.random.nextBytes(NONCE_BYTES)).also { pendingNonces[peer] = it }
        }
        runCatchingCancellable { inner.sendTo(peer, TapAdmitMessage.encode(TapAdmitMessage.Challenge(nonce))) }
            .onFailure { logger.debug { "challenge send to $peer failed: ${it.message}" } }
    }

    private suspend fun handleFrame(swatch: Swatch) {
        val sender = swatch.sender ?: return
        when (val admit = TapAdmitMessage.decode(swatch.toByteArray())) {
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

    private suspend fun respondToChallenge(host: PeerId, challenge: TapAdmitMessage.Challenge) {
        val prover = role as GateRole.Prover
        // Bind to the first host we answer and refuse challenges from any other sender: this
        // narrows the offline-harvest surface in the role-inverted topology where the prover
        // hosts the session and a stray joiner could otherwise farm HMAC(code, nonce) tags.
        val bound = lock.withLock {
            when (boundHost) {
                null -> { boundHost = host; true }
                host -> true
                else -> false
            }
        }
        if (!bound) {
            logger.debug { "ignoring challenge from unexpected sender $host (bound to another host)" }
            return
        }
        val tag = ByteString(hmacSha256(prover.code.encodeToByteArray(), challenge.nonce.toByteArray()))
        runCatchingCancellable { inner.sendTo(host, TapAdmitMessage.encode(TapAdmitMessage.Proof(tag))) }
            .onFailure { logger.debug { "proof send to $host failed: ${it.message}" } }
    }

    private suspend fun verify(peer: PeerId, proof: TapAdmitMessage.Proof) {
        val verifier = role as GateRole.Verifier
        val admitted = lock.withLock {
            val nonce = pendingNonces[peer] ?: return
            val expected = hmacSha256(verifier.token.code.encodeToByteArray(), nonce.toByteArray())
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

    private companion object {
        const val NONCE_BYTES = 16
    }
}

/** Wrap [this] with the token gate for the given [role]; the gate's collectors run in [scope]. */
internal fun Seam.tokenGated(role: GateRole, scope: CoroutineScope): Seam = TokenGatedSeam(this, role, scope)
