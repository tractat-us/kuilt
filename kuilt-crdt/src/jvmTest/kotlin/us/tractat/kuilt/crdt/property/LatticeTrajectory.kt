package us.tractat.kuilt.crdt.property

import us.tractat.kuilt.crdt.Quilted

/**
 * Assert `piece` is associative over every ordered triple drawn from **one replica's running
 * history** — its state after op 0, after op 1, after op 2, and so on.
 *
 * **Why this exists alongside `pieceIsAssociative`.** Every suite in this package generates its
 * three operands from a *separate* provider bound to a *separate* replica. That design is
 * deliberate and it is right for what it protects: two states generated independently on the same
 * replica can mint the same `(replica, seq)` for two different events, and a "failure" found that
 * way is a broken generator, not a broken lattice. But it also means **no generated state is ever a
 * causal ancestor of another** — no operand's context can witness a dot another operand still
 * carries. A defect that needs one operand to *retire a tag a second operand holds* is therefore
 * unreachable, and the law passes while the type is not associative. That is exactly how #2086
 * survived in `ORMap`, which has had a `pieceIsAssociative` property since the type landed.
 *
 * A trajectory restores ancestry without giving up dot uniqueness. Every state comes from the same
 * single-replica fold, so each is a prefix of the next and each dot is minted exactly once — the
 * states are all reachable, and the triple `(s, s.remove(k), s.remove(k).put(k, v))` is drawn from
 * an ordinary run rather than fabricated.
 */
internal fun <S : Quilted<S>> assertAssociativeAlongTrajectory(trajectory: List<S>) {
    for (a in trajectory) for (b in trajectory) for (c in trajectory) {
        val leftNested = a.piece(b).piece(c)
        val rightNested = a.piece(b.piece(c))
        check(leftNested == rightNested) {
            "associativity failed along a causal trajectory:\n" +
                "  a       = $a\n" +
                "  b       = $b\n" +
                "  c       = $c\n" +
                "  (a⊔b)⊔c = $leftNested\n" +
                "  a⊔(b⊔c) = $rightNested"
        }
    }
}
