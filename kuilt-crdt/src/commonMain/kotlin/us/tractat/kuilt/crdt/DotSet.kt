package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * The simplest [DotStore]: a bare set of dots carrying no payload. Presence is
 * "non-empty". As `Causal<DotSet>` it is an enable-wins flag / set of opaque
 * adds; nested inside a DotMap it becomes the per-element store of an OR-Set.
 *
 * Serialized by [DotSetSerializer], which emits [dots] sorted by [Dot] so that
 * two replicas at the same logical state produce identical bytes regardless of
 * delivery order (issue #713).
 */
@Serializable(with = DotSetSerializer::class)
public class DotSet(override val dots: Set<Dot> = emptySet()) : DotStore<DotSet> {

    override val empty: DotSet get() = DotSet()

    override fun join(other: DotSet, context: DotContext, otherContext: DotContext): DotSet {
        val kept = LinkedHashSet<Dot>()
        for (dot in dots) {
            if (dot in other.dots || !otherContext.contains(dot)) kept.add(dot)
        }
        for (dot in other.dots) {
            if (!context.contains(dot)) kept.add(dot)
        }
        return DotSet(kept)
    }

    override fun equals(other: Any?): Boolean = other is DotSet && dots == other.dots
    override fun hashCode(): Int = dots.hashCode()
    override fun toString(): String = "DotSet($dots)"
}
