package us.tractat.kuilt.test

import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Swatch
import kotlin.random.Random
import kotlin.time.Duration

/**
 * Declarative description of which faults [FaultySeam] should inject.
 *
 * All probabilistic profiles take an explicit [seed] so tests are
 * deterministic: two runs with the same seed produce identical behaviour.
 *
 * Faults are per-[Direction] unless otherwise noted.
 */
public sealed interface FaultProfile {
    /** No faults — all frames delivered in order. */
    public data object Healthy : FaultProfile

    /**
     * Drop every frame in [direction].
     *
     * This is the canonical "partition" profile.
     * A bidirectional partition is [DropAll] with [Direction.Both].
     * An asymmetric (one-way) loss is [DropAll] with [Direction.Outbound]
     * or [Direction.Inbound].
     */
    public data class DropAll(
        val direction: Direction = Direction.Both,
    ) : FaultProfile

    /**
     * Drop each frame independently with probability [probability] in [direction].
     *
     * [seed] makes the pseudo-random draw deterministic across test runs.
     * A [probability] of 0.0 never drops; 1.0 always drops.
     */
    public data class DropProbabilistic(
        val probability: Double,
        val seed: Long,
        val direction: Direction = Direction.Both,
    ) : FaultProfile

    /**
     * Drop only the frames whose 0-based send index appears in [frameIndexes].
     *
     * Outbound index is tracked per-link across broadcast and sendTo calls.
     * Inbound index is tracked per-link across received frames.
     * Indexes outside [frameIndexes] are delivered normally.
     */
    public data class DropSpecific(
        val frameIndexes: Set<Int>,
        val direction: Direction = Direction.Both,
    ) : FaultProfile

    /**
     * Delay every frame in [direction] by [delay].
     *
     * Uses [kotlinx.coroutines.delay] so [kotlinx.coroutines.test.runTest]'s
     * virtual time controls delivery — no wall-clock dependency.
     */
    public data class DelayAll(
        val delay: Duration,
        val direction: Direction = Direction.Both,
    ) : FaultProfile

    /**
     * Buffer up to [windowSize] frames then emit them in a randomised order.
     *
     * The window slides: once full it flushes all buffered frames in a
     * [seed]-determined permutation, then starts filling again. [direction]
     * controls which message stream is reordered.
     *
     * [seed] guarantees determinism across test runs.
     *
     * Each held outbound frame is flushed to its own destination: a `sendTo` frame to its peer, a
     * `broadcast` frame to everyone. A held `sendTo` whose peer has left by the flush is dropped.
     * Every held frame counts in [FaultySeam.framesDelayed] when it enters the window.
     *
     * **A partial window is released, or counted lost — never silently kept.** Any
     * [FaultySeam.setFaultProfile] (so `heal()` and `partition()` too) empties both windows and
     * delivers what they held, in the order it arrived, each outbound frame to its own route. The
     * release runs in the seam's scope because `setFaultProfile` cannot suspend, so a frame sent
     * straight after the swap can arrive ahead of it. Frames still held when the seam closes, or
     * when its link ends underneath it, count in [FaultySeam.framesDropped] — as does a released
     * frame that finds the link already closed.
     */
    public data class ReorderWindow(
        val windowSize: Int,
        val seed: Long,
        val direction: Direction = Direction.Both,
    ) : FaultProfile

    /**
     * Allow at most [maxOutbound] total outbound frames (per-link lifetime).
     *
     * The first [maxOutbound] sends are delivered normally. Every subsequent
     * send is silently dropped (simulates tail-drop / send-quota exhaustion).
     *
     * This is the outbound-only ceiling. Inbound is unaffected.
     */
    public data class BufferCeiling(
        val maxOutbound: Int,
    ) : FaultProfile

    /**
     * Close the link with [reason] immediately after the ([frameIndex])-th
     * outbound frame (0-based) is accepted by send.
     *
     * Frames with index < [frameIndex] are sent normally.
     * Frames at or after [frameIndex] see the link already closed.
     */
    public data class CloseAt(
        val frameIndex: Int,
        val reason: CloseReason = CloseReason.Normal,
    ) : FaultProfile

    /**
     * Compose multiple profiles. Applied left-to-right in [profiles] order:
     * if any profile drops a frame, later profiles are not consulted for that
     * frame. Delays accumulate.
     */
    public data class Composite(
        val profiles: List<FaultProfile>,
    ) : FaultProfile
}

/** Which direction a fault applies to. */
public enum class Direction {
    /** Frames sent by this link (broadcast / sendTo). */
    Outbound,

    /** Frames received by this link (incoming). */
    Inbound,

    /** Both directions. */
    Both,
}

// ── Internal fault-application logic ─────────────────────────────────────────

/**
 * Mutable per-link state that drives fault-profile evaluation.
 * One instance per [FaultySeam].
 */
internal class FaultState(
    profile: FaultProfile,
) {
    var profile: FaultProfile = profile

    // Per-direction send counters (0-based frame index)
    private var outboundCount = 0
    private var inboundCount = 0

    // Per-direction Random instances derived from composite seeds
    private var outboundRandom: Random? = null
    private var inboundRandom: Random? = null

    // Reorder window buffers (outbound / inbound). An outbound entry keeps its own [Route]: the
    // call that fills the window is not the call that queued the other frames in it (#2879).
    private val outboundWindow = mutableListOf<OutboundFrame>()
    private val inboundWindow = mutableListOf<Swatch>()

    // How many inbound frames a ReorderWindow has taken into its window, lifetime. Read as a delta
    // around one evaluation so [evaluateInbound] can tell "held" from "dropped" (#2879).
    private var inboundFramesHeld = 0L

    /**
     * Empty both reorder windows and return what they held, in the order it arrived (#2882).
     *
     * No permutation is drawn: the window never filled, so no shuffle was owed, and drawing one here
     * would shift every later window's permutation for the same seed.
     */
    fun drainHeld(): HeldFrames {
        val held = HeldFrames(outboundWindow.toList(), inboundWindow.toList())
        outboundWindow.clear()
        inboundWindow.clear()
        return held
    }

    private fun nextOutboundIndex(): Int = outboundCount++

    private fun nextInboundIndex(): Int = inboundCount++

    /**
     * Evaluate [profile] for an outbound frame.
     * Returns [OutboundDecision] that tells the link what to do with the send.
     */
    fun evaluateOutbound(frame: OutboundFrame): OutboundDecision = evaluateOutboundFor(profile, frame, nextOutboundIndex())

    /**
     * Evaluate [profile] for an inbound frame.
     * The outcome's frames are what should be delivered now (may be empty, may be reordered, may
     * contain the original plus buffered frames); [InboundOutcome.held] says whether [frame] went
     * into a reorder window rather than being dropped.
     */
    fun evaluateInbound(frame: Swatch): InboundOutcome {
        val index = nextInboundIndex()
        val heldBefore = inboundFramesHeld
        val frames = evaluateInboundFor(profile, frame, index)
        return InboundOutcome(frames, held = inboundFramesHeld != heldBefore)
    }

    // ── Outbound evaluation ───────────────────────────────────────────────────

    private fun evaluateOutboundFor(
        p: FaultProfile,
        frame: OutboundFrame,
        index: Int,
    ): OutboundDecision {
        val payload = frame.payload
        return when (p) {
            is FaultProfile.Healthy -> OutboundDecision.Send(payload)
            is FaultProfile.DropAll -> if (p.direction.appliesToOutbound()) OutboundDecision.Drop else OutboundDecision.Send(payload)
            is FaultProfile.DropProbabilistic -> {
                if (!p.direction.appliesToOutbound()) {
                    OutboundDecision.Send(payload)
                } else {
                    val rng = outboundRandom ?: Random(p.seed).also { outboundRandom = it }
                    if (rng.nextDouble() < p.probability) OutboundDecision.Drop else OutboundDecision.Send(payload)
                }
            }
            is FaultProfile.DropSpecific -> {
                if (!p.direction.appliesToOutbound() || index !in p.frameIndexes) {
                    OutboundDecision.Send(payload)
                } else {
                    OutboundDecision.Drop
                }
            }
            is FaultProfile.DelayAll -> {
                if (p.direction.appliesToOutbound()) OutboundDecision.Delay(payload, p.delay) else OutboundDecision.Send(payload)
            }
            is FaultProfile.ReorderWindow -> {
                if (!p.direction.appliesToOutbound()) return OutboundDecision.Send(payload)
                outboundWindow += frame
                if (outboundWindow.size >= p.windowSize) {
                    val flushed = flushOutboundWindow(p)
                    OutboundDecision.SendBurst(flushed)
                } else {
                    OutboundDecision.Buffer
                }
            }
            is FaultProfile.BufferCeiling -> {
                // index is the 0-based count of this send call (already incremented by nextOutboundIndex()).
                // Drop once we've accepted maxOutbound frames total.
                if (index >= p.maxOutbound) {
                    OutboundDecision.Drop
                } else {
                    OutboundDecision.Send(payload)
                }
            }
            is FaultProfile.CloseAt -> {
                if (index >= p.frameIndex) OutboundDecision.CloseLink(p.reason) else OutboundDecision.Send(payload)
            }
            is FaultProfile.Composite -> evaluateCompositeOutbound(p.profiles, frame, index)
        }
    }

    private fun flushOutboundWindow(p: FaultProfile.ReorderWindow): List<OutboundFrame> {
        val rng = outboundRandom ?: Random(p.seed).also { outboundRandom = it }
        val shuffled = outboundWindow.toMutableList().also { it.shuffle(rng) }
        outboundWindow.clear()
        return shuffled
    }

    private fun evaluateCompositeOutbound(
        profiles: List<FaultProfile>,
        frame: OutboundFrame,
        index: Int,
    ): OutboundDecision {
        var current: OutboundDecision = OutboundDecision.Send(frame.payload)
        var accumulatedDelay = Duration.ZERO
        for (p in profiles) {
            val sendPayload = (current as? OutboundDecision.Send)?.payload ?: frame.payload
            val decision = evaluateOutboundFor(p, OutboundFrame(sendPayload, frame.route), index)
            when (decision) {
                is OutboundDecision.Drop -> return OutboundDecision.Drop
                is OutboundDecision.CloseLink -> return decision
                is OutboundDecision.Buffer -> return OutboundDecision.Buffer
                is OutboundDecision.SendBurst -> return decision
                is OutboundDecision.Delay -> accumulatedDelay += decision.delay
                is OutboundDecision.Send -> current = decision
            }
        }
        return if (accumulatedDelay > Duration.ZERO) {
            OutboundDecision.Delay((current as OutboundDecision.Send).payload, accumulatedDelay)
        } else {
            current
        }
    }

    // ── Inbound evaluation ────────────────────────────────────────────────────

    private fun evaluateInboundFor(
        p: FaultProfile,
        frame: Swatch,
        index: Int,
    ): List<Swatch> =
        when (p) {
            is FaultProfile.Healthy -> listOf(frame)
            is FaultProfile.DropAll -> if (p.direction.appliesToInbound()) emptyList() else listOf(frame)
            is FaultProfile.DropProbabilistic -> {
                if (!p.direction.appliesToInbound()) {
                    listOf(frame)
                } else {
                    val rng = inboundRandom ?: Random(p.seed + 1L).also { inboundRandom = it }
                    if (rng.nextDouble() < p.probability) emptyList() else listOf(frame)
                }
            }
            is FaultProfile.DropSpecific -> {
                if (!p.direction.appliesToInbound() || index !in p.frameIndexes) listOf(frame) else emptyList()
            }
            is FaultProfile.DelayAll -> listOf(frame) // delay handled at delivery site
            is FaultProfile.ReorderWindow -> {
                if (!p.direction.appliesToInbound()) return listOf(frame)
                inboundWindow += frame
                if (inboundWindow.size >= p.windowSize) {
                    val rng = inboundRandom ?: Random(p.seed + 1L).also { inboundRandom = it }
                    val shuffled = inboundWindow.toMutableList().also { it.shuffle(rng) }
                    inboundWindow.clear()
                    shuffled
                } else {
                    inboundFramesHeld++
                    emptyList()
                }
            }
            is FaultProfile.BufferCeiling -> listOf(frame) // ceiling is outbound-only
            is FaultProfile.CloseAt -> listOf(frame) // close-at is outbound-only
            is FaultProfile.Composite -> evaluateCompositeInbound(p.profiles, frame, index)
        }

    private fun evaluateCompositeInbound(
        profiles: List<FaultProfile>,
        frame: Swatch,
        index: Int,
    ): List<Swatch> {
        var current = listOf(frame)
        for (p in profiles) {
            if (current.isEmpty()) return emptyList()
            current = current.flatMap { f -> evaluateInboundFor(p, f, index) }
        }
        return current
    }

    fun inboundDelay(p: FaultProfile): Duration? =
        when (p) {
            is FaultProfile.DelayAll -> if (p.direction.appliesToInbound()) p.delay else null
            is FaultProfile.Composite -> p.profiles.sumDelay { inboundDelay(it) }
            else -> null
        }

    private fun List<FaultProfile>.sumDelay(extract: (FaultProfile) -> Duration?): Duration? {
        val total = fold(Duration.ZERO) { acc, p -> acc + (extract(p) ?: Duration.ZERO) }
        return if (total == Duration.ZERO) null else total
    }
}

private fun Direction.appliesToOutbound() = this == Direction.Outbound || this == Direction.Both

private fun Direction.appliesToInbound() = this == Direction.Inbound || this == Direction.Both

/** Where an outbound frame is going: one peer, or every peer. */
internal sealed interface Route {
    data class To(
        val peer: PeerId,
    ) : Route

    data object Broadcast : Route
}

/**
 * An outbound frame together with its [route]. A reorder window holds these rather than bare
 * payloads, so each held frame is flushed to where *it* was going (#2879).
 */
internal class OutboundFrame(
    val payload: ByteArray,
    val route: Route,
)

/**
 * What the inbound path should do with one received frame: deliver [frames] now, and whether the
 * received frame was [held] in a reorder window (delayed, not dropped) when [frames] is empty.
 */
internal class InboundOutcome(
    val frames: List<Swatch>,
    val held: Boolean,
)

/** The frames a [FaultState] had in its reorder windows when they were drained. */
internal class HeldFrames(
    val outbound: List<OutboundFrame>,
    val inbound: List<Swatch>,
) {
    val size: Int get() = outbound.size + inbound.size
}

/** What the outbound path should do with a frame. */
internal sealed interface OutboundDecision {
    /** Deliver the (possibly mutated) payload immediately. */
    data class Send(
        val payload: ByteArray,
    ) : OutboundDecision

    /** Deliver the payload after the given delay (virtual time). */
    data class Delay(
        val payload: ByteArray,
        val delay: kotlin.time.Duration,
    ) : OutboundDecision

    /** Discard — the transport never delivers this frame. */
    data object Drop : OutboundDecision

    /** Buffer internally for later reorder-window flush. */
    data object Buffer : OutboundDecision

    /** Flush a batch of reordered frames, each to its own [OutboundFrame.route]. */
    data class SendBurst(
        val frames: List<OutboundFrame>,
    ) : OutboundDecision

    /** Close the link with this reason instead of sending. */
    data class CloseLink(
        val reason: CloseReason,
    ) : OutboundDecision
}
