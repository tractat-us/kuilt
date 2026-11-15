package us.tractat.kuilt.otel

/**
 * How long an exporter waits, counted in turns, before it reads again a persisted state whose read
 * the store refused: after `k` refused reads in a row, the next `2^k − 1` turns refuse without
 * touching the store (#3404, #3436).
 *
 * The exporters fail closed on a refused read: a turn reads again first, and writes nothing while
 * the read is refused. Retried on every turn, a long outage made every turn pay the store's full
 * timeout. The window is counted in turns because no exporter owns a clock.
 *
 * Immutable, so a caller can decide on one value and consume it with an identity compare under its
 * own lock: `if (state === seen) state = seen.skipped()`. A turn that raced a newer window then
 * spends nothing of it.
 *
 * [WarpLogRecordExporter] keeps its own copy of these few lines, the shape this type was taken
 * from; [WarpMetricExporter] holds one of these per metric kind.
 *
 * @property cause The last refusal's cause, which a skipped turn reports as its own.
 * @property refusals `k`: refused reads in a row, capped at [SATURATED_REFUSALS].
 * @property skipTurns The turns still to skip before the next read.
 */
internal class RefusedReadBackoff private constructor(
    val cause: Throwable,
    val refusals: Int,
    val skipTurns: Int,
) {
    /** This window with one skipped turn spent. */
    fun skipped(): RefusedReadBackoff = RefusedReadBackoff(cause, refusals, skipTurns - 1)

    internal companion object {
        /**
         * The window after a refused read whose predecessor was [prior], `null` when the last read
         * landed or none has run: one more refusal in a row, `k`, and a window of `2^k − 1` turns.
         * `k` stops at [SATURATED_REFUSALS], which is the window's one cap.
         */
        fun after(prior: RefusedReadBackoff?, cause: Throwable): RefusedReadBackoff {
            val refusals = minOf((prior?.refusals ?: 0) + 1, SATURATED_REFUSALS)
            return RefusedReadBackoff(cause, refusals, (1 shl refusals) - 1)
        }

        /**
         * Where `k` stops, so the longest run of turns that refuse without reading is `2^6 − 1 = 63`,
         * reached on the sixth refusal in a row. A long outage pays one read per 64 turns. The price
         * is on the far side of the outage: a store that heals inside a window is seen up to 63
         * turns late, and is not bounded in time, since a turn comes when the app makes one.
         */
        const val SATURATED_REFUSALS = 6
    }
}
