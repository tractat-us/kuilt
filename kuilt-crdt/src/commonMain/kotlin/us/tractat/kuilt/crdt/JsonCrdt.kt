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
 *   structural type wins: `Object > Array > Leaf`. This is a v1 simplification;
 *   move/re-type semantics are out of scope.
 *
 * **Out of scope v1.** Move and subtree-reattachment operations are deferred.
 * Array semantics use [Rga] (position-stable). Conflict-free re-typing is a
 * follow-up.
 *
 * **Serialization.** Use [JsonCrdt.serializer] to obtain a [KSerializer]. The
 * [replica] id is *not* included in the wire format — it is a local identity.
 * After deserializing, call [withReplica] to restore the local replica id before
 * performing mutations.
 *
 * @see JsonNode the node algebra this document is built over.
 * @see JsonValue the scalar type for [JsonNode.Leaf] registers.
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
     * Set [key] to [node], returning the updated document.
     *
     * If the key already exists, its current value is merged with [node] via
     * [JsonNode.piece] — a put is additive within the node's own lattice. This
     * matches [ORMap.put]'s semantics and preserves the add-wins invariant for
     * nested structure.
     */
    public fun set(key: String, node: JsonNode): JsonCrdt =
        JsonCrdt(root.put(replica, key, node), replica)

    /**
     * Remove [key] from this document, returning the updated document.
     * Concurrent adds of the same key on another replica will survive the merge
     * (add-wins).
     */
    public fun remove(key: String): JsonCrdt =
        JsonCrdt(root.remove(key), replica)

    override fun piece(other: JsonCrdt): JsonCrdt =
        JsonCrdt(root.piece(other.root), replica)

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
         * Returns a [KSerializer] for [JsonCrdt]. The [replica] id is not serialized;
         * deserialized documents default to [ReplicaId]`("")` — call [withReplica]
         * before mutating.
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
        JsonCrdt(innerSerializer.deserialize(decoder), ReplicaId(""))
}
