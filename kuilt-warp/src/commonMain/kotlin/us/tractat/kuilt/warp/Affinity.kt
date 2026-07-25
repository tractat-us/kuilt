package us.tractat.kuilt.warp

import kotlinx.serialization.Serializable

/**
 * A **location-eligibility predicate** — the "can I execute *here*" question, riding the
 * opaque task envelope (H8, design §14.6, Model A).
 *
 * An `Affinity` is a small **serializable expression** over the capability tokens a peer
 * advertises in its [CapSet]. It rides the [TaskDescriptor] the same way H6's [Lane] does:
 * warp core carries it and evaluates it with [matches], but assigns the tokens *no* meaning
 * and depends on no module that interprets them. Placement then consistent-hashes over the
 * **eligible subset** of the roster — the peers whose advertised [CapSet] satisfies this
 * predicate — rather than the whole roster (see [TaskRing.owner]). Every peer computes the
 * same eligible set from the same convergent capability view, so determinism holds for the
 * same reason the ring does.
 *
 * Eligibility is **independent of entitlement**: this type lives in `:kuilt-warp` and names no
 * `:kuilt-heddle` type. It *composes* with a lane (a task may carry both an `Affinity` and a
 * [Lane]) but does not live inside one — eligibility answers *where*, a lane answers *how much*.
 *
 * Because it is serializable it cannot capture a Kotlin lambda; instead it is a closed algebra
 * of leaf predicates ([Has], [Attr]) and combinators ([And], [Or], [Not]), with [Anywhere] as
 * the no-requirement default. Build predicates fluently:
 *
 * ```kotlin
 * val where = Affinity.has("GPU") and Affinity.attr("region", "us-east")
 * ```
 *
 * @sample us.tractat.kuilt.warp.sampleAffinity
 */
@Serializable
public sealed interface Affinity {
    /** Whether a peer advertising [caps] satisfies this predicate. Pure and deterministic. */
    public fun matches(caps: CapSet): Boolean

    /**
     * The no-requirement default: every peer is eligible, so placement is over the whole roster —
     * warp's pre-H8 behaviour, bit-for-bit. This is the [TaskDescriptor.affinity] default; a
     * descriptor that never sets an affinity is byte-for-byte identical on the wire to a
     * pre-H8 descriptor (CBOR omits a field at its default).
     */
    @Serializable
    public data object Anywhere : Affinity {
        override fun matches(caps: CapSet): Boolean = true
    }

    /** Satisfied when the peer advertises boolean capability [token] (`caps.has(token)`). */
    @Serializable
    public data class Has(public val token: String) : Affinity {
        override fun matches(caps: CapSet): Boolean = caps.has(token)
    }

    /** Satisfied when the peer advertises keyed capability [key] with exactly [value]. */
    @Serializable
    public data class Attr(public val key: String, public val value: String) : Affinity {
        override fun matches(caps: CapSet): Boolean = caps.attr(key) == value
    }

    /** Satisfied when **every** term is satisfied. An empty [terms] is vacuously true. */
    @Serializable
    public data class And(public val terms: List<Affinity>) : Affinity {
        override fun matches(caps: CapSet): Boolean = terms.all { it.matches(caps) }
    }

    /** Satisfied when **any** term is satisfied. An empty [terms] is vacuously false. */
    @Serializable
    public data class Or(public val terms: List<Affinity>) : Affinity {
        override fun matches(caps: CapSet): Boolean = terms.any { it.matches(caps) }
    }

    /** Satisfied when [term] is **not** satisfied. */
    @Serializable
    public data class Not(public val term: Affinity) : Affinity {
        override fun matches(caps: CapSet): Boolean = !term.matches(caps)
    }

    public companion object {
        /** A predicate requiring boolean capability [token]. */
        public fun has(token: String): Affinity = Has(token)

        /** A predicate requiring keyed capability [key] to equal [value]. */
        public fun attr(key: String, value: String): Affinity = Attr(key, value)
    }
}

/** Conjunction: `a and b` is satisfied when both [this] and [other] are. */
public infix fun Affinity.and(other: Affinity): Affinity =
    Affinity.And(flatten<Affinity.And>(this, other) { it.terms })

/** Disjunction: `a or b` is satisfied when either [this] or [other] is. */
public infix fun Affinity.or(other: Affinity): Affinity =
    Affinity.Or(flatten<Affinity.Or>(this, other) { it.terms })

/** Negation: `not(a)` is satisfied when [affinity] is not. */
public fun not(affinity: Affinity): Affinity = Affinity.Not(affinity)

/**
 * Return a copy of this descriptor requiring [affinity] at its execution site — the
 * producer-side eligibility tagging step, the sibling of `TaskDescriptor.inLane(...)`.
 * Everything else (op, args, trace, pin, lane) is preserved, so eligibility composes with a
 * lane. This is the shipped `.where { }` surface: build a [TaskDescriptor], tag it with
 * [where], and enqueue it on a [WarpNode]. Placement then hashes over the eligible subset.
 */
public fun TaskDescriptor.where(affinity: Affinity): TaskDescriptor =
    TaskDescriptor(
        op = op,
        args = args,
        traceparent = traceparent,
        pinnedOwner = pinnedOwner,
        lane = lane,
        affinity = affinity,
    )

/** Flatten same-kind combinators so `a and b and c` is one [Affinity.And], not a nested tree. */
private inline fun <reified T : Affinity> flatten(a: Affinity, b: Affinity, terms: (T) -> List<Affinity>): List<Affinity> {
    val left = if (a is T) terms(a) else listOf(a)
    val right = if (b is T) terms(b) else listOf(b)
    return left + right
}
