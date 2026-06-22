@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.quilter

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.crdt.BoundedCounter
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Number of surplus peers to contact in parallel on a single low-quota event. */
private const val BORROW_FAN_OUT = 2

/**
 * Configuration for [BoundedCounterTransferCoordinator].
 *
 * @param lowWaterThreshold when [BoundedCounter.quota] for the local replica drops to or below
 *   this value, a [BoundedCounterCoordMessage.TransferRequest] is sent to the top surplus peer(s).
 * @param requestedAmount how many quota units to request in each [BoundedCounterCoordMessage.TransferRequest].
 * @param surplusFloor the minimum quota a donor will retain after a transfer.
 *   A donor with `quota(self) <= surplusFloor` will not donate.
 * @param maxRetries how many times to retry a [BoundedCounterCoordMessage.TransferRequest] before
 *   giving up and degrading to "deny locally". Each retry doubles the backoff interval.
 * @param initialRetryDelay delay before the first retry.
 */
public data class BoundedCounterTransferConfig(
    val lowWaterThreshold: Long = 0L,
    val requestedAmount: Long = 10L,
    val surplusFloor: Long = 0L,
    val maxRetries: Int = 3,
    val initialRetryDelay: Duration = 2.seconds,
)

/**
 * Coordinator that automatically rebalances [BoundedCounter] quota across peers over a [Seam].
 *
 * ## Role
 *
 * [BoundedCounter]'s [BoundedCounter.trySpend] enforces local quota without coordination.
 * When quota runs low, however, a replica needs to obtain quota from peers. This coordinator
 * provides that active rebalancing.
 *
 * ## Protocol
 *
 * **Requester side (targeted borrow):** when [BoundedCounter.quota] for [self] drops to or below
 * [BoundedCounterTransferConfig.lowWaterThreshold], a [BoundedCounterCoordMessage.TransferRequest]
 * is sent via [Seam.sendTo] to the top-[BORROW_FAN_OUT] surplus peers, computed locally from
 * `BoundedCounter.quota(peerId)` over the connected peer set — so it is partition-safe and needs
 * no global roster. Only reachable peers (in [Seam.peers]) are considered.
 * The coordinator retries up to [BoundedCounterTransferConfig.maxRetries] times with exponential
 * backoff. If no transfer arrives, the requester degrades gracefully —
 * [BoundedCounter.trySpend] continues to deny locally.
 *
 * **Donor side:** on receiving a [BoundedCounterCoordMessage.TransferRequest], this replica
 * evaluates its own surplus (`quota(self) - surplusFloor`). If positive, it calls
 * [BoundedCounter.transfer] and passes the resulting [Patch] to [applyTransfer], which
 * broadcasts the state delta via [Quilter]. The state update then propagates
 * via the existing delta-replication path — there is no explicit response message.
 *
 * ## Safety invariant
 *
 * **The transfer request is advisory and does not bypass [BoundedCounter.trySpend].**
 * The local quota check on [BoundedCounter.trySpend] is the ultimate gatekeeper — it is
 * the only place where "spend" is committed, and it always uses the current merged state.
 * A transfer that hasn't propagated yet cannot unlock a [BoundedCounter.trySpend] —
 * quota is only available once the state delta arrives and is merged.
 *
 * Two concurrent donors responding to the same request compose correctly: each writes its
 * own row of the transfer matrix (per [BoundedCounter.transfer]'s design), so there is no
 * collision. The requester simply receives more quota than it asked for, which is safe.
 *
 * ## Multiplexing
 *
 * The coordinator receives frames from a [us.tractat.kuilt.core.MuxSeam] channel — a [Seam]
 * view that carries only frames tagged with its assigned byte prefix. This avoids a second
 * collection of the underlying seam's [Seam.incoming] flow (which is single-collection by contract).
 *
 * @param coordSeam a [us.tractat.kuilt.core.MuxSeam] channel — must be pre-wired by the caller.
 * @param state live [BoundedCounter] state (updated whenever [Quilter] applies a patch).
 * @param self this replica's [ReplicaId].
 * @param applyTransfer called by the donor side with a transfer [Patch]; the caller is expected to
 *   invoke [Quilter.apply] so the delta propagates to peers.
 * @param scope the [CoroutineScope] for background coroutines.
 * @param config tuning parameters.
 */
public class BoundedCounterTransferCoordinator(
    private val coordSeam: Seam,
    private val state: StateFlow<BoundedCounter>,
    private val self: ReplicaId,
    private val applyTransfer: (Patch<BoundedCounter>) -> Unit,
    private val scope: CoroutineScope,
    private val config: BoundedCounterTransferConfig = BoundedCounterTransferConfig(),
) : AutoCloseable {
    private val serializer = BoundedCounterCoordMessage.serializer()
    private val lock = reentrantLock()

    private val backgroundJobs: List<Job>

    /** Exposed internally so tests can verify [close] cancels both background jobs. */
    internal val backgroundJobsForTest: List<Job> get() = backgroundJobs

    init {
        val quotaJob = observeQuota()
        val incomingJob = observeIncoming()
        backgroundJobs = listOf(quotaJob, incomingJob)
    }

    /**
     * Cancels the quota-observer and incoming-frame-collector background jobs. Idempotent —
     * safe to call more than once.
     *
     * After [close], no further transfer requests are sent and incoming coordination frames are
     * ignored. The [scope] passed at construction is **not** cancelled — only the jobs owned
     * by this coordinator are stopped, leaving other coroutines in that scope alive.
     */
    override fun close() {
        backgroundJobs.forEach { it.cancel() }
    }

    private fun observeQuota(): Job {
        var requestInFlight = false
        return state.onEach { bc ->
            val quota = bc.quota(self)
            val shouldLaunch = lock.withLock {
                if (quota <= config.lowWaterThreshold && !requestInFlight) {
                    requestInFlight = true
                    true
                } else {
                    false
                }
            }
            if (shouldLaunch) {
                scope.launch {
                    sendRequestWithRetries()
                    lock.withLock { requestInFlight = false }
                }
            }
        }.launchIn(scope)
    }

    private suspend fun sendRequestWithRetries() {
        val msg = BoundedCounterCoordMessage.TransferRequest(
            requester = self,
            amount = config.requestedAmount,
        )
        val encoded = encode(msg)
        var delay = config.initialRetryDelay
        repeat(config.maxRetries) { attempt ->
            val reachable = coordSeam.peers.value - PeerId(self.value)
            if (reachable.isEmpty()) return
            val targets = topSurplusPeers(state.value, reachable)
            targets.forEach { target ->
                runCatchingCancellable { coordSeam.sendTo(target, encoded) }
                    .onFailure { /* send failed — retry or degrade on next iteration */ }
            }
            // check if quota improved (a donor may have responded already)
            if (state.value.quota(self) > config.lowWaterThreshold) return
            if (attempt < config.maxRetries - 1) {
                delay(delay)
                delay = delay * 2
            }
        }
        // exhausted retries — degrade to "deny locally" (trySpend returns null until state updates)
    }

    /**
     * Returns the top [BORROW_FAN_OUT] peers (from [reachable]) sorted by descending surplus
     * (`quota(peer) - surplusFloor`). Peers with no surplus are excluded.
     *
     * Computed locally from the current [BoundedCounter] state — no network round-trip needed.
     * Filtering to the [reachable] set makes this partition-safe.
     */
    private fun topSurplusPeers(bc: BoundedCounter, reachable: Set<PeerId>): List<PeerId> =
        reachable
            .map { peer -> peer to (bc.quota(ReplicaId(peer.value)) - config.surplusFloor) }
            .filter { (_, surplus) -> surplus > 0L }
            .sortedByDescending { (_, surplus) -> surplus }
            .take(BORROW_FAN_OUT)
            .map { (peer, _) -> peer }

    private fun observeIncoming(): Job =
        coordSeam.incoming
            .onEach { swatch -> swatch.sender?.let { dispatch(it, swatch) } }
            .launchIn(scope)

    private fun dispatch(sender: PeerId, swatch: Swatch) {
        val msg = runCatching { swatch.decode(Cbor, serializer) }.getOrNull() ?: return
        when (msg) {
            is BoundedCounterCoordMessage.TransferRequest -> onTransferRequest(msg, sender)
        }
    }

    private fun onTransferRequest(
        msg: BoundedCounterCoordMessage.TransferRequest,
        sender: PeerId,
    ) {
        if (msg.requester == self) return // safety: ignore requests from self
        val bc = state.value
        val surplus = bc.quota(self) - config.surplusFloor
        if (surplus <= 0L) return
        val grant = min(surplus, msg.amount)
        val patch = bc.transfer(from = self, to = msg.requester, amount = grant) ?: return
        applyTransfer(patch)
    }

    private fun encode(msg: BoundedCounterCoordMessage): ByteArray =
        Cbor.encodeToByteArray(serializer, msg)
}
