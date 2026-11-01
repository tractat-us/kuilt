package us.tractat.kuilt.core

import kotlinx.coroutines.channels.Channel

/** Overflow behaviour when a bounded delivery buffer is full. Mirrors Reactor's `onBackpressure*`. */
public enum class Overflow { SUSPEND, DROP_OLDEST, DROP_LATEST, FAIL }

/**
 * How an in-process fabric buffers frames for one receiver: a bounded [capacity] and an
 * [overflow] strategy. There is deliberately no UNLIMITED option — unbounded delivery is the
 * defect this type exists to make unrepresentable (#701).
 *
 * @param capacity frames buffered per receiver, from 1 up to `Int.MAX_VALUE - 1`. `Int.MAX_VALUE`
 *   is refused because it is `Channel.UNLIMITED`: a channel built with it has no bound and ignores
 *   [overflow], so asking for a very large buffer would quietly ask for an unbounded one.
 * @throws IllegalArgumentException if [capacity] is outside that range.
 */
public data class DeliveryPolicy(
    val capacity: Int = DEFAULT_CAPACITY,
    val overflow: Overflow = Overflow.SUSPEND,
) {
    init {
        require(capacity >= 1) { "capacity must be >= 1, was $capacity" }
        require(capacity != Channel.UNLIMITED) {
            "capacity must be below Channel.UNLIMITED, which builds an unbounded channel, was $capacity"
        }
    }

    public companion object {
        /** Generous default; revisit if a conformance test throttles on it. */
        public const val DEFAULT_CAPACITY: Int = 256

        /** Lossless, ordered, backpressured — the contract-faithful default. */
        public val Reliable: DeliveryPolicy = DeliveryPolicy(overflow = Overflow.SUSPEND)

        /** Lossy: drops the oldest buffered frame instead of blocking. Models a radio/UDP fabric. */
        public val Lossy: DeliveryPolicy = DeliveryPolicy(overflow = Overflow.DROP_OLDEST)

        /** Strict: a full buffer throws [FrameOverflow]. For tests asserting no overflow. */
        public val Strict: DeliveryPolicy = DeliveryPolicy(overflow = Overflow.FAIL)
    }
}

/** Thrown by a [Overflow.FAIL] delivery buffer when a frame arrives and the buffer is full. */
public class FrameOverflow(message: String) : RuntimeException(message)
