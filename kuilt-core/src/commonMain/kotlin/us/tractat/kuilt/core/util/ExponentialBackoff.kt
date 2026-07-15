package us.tractat.kuilt.core.util

import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration

/**
 * Full-jitter exponential backoff. Stateless: [delay] is a pure function of the 0-based [attempt],
 * so the caller keeps the attempt counter (it resets naturally each retry episode). Randomness is an
 * injected dependency — pass a seeded [Random] in tests for determinism.
 *
 * The delay for an attempt is uniform in `[0, min(cap, base · factor^attempt))` ("full jitter") —
 * decorrelating many simultaneous retriers so a shared-transport blip that flaps N edges at once does
 * not produce a synchronized reconnect storm.
 */
public class ExponentialBackoff(
    private val base: Duration,
    private val cap: Duration,
    private val factor: Double = 2.0,
    private val random: Random,
) {
    init {
        require(base > Duration.ZERO) { "base must be positive, was $base" }
        require(cap >= base) { "cap ($cap) must be >= base ($base)" }
        require(factor >= 1.0) { "factor must be >= 1.0, was $factor" }
    }

    /** Full-jitter delay for [attempt] (0-based). Never negative; clamped to [cap]; overflow-safe. */
    public fun delay(attempt: Int): Duration {
        // base * factor^attempt overflows to Duration.INFINITE for large attempt; minOf clamps it.
        val ceiling = minOf(base * factor.pow(attempt), cap)
        return ceiling * random.nextDouble()
    }
}
