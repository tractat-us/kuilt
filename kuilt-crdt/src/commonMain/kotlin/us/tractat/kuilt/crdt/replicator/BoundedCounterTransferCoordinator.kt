@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.crdt.replicator

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
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.crdt.BoundedCounter
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for [BoundedCounterTransferCoordinator].
 *
 * @param lowWaterThreshold when [BoundedCounter.quota] for the local replica drops to or below
 *   this value, a [BoundedCounterCoordMessage.TransferRequest] is broadcast to all peers.
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
 * **Requester side:** when [BoundedCounter.quota] for [self] drops to or below
 * [BoundedCounterTransferConfig.lowWaterThreshold], a [BoundedCounterCoordMessage.TransferRequest]
 * is broadcast to all current peers. The coordinator retries up to [BoundedCounterTransferConfig.maxRetries]
 * times with exponential backoff. If no transfer arrives, the requester degrades gracefully —
 * [BoundedCounter.trySpend] continues to deny locally.
 *
 * **Donor side:** on receiving a [BoundedCounterCoordMessage.TransferRequest], this replica
 * evaluates its own surplus (`quota(self) - surplusFloor`). If positive, it calls
 * [BoundedCounter.transfer] and passes the resulting [Patch] to [applyTransfer], which
 * broadcasts the state delta via [SeamReplicator]. The state update then propagates
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
 * @param state live [BoundedCounter] state (updated whenever [SeamReplicator] applies a patch).
 * @param self this replica's [ReplicaId].
 * @param applyTransfer called by the donor side with a transfer [Patch]; the caller is expected to
 *   invoke [SeamReplicator.apply] so the delta propagates to peers.
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
            if (quota <= config.lowWaterThreshold && !requestInFlight) {
                requestInFlight = true
                scope.launch {
                    sendRequestWithRetries()
                    requestInFlight = false
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
            val peersAttempt = coordSeam.peers.value - PeerId(self.value)
            if (peersAttempt.isEmpty()) return
            runCatching { coordSeam.broadcast(encoded) }
            // check if quota improved (a donor may have responded already)
            if (state.value.quota(self) > config.lowWaterThreshold) return
            if (attempt < config.maxRetries - 1) {
                delay(delay)
                delay = delay * 2
            }
        }
        // exhausted retries — degrade to "deny locally" (trySpend returns null until state updates)
    }

    private fun observeIncoming(): Job =
        coordSeam.incoming
            .onEach { swatch -> swatch.sender?.let { dispatch(it, swatch) } }
            .launchIn(scope)

    private fun dispatch(sender: PeerId, swatch: Swatch) {
        val msg = runCatching { decode(swatch.payload) }.getOrNull() ?: return
        when (msg) {
            is BoundedCounterCoordMessage.TransferRequest -> onTransferRequest(msg, sender)
        }
    }

    private fun onTransferRequest(
        msg: BoundedCounterCoordMessage.TransferRequest,
        sender: PeerId,
    ) {
        if (msg.requester == self) return // ignore own broadcast reflected back
        val bc = state.value
        val surplus = bc.quota(self) - config.surplusFloor
        if (surplus <= 0L) return
        val grant = min(surplus, msg.amount)
        val patch = bc.transfer(from = self, to = msg.requester, amount = grant) ?: return
        applyTransfer(patch)
    }

    private fun encode(msg: BoundedCounterCoordMessage): ByteArray =
        Cbor.encodeToByteArray(serializer, msg)

    private fun decode(bytes: ByteArray): BoundedCounterCoordMessage =
        Cbor.decodeFromByteArray(serializer, bytes)
}
