@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.quilter

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PumpVerdict
import us.tractat.kuilt.core.ScopedCloseable
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.pumpIn
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.crdt.BoundedCounter
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.math.minusOrNull
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger("us.tractat.kuilt.quilter.BoundedCounterTransferCoordinator")

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
 * ## Proactive equalizer (optional)
 *
 * When [equalizerConfig] is non-null, a periodic background task fires on each tick and
 * transfers surplus quota to the single lowest-quota reachable peer. The equalizer's goal
 * is to keep quotas near the fair share (`bound / liveN`) so low-water events rarely fire
 * under stable load. It skips ticks where this replica's surplus over the fair share is
 * within [BoundedCounterEqualizerConfig.minImbalanceThreshold] — avoiding idle noise.
 *
 * The equalizer is **optional = tuning**, not a functional gate: passing `null` (the
 * default) leaves the reactive targeted-borrow path fully correct. The equalizer only
 * reduces how often reactive borrows fire.
 *
 * ## Lifecycle
 *
 * [AutoCloseable.close] is inherited from [ScopedCloseable]: it cancels the coordinator's own
 * child job — the quota observer, the incoming-frame collector, the optional equalizer loop, and
 * **any borrow currently retrying** — without touching the `scope` passed at construction, so
 * other coroutines the caller parked there stay alive. It is idempotent and thread-safe.
 *
 * The last item in that list is why this class extends [ScopedCloseable] rather than tracking a
 * job list by hand. A borrow is launched *reactively*, on a low-water event, long after the
 * constructor has run; a hand-maintained list cannot contain it, so `close()` cancelled the
 * observer while the borrow it had already started went on calling [Seam.sendTo] through its
 * whole retry backoff (#2502). Under [ScopedCloseable] every `scope.launch` in this class is
 * structurally a child of the job `close()` cancels, so that drift is not merely fixed but
 * unrepresentable.
 *
 * @param coordSeam a [us.tractat.kuilt.core.MuxSeam] channel — must be pre-wired by the caller.
 * @param state live [BoundedCounter] state (updated whenever [Quilter] applies a patch).
 * @param self this replica's [ReplicaId].
 * @param applyTransfer called by the donor side with a transfer [Patch]; the caller is expected to
 *   invoke [Quilter.apply] so the delta propagates to peers.
 * @param scope the caller's [CoroutineScope]. The coordinator launches nothing directly into it —
 *   [ScopedCloseable] interposes an owned child job, so cancelling this scope still stops the
 *   coordinator but closing the coordinator leaves this scope alone. The periodic equalizer loop
 *   uses [delay] on this scope's dispatcher — inject a test dispatcher for virtual-time control.
 * @param config reactive-borrow tuning parameters.
 * @param equalizerConfig proactive equalizer parameters, or `null` to disable the equalizer.
 */
public class BoundedCounterTransferCoordinator(
    private val coordSeam: Seam,
    private val state: StateFlow<BoundedCounter>,
    private val self: ReplicaId,
    private val applyTransfer: (Patch<BoundedCounter>) -> Unit,
    scope: CoroutineScope,
    private val config: BoundedCounterTransferConfig = BoundedCounterTransferConfig(),
    private val equalizerConfig: BoundedCounterEqualizerConfig? = null,
) : ScopedCloseable(scope) {
    private val serializer = BoundedCounterCoordMessage.serializer()
    private val lock = reentrantLock()

    private val backgroundJobs: List<Job>

    /**
     * The jobs started in the constructor, exposed internally for
     * `CloseableLifecycleConformanceSuite.backgroundJobsOf`.
     *
     * **This list is not the set of coroutines [close] stops, and must not be read as one.** The
     * reactive borrow is launched on a low-water event and never appears here; what stops it is
     * [ScopedCloseable] ownership, not membership of this list. Asserting that every job here is
     * inactive is exactly the property that stayed green throughout #2502.
     */
    internal val backgroundJobsForTest: List<Job> get() = backgroundJobs

    init {
        val quotaJob = observeQuota()
        val incomingJob = observeIncoming()
        val equalizerJob = equalizerConfig?.let { startEqualizer(it) }
        backgroundJobs = listOfNotNull(quotaJob, incomingJob, equalizerJob)
    }

    private fun observeQuota(): Job {
        var requestInFlight = false
        return state.pumpIn(
            scope = scope,
            onFailure = { phase, failure ->
                logger.warn(failure) { "BoundedCounter quota observer for $self failed ($phase)" }
                PumpVerdict.CONTINUE
            },
            name = "bounded-counter-quota[${self.value}]",
        ) { bc ->
            val quota = bc.quotaAtMost(self)
            val shouldLaunch = lock.withLock {
                if (quota <= config.lowWaterThreshold && !requestInFlight) {
                    requestInFlight = true
                    true
                } else {
                    false
                }
            }
            if (shouldLaunch) {
                // `scope` resolves to ScopedCloseable's owned child scope, NOT the constructor's
                // `scope` parameter — a primary-constructor parameter is out of scope in a member
                // function body. That is precisely what makes this borrow a child of the job
                // close() cancels; parented to the caller's scope it outlived close() and went on
                // retrying against a closed coordinator (#2502).
                scope.launch {
                    // `finally`, not a trailing statement. The borrow ends abnormally on two live
                    // paths: close()/parent cancellation, and a CancellationException the *callee*
                    // minted (a seam whose sendTo wraps an internal withTimeout), which
                    // runCatchingCancellable rethrows by contract. Either skips a trailing reset
                    // and latches the flag true forever — and on the second path the quota
                    // observer is still running, so the replica silently stops asking for quota
                    // with nothing to signal why. `withLock` does not suspend, so it still runs
                    // while the coroutine unwinds.
                    try {
                        sendRequestWithRetries()
                    } finally {
                        lock.withLock { requestInFlight = false }
                    }
                }
            }
        }
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
            if (state.value.quotaAtMost(self) > config.lowWaterThreshold) return
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
     *
     * Ranks by quota rather than by `quota - surplusFloor`: the order and the cut are the same, and
     * nothing is subtracted, so a saturated quota cannot wrap against a negative floor.
     */
    private fun topSurplusPeers(bc: BoundedCounter, reachable: Set<PeerId>): List<PeerId> =
        reachable
            .map { peer -> peer to bc.quotaAtMost(ReplicaId(peer.value)) }
            .filter { (_, quota) -> quota > config.surplusFloor }
            .sortedByDescending { (_, quota) -> quota }
            .take(BORROW_FAN_OUT)
            .map { (peer, _) -> peer }

    private fun observeIncoming(): Job =
        coordSeam.incoming.pumpIn(
            scope = scope,
            onFailure = { phase, failure ->
                // An item failure can come from the consumer's own applyTransfer, so the trace stays.
                logger.warn(failure) { "BoundedCounter transfer-request collector for $self failed ($phase)" }
                PumpVerdict.CONTINUE
            },
            name = "bounded-counter-requests[${self.value}]",
        ) { swatch -> swatch.sender?.let { dispatch(it, swatch) } }

    private fun dispatch(sender: PeerId, swatch: Swatch) {
        val msg = runCatchingCancellable { swatch.decode(Cbor, serializer) }.getOrNull() ?: return
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
        val quota = bc.quotaAtMost(self)
        if (quota <= config.surplusFloor) return
        // Above the floor, the difference can pass Long.MAX_VALUE only by being large; [transfer]
        // re-checks the grant against the exact quota either way.
        val surplus = quota.minusOrNull(config.surplusFloor) ?: Long.MAX_VALUE
        val grant = min(surplus, msg.amount)
        val patch = bc.transfer(from = self, to = msg.requester, amount = grant) ?: return
        applyTransfer(patch)
    }

    /**
     * Starts the proactive equalizer background loop.
     *
     * Each tick: if `quota(self)` exceeds the fair share (`bound / liveN`) by more than
     * [BoundedCounterEqualizerConfig.minImbalanceThreshold], transfers the excess to the
     * single lowest-quota reachable peer. Only one bilateral transfer is issued per tick
     * (fire-and-forget — propagates as a normal CRDT delta via [applyTransfer]).
     *
     * Uses [BoundedCounterEqualizerConfig.random] for tie-breaking when multiple peers
     * share the minimum quota.
     */
    private fun startEqualizer(cfg: BoundedCounterEqualizerConfig): Job =
        scope.launch {
            // Read and written only by this one coroutine, one tick at a time.
            var failing = false
            while (true) {
                delay(cfg.cadence)
                // A failure in one tick must not kill the periodic loop; runCatchingCancellable
                // still rethrows CancellationException so the loop cancels cleanly on close().
                // Rebalance retries next tick. A failure that persists — a fair share too large to
                // read in a `Long` throws on every tick (#3343) — is reported once at WARN, with its
                // trace since it can come from the consumer's own applyTransfer, then at DEBUG until a
                // tick succeeds again, so it is visible without a line per tick.
                runCatchingCancellable { equalizeTick(cfg) }
                    .onSuccess { failing = false }
                    .onFailure { failure ->
                        val line = "BoundedCounter equalizer tick for $self failed; retrying next tick"
                        if (failing) logger.debug { "$line: $failure" } else logger.warn(failure) { line }
                        failing = true
                    }
            }
        }

    private fun equalizeTick(cfg: BoundedCounterEqualizerConfig) {
        val bc = state.value
        val reachablePeers = coordSeam.peers.value - PeerId(self.value)
        if (reachablePeers.isEmpty()) return

        val liveN = reachablePeers.size + 1 // peers + self
        // The bound, remaining budget plus spent, is formed exactly inside BoundedCounter: it, and
        // the remaining budget alone, can be past Long.MAX_VALUE while the share fits (#3343). Only a
        // share that does not fit throws, and that is logged by the loop.
        val fairShare = bc.fairShareOrNull(liveN)
            ?: throw ArithmeticException("BoundedCounter fair share over $liveN replicas does not fit in a Long")

        // Both quotas and the share are at least 0, so neither difference can wrap. A saturated
        // quota understates the excess, so an oversized holder drains over several ticks.
        val myQuota = bc.quotaAtMost(self)
        val excess = myQuota - fairShare
        if (excess <= cfg.minImbalanceThreshold) return

        // Power-of-two-choices: sample two random reachable peers and pick the lower-quota
        // one. Randomised target selection (rather than always the single global minimum)
        // stops many over-share peers from thundering onto the same recipient in one round.
        val recipient = powerOfTwoLowest(bc, reachablePeers, cfg) ?: return
        val recipientDeficit = fairShare - bc.quotaAtMost(ReplicaId(recipient.value))
        if (recipientDeficit <= 0L) return // chosen peer isn't below its share — skip this round

        // Cap the transfer so neither side crosses the fair share: a single step can't push
        // self below, or the recipient above, fairShare — so it cannot overshoot. Convergence
        // is monotone toward the fair share rather than oscillating.
        val amount = minOf(excess, recipientDeficit)
        val patch = bc.transfer(from = self, to = ReplicaId(recipient.value), amount = amount)
            ?: return
        applyTransfer(patch)
    }

    /**
     * Power-of-two-choices recipient selection: samples up to two distinct reachable peers
     * (using [BoundedCounterEqualizerConfig.random]) and returns the one with the lower quota.
     * Spreading targets across rounds prevents the thundering-herd overshoot a single-global-
     * minimum pick would cause when several peers rebalance concurrently. Returns null only
     * if [reachable] is empty (the caller already guards against that).
     */
    private fun powerOfTwoLowest(
        bc: BoundedCounter,
        reachable: Set<PeerId>,
        cfg: BoundedCounterEqualizerConfig,
    ): PeerId? =
        reachable
            .shuffled(cfg.random)
            .take(2)
            .minByOrNull { peer -> bc.quotaAtMost(ReplicaId(peer.value)) }

    /**
     * [replica]'s quota, read as [Long.MAX_VALUE] when it is larger (#3343). [BoundedCounter.quota]
     * throws there, and every read in this class runs inside a pump or a launched borrow, where a
     * throw ended it for good — and on Kotlin/Native aborted the process (#1788).
     *
     * Reading high is safe at every use: each is a threshold comparison, a ranking, or the cap on a
     * grant that [BoundedCounter.transfer] re-checks against the exact quota. A quota is never below
     * 0, so `null` from [BoundedCounter.quotaOrNull] always means too large, never too small.
     */
    private fun BoundedCounter.quotaAtMost(replica: ReplicaId): Long = quotaOrNull(replica) ?: Long.MAX_VALUE

    private fun encode(msg: BoundedCounterCoordMessage): ByteArray =
        Cbor.encodeToByteArray(serializer, msg)
}
