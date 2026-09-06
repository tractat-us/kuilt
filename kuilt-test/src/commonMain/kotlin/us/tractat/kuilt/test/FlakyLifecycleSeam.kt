package us.tractat.kuilt.test

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
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.SeamStateGate
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.test.internal.initialLifecycleState
import us.tractat.kuilt.test.internal.onEnterWeaving
import us.tractat.kuilt.test.internal.onRecover
import us.tractat.kuilt.test.internal.onTear
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
 * `state → Torn(reason)` (terminal), [peers] collapses to `{selfId}` (published
 * *before* the latch), [incoming] completes, subsequent sends throw
 * [IllegalStateException].
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
    private val stateGate = SeamStateGate(initialLifecycleState(delegate.state.value))
    private val _peers = MutableStateFlow(delegate.peers.value)
    private val mutex = Mutex()

    private val isTorn: Boolean get() = stateGate.state.value is SeamState.Torn

    init {
        // A delegate that is ALREADY `Torn` starts this wrapper terminal — and a gate constructed
        // with a `Torn` initial is not *latched*, which is the quieter half of the bug the gate
        // exists to close ([SeamStateGate.update]'s KDoc names it): the state reads terminal while
        // the next `update` would still revive it. Latching here makes "born torn" and "torn by
        // [tear]" the same state. `tear` republishes an equal `Torn(reason)`, and `MutableStateFlow`
        // conflates equal values, so no spurious emission is produced.
        (stateGate.state.value as? SeamState.Torn)?.let { stateGate.tear(it.reason) }

        // While Woven, forward delegate membership changes to _peers.
        // The Woven check is evaluated only once per emission (the collector lambda has no
        // suspension point), so under a single-threaded or confined dispatcher this is atomic with
        // respect to the enterWeaving/recover `_peers` writes. NOTE the scope of that claim: it is
        // about `_peers`, which is NOT a `SeamState` flow and is deliberately out of this class's
        // migration to [SeamStateGate] — the terminal `SeamState` can no longer be lost on any
        // dispatcher, while this roster collector's check-then-write is unchanged (#2633).
        scope.launch {
            delegate.peers.collect { delegatePeers ->
                if (stateGate.state.value is SeamState.Woven) _peers.value = delegatePeers
            }
        }
    }

    // ── Seam ──────────────────────────────────────────────────────────────────

    override val selfId: PeerId get() = delegate.selfId

    override val peers: StateFlow<Set<PeerId>> get() = _peers.asStateFlow()

    override val state: StateFlow<SeamState> = stateGate.state

    /**
     * Frames from [delegate.incoming], filtered by this seam's lifecycle state.
     *
     * Frames arriving while [SeamState.Weaving] are **dropped** — not buffered.
     * The gate check runs in the **consumer's coroutine** (not a background pipe),
     * so [state] is read at the correct point relative to lifecycle transitions.
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
            val deliver = mutex.withLock { !isTorn && stateGate.state.value is SeamState.Woven }
            if (deliver) emit(frame)
        }
    }

    override suspend fun broadcast(payload: ByteArray) {
        mutex.withLock { checkNotTorn() }
        if (stateGate.state.value is SeamState.Weaving || _peers.value.size <= 1) return
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
     * Publishes the state before `_peers` so that the delegate-peers collector, on its next
     * emission, sees [SeamState.Weaving] and skips — **narrowing** the roster dual-write window.
     *
     * The state write itself goes through [SeamStateGate.update], so a concurrent [tear] can no
     * longer be clobbered by this transition **on any dispatcher**: once [tear] latches, this
     * `update` is a no-op. That is the whole of #2633; what remains is the narrower `_peers` window
     * described in the constructor, which is about the roster and not about the terminal state.
     *
     * `onEnterWeaving` returns its argument *identically* for every non-transition — already
     * [SeamState.Weaving], or terminal [SeamState.Torn] — so `next !== current` narrows `next` to
     * [SeamState.Weaving] and [SeamStateGate.update]'s `Torn` rejection is unreachable here by
     * construction, not by convention.
     */
    public fun enterWeaving() {
        val current = stateGate.state.value
        val next = onEnterWeaving(current)
        if (next === current) return
        stateGate.update(next)
        _peers.value = setOf(selfId)
    }

    /**
     * Transition `Weaving → Woven`. Inbound delivery and [peers] resume from
     * the delegate.
     *
     * No-op if already [SeamState.Woven] or [SeamState.Torn] — and, like [enterWeaving], a no-op
     * once [tear] has latched the gate, whichever dispatcher the two ran on. `onRecover` returns its
     * argument identically for every non-transition, so `next !== current` narrows `next` to
     * [SeamState.Woven] and [SeamStateGate.update] can never be handed a `Torn`.
     */
    public fun recover() {
        val current = stateGate.state.value
        val next = onRecover(current)
        if (next === current) return
        _peers.value = delegate.peers.value
        stateGate.update(next)
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
        // `{ selfId }`, never `emptySet()`: `Seam.peers` always includes this peer's own id, so a Torn
        // roster collapses to exactly `{ selfId }` — the same collapse [enterWeaving] already performs.
        // Written before the terminal publish so a consumer woken by `Torn` cannot read the pre-tear
        // roster (#1854). It is UNCONDITIONAL — [SeamStateGate.tear] fuses the "already torn?" check
        // with the publish, so there is no pre-check left to hang this write behind, and none is
        // wanted: after a tear `_peers` is stably `{ selfId }`, so a second call re-writes an equal
        // value that `MutableStateFlow` conflates away. The one case where it is not equal is the
        // roster collector having clobbered it, which this repairs rather than preserves.
        _peers.value = setOf(selfId)
        // Single-shot and atomic: the losing caller of two concurrent tears gets `false` and does not
        // re-close the delegate, which is what the old `onTear(...) === current` pre-check bought
        // non-atomically. `onTear` is now only exercised by its own unit tests.
        if (!stateGate.tear(reason)) return
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

    /**
     * The [delegate]'s verdict, verbatim. This loom flaps its seams' *lifecycle*; it does not change
     * which medium carries them or whether that medium is usable on this runtime — the pre-connect
     * question [us.tractat.kuilt.core.Loom.capability] answers. Substituting a verdict of its own
     * would only discard the delegate's established one (#1936).
     */
    override fun capability(): TransportCapability = delegate.capability()

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
