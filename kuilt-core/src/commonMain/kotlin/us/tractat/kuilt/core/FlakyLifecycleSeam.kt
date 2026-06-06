package us.tractat.kuilt.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.tractat.kuilt.core.internal.initialLifecycleState
import us.tractat.kuilt.core.internal.onEnterWeaving
import us.tractat.kuilt.core.internal.onRecover
import us.tractat.kuilt.core.internal.onTear
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.time.Duration

/**
 * A [Seam] wrapper that **owns its own [state]** and gates the contract on it,
 * enabling lifecycle-flap scenarios in tests.
 *
 * Unlike [FaultySeam] (which delegates `state` and injects per-frame faults),
 * this wrapper can simulate a transport link that drops and recovers:
 * `Woven → Weaving → Woven` (transient reconnect) or escalates to `Torn`
 * (permanent failure).
 *
 * ## Behaviour while [SeamState.Weaving]
 *
 * - [peers] collapses to `{selfId}` — this peer is momentarily alone.
 * - [broadcast] is the contract's **defined no-op** (no other peers; returns
 *   immediately without throwing).
 * - [sendTo] throws [PeerNotConnected] for any absent peer (as always).
 * - Inbound frames from the delegate are **dropped** while weaving — they are
 *   not buffered and will not appear on [incoming] after [recover].
 *
 * ## Behaviour on [recover]
 *
 * `state → Woven`, [peers] refills from the delegate, inbound delivery resumes.
 *
 * ## Behaviour on [tear]
 *
 * `state → Torn(reason)` (terminal), [incoming] completes, subsequent sends
 * throw [IllegalStateException].
 *
 * ## Composition
 *
 * Lifecycle wrapper outer, frame-fault inner:
 * `FlakyLifecycleSeam(FaultySeam(realSeam), scope)`. The lifecycle wrapper gates
 * the consumer-facing contract on its own `state`; the inner [FaultySeam] applies
 * frame-level faults to whatever flows while [SeamState.Woven].
 *
 * **Determinism guarantee:** all timing goes through [kotlinx.coroutines.delay]
 * so [kotlinx.coroutines.test.runTest] controls virtual time. [FlapSchedule]
 * jitter is seeded — same seed, same flap pattern.
 */
public class FlakyLifecycleSeam(
    private val delegate: Seam,
    private val scope: CoroutineScope,
) : Seam {
    private val _state = MutableStateFlow<SeamState>(initialLifecycleState(delegate.state.value))
    private val _peers = MutableStateFlow(delegate.peers.value)
    private val mutex = Mutex()

    private val isTorn: Boolean get() = _state.value is SeamState.Torn

    init {
        // While Woven, forward delegate membership changes to _peers.
        // The Woven check is evaluated only once per emission (the collector lambda
        // has no suspension point), so under a single-threaded or confined dispatcher
        // this is atomic with respect to enterWeaving/recover writes.
        scope.launch {
            delegate.peers.collect { delegatePeers ->
                if (_state.value is SeamState.Woven) _peers.value = delegatePeers
            }
        }
    }

    // ── Seam ──────────────────────────────────────────────────────────────────

    override val selfId: PeerId get() = delegate.selfId

    override val peers: StateFlow<Set<PeerId>> get() = _peers.asStateFlow()

    override val state: StateFlow<SeamState> get() = _state.asStateFlow()

    /**
     * Frames from [delegate.incoming], filtered by this seam's lifecycle state.
     *
     * Frames arriving while [SeamState.Weaving] are **dropped** — not buffered.
     * The gate check runs in the **consumer's coroutine** (not a background pipe),
     * so [_state] is read at the correct point relative to lifecycle transitions.
     *
     * The flow completes after [tear] is called: [tear] schedules
     * [delegate.close] in [scope], causing [delegate.incoming] to terminate,
     * which lets this flow exit cleanly. Callers should `yield()` or
     * `advanceUntilIdle()` after [tear] to let the delegate close propagate.
     *
     * Single-collection contract inherited from [Seam.incoming].
     */
    override val incoming: Flow<Swatch> = flow {
        delegate.incoming.collect { frame ->
            val deliver = mutex.withLock { !isTorn && _state.value is SeamState.Woven }
            if (deliver) emit(frame)
        }
    }

    override suspend fun broadcast(payload: ByteArray) {
        mutex.withLock { checkNotTorn() }
        if (_state.value is SeamState.Weaving || _peers.value.size <= 1) return
        delegate.broadcast(payload)
    }

    override suspend fun sendTo(
        peer: PeerId,
        payload: ByteArray,
    ) {
        mutex.withLock { checkNotTorn() }
        if (peer !in _peers.value) throw PeerNotConnected(peer)
        delegate.sendTo(peer, payload)
    }

    override suspend fun close(reason: CloseReason) {
        tear(reason)
        delegate.close(reason)
    }

    // ── Imperative control surface ────────────────────────────────────────────

    /**
     * Transition `Woven → Weaving`. Held until [recover] or [tear] is called.
     *
     * No-op if already [SeamState.Weaving] or [SeamState.Torn].
     *
     * Writes `_state` before `_peers` so that the delegate-peers collector, on
     * its next emission, sees [SeamState.Weaving] and skips — **narrowing** the
     * dual-write window. The race is in any case unmanifestable on the confined
     * single-threaded test dispatcher this class is always used with: the
     * collector's check-and-write is a single lambda with no suspension point,
     * so it is atomic there. Full elimination on a multi-threaded dispatcher
     * would require locking the collector's check+write together; unnecessary
     * for this test-only class.
     */
    public fun enterWeaving() {
        val next = onEnterWeaving(_state.value)
        if (next === _state.value) return
        _state.value = next
        _peers.value = setOf(selfId)
    }

    /**
     * Transition `Weaving → Woven`. Inbound delivery and [peers] resume from
     * the delegate.
     *
     * No-op if already [SeamState.Woven] or [SeamState.Torn].
     */
    public fun recover() {
        val next = onRecover(_state.value)
        if (next === _state.value) return
        _peers.value = delegate.peers.value
        _state.value = next
    }

    /**
     * Transition to [SeamState.Torn] (terminal). [incoming] completes;
     * subsequent sends throw [IllegalStateException].
     *
     * Idempotent — calling again after already torn is harmless.
     *
     * Schedules delegate closure in [scope] so that [delegate.incoming]
     * terminates, which lets the [incoming] flow exit cleanly.
     */
    public fun tear(reason: CloseReason = CloseReason.Unreachable) {
        val next = onTear(_state.value, reason)
        if (next === _state.value) return
        _peers.value = emptySet()
        _state.value = next
        scope.launch { delegate.close(reason) }
    }

    /**
     * Suspend through [weavingFor] in [SeamState.Weaving] then return to
     * [SeamState.Woven] — one atomic blip.
     */
    public suspend fun blip(weavingFor: Duration) {
        enterWeaving()
        delay(weavingFor)
        recover()
    }

    /**
     * Perform [flaps] blips (each [SeamState.Weaving] for [weavingFor]), then
     * [tear] with [reason]. After this call completes the seam is terminal.
     */
    public suspend fun flapThenTear(
        flaps: Int,
        weavingFor: Duration,
        reason: CloseReason = CloseReason.Unreachable,
    ) {
        repeat(flaps) { blip(weavingFor) }
        tear(reason)
    }

    /**
     * Launch a [FlapSchedule] loop in [scope] and return the running [Job].
     *
     * Alternates [SeamState.Woven] (≈[FlapSchedule.meanUptime]) and
     * [SeamState.Weaving] (≈[FlapSchedule.meanDowntime]) for
     * [FlapSchedule.giveUpAfter] flaps, then [tear]s. If
     * [FlapSchedule.giveUpAfter] is `0`, runs indefinitely until the seam is
     * cancelled or [tear] is called externally.
     *
     * All delays use [kotlinx.coroutines.delay] — virtual time under `runTest`.
     */
    public fun drive(schedule: FlapSchedule): Job = scope.launch {
        val rng = Random(schedule.seed)
        var flapsCompleted = 0
        while (!isTorn) {
            val uptimeMs = jitter(rng, schedule.meanUptime.inWholeMilliseconds)
            delay(uptimeMs)
            if (isTorn) break

            enterWeaving()
            val downtimeMs = jitter(rng, schedule.meanDowntime.inWholeMilliseconds)
            delay(downtimeMs)
            if (isTorn) break

            recover()
            flapsCompleted++

            if (schedule.giveUpAfter > 0 && flapsCompleted >= schedule.giveUpAfter) {
                tear(CloseReason.Unreachable)
                break
            }
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun checkNotTorn() {
        check(!isTorn) { "Seam for $selfId is torn (closed)" }
    }

    /** Returns a jittered duration in the range [50%, 150%) of [mean] ms. */
    private fun jitter(
        rng: Random,
        mean: Long,
    ): Long = (mean * (0.5 + rng.nextDouble())).roundToLong().coerceAtLeast(0L)
}

/**
 * A [Loom] wrapper that produces [FlakyLifecycleSeam] instances.
 *
 * Exposes created seams via [links] (in creation order) so scenario tests can
 * drive specific links' lifecycles — mirroring [FaultyLoom].
 */
public class FlakyLifecycleLoom(
    private val delegate: Loom,
    private val scope: CoroutineScope,
) : Loom {
    private val _links = MutableStateFlow<List<FlakyLifecycleSeam>>(emptyList())

    /** All [FlakyLifecycleSeam] instances created by this factory, in creation order. */
    public val links: List<FlakyLifecycleSeam> get() = _links.value

    override suspend fun weave(rendezvous: Rendezvous): FlakyLifecycleSeam = wrap(delegate.weave(rendezvous))

    override suspend fun host(pattern: Pattern): FlakyLifecycleSeam = wrap(delegate.host(pattern))

    override suspend fun join(tag: Tag): FlakyLifecycleSeam = wrap(delegate.join(tag))

    private fun wrap(delegate: Seam): FlakyLifecycleSeam {
        val seam = FlakyLifecycleSeam(delegate, scope)
        _links.value = _links.value + seam
        return seam
    }
}

/**
 * Declarative soak driver for [FlakyLifecycleSeam.drive].
 *
 * Alternates [SeamState.Woven] (≈[meanUptime] with ±50% jitter) and
 * [SeamState.Weaving] (≈[meanDowntime] with ±50% jitter) for [giveUpAfter]
 * cycles, then tears with [CloseReason.Unreachable]. Set [giveUpAfter] to `0`
 * for an infinite loop.
 *
 * All timing uses [kotlinx.coroutines.delay] — deterministic under
 * `runTest` virtual time. [seed] guarantees reproducibility across runs.
 */
public data class FlapSchedule(
    val seed: Long,
    val meanUptime: Duration,
    val meanDowntime: Duration,
    /** Number of flaps before tearing; `0` means run indefinitely. */
    val giveUpAfter: Int,
)
