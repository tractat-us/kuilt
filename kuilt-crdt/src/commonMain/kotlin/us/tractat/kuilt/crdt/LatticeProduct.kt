package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * The componentwise product of two join-semilattices.
 *
 * Given two [Quilted] types [A] and [B], the pair `(a, b)` forms a new
 * join-semilattice whose join ([piece]) is applied to each component
 * independently:
 *
 * ```
 * (a₁, b₁).piece(a₂, b₂) = (a₁.piece(a₂), b₁.piece(b₂))
 * ```
 *
 * This is monotone by construction: componentwise join of two semilattices is
 * itself a semilattice. The three lattice laws follow directly from the laws
 * already satisfied by [A] and [B]:
 *
 * - **idempotent**  `p.piece(p) == p`
 * - **commutative** `p.piece(q) == q.piece(p)`
 * - **associative** `p.piece(q).piece(r) == p.piece(q.piece(r))`
 *
 * **Use on the coordination-free path:** combine two [CoordinationFree] values
 * into a single atomic snapshot via the [zip][us.tractat.kuilt.warp.zip]
 * combinator in `:kuilt-warp`.
 *
 * @param A the first component lattice type.
 * @param B the second component lattice type.
 * @property first the first component.
 * @property second the second component.
 *
 * @sample us.tractat.kuilt.crdt.sampleLatticeProduct
 */
@Serializable
public class LatticeProduct<A : Quilted<A>, B : Quilted<B>>(
    public val first: A,
    public val second: B,
) : Quilted<LatticeProduct<A, B>> {

    /** Componentwise join: each component absorbs the corresponding component of [other]. */
    override fun piece(other: LatticeProduct<A, B>): LatticeProduct<A, B> =
        LatticeProduct(first.piece(other.first), second.piece(other.second))

    /**
     * The union of both components' causal dots.
     *
     * For CRDTs that carry per-op [Dot]s (e.g. [Rga]), the product exposes the
     * full delivered set: the union of [A]'s dots and [B]'s dots. CRDTs that
     * return the default empty set (e.g. [GCounter]) contribute nothing — the
     * union stays empty.
     */
    override fun causalDots(): Set<Dot> = first.causalDots() + second.causalDots()

    override fun equals(other: Any?): Boolean =
        other is LatticeProduct<*, *> && first == other.first && second == other.second

    override fun hashCode(): Int = 31 * first.hashCode() + second.hashCode()

    override fun toString(): String = "LatticeProduct($first, $second)"

    public companion object {
        /** Construct a [LatticeProduct] from a pair of [Quilted] values. */
        public fun <A : Quilted<A>, B : Quilted<B>> of(first: A, second: B): LatticeProduct<A, B> =
            LatticeProduct(first, second)
    }
}
