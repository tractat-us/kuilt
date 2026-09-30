package us.tractat.kuilt.demo.workspace.state

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.LatticeProduct
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId

/** One shortlisted venue as first added. Its [Rga] insert dot is the venue's handle. */
@Serializable
public data class VenueAdded(val name: String, val price: Int)

/**
 * One edit in the typed model's edit log. Every edit is an [Rga] insert, so every edit has a dot.
 *
 * Short [SerialName]s keep the polymorphic discriminator from dominating [TypedModel.encodedSize]:
 * the default is the fully-qualified class name, which no real consumer would ship.
 */
@Serializable
public sealed interface Edit {
    @Serializable
    @SerialName("price")
    public data class PriceSet(val target: Dot, val price: Int) : Edit

    @Serializable
    @SerialName("removed")
    public data class Removed(val target: Dot) : Edit

    @Serializable
    @SerialName("budget")
    public data class BudgetSet(val budget: Int) : Edit
}

public typealias TypedLattice = LatticeProduct<Rga<VenueAdded>, Rga<Edit>>

/**
 * The typed model's state: the replicated lattice value plus the delta of the mutation that
 * produced it.
 *
 * [lastDelta] exists only so [TypedModel.encodedSize] can measure what one mutation would put on
 * the wire; it is not replicated. It is a [LatticeProduct] whose touched component holds just the
 * one insert (`Rga.empty().apply(op)`, the single-op delta idiom `RgaGcCoordinator` uses) and whose
 * other component is empty. [TypedModel.merge] produces a state with no last delta.
 */
public data class TypedState(val lattice: TypedLattice, val lastDelta: TypedLattice?)

/**
 * The typed dinner model: `LatticeProduct<Rga<VenueAdded>, Rga<Edit>>`.
 *
 * Every mutation is `insertAt(replica, size, value)` on the right log — venues on the first, every
 * edit on the second — so every input is an [Rga] element and carries a dot. Nothing is ever
 * removed from either log at the [Rga] level: a removal is itself an [Edit.Removed] insert.
 *
 * [shortlist] folds the edit log in `entries()` order, which is [RgaId] order
 * `(lamport, replica)`: the last [Edit.PriceSet] per target wins, and a target with any
 * [Edit.Removed] is hidden, together with every [Edit.PriceSet] aimed at it (remove wins).
 */
public object TypedModel : DinnerModel<TypedState> {
    override val name: String = "typed"

    private val deltaSerializer: KSerializer<TypedLattice> = LatticeProduct.serializer(
        Rga.wireSerializer(VenueAdded.serializer()),
        Rga.wireSerializer(Edit.serializer()),
    )

    override fun empty(replica: ReplicaId): TypedState =
        TypedState(LatticeProduct(Rga.empty(), Rga.empty()), lastDelta = null)

    override fun addVenue(
        s: TypedState,
        replica: ReplicaId,
        name: String,
        price: Int,
    ): Pair<TypedState, VenueHandle> {
        val venues = s.lattice.first
        val (next, op) = venues.insertAt(replica, venues.size, VenueAdded(name, price))
        val state = TypedState(
            lattice = LatticeProduct(next, s.lattice.second),
            lastDelta = LatticeProduct(Rga.empty<VenueAdded>().apply(op), Rga.empty()),
        )
        return state to VenueHandle(op.id.dot)
    }

    override fun setPrice(s: TypedState, replica: ReplicaId, venue: VenueHandle, price: Int): TypedState {
        requireVisible(s, venue)
        return appendEdit(s, replica, Edit.PriceSet(venue.dot, price))
    }

    override fun removeVenue(s: TypedState, replica: ReplicaId, venue: VenueHandle): TypedState {
        requireVisible(s, venue)
        return appendEdit(s, replica, Edit.Removed(venue.dot))
    }

    override fun setBudget(s: TypedState, replica: ReplicaId, budget: Int): TypedState =
        appendEdit(s, replica, Edit.BudgetSet(budget))

    override fun merge(a: TypedState, b: TypedState): TypedState =
        TypedState(a.lattice.piece(b.lattice), lastDelta = null)

    override fun shortlist(s: TypedState): List<VenueView> {
        val prices = mutableMapOf<Dot, Int>()
        val removed = mutableSetOf<Dot>()
        for ((_, edit) in s.lattice.second.entries()) {
            when (edit) {
                is Edit.PriceSet -> prices[edit.target] = edit.price
                is Edit.Removed -> removed += edit.target
                is Edit.BudgetSet -> Unit
            }
        }
        return s.lattice.first.entries()
            .filter { (id, _) -> id.dot !in removed }
            .map { (id, added) -> VenueView(VenueHandle(id.dot), added.name, prices[id.dot] ?: added.price) }
    }

    /**
     * The effective [Edit.BudgetSet] followed by every other [Edit.BudgetSet] its author is not
     * proven to have seen, in edit-log `entries()` order.
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
        val edits = s.lattice.second
        val entries = edits.entries()
        val budgets = entries.filter { (_, edit) -> edit is Edit.BudgetSet }
        val effective = budgets.lastOrNull() ?: return emptyList()
        val afterOf = edits.positionsFor(entries.map { it.first }.toSet())
        val seen = causalPast(effective.first, afterOf, entries.map { it.first })
        return budgets
            .filter { (id, _) -> id == effective.first || id !in seen }
            .map { (_, edit) -> (edit as Edit.BudgetSet).budget }
    }

    /**
     * The last [Edit.BudgetSet] in edit-log `entries()` order, i.e. the greatest [RgaId]
     * `(lamport, replica)`. A deterministic last-writer-wins answer; [budgetCandidates] is where
     * a concurrent loser stays visible.
     */
    override fun effectiveBudget(s: TypedState): Int? = s.lattice.second.entries()
        .mapNotNull { (_, edit) -> (edit as? Edit.BudgetSet)?.budget }
        .lastOrNull()

    /**
     * `LatticeProduct.causalDots()`: the union of the venue log's dots and the edit log's dots.
     *
     * FINDING: the union is lossy. Each [Rga] mints its `seq` per replica *per log*
     * (`nextSeqFor` reads that log's own high-water), so a replica's first venue and its first
     * edit are both `Dot(replica, 1)`. The product unions two independent dot spaces into one set,
     * so the two inputs collapse into one identity: after `addVenue` then `setBudget` on replica
     * `a` this returns `{(a, 1)}`, one dot for two inputs (`IdentityTest.typedModelGivesEveryInputADot`
     * reds on exactly this). Each input still has a dot *within its own log* — a `PriceSet.target`
     * is unambiguous because it always names the venue log — but the product's delivered frontier
     * cannot tell "venue (a, 3) delivered" from "edit (a, 3) delivered".
     */
    override fun causalDots(s: TypedState): Set<Dot> = s.lattice.causalDots()

    /** CBOR (the codec `Quilter` defaults to) over `LatticeProduct.serializer(Rga.wireSerializer(…), …)`. */
    @OptIn(ExperimentalSerializationApi::class)
    override fun encodedSize(s: TypedState): Int {
        val delta = checkNotNull(s.lastDelta) {
            "typed: this state came from merge, not a mutation, so it has no last delta to measure"
        }
        return Cbor.encodeToByteArray(deltaSerializer, delta).size
    }

    private fun appendEdit(s: TypedState, replica: ReplicaId, edit: Edit): TypedState {
        val edits = s.lattice.second
        val (next, op) = edits.insertAt(replica, edits.size, edit)
        return TypedState(
            lattice = LatticeProduct(s.lattice.first, next),
            lastDelta = LatticeProduct(Rga.empty(), Rga.empty<Edit>().apply(op)),
        )
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
