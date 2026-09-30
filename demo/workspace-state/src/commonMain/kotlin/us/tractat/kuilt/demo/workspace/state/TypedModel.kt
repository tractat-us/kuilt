package us.tractat.kuilt.demo.workspace.state

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId

/**
 * One input in the typed model's single log. Every input is an [Rga] insert, so every input has a
 * dot, and all of them share one dot space and one Lamport clock.
 *
 * Short [SerialName]s keep the polymorphic discriminator from dominating [TypedModel.encodedSize]:
 * the default is the fully-qualified class name, which no real consumer would ship.
 */
@Serializable
public sealed interface Entry {
    /** A shortlisted venue as first added. Its insert dot is the venue's handle. */
    @Serializable
    @SerialName("venue")
    public data class VenueAdded(val name: String, val price: Int) : Entry

    @Serializable
    @SerialName("price")
    public data class PriceSet(val target: Dot, val price: Int) : Entry

    @Serializable
    @SerialName("removed")
    public data class Removed(val target: Dot) : Entry

    @Serializable
    @SerialName("budget")
    public data class BudgetSet(val budget: Int) : Entry
}

/**
 * The typed model's state: the replicated log plus the delta of the mutation that produced it.
 *
 * [lastDelta] exists only so [TypedModel.encodedSize] can measure what one mutation would put on
 * the wire; it is not replicated. It is the one-insert log `Rga.empty().apply(op)`, the single-op
 * delta idiom `RgaGcCoordinator` uses. [TypedModel.merge] produces a state with no last delta.
 */
public data class TypedState(val log: Rga<Entry>, val lastDelta: Rga<Entry>?)

/**
 * The typed dinner model: ONE log, `Rga<Entry>`.
 *
 * Every mutation is `insertAt(replica, size, entry)` on that log, so every input is an [Rga]
 * element and carries a dot. Nothing is ever removed at the [Rga] level: a removal is itself an
 * [Entry.Removed] insert.
 *
 * Why one log. The plan named `LatticeProduct<Rga<VenueAdded>, Rga<Edit>>`; the controller ruled
 * it out. Each [Rga] mints its `seq` per replica from its *own* high-water, so two logs in a
 * product are two independent dot spaces: a replica's first venue and its first edit were both
 * `Dot(a, 1)`, and the product's `causalDots()` union collapsed them into one identity (pinned by
 * `TwoLogProductTest`). The two logs also ran separate Lamport clocks. One log gives one dot space
 * and one clock.
 *
 * Last-writer-wins is explicit, by [RgaId] `(lamport, replica)`: the [Entry.PriceSet] with the
 * greatest id per target wins, and so does the [Entry.BudgetSet] with the greatest id. A target
 * with any [Entry.Removed] is hidden, together with every [Entry.PriceSet] aimed at it (remove
 * wins).
 *
 * Why the model sorts explicitly: **`Rga` sequence order (`entries()`) is NOT [RgaId] order.** The
 * sequence is a depth-first walk of the insert-after tree with siblings in *descending* id order,
 * so when `a` and `b` both append after the same element, `entries()` yields the higher id first
 * and the lower id last — "last in `entries()`" would pick the lower id (pinned by
 * `TypedModelOrderTest`). [RgaId] order still respects causality: an insert made after seeing
 * another has a higher Lamport stamp.
 */
public object TypedModel : DinnerModel<TypedState> {
    override val name: String = "typed"

    private val deltaSerializer: KSerializer<Rga<Entry>> = Rga.wireSerializer(Entry.serializer())

    override fun empty(replica: ReplicaId): TypedState = TypedState(Rga.empty(), lastDelta = null)

    override fun addVenue(
        s: TypedState,
        replica: ReplicaId,
        name: String,
        price: Int,
    ): Pair<TypedState, VenueHandle> {
        val (state, id) = append(s, replica, Entry.VenueAdded(name, price))
        return state to VenueHandle(id.dot)
    }

    override fun setPrice(s: TypedState, replica: ReplicaId, venue: VenueHandle, price: Int): TypedState {
        requireVisible(s, venue)
        return append(s, replica, Entry.PriceSet(venue.dot, price)).first
    }

    override fun removeVenue(s: TypedState, replica: ReplicaId, venue: VenueHandle): TypedState {
        requireVisible(s, venue)
        return append(s, replica, Entry.Removed(venue.dot)).first
    }

    override fun setBudget(s: TypedState, replica: ReplicaId, budget: Int): TypedState =
        append(s, replica, Entry.BudgetSet(budget)).first

    override fun merge(a: TypedState, b: TypedState): TypedState = TypedState(a.log.piece(b.log), lastDelta = null)

    override fun shortlist(s: TypedState): List<VenueView> {
        val entries = s.log.entries()
        // Sorted by RgaId, not taken in entries() order: sequence order is not RgaId order.
        val prices = entries
            .mapNotNull { (id, entry) -> (entry as? Entry.PriceSet)?.let { id to it } }
            .sortedBy { (id, _) -> id }
            .associate { (_, set) -> set.target to set.price }
        val removed = entries.mapNotNull { (_, entry) -> (entry as? Entry.Removed)?.target }.toSet()
        return entries.mapNotNull { (id, entry) ->
            (entry as? Entry.VenueAdded)
                ?.takeIf { id.dot !in removed }
                ?.let { VenueView(VenueHandle(id.dot), it.name, prices[id.dot] ?: it.price) }
        }
    }

    /**
     * The effective budget ([effectiveBudget], the greatest [RgaId]) FIRST, then every other
     * [Entry.BudgetSet] its author is not proven to have seen, in descending [RgaId] order — so
     * the list reads "the winner, then the losers from most to least recent".
     *
     * "Proven seen" is what the [Rga] log honestly records: the causal past of the effective
     * insert is the transitive closure of its `after` predecessor link (`Rga.positionsFor`) — the
     * author inserted after an element, so it had seen it — together with every earlier insert
     * by the same replica (a lower `seq`). Everything in that closure happened before the
     * effective write, so this never drops a genuinely concurrent budget; it can keep one the
     * author had in fact seen but that sits off the `after` chain (for example an earlier budget
     * that a later concurrent edit was ordered after).
     *
     * NEEDS: a per-op record of what its author had seen (a version vector on each edit) to tell
     * "concurrent with the effective budget" from "seen but off the `after` chain"; the Rga log
     * records only the one predecessor, so this is an over-approximation of concurrency.
     */
    override fun budgetCandidates(s: TypedState): List<Int> {
        val ids = s.log.entries().map { it.first }
        val budgets = budgetsNewestFirst(s)
        val (effectiveId, _) = budgets.firstOrNull() ?: return emptyList()
        val seen = causalPast(effectiveId, s.log.positionsFor(ids.toSet()), ids)
        return budgets
            .filter { (id, _) -> id == effectiveId || id !in seen }
            .map { (_, budget) -> budget }
    }

    /**
     * The [Entry.BudgetSet] with the greatest [RgaId] `(lamport, replica)` — explicit
     * last-writer-wins by id, NOT the last one in `entries()` order (see the class KDoc).
     * [budgetCandidates] is where a concurrent loser stays visible.
     */
    override fun effectiveBudget(s: TypedState): Int? = budgetsNewestFirst(s).firstOrNull()?.second

    /** Every [Entry.BudgetSet] with its id, greatest [RgaId] first. */
    private fun budgetsNewestFirst(s: TypedState): List<Pair<RgaId, Int>> = s.log.entries()
        .mapNotNull { (id, entry) -> (entry as? Entry.BudgetSet)?.let { id to it.budget } }
        .sortedByDescending { (id, _) -> id }

    /** The log's own `causalDots()`: one dot per input, all in one dot space. */
    override fun causalDots(s: TypedState): Set<Dot> = s.log.causalDots()

    /** CBOR (the codec `Quilter` defaults to) over `Rga.wireSerializer(Entry.serializer())`. */
    @OptIn(ExperimentalSerializationApi::class)
    override fun encodedSize(s: TypedState): Int {
        val delta = checkNotNull(s.lastDelta) {
            "typed: this state came from merge, not a mutation, so it has no last delta to measure"
        }
        return Cbor.encodeToByteArray(deltaSerializer, delta).size
    }

    private fun append(s: TypedState, replica: ReplicaId, entry: Entry): Pair<TypedState, RgaId> {
        val (next, op) = s.log.insertAt(replica, s.log.size, entry)
        return TypedState(next, lastDelta = Rga.empty<Entry>().apply(op)) to op.id
    }

    private fun requireVisible(s: TypedState, venue: VenueHandle) {
        require(shortlist(s).any { it.handle == venue }) {
            "typed: venue $venue is not on this replica's shortlist (never added here, or removed)"
        }
    }

    private fun causalPast(of: RgaId, afterOf: Map<RgaId, RgaId>, ids: List<RgaId>): Set<RgaId> {
        val seen = mutableSetOf<RgaId>()
        val frontier = ArrayDeque<RgaId>()
        afterOf[of]?.let(frontier::addLast)
        ids.filter { it.replicaId == of.replicaId && it.seq < of.seq }.forEach(frontier::addLast)
        while (frontier.isNotEmpty()) {
            val id = frontier.removeFirst()
            if (id == RgaId.HEAD || !seen.add(id)) continue
            afterOf[id]?.let(frontier::addLast)
        }
        return seen
    }
}
