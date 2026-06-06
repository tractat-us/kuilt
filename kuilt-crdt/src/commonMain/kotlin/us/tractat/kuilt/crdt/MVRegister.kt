package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * A multi-value register holding the value(s) last written. A single write yields
 * one value; concurrent writes from different replicas are all retained until a
 * later write observes and supersedes them — surfacing the conflict rather than
 * silently picking a winner.
 *
 * Thin wrapper over `Causal<DotFun<V>>`: each write mints a fresh dot → value and
 * drops every dot it has already observed; the causal merge keeps exactly the
 * writes that are mutually concurrent.
 */
@Serializable
public class MVRegister<V> private constructor(
    private val causal: Causal<DotFun<V>>,
) : Quilted<MVRegister<V>> {

    /** The current value(s): one normally, several if concurrent writes are unresolved, none if never written. */
    public val values: Set<V> get() = causal.store.values.values.toSet()

    /** Write [value] on behalf of [replica], superseding every value this register has observed. */
    public fun set(replica: ReplicaId, value: V): MVRegister<V> {
        val dot = causal.context.nextDot(replica)
        return MVRegister(Causal(DotFun(mapOf(dot to value)), causal.context.add(dot)))
    }

    override fun piece(other: MVRegister<V>): MVRegister<V> = MVRegister(causal.piece(other.causal))

    override fun equals(other: Any?): Boolean = other is MVRegister<*> && causal == other.causal
    override fun hashCode(): Int = causal.hashCode()
    override fun toString(): String = "MVRegister($values)"

    public companion object {
        /** A register with no value yet. */
        public fun <V> empty(): MVRegister<V> = MVRegister(Causal(DotFun(), DotContext.EMPTY))
    }
}
