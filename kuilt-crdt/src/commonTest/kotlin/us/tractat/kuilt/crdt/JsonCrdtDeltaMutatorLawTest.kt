package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The delta-mutator law for [JsonCrdt], asserted **on encoded bytes**, and — the part no law can
 * see — the measurement that a write's frame does not grow with the document.
 *
 * `JsonCrdt` is a thin wrapper over `ORMap<String, JsonNode>`, whose mutators already return the
 * change rather than the whole map. Until #2111 the wrapper threw that away: `set`/`remove`
 * absorbed the `ORMap` delta locally and handed back a whole new document, so
 * `Patch(doc.set(k, v))` put every key *and every key's subtree* on the wire on every write.
 *
 * @see ORMapDeltaMutatorLawTest for the same shape one level down, and for why the reference side
 *   is deliberately the internal whole-state form rather than the delta path compared against
 *   itself.
 */
@OptIn(ExperimentalSerializationApi::class)
class JsonCrdtDeltaMutatorLawTest {

    private val alpha = ReplicaId("alpha")
    private val bravo = ReplicaId("bravo")

    private val cbor = Cbor {}

    private fun bytes(doc: JsonCrdt): ByteArray = cbor.encodeToByteArray(JsonCrdt.serializer(), doc)

    // ── the frame is flat in document size ────────────────────────────────────────

    /**
     * **The point of #2111, and the one thing the law cannot see.** `Patch(doc.setWhole(…))` — the
     * whole document — satisfies `X.piece(mᵟ(X)) == m(X)` perfectly, which is exactly what shipped:
     * every law, every convergence property and every conformance suite stayed green while each
     * write put the entire document on the wire.
     *
     * Measured at two document sizes an order of magnitude apart. Flatness, not "smaller than the
     * whole document", is the invariant: the latter would pass a change that reintroduced
     * O(keys) with a better constant.
     */
    @Test
    fun aSetsFrameIsFlatInDocumentSize() {
        val small = documentOfSize(SMALL_STATE)
        val large = documentOfSize(LARGE_STATE)
        val written = leaf(bravo, "fresh")

        assertFlat(
            small = setFrame(small.withReplica(bravo), "k-0", written),
            large = setFrame(large.withReplica(bravo), "k-0", written),
            whole = bytes(large),
            what = "set frame over a $SMALL_STATE-key vs a $LARGE_STATE-key document",
        )
    }

    /** The same for [JsonCrdt.remove]'s frame. */
    @Test
    fun aRemovesFrameIsFlatInDocumentSize() {
        val small = documentOfSize(SMALL_STATE)
        val large = documentOfSize(LARGE_STATE)

        assertFlat(
            small = removeFrame(small, "k-0"),
            large = removeFrame(large, "k-0"),
            whole = bytes(large),
            what = "remove frame over a $SMALL_STATE-key vs a $LARGE_STATE-key document",
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    /**
     * The bytes a replicator would broadcast for `doc.set(key, node)` — a patch's delta is
     * broadcast verbatim, so this is the frame.
     */
    private fun setFrame(doc: JsonCrdt, key: String, node: JsonNode): ByteArray =
        bytes(doc.set(key, node))

    /** The bytes a replicator would broadcast for `doc.remove(key)`. */
    private fun removeFrame(doc: JsonCrdt, key: String): ByteArray =
        bytes(doc.remove(key))

    /**
     * The whole-document write — the O(document) spelling the fixtures are built from, so a
     * generator never depends on the mechanism under test.
     */
    private fun JsonCrdt.writeWhole(key: String, node: JsonNode): JsonCrdt = set(key, node)

    /** A scalar leaf whose register dot is minted by [writer]. */
    private fun leaf(writer: ReplicaId, value: String): JsonNode.Leaf =
        JsonNode.Leaf(MVRegister.empty<JsonValue>().set(writer, JsonValue.Str(value)))

    /** A document of [keyCount] scalar keys, all written by [alpha]. */
    private fun documentOfSize(keyCount: Int): JsonCrdt =
        (0 until keyCount).fold(JsonCrdt.empty(alpha)) { doc, index ->
            doc.writeWhole("k-$index", leaf(alpha, "v-$index"))
        }

    /**
     * Asserts a frame's encoded size does not grow with the document it was built from: [large]
     * must be within [FLAT_TOLERANCE_PERCENT]% of [small], measured across an order of magnitude.
     * [whole] is reported for scale only — it is deliberately *not* what the assertion compares
     * against.
     */
    private fun assertFlat(small: ByteArray, large: ByteArray, whole: ByteArray, what: String) {
        assertAll(
            {
                assertTrue(
                    large.size * 100 <= small.size * FLAT_TOLERANCE_PERCENT,
                    "$what: the frame must be flat in document size, but grew from ${small.size} b " +
                        "to ${large.size} b (the whole document at that size is ${whole.size} b)",
                )
            },
            {
                assertTrue(
                    small.isNotEmpty() && large.isNotEmpty(),
                    "$what: vacuous — an empty frame would be flat for the wrong reason",
                )
            },
        )
    }

    private companion object {
        /** The two document sizes the flat-frame tests measure across — an order of magnitude apart. */
        const val SMALL_STATE = 100
        const val LARGE_STATE = 1_000

        /**
         * How much a frame may grow across that order of magnitude. Not zero: the minted dot's
         * sequence number is a varint, so it costs a byte more at the larger size. Anything beyond
         * this is a term that scales with the document.
         */
        const val FLAT_TOLERANCE_PERCENT = 120
    }
}
