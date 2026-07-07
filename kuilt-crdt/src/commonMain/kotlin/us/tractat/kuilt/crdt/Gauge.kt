package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * A gauge: the current level of something — temperature, queue depth, players
 * online, memory in use. Where a counter answers "how many so far?", a gauge
 * answers "what is it *right now*?" — the reading that a `Sum` metric cannot
 * express, because levels go up *and* down and only the latest reading matters.
 *
 * **As a CRDT.** The mergeable form of "latest reading" is last-writer-wins:
 * the state is a single observation tagged `(timestamp, replicaId)`, and
 * [piece] keeps the larger tag — literally the [LWWRegister] join, which this
 * type wraps. Ties on `timestamp` break lexicographically on `replicaId`, so
 * the merge is deterministic regardless of arrival order; the lattice laws
 * (idempotent, commutative, associative) are inherited from [LWWRegister].
 * Many peers can observe independently and merge in any order, with any
 * duplication, and converge on the newest observation.
 *
 * **Time is a dependency.** [observe] takes the timestamp as a parameter —
 * this type never reads a wall clock. Use whatever monotonic source the rest
 * of your pipeline uses, and keep `(replica, timestamp)` pairs unique per
 * write (the [LWWRegister.set] tag-uniqueness contract). As with any LWW type,
 * clock skew between peers silently favours the faster clock; pair with a
 * hybrid logical clock above this layer if that matters.
 *
 * **Mutator shape.** [observe] returns a full new state — for an LWW type the
 * whole state *is* the minimal delta (one tagged cell). Apply locally with
 * `gauge = gauge.piece(gauge.observe(replica, ts, v))` so a belated
 * older-timestamp observation can never regress a newer one.
 *
 * **OTel interop.** The state is structurally an OTLP `NumberDataPoint` under
 * a `Gauge` metric: [value] plus [timestamp]. The OTLP mapping itself lives
 * with the metrics exporter, not in this module.
 *
 * @sample us.tractat.kuilt.crdt.sampleGauge
 */
@Serializable
public class Gauge private constructor(
    private val register: LWWRegister<Double>,
) : Quilted<Gauge> {

    /** The latest observed value, or `null` if nothing has been observed yet. */
    public val value: Double? get() = register.value

    /** The winning observation's timestamp, or `null` if nothing has been observed yet. */
    public val timestamp: Long? get() = if (register.value == null) null else register.timestamp

    /**
     * Record that [replica] observed [value] at [timestamp]. Returns a new
     * state carrying just this tagged observation; absorb it with [piece]
     * (`gauge = gauge.piece(gauge.observe(replica, ts, v))`) so an
     * older-timestamp observation never overwrites a newer one.
     *
     * The `(replica, timestamp)` pair must uniquely identify this observation —
     * see [LWWRegister.set] for the tag-uniqueness contract.
     *
     * @throws IllegalArgumentException if [value] is NaN or infinite.
     */
    public fun observe(replica: ReplicaId, timestamp: Long, value: Double): Gauge {
        require(value.isFinite()) { "Gauge observations must be finite, was $value" }
        return Gauge(register.set(replica, timestamp, value))
    }

    /** The join: the observation with the larger `(timestamp, replicaId)` tag wins. */
    override fun piece(other: Gauge): Gauge = Gauge(register.piece(other.register))

    override fun equals(other: Any?): Boolean = other is Gauge && register == other.register

    override fun hashCode(): Int = register.hashCode()

    override fun toString(): String = "Gauge(value=$value, ts=$timestamp)"

    public companion object {
        /** An empty gauge. Any [observe] supersedes it. */
        public fun empty(): Gauge = Gauge(LWWRegister.empty())
    }
}
