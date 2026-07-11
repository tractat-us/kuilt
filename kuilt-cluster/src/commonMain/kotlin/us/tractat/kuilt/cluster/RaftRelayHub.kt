package us.tractat.kuilt.cluster

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftEnvelope

private val log = KotlinLogging.logger("us.tractat.kuilt.cluster.RaftRelayHub")

/**
 * Fan several learner spokes into one shared set of in-process voters, routing each
 * relayed Raft message to the *named* voter and keeping the true origin intact — the
 * server-side relay-dialect core.
 *
 * A server hosts M voter nodes in one process and admits many learner clients, each
 * over its own two-peer spoke [Seam]. Every learner may address any voter, so the
 * hub is the single fan-in point: it is the **sole collector** of every spoke's raft
 * channel, decodes each frame's [RaftRelay] envelope, and routes it **by
 * [RaftRelay.dest]** to exactly that voter's inbound. Routing once, here, is why a
 * per-voter transport cannot own the spokes — that would demand M collectors of one
 * `incoming` stream (a single-collection violation) and would make every non-addressee
 * voter re-forward a frame that was never for it.
 *
 * ## Preserving the true origin
 *
 * The Raft engine keys reply addressing, vote and pre-vote tallies,
 * `matchIndex`/`nextIndex`, CheckQuorum, ReadIndex acks and leadership-transfer auth
 * on the incoming envelope's `from`. The relay carries the real sender inside the
 * frame as [RaftRelay.origin]; the hub presents `RaftEnvelope(from = origin)` at the
 * destination voter and never re-stamps it with the relaying server's id. The down
 * leg ([sendToLearner]) likewise stamps the true voter as origin so the client
 * credits the voter, not the relay.
 *
 * ## First-hop origin validation (commit-safety)
 *
 * `origin` rides inside a forgeable frame, so a spoke frame is validated via
 * [validFirstHop] before it can reach any inbound: a spoke may speak only for itself
 * (`origin == sender`). A forged frame reaches no engine. A `dest` that is not a
 * voter is dropped — this is a single process with no onward core hop, so the hub
 * NEVER re-forwards.
 *
 * ## Thread safety
 *
 * All mutable state ([inboundByVoter], [spokeSeams], [spokeJobs]) is guarded by an
 * atomicfu reentrant lock; suspend calls (the spoke send) are issued *outside* the
 * locked section. [MutableSharedFlow.tryEmit] is non-suspending and runs after the
 * inbound snapshot is taken. Correct under a multi-threaded dispatcher.
 *
 * @param voters the in-process voter node ids — the first-hop trust boundary and the
 *   set of legal [RaftRelay.dest] targets.
 */
internal class RaftRelayHub(private val voters: Set<NodeId>) {

    private val lock = reentrantLock()

    /** Voter NodeId → that voter's inbound SharedFlow — populated once at mesh construction. */
    private val inboundByVoter: MutableMap<NodeId, MutableSharedFlow<RaftEnvelope>> = mutableMapOf()

    /** Learner NodeId → its spoke Seam. */
    private val spokeSeams: MutableMap<NodeId, Seam> = mutableMapOf()

    /** Learner NodeId → the sole collector Job draining that spoke's raft channel. */
    private val spokeJobs: MutableMap<NodeId, Job> = mutableMapOf()

    private val _learnersFlow: MutableStateFlow<Set<NodeId>> = MutableStateFlow(emptySet())

    /** The live learner-id set; voter transports report it in their `peers`. */
    val learnersFlow: StateFlow<Set<NodeId>> = _learnersFlow.asStateFlow()

    /**
     * Register a voter's inbound [MutableSharedFlow] keyed by [voterId].
     *
     * Called once per voter during mesh construction, before any [addSpoke] call.
     */
    fun registerVoterInbound(voterId: NodeId, inbound: MutableSharedFlow<RaftEnvelope>) {
        lock.withLock { inboundByVoter[voterId] = inbound }
    }

    /**
     * Register a learner's spoke [seam] and start the SOLE collector of its raft
     * channel. Each frame is decoded to a [RaftRelay] (undecodable → dropped),
     * first-hop-validated ([validFirstHop] — a spoke may speak only for itself), then
     * routed by [RaftRelay.dest] to that voter's inbound with the true origin
     * preserved as `from`. A `dest` that is not a voter is dropped; the hub is a
     * single process and never re-forwards.
     *
     * ## Re-admit race (cross-relay failover)
     *
     * A learner re-admitted on a surviving relay while an old room is still tearing
     * can race a stale `addSpoke`/`removeSpoke` for the same [learnerId]. So this
     * cancels any **prior** collector job for [learnerId] under the lock before
     * installing the new one (no orphaned collector, no double-delivery), and
     * [removeSpoke] is seam-identity-guarded so a late `finally removeSpoke` from
     * the old room cannot cancel this new spoke's collector.
     *
     * @param learnerId the learner's [NodeId] (derived from the spoke's `selfId`).
     * @param seam the learner's two-peer spoke seam; the hub owns its `incoming`.
     * @param scope parents the collector coroutine. **Required.**
     */
    fun addSpoke(learnerId: NodeId, seam: Seam, scope: CoroutineScope) {
        val (inboundSnapshot, priorJob) = lock.withLock {
            val prior = spokeJobs.remove(learnerId)
            spokeSeams[learnerId] = seam
            _learnersFlow.update { it + learnerId }
            inboundByVoter.toMap() to prior
        }
        // Cancel the prior collector OUTSIDE the lock — a re-admit for the same
        // learner must not leave the old spoke's collector draining.
        priorJob?.cancel()
        val job = scope.launch {
            runCatchingCancellable {
                seam.incoming.collect { swatch ->
                    val senderPeer = swatch.sender ?: return@collect
                    val relay = runCatchingCancellable { RaftRelay.decode(swatch.toByteArray()) }.getOrNull()
                    if (relay == null) {
                        log.debug { "raft-relay-hub: undecodable frame from ${senderPeer.value} — dropping" }
                        return@collect
                    }
                    val sender = NodeId(senderPeer.value)
                    if (!validFirstHop(sender = sender, origin = relay.origin, core = voters)) {
                        log.debug { "raft-relay-hub: rejected spoofed frame (origin=${relay.origin}, sender=$sender)" }
                        return@collect
                    }
                    val inbound = inboundSnapshot[relay.dest]
                    if (inbound == null) {
                        log.debug { "raft-relay-hub: dest ${relay.dest} is not a voter — dropping (never re-forwarded)" }
                        return@collect
                    }
                    // True origin preserved as from; never re-stamped with a server id.
                    inbound.tryEmit(RaftEnvelope(relay.origin, relay.bytes))
                }
            }.onFailure { log.debug { "raft-relay-hub: spoke $learnerId collector ended: ${it.message}" } }
        }
        lock.withLock { spokeJobs[learnerId] = job }
    }

    /**
     * Deregister a learner: cancel its collector, drop its seam, withdraw it from
     * [learnersFlow]. Idempotent.
     *
     * **Seam-identity-guarded:** removes only if the currently-registered spoke IS
     * [seam]. A late `finally removeSpoke` from an old room (during cross-relay
     * failover) whose seam has already been superseded by a fresh [addSpoke] is a
     * no-op — it must not cancel the new spoke's collector.
     */
    fun removeSpoke(learnerId: NodeId, seam: Seam) {
        val job = lock.withLock {
            if (spokeSeams[learnerId] !== seam) return@withLock null
            spokeSeams.remove(learnerId)
            _learnersFlow.update { it - learnerId }
            spokeJobs.remove(learnerId)
        }
        job?.cancel()
    }

    /**
     * Route a voter's `sendTo(learnerId, bytes)` down that learner's two-peer spoke,
     * wrapping it as `RaftRelay(origin = fromVoter, dest = learnerId, bytes)` so the
     * client credits the **true voter**, not the relay id. Best-effort; returns
     * silently if the learner is not registered (e.g. tore before the send arrived).
     *
     * `broadcast` on a two-peer room channel reaches exactly the one learner, so this
     * is a single-addressee send, never a fan-out.
     *
     * R9 — payload budget: this broadcast is unbudgeted today (as the prior
     * single-server learner router was). The spoke may be a framed WS fabric while
     * the voter-channel transport reports `maxPayloadBytes = null`; the added
     * [RaftRelay] header (see [RELAY_HEADER_BUDGET]) is not reserved here. This is a
     * pre-existing exposure carried forward unchanged — not fixed in this task.
     */
    suspend fun sendToLearner(fromVoter: NodeId, learnerId: NodeId, bytes: ByteArray) {
        val seam = lock.withLock { spokeSeams[learnerId] } ?: return
        val encoded = RaftRelay.encode(RaftRelay(origin = fromVoter, dest = learnerId, bytes = bytes))
        runCatchingCancellable { seam.broadcast(encoded) }
            .onFailure { log.debug { "raft-relay-hub: drop to learner $learnerId on tear" } }
    }
}
