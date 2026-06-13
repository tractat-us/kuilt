package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * An entry of an [ORMap]: a [DotSet] of "presence tags" plus a [Quilted] value.
 * The tag set is the observed-remove handle for the *key*; the value merges via
 * its own [Quilted.piece] when both sides hold it.
 */
@Serializable
public class ORMapEntry<S : Quilted<S>>(
    public val tags: DotSet,
    public val value: S,
) : DotStore<ORMapEntry<S>> {

    override val dots: Set<Dot> get() = tags.dots
    override val isBottom: Boolean get() = tags.isBottom

    /**
     * The structural empty: empty tag set, same value shape. Used by [DotMap.join]
     * when this entry appears only on one side — causal filtering then decides
     * whether the tags survive, keeping the value iff they do.
     */
    override val empty: ORMapEntry<S> get() = ORMapEntry(DotSet(), value)

    override fun join(
        other: ORMapEntry<S>,
        context: DotContext,
        otherContext: DotContext,
    ): ORMapEntry<S> {
        val joinedTags = tags.join(other.tags, context, otherContext)
        val joinedValue = value.piece(other.value)
        return ORMapEntry(joinedTags, joinedValue)
    }

    override fun equals(other: Any?): Boolean =
        other is ORMapEntry<*> && tags == other.tags && value == other.value

    override fun hashCode(): Int = 31 * tags.hashCode() + value.hashCode()

    override fun toString(): String = "ORMapEntry(tags=$tags, value=$value)"
}

/**
 * An **observed-remove map**: keys [K] each carry a [Quilted] value [S] that
 * merges via its own [Quilted.piece]. A concurrent `put` of the same key
 * survives a `remove` (add-wins on the key), and when both replicas hold the
 * key the values are pieced together.
 *
 * Built over `Causal<DotMap<K, ORMapEntry<S>>>`: each entry's `tags` is the
 * observed-remove handle for the key; the value lives alongside and is merged
 * by its own `piece`.
 *
 * @sample us.tractat.kuilt.crdt.sampleORMap
 */
@Serializable
public class ORMap<K, S : Quilted<S>> private constructor(
    private val causal: Causal<DotMap<K, ORMapEntry<S>>>,
) : Quilted<ORMap<K, S>> {

    /** Currently-present keys. */
    public val keys: Set<K> get() = causal.store.entries.keys

    /** The value for [key], or `null` if absent. */
    public operator fun get(key: K): S? = causal.store.entries[key]?.value

    /**
     * Put [value] under [key], minting a fresh add-tag on behalf of [replica].
     * If the key already exists locally, the new value is `piece`d with the
     * existing one (so a put is additive, not destructive, for the value lattice).
     */
    public fun put(replica: ReplicaId, key: K, value: S): ORMap<K, S> {
        val dot = causal.context.nextDot(replica)
        val existing = causal.store.entries[key]
        val mergedValue = existing?.value?.piece(value) ?: value
        val newEntry = ORMapEntry(DotSet(setOf(dot)), mergedValue)
        return ORMap(
            Causal(
                DotMap(causal.store.entries + (key to newEntry)),
                causal.context.add(dot),
            ),
        )
    }

    /** Remove [key]: drop its current tags. Context retains them — propagates on merge. */
    public fun remove(key: K): ORMap<K, S> {
        if (key !in causal.store.entries) return this
        return ORMap(Causal(DotMap(causal.store.entries - key), causal.context))
    }

    /** The causal merge. */
    override fun piece(other: ORMap<K, S>): ORMap<K, S> = ORMap(causal.piece(other.causal))

    override fun equals(other: Any?): Boolean = other is ORMap<*, *> && causal == other.causal
    override fun hashCode(): Int = causal.hashCode()
    override fun toString(): String = "ORMap(${causal.store.entries.mapValues { it.value.value }})"

    public companion object {
        /** The empty map. */
        public fun <K, S : Quilted<S>> empty(): ORMap<K, S> =
            ORMap(Causal(DotMap(), DotContext.EMPTY))
    }
}
