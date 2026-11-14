package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.math.Rounding
import us.tractat.kuilt.math.timesWide

/**
 * The three consistent pieces of the temporary fairness-error bound at one parent
 * (design §8.2). While peers are unreconciled — mid-partition, before anti-entropy
 * heals — different peers can independently steer entitlement among a parent's
 * children, so the *observed* fairness error is bounded, not zero. This exposes the
 * bound's pieces as derived metrics; the module "must not claim tighter than it proves"
 * (design §8.2), so all three are reported and the tests assert they stay ordered
 * `observedDeviation ≤ currentBound ≤ configuredWorstCase`.
 *
 * ```text
 * error(p) ≤ Σ over unreconciled peers s of B(p, s) + discretization
 * ```
 *
 * @property parent the parent group these metrics describe.
 * @property configuredWorstCase the coarse config-only bound `n·E + quantum`: `n` peers
 *   each capped at `E = maxHoldingsPerPeer`, plus one quantum of discretization. The
 *   most the error could ever be under the configured caps, independent of state.
 * @property currentBound the state-dependent bound from *live* holdings: the entitlement
 *   currently sitting at [parent] across all peers (each term spendable — i.e.
 *   independently steerable among [parent]'s children), plus discretization, never
 *   exceeding [configuredWorstCase]. Falls as peers spend down and reconcile.
 * @property observedDeviation the fairness error actually present now: the largest gap,
 *   over [parent]'s **demanding** active children, between a child's *ideal* committed
 *   service (its share of `totalCommitted` **among the demanding children**) and its
 *   *actual* committed service, in whole service units (rounded up). A child advertising
 *   no demand is not owed service (design §8.2's fairness-error model is over the
 *   *active/backlogged* set), so it is excluded — otherwise a fair scheduler legitimately
 *   serving only the demanding child would read as a bound violation. Zero when the
 *   demanding children sit exactly on their weight ratio.
 */
public data class BoundMetrics(
    public val parent: GroupId,
    public val configuredWorstCase: Long,
    public val currentBound: Long,
    public val observedDeviation: Long,
) {
    /** True when the three pieces are ordered as design §8.2 requires. */
    public val isConsistent: Boolean
        get() = observedDeviation <= currentBound && currentBound <= configuredWorstCase

    public companion object {
        /**
         * Derive the bound metrics at [parent] from the merged [ledger], the live [roster]
         * (every peer that can steer entitlement here — including self **and** currently
         * unreachable peers, since a partitioned peer is exactly the divergence source the
         * bound must count), the set of [demanding] child edges (those advertising live
         * demand), and the [config] caps. Pure — a function of the arguments, computed
         * identically on every peer that has merged the same state.
         *
         * @throws ArithmeticException when the demanding children's weight sum, or one demanding
         * child's reduced share of it, does not fit in a `Long`. Either needs the weights' common
         * denominator `L` or their numerator sum `S` over it to pass `Long`. `LocalHeddle`'s
         * apply-time weight budget refuses such weight sets; `BoundMetrics` is still reachable with
         * them through a replicated ledger (`HeddleNode`).
         */
        public fun at(
            ledger: EntitlementLedger,
            parent: GroupId,
            roster: Set<ReplicaId>,
            demanding: Set<AttachmentId>,
            config: HeddleConfig,
        ): BoundMetrics {
            val discretization = config.policy.quantum
            val peerCount = maxOf(1, roster.size).toLong()
            val configuredWorstCase =
                checkedAdd(checkedMul(peerCount, config.maxHoldingsPerPeer), discretization)

            var steerable = 0L
            for (r in roster) {
                val h = ledger.holdings(parent, r)
                if (h > 0L) steerable = checkedAdd(steerable, h)
            }
            val currentBound = minOf(configuredWorstCase, checkedAdd(steerable, discretization))

            return BoundMetrics(
                parent = parent,
                configuredWorstCase = configuredWorstCase,
                currentBound = currentBound,
                observedDeviation = observedDeviation(ledger, parent, demanding),
            )
        }

        /**
         * The current fairness error at [parent]: `max_i |idealCommitted_i − actualCommitted_i|`
         * over the **demanding** active children `i` (those in [demanding]), in whole service
         * units (rounded up). The ideal is a child's weight-share of the service committed
         * *among the demanding set* — a non-demanding child is not owed service (EEVDF measures
         * lag over the backlogged set, design §8.2), so it is excluded from both the total and
         * the max. Exact rational throughout — no floating point — so every peer computes the
         * same value from the same state.
         *
         * @throws ArithmeticException when the demanding weight sum `W`, or a demanding child's
         * reduced share `w_i/W`, does not fit in a `Long`. Both need `L`, the weights' common
         * denominator, or `S`, their numerators' sum over `L`, to pass `Long`: `W` reduced is `S/L`, and a
         * share reduced is at most `S` over at most `S`. `L` alone can do it: weights `1/(2³²+1)` and
         * `1/(2³²−1)` give `S = 2³³` but `L = 2⁶⁴−1`. The scheduler forms the same `L` and `S` for a
         * front over these children, and `VirtualTime.mean` can throw there too (`pick` and
         * `frontTime` take their mean over a different set of children). `W` can still fit: `2⁵⁷/3` and `1/192`
         * sum to `S = 2⁶³+1` over `L = 192`, `W` reduces to `((2⁶³+1)/3)/64`, and the first share is
         * `2⁶³/(2⁶³+1)`. A quotient whose reduced form fits never throws.
         */
        private fun observedDeviation(
            ledger: EntitlementLedger,
            parent: GroupId,
            demanding: Set<AttachmentId>,
        ): Long {
            val children = ledger.activeChildren(parent).filter { it.attachment in demanding }
            if (children.isEmpty()) return 0L

            // Total committed service and total weight over the DEMANDING active children.
            var totalCommitted = 0L
            var weightSum = Rational.ZERO
            val weights = HashMap<AttachmentId, Weight>()
            for (c in children) {
                val w = ledger.record(c.attachment)?.weight ?: continue
                weights[c.attachment] = w
                totalCommitted = checkedAdd(totalCommitted, c.committedService)
                weightSum += Rational.of(w.numerator, w.denominator)
            }
            if (totalCommitted == 0L || weightSum == Rational.ZERO) return 0L

            var maxDeviation = 0L
            for (c in children) {
                val w = weights[c.attachment] ?: continue
                val share = Rational.of(w.numerator, w.denominator) / weightSum
                maxDeviation = maxOf(maxDeviation, gap(share, totalCommitted, c.committedService))
            }
            return maxDeviation
        }

        /**
         * `⌈|share · total − committed|⌉`, for a reduced `share = p/q`: the numerator
         * `|p·total − committed·q|` is formed in 128 bits and divided by `q` rounding up, so no
         * intermediate decides whether it fits — only the answer does (#3319). Multiplying the
         * share by a lifetime total as a `Long` rational threw once `p · total / gcd(total, q)` passed
         * `2⁶³`, and on every call after that: a `31 : 1` split did at a total of `2⁵⁹ − 1`, which two
         * edges can carry.
         *
         * The answer always fits when `0 ≤ committed ≤ total` and `0 ≤ share ≤ 1`, which holds for
         * every demanding child of a ledger whose committed service is non-negative: the gap is then
         * at most `total`. Were a committed service ever negative the gap could pass `Long`, and that
         * throws, as an overflow anywhere else in the scheduler does.
         */
        private fun gap(share: Rational, total: Long, committed: Long): Long {
            val ideal = share.numerator.timesWide(total)
            val actual = committed.timesWide(share.denominator)
            val difference = if (ideal >= actual) ideal - actual else actual - ideal
            return difference.divideOrNull(share.denominator, Rounding.CEILING)
                ?: throw ArithmeticException("fairness gap does not fit in a Long: $share · $total − $committed")
        }
    }
}
