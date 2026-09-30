package us.tractat.kuilt.demo.workspace.state

import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ReplicaId

/** An opaque, merge-stable handle to one shortlisted venue. Never a list index. */
public data class VenueHandle(val dot: Dot)

public data class VenueView(val handle: VenueHandle, val name: String, val pricePerHead: Int)

/**
 * The probe surface. Each mutator returns the new state; `merge` is the model's `piece`.
 *
 * Every probe in this module is written once against this interface and run against both
 * [TypedModel] and [JsonModel]. Mutators throw for an input shape the model cannot express;
 * an unsupported shape fails explicitly, never silently.
 */
public interface DinnerModel<S> {
    public val name: String
    public fun empty(replica: ReplicaId): S
    public fun addVenue(s: S, replica: ReplicaId, name: String, price: Int): Pair<S, VenueHandle>
    public fun setPrice(s: S, replica: ReplicaId, venue: VenueHandle, price: Int): S
    public fun removeVenue(s: S, replica: ReplicaId, venue: VenueHandle): S
    public fun setBudget(s: S, replica: ReplicaId, budget: Int): S
    public fun merge(a: S, b: S): S
    public fun shortlist(s: S): List<VenueView>

    /** Every budget value still recoverable after merge, in the model's deterministic order. */
    public fun budgetCandidates(s: S): List<Int>

    /** The model's single answer, and how it chose (documented in KDoc). */
    public fun effectiveBudget(s: S): Int?
    public fun causalDots(s: S): Set<Dot>

    /** Encoded delta size of the last mutation, for Review Focus 5. */
    public fun encodedSize(s: S): Int
}
