package us.tractat.kuilt.crdt

import kotlinx.serialization.KSerializer

/**
 * An op-log CRDT, viewed as the **stream of operations** it holds rather than as a value.
 *
 * A replica of [Rga] or [Fugue] is a log of small, immutable edits — "insert this here",
 * "remove that", "I have forgotten these". Most consumers never see the log; they read the
 * list it adds up to. This contract is for the one consumer that wants the edits themselves:
 * something that writes each edit down as it happens, and keeps it after the replica that made
 * it has moved on.
 *
 * That is the whole reason this is a separate contract from [Quilted]. Merging two replicas
 * (`piece`) makes forgetting **contagious**: a replica that has compacted an element propagates
 * that compaction to everyone it merges with, so a long-memory peer cannot hold history a
 * short-memory peer has dropped. Operations do not have that property — they are just facts
 * about what happened — so a consumer fed operations can retain a year of them beside a replica
 * retaining an hour.
 *
 * ### The classification is the point
 *
 * [classify] splits the log three ways ([LogOp]). [LogOp.Insert] and [LogOp.Remove] are
 * *content*; [LogOp.Compact] is a record of *forgetting*. A write-only archive keeps the first
 * two and discards the third, which is exactly what lets its history outlive its source's.
 * Getting that split wrong is a data-loss bug, so it lives here, in the CRDT that mints the
 * ops, and not in each consumer.
 *
 * ### What this contract deliberately does not expose
 *
 * [operations] is a `Sequence` — a **view**, which any future backing (a materialised cache, a
 * paged store, a lazily-decoded segment) can stream. It is not the op-log field, and the
 * concrete collection type behind it is not part of this contract. That representation
 * independence is why this interface exists at all instead of the op-log simply being made
 * public: publishing the field would pin its type as a compatibility commitment.
 *
 * ### Instance-scoped, for now
 *
 * [classify] and [dotOf] are instance methods, so a consumer cannot classify an op without a
 * live CRDT in hand. That is acceptable for the append side — the path that classifies always
 * holds the replica it is classifying for — but a replay-side validator, reading ops back from
 * storage with no replica available, would want them free-standing. Splitting them out is a
 * source-compatible change if that day comes.
 *
 * @param Id the element-identity type ([RgaId] / [FugueId]).
 * @param V the element type carried by inserts.
 * @param Op the operation type (`RgaOp<V>` / `FugueOp<V>`).
 */
public interface OpLogCrdt<Id : Any, V, Op : Any> {

    /**
     * Every operation this replica currently holds, in no guaranteed order.
     *
     * "Currently holds" is doing real work: an op-log CRDT forgets. A compacted or windowed
     * replica no longer yields the ops it dropped, and — for [Rga] — an op suppressed by the
     * [Rga.compactedBelow] floor leaves with **no** [LogOp.Compact] naming it. So this is the
     * live log, never a complete history, which is why an archive has to be fed as ops arrive
     * rather than reconstructed from a replica afterwards.
     *
     * **The returned sequence may be iterated more than once**, and every iteration observes the
     * same ops — the replica is immutable, so a mutation produces a *new* replica rather than
     * disturbing this one's stream.
     *
     * Stated because Kotlin's [Sequence] is multi-pass *unless its documentation says otherwise*,
     * so silence here would still read as this guarantee while quietly reserving the right to
     * withdraw it. A future backing that streams (a paged store, a lazily-decoded archive segment)
     * must therefore re-open its cursor per `iterator()` call rather than hand out a one-shot one:
     * constraining this to a single pass later would break consumers at **runtime**, with no
     * compile-time signal, on the one surface this contract exists to keep swappable. Declaring
     * the stronger guarantee now costs nothing today and keeps that door shut.
     */
    public fun operations(): Sequence<Op>

    /**
     * Classify [op] as an [LogOp] — insert, remove, or compaction record.
     *
     * See [LogOp]: the insert/remove versus compaction split is the safety-critical one.
     */
    public fun classify(op: Op): LogOp<Id>

    /**
     * The causal [Dot] `(replica, seq)` that [id] belongs to.
     *
     * Only an insert mints a dot. A remove reuses its target insert's id, so projecting a
     * remove's id yields the dot of the *insert* it tombstones — which is why a dot-keyed
     * cursor over an op stream is well-defined over inserts only.
     */
    public fun dotOf(id: Id): Dot

    /**
     * The **canonical** [KSerializer] for this CRDT's operations, threading [vSerializer]
     * through the element type.
     *
     * Use this rather than reaching for a compiler-generated serializer for [Op]. The generated
     * sealed serializer writes a different wire format (class-discriminator polymorphism instead
     * of the canonical leading `t` tag) and defaults the element type to
     * `PolymorphicSerializer(Any::class)`, which CBOR cannot encode — so bytes written with it
     * would sit outside the golden vectors that pin this format across versions. Anything
     * persisting ops for later reading must go through here.
     */
    public fun opSerializer(vSerializer: KSerializer<V>): KSerializer<Op>
}
