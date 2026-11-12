package us.tractat.kuilt.heddle

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * A non-negative quantity of **service** — the abstract unit the ledger accounts
 * in (CPU-milliseconds, tasks, tokens; whatever the consumer meters). Always
 * `>= 0`; a negative amount of service is meaningless and is rejected at
 * construction rather than allowed to poison the tally.
 *
 * Addition is overflow-checked ([plus] throws on `Long` overflow), so a running
 * total can never silently wrap past [Long.MAX_VALUE].
 */
@Serializable
@JvmInline
public value class ServiceUnits(public val value: Long) : Comparable<ServiceUnits> {
    init {
        require(value >= 0L) { "ServiceUnits must be non-negative, was $value" }
    }

    /** The overflow-checked sum of this and [other]. */
    public operator fun plus(other: ServiceUnits): ServiceUnits =
        ServiceUnits(checkedAdd(value, other.value))

    override fun compareTo(other: ServiceUnits): Int = value.compareTo(other.value)

    public companion object {
        /** No service. */
        public val ZERO: ServiceUnits = ServiceUnits(0L)
    }
}
