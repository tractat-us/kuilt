package us.tractat.kuilt.otel

import kotlinx.serialization.Serializable
import us.tractat.kuilt.crdt.Dot

/**
 * A span's position in kuilt's happens-before relation.
 *
 * Each stamped span carries its own event id ([dot]) plus the set of events it had
 * already observed when it was created ([predecessors] — the *causal frontier* at
 * that moment). Together these record, with no wall clock, which span could have
 * led to which: if span `e1`'s [dot] sits in span `e2`'s [predecessors], then `e1`
 * happened-before `e2`.
 *
 * Happens-before is a *superset* of true causality — `e1` being a predecessor of
 * `e2` means `e1` **could** have caused `e2`, never that it **did**. That honesty
 * is deliberate: links derived from these stamps are always tagged
 * `kuilt.causality=potential`.
 *
 * @property dot this span's own clock-free event id, minted by a [WarpCausalClock].
 * @property predecessors the causal frontier observed when the span was created.
 */
@Serializable
public data class CausalStamp(
    public val dot: Dot,
    public val predecessors: Set<Dot>,
)
