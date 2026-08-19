package us.tractat.kuilt.crdt

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A CRDT-backed JSON document: a recursive, convergent, arbitrary-depth JSON
 * value that merges correctly under concurrent edits from multiple replicas.
 *
 * The root is always a JSON object keyed by [String]. Values at each key can
 * be nested objects, arrays, or scalars:
 *
 * ```
 * JsonNode = JsonObject(ORMap<String, JsonNode>)
 *          | JsonArray(Rga<JsonNode>)
 *          | JsonLeaf(MVRegister<JsonValue>)
 * ```
 *
 * **Conflict resolution.** Merge is structural and recursive:
 * - *Key presence* — add-wins: a concurrent `put` of the same key survives a `remove`.
 * - *Nested values* — recursed via [JsonNode.piece]: objects merge their maps,
 *   arrays merge their op-logs, leaves merge their multi-value registers.
 * - *Concurrent scalar writes* — the [JsonNode.Leaf]'s [MVRegister] retains all
 *   concurrent values; the caller resolves by calling `set` again once they read
 *   the multi-value state.
 * - *Cross-type conflicts* (e.g. one replica replaces an object with a scalar
 *   concurrently with the other replica adding a key to the object) — the richer
 *   structural type wins: `Object > Array > Leaf`. **This is a data-loss decision,
 *   not a data-preservation one.** The losing node's entire subtree is silently
 *   discarded. The scalar equivalent ([JsonNode.Leaf] vs [JsonNode.Leaf]) surfaces
 *   both values via [MVRegister], but a Leaf-vs-Object cross-type conflict does not.
 *   This is a deliberate v1 simplification; a future version may model cross-type
 *   conflicts as a multi-valued register at the type level.
 *
 * **Every mutator returns the change rather than a new document**: [set] and [remove] hand back a
 * [Patch] holding just the key they touched, which is what belongs on the wire. [piece] absorbs
 * one — and is also how a caller who wants the resulting whole document gets one:
 * `doc.piece(doc.set(key, node))`.
 *
 * **Known limitations (v1):**
 * - *Nested writes are still O(subtree)* — a write inside an existing [JsonNode.Object] or
 *   [JsonNode.Array] is expressed by rebuilding the enclosing node and [set]ting it at the top, so
 *   the frame is one key whose value is the whole rebuilt subtree. A path-addressed mutator would
 *   make it O(depth); that is #2469.
 * - *Move / subtree-reattachment* — not supported.
 * - *Nested [Rga] GC* — arrays embedded inside a JSON document do not participate
 *   in the [Rga.compact] / [us.tractat.kuilt.quilter.Quilter] GC
 *   path. Tombstones inside array elements accumulate without bound until an
 *   explicit compact is triggered by the caller.
 * - *Conflict-free re-typing* — concurrent changes of a key's type are resolved
 *   by the precedence rule above, not by surfacing a conflict.
 *
 * **Serialization.** Use [JsonCrdt.serializer] to obtain a [KSerializer]. The
 * [replica] id is *not* included in the wire format — it is a local identity.
 * After deserializing, call [withReplica] to restore the local replica id before
 * performing mutations.
 *
 * **Caution — mutate after [withReplica].** The deserialized document defaults to
 * [ReplicaId]`("")`, which collides with [RgaId.HEAD]'s sentinel replica and may
 * corrupt [Dot] uniqueness if used to mint new operations. Always call [withReplica]
 * before invoking [set] or [remove] on a deserialized document.
 *
 * @see JsonNode the node algebra this document is built over.
 * @see JsonValue the scalar type for [JsonNode.Leaf] registers.
 *
 * @sample us.tractat.kuilt.crdt.sampleJsonCrdt
 */
public class JsonCrdt internal constructor(
    internal val root: ORMap<String, JsonNode>,
    private val replica: ReplicaId,
) : Quilted<JsonCrdt> {

    /** The top-level keys currently present in this document. */
    public val keys: Set<String> get() = root.keys

    /** Returns the [JsonNode] for [key], or `null` if absent. */
    public operator fun get(key: String): JsonNode? = root[key]

    /**
     * Set [key] to [node] — and return **the change**: one key, the node you supplied, and a short
     * causal note, rather than the whole new document.
     *
     * If the key already exists, its current value is merged with [node] via [JsonNode.piece] — a
     * write is additive within the node's own lattice. This is [ORMap.put]'s semantics, which the
     * root is, and it preserves the add-wins invariant for nested structure. The merge happens at
     * *each receiver*, against that receiver's own copy, which is why the delta carries [node] and
     * not the locally merged result.
     *
     * This is what to put on the wire. A replicator broadcasts a patch's delta verbatim, so a
     * mutator that handed back the new document would ship every key **and every key's subtree** on
     * every write, at a cost that grows with the document; this frame's size does not depend on how
     * large the document is. The idiom is `quilter.mutate { it.set(key, node) }` —
     * read-modify-write inside the replicator's own lock. To hold the resulting document locally,
     * absorb the patch: `doc.piece(doc.set(key, node))`, or `doc.piece { it.set(key, node) }`.
     *
     * **Flat in the document, not in [node].** The saving is over the *rest* of the document. The
     * frame still carries the whole node you passed, and a write nested inside an existing
     * `JsonNode.Object`/`JsonNode.Array` is expressed today by rebuilding the enclosing node and
     * setting it here — so the frame is one key whose value is that whole rebuilt subtree. A
     * genuinely minimal nested write needs a path-addressed mutator; that is #2469, not something
     * this method can do. It also carries whatever this replica has already contributed to [key],
     * for the reason [ORMap.put] spells out: the tag this write mints supersedes the sender's older
     * tags on the key and therefore has to carry what they were holding.
     *
     * @throws IllegalArgumentException if the document was not configured with a replica id
     *   (i.e. it was deserialized and [withReplica] was not called beforehand).
     *
     * @sample us.tractat.kuilt.crdt.sampleJsonCrdt
     */
    public fun set(key: String, node: JsonNode): Patch<JsonCrdt> {
        requireReplica()
        return Patch(JsonCrdt(root.put(replica, key, node).delta, replica))
    }

    /**
     * Remove [key] — and return **the change**: the tags currently on it, retired, and nothing
     * else. Absorbing that patch drops the key; the retired tags stay witnessed, so the removal
     * propagates on merge. To hold the resulting document locally,
     * `doc.piece(doc.remove(key))`.
     *
     * Concurrent writes of the same key on another replica survive the merge (add-wins): they mint
     * a tag this removal never observed, so it is not in the delta's context.
     *
     * Removing a key that is absent yields the lattice identity, so absorbing it changes nothing.
     *
     * @throws IllegalArgumentException if the document was not configured with a replica id
     *   (i.e. it was deserialized and [withReplica] was not called beforehand).
     *
     * @sample us.tractat.kuilt.crdt.sampleJsonCrdt
     */
    public fun remove(key: String): Patch<JsonCrdt> {
        requireReplica()
        return Patch(JsonCrdt(root.remove(key).delta, replica))
    }

    /**
     * The whole document a [set] produces — the reference semantics [set]'s delta must reproduce
     * under [piece], byte for byte.
     *
     * Deliberately **not public**: it is the O(document) spelling this type exists to keep off the
     * wire, and the only caller that needs it is `JsonCrdtDeltaMutatorLawTest`, which cannot state
     * the delta-mutator law without an independent reference to compare against.
     *
     * **It must delegate to [ORMap.putWhole], not to `root.piece { it.put(…) }`.** The convenient
     * spelling is not a second implementation: `S.piece(mutate)` is `piece(mutate(this).delta)`, so
     * `root.piece { it.put(…) }` *is* `root.piece(root.put(…).delta)` — the delta path, letter for
     * letter. A law stated against it reads `x == x` and cannot go red under any change to [set].
     * That is what shipped in the first cut of #2111, and it is why this KDoc now names the callee.
     * [ORMap.putWhole] builds `entries + (key to newEntry)` and `context.add(dot)` directly and
     * shares no code with `putPatch`, which is what makes the comparison mean anything.
     */
    internal fun setWhole(key: String, node: JsonNode): JsonCrdt {
        requireReplica()
        return JsonCrdt(root.putWhole(replica, key, node), replica)
    }

    /** The whole document a [remove] produces. Internal for the same reason as [setWhole]. */
    internal fun removeWhole(key: String): JsonCrdt {
        requireReplica()
        return JsonCrdt(root.removeWhole(key), replica)
    }

    private fun requireReplica() {
        require(replica.value.isNotEmpty()) {
            "Cannot mutate a JsonCrdt with an empty replica id — call withReplica() after deserialization."
        }
    }

    override fun piece(other: JsonCrdt): JsonCrdt =
        JsonCrdt(root.piece(other.root), replica)

    /**
     * Unions the [Rga.causalDots] of every [JsonNode.Array] reachable from the root,
     * recursing through [JsonNode.Object] values. This feeds the causal-stability GC
     * barrier in [us.tractat.kuilt.quilter.Quilter]: without this
     * override, embedded [Rga] tombstones in nested arrays would never be considered
     * for compaction because the delivered frontier would always be empty.
     */
    override fun causalDots(): Set<Dot> = root.keys
        .mapNotNull { root[it] }
        .flatMap { it.causalDots() }
        .toSet()

    /**
     * The elementwise max of the [Quilted.causalFloor]s of every node reachable from the root —
     * the floor counterpart of the [causalDots] union above, over the same reachable set.
     *
     * A nested [Rga] that has floored a prefix no longer reports those dots through
     * [causalDots], so a document that inherited the empty default would hand the replicator a
     * frontier with a permanent hole in it.
     */
    override fun causalFloor(): VersionVector = root.keys
        .mapNotNull { root[it] }
        .fold(VersionVector.EMPTY) { floor, node -> floor.ceilWith(node.causalFloor()) }

    /**
     * Returns a copy of this document configured to issue mutations on behalf of
     * [replica]. Call this after deserialization to restore the local replica id.
     */
    public fun withReplica(replica: ReplicaId): JsonCrdt =
        JsonCrdt(root, replica)

    override fun equals(other: Any?): Boolean =
        other is JsonCrdt && root == other.root

    override fun hashCode(): Int = root.hashCode()

    override fun toString(): String = "JsonCrdt($root)"

    public companion object {
        /**
         * Returns the empty document for [replica]. The replica id is required
         * to mint fresh [Dot]s on mutation. Each physical peer should supply its
         * own stable [ReplicaId].
         */
        public fun empty(replica: ReplicaId): JsonCrdt =
            JsonCrdt(ORMap.empty<String, JsonNode>(), replica)

        /**
         * Returns a [KSerializer] for [JsonCrdt]. The [replica] id is not included
         * in the wire format; deserialized documents default to [ReplicaId]`("")`.
         *
         * **Always call [withReplica] before mutating a deserialized document.**
         * Minting dots under [ReplicaId]`("")` shares the sentinel namespace used by
         * [RgaId.HEAD] and corrupts [Dot] uniqueness across peers.
         */
        public fun serializer(): KSerializer<JsonCrdt> = JsonCrdtSerializer
    }
}

// ---- Serializer ----

private object JsonCrdtSerializer : KSerializer<JsonCrdt> {

    private val innerSerializer: KSerializer<ORMap<String, JsonNode>> by lazy {
        ORMap.serializer(String.serializer(), JsonNode.serializer())
    }

    override val descriptor get() = innerSerializer.descriptor

    override fun serialize(encoder: Encoder, value: JsonCrdt): Unit =
        innerSerializer.serialize(encoder, value.root)

    override fun deserialize(decoder: Decoder): JsonCrdt =
        JsonCrdt(innerSerializer.deserialize(decoder), ReplicaId.Bottom)
}
