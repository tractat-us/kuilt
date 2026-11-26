package us.tractat.kuilt.heddle

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import us.tractat.kuilt.crdt.CanonicalMapSerializer
import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.timesWide

/**
 * What a [FairShareAllocator] remembers between calls, as a value: the wake clamps it has
 * computed, the demanding state each edge was last observed in (design §7.2, issues #1695,
 * #1714), and each parent's net-service clock with the departures read against it (#3376).
 *
 * A scheduler that runs in one process and outlives a restart needs this. Without it a restarted
 * allocator has observed nothing, so every seated child it finds asking is a waker it clamps to
 * the front, plus its edge's outstanding (the restart rule, design §7.2, #3238, #3721). That denies idle credit, but it costs every such child its lag: a child
 * that paused just before the restart comes back clamped to the front, not
 * moved by its parent's clock, so the lag a short burst left is forgiven, and a child that was owed
 * service loses what it was owed. So the allocator exports its memory through
 * [FairShareAllocator.memory] and takes it back through its constructor, and a caller that persists
 * its ledger persists this beside it.
 *
 * It is also how a caller asks a question without changing the answer: a throwaway allocator
 * built over a copy of this value makes the same picks the real one would, and the real one has
 * not moved.
 *
 * It is **scheduler-local, never replicated**. Two peers may hold different memories, and that only
 * reorders their grants: a wake offset can give a turn up, never create entitlement. Nothing here
 * merges.
 *
 * The maps are keyed by attachment id across the whole tree, the clocks by parent. They encode through
 * [CanonicalMapSerializer], so two equal memories encode to the same bytes whatever order the
 * allocator's own hash maps happen to hold them in. The contents are deliberately not public: the
 * only things a caller does with a memory are to keep it and to hand it back.
 *
 * Each wake clamp is kept in ticks of its edge's own grid, wide (#3353): a waker idle for long
 * beside a busy sibling is clamped the whole way up, and that can be past `Long`.
 * It encodes as its two words, like a [Gauge]'s ticks ([WideTicksSerializer]). A clamp of zero is
 * absent, since an edge reads a missing one as zero, and a memory holding one is refused.
 *
 * The clock is the virtual time a parent's asking children have kept, issued less refunded, per
 * unit of their weight, in clock ticks, 720,720 to one tick of a weight-1 child (a step that does
 * not divide evenly rounds up, by under a clock tick); `served` is where each child's
 * `baseIssued − returned` stood when its parent's clock last ran; and `departures` is where the clock
 * stood when this peer saw a child stop asking. A clock can run backward on a round whose refunds
 * outweigh its grants, so it, and a departure read off it, are signed ([SignedWideTicksSerializer]).
 * `shiftRemainders` is how far each child's shifts so far have run past its parent's clock, under
 * one of its ticks ([ShiftRemainder]): a returner's next shift takes it off, so the rounding never
 * adds up (#3416 review). Each of the four is elided when empty, so a memory that has run no clock
 * encodes as before.
 *
 * Its encoded shape is part of [LocalHeddleSnapshot]'s format 1, which changed it in place for
 * the wide clamps. A field added here takes a default, and any other change needs a frozen
 * surrogate first.
 */
@Serializable
public class AllocatorMemory internal constructor(
    @Serializable(with = CanonicalMapSerializer::class)
    internal val wakeOffsets: Map<AttachmentId, @Serializable(with = WideTicksSerializer::class) Int128>,
    @Serializable(with = CanonicalMapSerializer::class)
    internal val demandingObserved: Map<AttachmentId, Boolean>,
    @Serializable(with = CanonicalMapSerializer::class)
    internal val clocks: Map<GroupId, @Serializable(with = SignedWideTicksSerializer::class) Int128> = emptyMap(),
    @Serializable(with = CanonicalMapSerializer::class)
    internal val served: Map<AttachmentId, Long> = emptyMap(),
    @Serializable(with = CanonicalMapSerializer::class)
    internal val departures: Map<AttachmentId, @Serializable(with = SignedWideTicksSerializer::class) Int128> = emptyMap(),
    @Serializable(with = CanonicalMapSerializer::class)
    internal val shiftRemainders: Map<AttachmentId, ShiftRemainder> = emptyMap(),
) {
    init {
        // An edge reads a missing offset as zero, so a stored zero would spell one state two ways.
        // Decoding it is refused as corrupt, as a zero shift remainder is.
        require(wakeOffsets.values.none { it == Int128.ZERO }) {
            "a wake offset is positive, or absent, was zero on ${wakeOffsets.filterValues { it == Int128.ZERO }.keys.map { it.value }}"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is AllocatorMemory &&
            wakeOffsets == other.wakeOffsets &&
            demandingObserved == other.demandingObserved &&
            clocks == other.clocks &&
            served == other.served &&
            departures == other.departures &&
            shiftRemainders == other.shiftRemainders

    override fun hashCode(): Int {
        var h = wakeOffsets.hashCode()
        h = 31 * h + demandingObserved.hashCode()
        h = 31 * h + clocks.hashCode()
        h = 31 * h + served.hashCode()
        h = 31 * h + departures.hashCode()
        return 31 * h + shiftRemainders.hashCode()
    }

    override fun toString(): String =
        "AllocatorMemory(wakeOffsets=${wakeOffsets.size}, demandingObserved=${demandingObserved.size}, " +
            "clocks=${clocks.size}, departures=${departures.size}, shiftRemainders=${shiftRemainders.size})"

    public companion object {
        /** A memory that has observed nothing: what a brand-new allocator starts from. */
        public val EMPTY: AllocatorMemory = AllocatorMemory(emptyMap(), emptyMap())
    }
}

/** A non-negative wide count in its two words, each elided at zero: [nonNegativeWide]'s wire shape. */
@Serializable
private class WideTicksWords(val high: Long = 0L, val low: Long = 0L)

/**
 * An [Int128] that is never negative, as its two words `{high, low}`, the low one unsigned and each
 * elided at zero. Decoding a negative one throws, the read-path guard ([nonNegativeWide]).
 */
internal object WideTicksSerializer : KSerializer<Int128> {
    override val descriptor: SerialDescriptor = WideTicksWords.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Int128) {
        require(value >= Int128.ZERO) { "a wide count must be non-negative, was $value" }
        encoder.encodeSerializableValue(WideTicksWords.serializer(), WideTicksWords(value.high, value.low))
    }

    override fun deserialize(decoder: Decoder): Int128 {
        val words = decoder.decodeSerializableValue(WideTicksWords.serializer())
        return nonNegativeWide(words.high, words.low, "a wake clamp")
    }
}

/**
 * How far a child's shifts so far have run past its parent's net-service clock (#3416 review):
 * [numerator] over `CLOCK_GRID · `[denominator] of one of the child's ticks, in `(0, 1)`, where
 * [denominator] is its edge's weight's denominator: fixed for the edge, since a reweight is a new
 * edge, and checked against it wherever a remainder is read. A shift is a whole number of
 * ticks and the clock's run since the child left can be a fraction of one, so a shift rounds up by
 * this much, and the next one takes it off before it rounds: the child's total over-shift stays
 * under one tick, never credit, however many times it returns. A remainder of zero is absent.
 */
@Serializable
internal class ShiftRemainder(
    @Serializable(with = WideTicksSerializer::class) val numerator: Int128,
    val denominator: Long,
) {
    init {
        require(denominator > 0L) { "a shift remainder's denominator must be positive, was $denominator" }
        require(numerator > Int128.ZERO) { "a shift remainder is positive, or absent, was $numerator" }
        require(numerator < CLOCK_GRID.timesWide(denominator)) {
            "a shift remainder is under one tick, was $numerator over $CLOCK_GRID · $denominator"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ShiftRemainder && numerator == other.numerator && denominator == other.denominator

    override fun hashCode(): Int = 31 * numerator.hashCode() + denominator.hashCode()

    override fun toString(): String = "ShiftRemainder($numerator / ($CLOCK_GRID · $denominator))"
}

/** A signed wide count in its two words, each elided at zero: [SignedWideTicksSerializer]'s wire shape. */
@Serializable
private class SignedWideTicksWords(val high: Long = 0L, val low: Long = 0L)

/**
 * An [Int128] of either sign, as its two words `{high, low}` in two's complement, the low one
 * unsigned and each elided at zero: a net-service clock, which a refund can run backward (#3376).
 */
internal object SignedWideTicksSerializer : KSerializer<Int128> {
    override val descriptor: SerialDescriptor = SignedWideTicksWords.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Int128) {
        encoder.encodeSerializableValue(SignedWideTicksWords.serializer(), SignedWideTicksWords(value.high, value.low))
    }

    override fun deserialize(decoder: Decoder): Int128 {
        val words = decoder.decodeSerializableValue(SignedWideTicksWords.serializer())
        return Int128.fromTwosComplement(words.high, words.low)
    }
}
