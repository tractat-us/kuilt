package us.tractat.kuilt.demo.workspace.state

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.JsonCrdt
import us.tractat.kuilt.crdt.JsonNode
import us.tractat.kuilt.crdt.JsonValue
import us.tractat.kuilt.crdt.MVRegister
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.piece

/**
 * The JSON model's state: the document plus the delta of the mutation that produced it.
 *
 * [lastDelta] is the `Patch.delta` returned by [JsonCrdt.set] — exactly what a replicator would
 * broadcast — kept only so [JsonModel.encodedSize] can measure it. [JsonModel.merge] produces a
 * state with no last delta.
 */
public data class JsonState(val doc: JsonCrdt, val lastDelta: JsonCrdt?)

/**
 * The JSON dinner model: one [JsonCrdt] document
 * `{"shortlist": Array[Object{name, price}], "budget": Leaf}`.
 *
 * A venue's handle is its element's `RgaId.dot` in the `shortlist` array. Every write goes
 * through [JsonCrdt.set] at the top level: a change inside `shortlist` rebuilds the array and
 * writes the whole of it back (the #2469 whole-subtree shape), and the budget is a
 * [JsonNode.Leaf] write.
 *
 * Dot hygiene. The node builders below are only for *first* construction. A later write is
 * derived from the current node — a new `price`/`budget` leaf from the current register
 * (`existing.register.set(replica, v)`), a rebuilt element object from the current `ORMap` — because
 * a fresh `MVRegister.empty().set(replica, v)` mints dot `(replica, 1)` on every write, which would
 * put two different values under one dot and make every concurrent-edit result an artefact of this
 * harness rather than of `JsonCrdt`.
 */
public object JsonModel : DinnerModel<JsonState> {
    override val name: String = "json"

    private const val SHORTLIST = "shortlist"
    private const val BUDGET = "budget"
    private const val NAME = "name"
    private const val PRICE = "price"

    override fun empty(replica: ReplicaId): JsonState = JsonState(JsonCrdt.empty(replica), lastDelta = null)

    override fun addVenue(
        s: JsonState,
        replica: ReplicaId,
        name: String,
        price: Int,
    ): Pair<JsonState, VenueHandle> {
        val rga = shortlistRga(s.doc)
        val element = obj(replica, NAME to leaf(replica, JsonValue.Str(name)), PRICE to leaf(replica, num(price)))
        val (next, op) = rga.insertAt(replica, rga.size, element)
        return writeShortlist(s.doc, replica, next) to VenueHandle(op.id.dot)
    }

    /**
     * SHAPE: an `Rga` has no in-place update, so a price edit replaces the venue's element — insert
     * the rebuilt `Object{name, price}` directly after the old element, then remove the old one —
     * and writes the whole `shortlist` array back. This CHANGES THE VENUE'S IDENTITY: the edited
     * venue has a new `RgaId.dot`, every handle held to the old one stops resolving (a later
     * `setPrice`/`removeVenue` through it fails here), and a concurrent edit or removal on another
     * replica that still targets the old element no longer reaches the venue the user sees.
     */
    override fun setPrice(s: JsonState, replica: ReplicaId, venue: VenueHandle, price: Int): JsonState {
        val rga = shortlistRga(s.doc)
        val oldId = visibleId(rga, venue)
        val old = rga.valueAt(oldId) as? JsonNode.Object
            ?: error("json: shortlist element $venue is not an Object")
        val oldPrice = old.map[PRICE] as? JsonNode.Leaf
            ?: error("json: venue $venue has no price Leaf")
        val newPrice = JsonNode.Leaf(oldPrice.register.set(replica, num(price)))
        val rebuilt = JsonNode.Object(old.map.piece { it.put(replica, PRICE, newPrice) })
        val (inserted, _) = rga.insertAfter(replica, oldId, rebuilt)
        return writeShortlist(s.doc, replica, removeId(inserted, oldId))
    }

    override fun removeVenue(s: JsonState, replica: ReplicaId, venue: VenueHandle): JsonState {
        val rga = shortlistRga(s.doc)
        return writeShortlist(s.doc, replica, removeId(rga, visibleId(rga, venue)))
    }

    override fun setBudget(s: JsonState, replica: ReplicaId, budget: Int): JsonState {
        val leaf = when (val existing = s.doc[BUDGET]) {
            null -> leaf(replica, num(budget))
            is JsonNode.Leaf -> JsonNode.Leaf(existing.register.set(replica, num(budget)))
            else -> error("json: \"$BUDGET\" holds ${existing::class.simpleName}, not a Leaf")
        }
        return write(s.doc, replica, BUDGET, leaf)
    }

    override fun merge(a: JsonState, b: JsonState): JsonState = JsonState(a.doc.piece(b.doc), lastDelta = null)

    override fun shortlist(s: JsonState): List<VenueView> = shortlistRga(s.doc).entries().map { (id, node) ->
        val element = node as? JsonNode.Object ?: error("json: shortlist element ${id.dot} is not an Object")
        VenueView(
            handle = VenueHandle(id.dot),
            name = single(element, NAME).let { it as? JsonValue.Str ?: error("json: name is $it") }.value,
            pricePerHead = single(element, PRICE).let { it as? JsonValue.Num ?: error("json: price is $it") }
                .value.toInt(),
        )
    }

    /** The budget leaf's `MVRegister.values`, ascending by amount: every concurrent write survives the merge. */
    override fun budgetCandidates(s: JsonState): List<Int> = budgetValues(s.doc).sorted()

    /**
     * The one value when the budget register holds exactly one, and `null` otherwise. `JsonCrdt`
     * picks no winner between concurrent leaf writes, so with two or more candidates there is no
     * single answer — any choice would be an application policy, not the model's. `null` with an
     * empty [budgetCandidates] means the budget was never set.
     */
    override fun effectiveBudget(s: JsonState): Int? = budgetValues(s.doc).singleOrNull()

    override fun causalDots(s: JsonState): Set<Dot> = s.doc.causalDots()

    /** CBOR (the codec `Quilter` defaults to) over [JsonCrdt.serializer], applied to the last `Patch.delta`. */
    @OptIn(ExperimentalSerializationApi::class)
    override fun encodedSize(s: JsonState): Int {
        val delta = checkNotNull(s.lastDelta) {
            "json: this state came from merge, not a mutation, so it has no last delta to measure"
        }
        return Cbor.encodeToByteArray(JsonCrdt.serializer(), delta).size
    }

    private fun shortlistRga(doc: JsonCrdt): Rga<JsonNode> = when (val node = doc[SHORTLIST]) {
        null -> Rga.empty()
        is JsonNode.Array -> node.rga
        else -> error("json: \"$SHORTLIST\" holds ${node::class.simpleName}, not an Array")
    }

    private fun writeShortlist(doc: JsonCrdt, replica: ReplicaId, rga: Rga<JsonNode>): JsonState =
        write(doc, replica, SHORTLIST, JsonNode.Array(rga))

    private fun write(doc: JsonCrdt, replica: ReplicaId, key: String, node: JsonNode): JsonState {
        val mine = doc.withReplica(replica)
        val patch = mine.set(key, node)
        return JsonState(mine.piece(patch), lastDelta = patch.delta)
    }

    private fun visibleId(rga: Rga<JsonNode>, venue: VenueHandle): RgaId =
        rga.entries().firstOrNull { (id, _) -> id.dot == venue.dot }?.first
            ?: error("json: venue $venue is not visible (never added here, removed, or replaced by a setPrice)")

    private fun removeId(rga: Rga<JsonNode>, id: RgaId): Rga<JsonNode> {
        val index = rga.entries().indexOfFirst { it.first == id }
        return rga.removeAt(index)?.first ?: error("json: element $id is not visible")
    }

    private fun budgetValues(doc: JsonCrdt): List<Int> = when (val node = doc[BUDGET]) {
        null -> emptyList()
        is JsonNode.Leaf -> node.register.values.map { (it as? JsonValue.Num ?: error("json: budget is $it")).value.toInt() }
        else -> error("json: \"$BUDGET\" holds ${node::class.simpleName}, not a Leaf")
    }

    private fun single(element: JsonNode.Object, key: String): JsonValue {
        val leaf = element.map[key] as? JsonNode.Leaf ?: error("json: element has no \"$key\" Leaf")
        return leaf.register.values.singleOrNull()
            ?: error("json: \"$key\" holds ${leaf.register.values.size} values, not one")
    }

    private fun num(n: Int): JsonValue = JsonValue.Num(n.toDouble())

    // ---- First-construction builders, after JsonCrdtTest's helpers (see the dot-hygiene note above) ----

    private fun leaf(replica: ReplicaId, v: JsonValue) = JsonNode.Leaf(MVRegister.empty<JsonValue>().set(replica, v))

    private fun obj(replica: ReplicaId, vararg pairs: Pair<String, JsonNode>): JsonNode.Object {
        val map = pairs.fold(ORMap.empty<String, JsonNode>()) { acc, (k, v) ->
            acc.piece { it.put(replica, k, v) }
        }
        return JsonNode.Object(map)
    }
}
