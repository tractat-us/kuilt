package us.tractat.kuilt.core

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
sealed interface FaultProfile {
    /** No faults — all frames delivered in order. */
    data object Healthy : FaultProfile

    /**
     * Drop every frame in [direction].
     *
     * This is the canonical "partition" profile.
     * A bidirectional partition is [DropAll] with [Direction.Both].
     * An asymmetric (one-way) loss is [DropAll] with [Direction.Outbound]
     * or [Direction.Inbound].
     */
    data class DropAll(
        val direction: Direction = Direction.Both,
    ) : FaultProfile

    /**
     * Drop each frame independently with probability [probability] in [direction].
     *
     * [seed] makes the pseudo-random draw deterministic across test runs.
     * A [probability] of 0.0 never drops; 1.0 always drops.
     */
    data class DropProbabilistic(
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
    data class DropSpecific(
        val frameIndexes: Set<Int>,
        val direction: Direction = Direction.Both,
    ) : FaultProfile

    /**
     * Delay every frame in [direction] by [delay].
     *
     * Uses [kotlinx.coroutines.delay] so [kotlinx.coroutines.test.runTest]'s
     * virtual time controls delivery — no wall-clock dependency.
     */
    data class DelayAll(
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
     */
    data class ReorderWindow(
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
    data class BufferCeiling(
        val maxOutbound: Int,
    ) : FaultProfile

    /**
     * Close the link with [reason] immediately after the ([frameIndex])-th
     * outbound frame (0-based) is accepted by send.
     *
     * Frames with index < [frameIndex] are sent normally.
     * Frames at or after [frameIndex] see the link already closed.
     */
    data class CloseAt(
        val frameIndex: Int,
        val reason: CloseReason = CloseReason.Normal,
    ) : FaultProfile

    /**
     * Compose multiple profiles. Applied left-to-right in [profiles] order:
     * if any profile drops a frame, later profiles are not consulted for that
     * frame. Delays accumulate.
     */
    data class Composite(
        val profiles: List<FaultProfile>,
    ) : FaultProfile
}

/** Which direction a fault applies to. */
enum class Direction {
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

    // Reorder window buffers (outbound / inbound)
    private val outboundWindow = mutableListOf<ByteArray>()
    private val inboundWindow = mutableListOf<Swatch>()

    private fun nextOutboundIndex(): Int = outboundCount++

    private fun nextInboundIndex(): Int = inboundCount++

    /**
     * Evaluate [profile] for an outbound frame.
     * Returns [OutboundDecision] that tells the link what to do with the send.
     */
    fun evaluateOutbound(payload: ByteArray): OutboundDecision = evaluateOutboundFor(profile, payload, nextOutboundIndex())

    /**
     * Evaluate [profile] for an inbound frame.
     * Returns the list of frames that should be delivered (may be empty,
     * may be reordered, may contain the original plus buffered frames).
     */
    fun evaluateInbound(frame: Swatch): List<Swatch> {
        val index = nextInboundIndex()
        return evaluateInboundFor(profile, frame, index)
    }

    // ── Outbound evaluation ───────────────────────────────────────────────────

    private fun evaluateOutboundFor(
        p: FaultProfile,
        payload: ByteArray,
        index: Int,
    ): OutboundDecision =
        when (p) {
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
                outboundWindow += payload
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
            is FaultProfile.Composite -> evaluateCompositeOutbound(p.profiles, payload, index)
        }

    private fun flushOutboundWindow(p: FaultProfile.ReorderWindow): List<ByteArray> {
        val rng = outboundRandom ?: Random(p.seed).also { outboundRandom = it }
        val shuffled = outboundWindow.toMutableList().also { it.shuffle(rng) }
        outboundWindow.clear()
        return shuffled
    }

    private fun evaluateCompositeOutbound(
        profiles: List<FaultProfile>,
        payload: ByteArray,
        index: Int,
    ): OutboundDecision {
        var current: OutboundDecision = OutboundDecision.Send(payload)
        var accumulatedDelay = Duration.ZERO
        for (p in profiles) {
            val decision = evaluateOutboundFor(p, (current as? OutboundDecision.Send)?.payload ?: payload, index)
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

    /** Flush a batch of reordered frames. */
    data class SendBurst(
        val payloads: List<ByteArray>,
    ) : OutboundDecision

    /** Close the link with this reason instead of sending. */
    data class CloseLink(
        val reason: CloseReason,
    ) : OutboundDecision
}
