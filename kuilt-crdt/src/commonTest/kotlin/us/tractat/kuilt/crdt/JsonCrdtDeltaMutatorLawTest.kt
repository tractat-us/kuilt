package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The delta-mutator law for [JsonCrdt], asserted **on encoded bytes**:
 *
 * ```
 * X.piece(mᵟ(X)) == m(X)
 * ```
 *
 * for every document `X` and every mutator `m` — [JsonCrdt.set]'s delta against
 * [JsonCrdt.setWhole], [JsonCrdt.remove]'s against [JsonCrdt.removeWhole].
 *
 * **The reference has to be a second implementation, and once was not.** The first cut of #2111
 * wrote `setWhole` as `JsonCrdt(root.piece { it.put(replica, key, node) }, replica)`, which looks
 * like an independent whole-state path and is not: `S.piece(mutate)` is `piece(mutate(this).delta)`,
 * so that expression *is* `root.piece(root.put(…).delta)` — exactly what the test's delta side
 * computes. Both law arms asserted `x == x`, over an immutable `root` and a pure `put`. They could
 * not go red under any change to [JsonCrdt.set], and would have stayed green through the
 * superseded-tags defect #2044 actually measured. [JsonCrdt.setWhole] and [JsonCrdt.removeWhole] now
 * delegate to [ORMap.putWhole]/[ORMap.removeWhole], which build `entries + (key to newEntry)` and
 * `context.add(dot)` directly and share no code with the patch path.
 *
 * **Positive controls, run against the repointed reference** — each mutates the *delta* path only
 * and leaves the whole-state path intact, so a green arm would mean the reference had collapsed
 * back into the mechanism:
 *
 * | mutation | effect |
 * |----------|--------|
 * | `putPatch` drops its superseded-tags term (#2044's measured failure) | [setDeltaSatisfiesTheMutatorLawOnBytes] **RED** |
 * | `putPatch` drops `foldOwn`, so the delta stops carrying the sender's own prior writes | [setDeltaSatisfiesTheMutatorLawOnBytes] **RED** |
 * | `removePatch` retires one observed tag fewer | [removeDeltaSatisfiesTheMutatorLawOnBytes] **RED** |
 *
 * Both convergence tests go red alongside each of the three. Under the pre-repoint `setWhole`, all
 * of these were green.
 *
 * **What #2111 was.** `JsonCrdt` is a thin wrapper over `ORMap<String, JsonNode>`, whose mutators
 * already return the change rather than the whole map. The wrapper threw that away: `set`/`remove`
 * absorbed the `ORMap` delta locally and handed back a whole new document, so
 * `Patch(doc.set(k, v))` put every key **and every key's subtree** on the wire on every write.
 *
 * Measured over the fixtures below, at 100 vs 1,000 keys (whole document: 127,333 b):
 *
 * | frame | before | after |
 * |-------|--------|-------|
 * | [aSetsFrameIsFlatInDocumentSize] | 12,416 b → 127,462 b | 177 b → 177 b |
 * | [aRemovesFrameIsFlatInDocumentSize] | 12,169 b → 127,215 b | 49 b → 49 b |
 * | [aSetsFrameIsFlatInTheStoredSubtreesSize] | 12,588 b → 127,634 b | 266 b → 266 b |
 *
 * **Why the law alone cannot see that, and why that is not a defect in the law.** Reinstating the
 * #2111 defect (make `set` return `Patch(setWhole(…))`) leaves **every law test and both
 * convergence tests green**; only the four flat-frame measurements and
 * [removingAnAbsentKeyYieldsTheLatticeIdentity] go red. Re-verified after the repoint above.
 *
 * That green is *correct*, not a symptom. A whole state is a perfectly valid delta — it dominates
 * itself in the lattice, so `X.piece(whole(X)) == whole(X)` holds by construction for any state
 * lattice whatsoever. No law over `piece` can ever separate a minimal delta from a maximal one;
 * only a measurement of the frame can. So the defect's signature is bytes, not correctness, and the
 * five assertions above are the ones standing between this change and a no-op that looks fully
 * pinned. The controls that prove the *law* arms are alive are the delta-path mutations in the
 * table above — a different question, needing a different mutation.
 *
 * **The shuffle/duplication property is verified here, not assumed.** #2111 was filed while
 * `ORMap.piece` — and therefore `JsonCrdt.piece` — was non-associative on the value axis, so the
 * issue asked for the stronger property to be *checked* rather than carried over from the other
 * three types. #2086 has since made the join associative at every depth of the document
 * (`JsonCrdtTest.pieceIsAssociativeOverCausallyRelatedTrajectories` measures 0 violations in
 * 39,232 ancestor triples, against 280 before). [setDeltasConvergeUnderShuffledAndDuplicatedDelivery]
 * and [removeDeltasConvergeUnderShuffledAndDuplicatedDelivery] confirm the consequence directly:
 * deltas delivered shuffled **and duplicated** encode identically to the whole-document fold. The
 * property holds.
 *
 * @see ORMapDeltaMutatorLawTest for the same shape one level down.
 */
@OptIn(ExperimentalSerializationApi::class)
class JsonCrdtDeltaMutatorLawTest {

    private val alpha = ReplicaId("alpha")
    private val bravo = ReplicaId("bravo")
    private val charlie = ReplicaId("charlie")

    private val cbor = Cbor {}

    /** The leaf a nested write adds at [FRESH_FIELD]. */
    private val addition: JsonNode.Leaf = scalar("W-fresh", "v")

    private fun bytes(doc: JsonCrdt): ByteArray = cbor.encodeToByteArray(JsonCrdt.serializer(), doc)

    // ── the law ───────────────────────────────────────────────────────────────────

    @Test
    fun setDeltaSatisfiesTheMutatorLawOnBytes() {
        val random = Random(2111)
        var uid = 0
        var multiTagTrials = 0

        repeat(LAW_TRIALS) { trial ->
            val state = randomState(random) { uid++ }
            val key = KEYS.random(random)
            val node = randomNode(random, uid++)
            if (state.tagsOn(key).size > 1) multiTagTrials++

            val viaFull = state.setWhole(key, node)
            val viaDelta = state.piece(state.set(key, node))

            assertEquals(viaFull, viaDelta, "trial $trial: set law by equality, key=$key")
            assertTrue(
                bytes(viaFull).contentEquals(bytes(viaDelta)),
                "trial $trial: set law by bytes, key=$key — documents are equal but encode differently",
            )
        }

        assertNonVacuous(multiTagTrials, "set")
    }

    @Test
    fun removeDeltaSatisfiesTheMutatorLawOnBytes() {
        val random = Random(2111)
        var uid = 0
        var multiTagTrials = 0

        repeat(LAW_TRIALS) { trial ->
            val state = randomState(random) { uid++ }
            val key = KEYS.random(random)
            if (state.tagsOn(key).size > 1) multiTagTrials++

            val viaFull = state.removeWhole(key)
            val viaDelta = state.piece(state.remove(key))

            assertEquals(viaFull, viaDelta, "trial $trial: remove law by equality, key=$key")
            assertTrue(
                bytes(viaFull).contentEquals(bytes(viaDelta)),
                "trial $trial: remove law by bytes, key=$key — documents are equal but encode differently",
            )
        }

        assertNonVacuous(multiTagTrials, "remove")
    }

    /** Removing a key the document never held is the lattice identity: absorbing it does nothing. */
    @Test
    fun removingAnAbsentKeyYieldsTheLatticeIdentity() {
        val state = JsonCrdt.empty(alpha)
            .setWhole("kept", scalar("W0", "x"))
            .setWhole("also-kept", scalar("W1", "y"))
        val identity = state.remove("never-set")

        assertAll(
            { assertEquals(emptySet<String>(), identity.delta.keys, "the delta itself holds no key") },
            { assertEquals(state, state.piece(identity), "absorbing it must change nothing") },
            {
                assertTrue(
                    bytes(state).contentEquals(bytes(state.piece(identity))),
                    "absorbing it must not even change the encoding",
                )
            },
        )
    }

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
        val written = scalar("W-fresh", "fresh")
        val smallFrame = setFrame(small.withReplica(bravo), "k-0", written)
        val largeFrame = setFrame(large.withReplica(bravo), "k-0", written)

        assertFlat(
            small = smallFrame,
            large = largeFrame,
            whole = bytes(large),
            what = "set frame over a $SMALL_STATE-key vs a $LARGE_STATE-key document",
        ) {
            assertAll(
                {
                    assertEquals(
                        setOf("k-0"),
                        decode(largeFrame).keys,
                        "the frame must carry the key that was written, and only it",
                    )
                },
                {
                    assertEquals(
                        written,
                        decode(largeFrame)["k-0"],
                        "\u2026holding the node that was written — a size test alone cannot see this",
                    )
                },
                {
                    assertEquals(
                        decode(smallFrame).keys,
                        decode(largeFrame).keys,
                        "both frames must carry the same write, or the two sizes are not comparable",
                    )
                },
            )
        }
    }

    /**
     * The same for [JsonCrdt.remove]'s frame.
     *
     * Its vacuity arm cannot be "the frame holds the key" — a remove frame's store is bottom by
     * design. The only claim that separates it from an empty frame is what absorbing it *does*: a
     * frame that retired nothing would be perfectly flat, and would leave the key standing.
     */
    @Test
    fun aRemovesFrameIsFlatInDocumentSize() {
        val small = documentOfSize(SMALL_STATE)
        val large = documentOfSize(LARGE_STATE)
        val smallFrame = removeFrame(small, "k-0")
        val largeFrame = removeFrame(large, "k-0")

        assertFlat(
            small = smallFrame,
            large = largeFrame,
            whole = bytes(large),
            what = "remove frame over a $SMALL_STATE-key vs a $LARGE_STATE-key document",
        ) {
            assertAll(
                {
                    assertEquals(
                        emptySet<String>(),
                        decode(largeFrame).keys,
                        "a remove frame's store is bottom — it says what to retire, not what to add",
                    )
                },
                {
                    assertTrue(
                        "k-0" !in large.piece(decode(largeFrame)).keys,
                        "absorbing the frame must actually retire the key \u2014 a frame retiring " +
                            "nothing would be flat too, and this is the only arm that can tell",
                    )
                },
                {
                    assertTrue(
                        "k-1" in large.piece(decode(largeFrame)).keys,
                        "\u2026and retire nothing else",
                    )
                },
                {
                    assertTrue(
                        "k-0" !in small.piece(decode(smallFrame)).keys,
                        "the same at the smaller size, or the two sizes are not comparable",
                    )
                },
            )
        }
    }

    /**
     * …and flat in the size of **what another replica has already stored under the key**.
     *
     * A `set` is additive over the node lattice, so the *receiver* ends up holding its own subtree
     * merged with the one written. The delta must carry only the node the caller passed: shipping
     * the locally merged result converges just as well and puts the receiver's own subtree back on
     * the wire, which is an O(stored subtree) frame wearing a delta's name.
     */
    @Test
    fun aSetsFrameIsFlatInTheStoredSubtreesSize() {
        val written = objectNode("W-fresh", "fresh" to scalar("W-fresh", "v"))
        val small = documentWithSubtreeOfSize(SMALL_STATE)
        val large = documentWithSubtreeOfSize(LARGE_STATE)

        assertAll(
            {
                val largeFrame = setFrame(large.withReplica(bravo), SUBTREE_KEY, written)
                assertFlat(
                    small = setFrame(small.withReplica(bravo), SUBTREE_KEY, written),
                    large = largeFrame,
                    whole = bytes(large),
                    what = "set frame over a $SMALL_STATE-field vs a $LARGE_STATE-field stored subtree",
                ) {
                    assertEquals(
                        written,
                        decode(largeFrame)[SUBTREE_KEY],
                        "the frame must carry the node that was written — decoded off the wire, not " +
                            "read back out of the patch the sender happens to be holding",
                    )
                }
            },
            {
                assertEquals(
                    written,
                    large.withReplica(bravo).set(SUBTREE_KEY, written).delta[SUBTREE_KEY],
                    "the delta must carry the caller's node verbatim, not the locally merged one",
                )
            },
            {
                // …and the receiver still lands on the merged subtree, because it re-does the merge.
                val author = large.withReplica(bravo)
                assertTrue(
                    bytes(author.setWhole(SUBTREE_KEY, written))
                        .contentEquals(bytes(author.piece(author.set(SUBTREE_KEY, written)))),
                    "the receiver re-does the node merge, so the law still holds — byte for byte",
                )
            },
        )
    }

    /**
     * **The one place the frame is *not* flat, measured rather than asserted away** — and the
     * reason #2469 exists.
     *
     * A write *inside* an existing [JsonNode.Object] has no mutator of its own. The obvious
     * spelling rebuilds the enclosing node and hands it to [JsonCrdt.set], so the frame is one key
     * whose value is the **whole rebuilt subtree** — O(the subtree), not O(the write). Everything
     * above is still true: the frame is one key, and flat in the rest of the document. This is the
     * remaining term.
     */
    @Test
    fun aNestedWritesFrameGrowsWithTheRebuiltSubtree() {
        val small = rebuiltNestedFrame(SMALL_STATE)
        val large = rebuiltNestedFrame(LARGE_STATE)

        assertTrue(
            large.size > small.size * NESTED_GROWTH_FLOOR,
            "rebuilding the enclosing object is O(subtree): the frame grew from ${small.size} b to " +
                "${large.size} b across $SMALL_STATE\u2192$LARGE_STATE fields. If this has gone flat, a " +
                "path-addressed mutator landed and #2469 can close",
        )
    }

    /**
     * The cheaper spelling, and **why it is not simply the answer**.
     *
     * The minimal nested frame is already expressible today: hand [JsonCrdt.set] the nested
     * [ORMap]'s own delta rather than the rebuilt map
     * (`JsonNode.Object(profile.map.put(…).delta)`). It is flat, and it reads back as the same
     * document. So #2469 is not a missing mechanism.
     *
     * But the two spellings are **not the same write**, which was worth finding out rather than
     * assuming — an earlier version of this test asserted they encoded identically, and they do
     * not. The rebuild parks the *whole merged subtree* on the tag it mints; the nested delta parks
     * only the field written. The difference is invisible until a concurrent [JsonCrdt.remove]
     * retires the *other* replica's tag: what survives is whatever the surviving tag was carrying,
     * so the rebuild keeps the entire subtree and the cheap spelling keeps one field. Neither is
     * wrong — they are different writes with different costs — and picking which one a
     * path-addressed mutator should mean is part of what #2469 has to settle.
     */
    @Test
    fun theCheapNestedSpellingIsFlatButKeepsLessAcrossAConcurrentRemove() {
        val doc = documentWithSubtreeOfSize(SMALL_STATE)
        val map = subtreeOf(doc)
        val viaRebuild = absorb(doc, JsonNode.Object(map.piece { it.put(bravo, FRESH_FIELD, addition) }))
        val viaNested = absorb(doc, JsonNode.Object(map.put(bravo, FRESH_FIELD, addition).delta))

        // alpha removes the key from its own copy, retiring the only tag it holds — bravo's write is
        // concurrent, so add-wins keeps the key, holding bravo's contribution alone.
        val removal = doc.remove(SUBTREE_KEY)
        val expected = (0 until SMALL_STATE).map { "f-$it" }.toSet() + FRESH_FIELD

        assertAll(
            {
                val largeFrame = nestedDeltaFrame(LARGE_STATE)
                assertFlat(
                    small = nestedDeltaFrame(SMALL_STATE),
                    large = largeFrame,
                    whole = bytes(documentWithSubtreeOfSize(LARGE_STATE)),
                    what = "set frame carrying the nested ORMap's own delta",
                ) {
                    assertEquals(
                        setOf(FRESH_FIELD),
                        (decode(largeFrame)[SUBTREE_KEY] as JsonNode.Object).map.keys,
                        "the frame must carry the one nested field written and nothing else — that " +
                            "is the whole claim this cheap spelling makes",
                    )
                }
            },
            {
                assertEquals(expected, subtreeOf(viaRebuild).keys, "the rebuild reads back whole")
            },
            {
                assertEquals(expected, subtreeOf(viaNested).keys, "\u2026and so does the cheap spelling")
            },
            {
                assertEquals(
                    expected,
                    subtreeOf(viaRebuild.piece(removal)).keys,
                    "past a concurrent remove the rebuild keeps the whole subtree \u2014 it is sitting on " +
                        "the tag that survived",
                )
            },
            {
                assertEquals(
                    setOf(FRESH_FIELD),
                    subtreeOf(viaNested.piece(removal)).keys,
                    "\u2026while the cheap spelling keeps only the field it wrote. Same document today, " +
                        "different document after a concurrent remove",
                )
            },
        )
    }

    // ── delivery-order independence (the property #2111 asked to check, not assume) ─

    /**
     * Three replicas, random **set** streams, every delta delivered **shuffled and duplicated**,
     * and the result compared byte-for-byte against the same op script folded through the
     * **whole-document mutators** — the path that shipped before this change.
     *
     * The reference is deliberately the whole-document fold and not the in-order *delta* fold. A
     * delta fold compared against itself is self-consistent under any mutation of the delta shape.
     */
    @Test
    fun setDeltasConvergeUnderShuffledAndDuplicatedDelivery() {
        val random = Random(23)
        var uid = 0

        repeat(CONVERGENCE_TRIALS) { trial ->
            val (deltas, fullStates) = randomStream(random, { uid++ }, withRemoves = false)

            val reference = fold(fullStates)
            val outOfOrder = fold((deltas + deltas).shuffled(random))

            assertTrue(
                bytes(reference).contentEquals(bytes(outOfOrder)),
                "trial $trial: ${deltas.size} set deltas, delivered shuffled and duplicated, must " +
                    "encode identically to the whole-document fold (reference=${reference.keys}, " +
                    "jumbled=${outOfOrder.keys})",
            )
        }
    }

    /** The same with removes mixed in — the arm that would have failed while #2086 was open. */
    @Test
    fun removeDeltasConvergeUnderShuffledAndDuplicatedDelivery() {
        val random = Random(29)
        var uid = 0
        var removeDeltas = 0

        repeat(CONVERGENCE_TRIALS) { trial ->
            val (deltas, fullStates) = randomStream(random, { uid++ }, withRemoves = true) { removeDeltas++ }

            val reference = fold(fullStates)
            val outOfOrder = fold((deltas + deltas).shuffled(random))

            assertTrue(
                bytes(reference).contentEquals(bytes(outOfOrder)),
                "trial $trial: ${deltas.size} deltas, delivered shuffled and duplicated, must encode " +
                    "identically to the whole-document fold (reference=${reference.keys}, " +
                    "jumbled=${outOfOrder.keys})",
            )
        }

        assertTrue(
            removeDeltas > 0,
            "vacuous: no remove delta was generated in $CONVERGENCE_TRIALS trials",
        )
    }

    /**
     * A concurrent set delta survives a remove delta in either order — add-wins on the key holds
     * through the delta path, and both orders encode identically.
     */
    @Test
    fun aConcurrentSetDeltaSurvivesARemoveDeltaInEitherOrder() {
        val start = JsonCrdt.empty(alpha)
            .setWhole("bystander", scalar("W0", "b"))
            .setWhole(KEY, scalar("W1", "v1"))

        val removal = start.remove(KEY).delta
        val concurrent = start.withReplica(bravo).set(KEY, scalar("W2", "v2")).delta

        val removeFirst = start.piece(removal).piece(concurrent)
        val setFirst = start.piece(concurrent).piece(removal)

        assertAll(
            { assertTrue(KEY in removeFirst.keys, "the set must win when the remove lands first") },
            { assertTrue(KEY in setFirst.keys, "the set must win when the set lands first") },
            {
                assertTrue(
                    "bystander" in removeFirst.keys && "bystander" in setFirst.keys,
                    "a remove delta must not retire tags belonging to any other key",
                )
            },
            {
                assertTrue(
                    bytes(removeFirst).contentEquals(bytes(setFirst)),
                    "both orders must encode identically, or the root-hash gate is off for the pair",
                )
            },
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    /**
     * The bytes a replicator would broadcast for `doc.set(key, node)` — a patch's delta is
     * broadcast verbatim, so this is the frame.
     */
    private fun setFrame(doc: JsonCrdt, key: String, node: JsonNode): ByteArray =
        bytes(doc.set(key, node).delta)

    /** The bytes a replicator would broadcast for `doc.remove(key)`. */
    private fun removeFrame(doc: JsonCrdt, key: String): ByteArray =
        bytes(doc.remove(key).delta)

    /** The root map's presence tags for [key] — how a trial knows it exercised a multi-tag key. */
    private fun JsonCrdt.tagsOn(key: String): Set<Dot> = root.tagsOn(key)

    /** The nested object stored at [SUBTREE_KEY]. */
    private fun subtreeOf(doc: JsonCrdt): ORMap<String, JsonNode> =
        (doc[SUBTREE_KEY] as JsonNode.Object).map

    /** [doc] with `bravo`'s write of [node] at [SUBTREE_KEY] absorbed. */
    private fun absorb(doc: JsonCrdt, node: JsonNode): JsonCrdt =
        doc.withReplica(bravo).let { it.piece(it.set(SUBTREE_KEY, node)) }

    /** The frame for the rebuild-the-enclosing-object spelling over a [fields]-field subtree. */
    private fun rebuiltNestedFrame(fields: Int): ByteArray {
        val doc = documentWithSubtreeOfSize(fields).withReplica(bravo)
        val map = subtreeOf(doc)
        return setFrame(doc, SUBTREE_KEY, JsonNode.Object(map.piece { it.put(bravo, FRESH_FIELD, addition) }))
    }

    /** The frame for the pass-the-nested-delta spelling over a [fields]-field subtree. */
    private fun nestedDeltaFrame(fields: Int): ByteArray {
        val doc = documentWithSubtreeOfSize(fields).withReplica(bravo)
        val map = subtreeOf(doc)
        return setFrame(doc, SUBTREE_KEY, JsonNode.Object(map.put(bravo, FRESH_FIELD, addition).delta))
    }

    /** A scalar leaf whose register dot is minted by [writer]. */
    private fun scalar(writer: String, value: String): JsonNode.Leaf =
        JsonNode.Leaf(MVRegister.empty<JsonValue>().set(ReplicaId(writer), JsonValue.Str(value)))

    /** An object node whose keys are minted by [writer], so nested dot spaces stay disjoint. */
    private fun objectNode(writer: String, vararg pairs: Pair<String, JsonNode>): JsonNode.Object =
        JsonNode.Object(
            pairs.fold(ORMap.empty<String, JsonNode>()) { acc, (k, v) ->
                acc.piece { it.put(ReplicaId(writer), k, v) }
            },
        )

    /** An array node whose insert ops are minted by [writer]. */
    private fun arrayNode(writer: String, vararg elements: JsonNode): JsonNode.Array =
        JsonNode.Array(
            elements.fold(Rga.empty<JsonNode>()) { acc, element ->
                acc.insertAfter(ReplicaId(writer), acc.sequence.lastOrNull() ?: RgaId.HEAD, element).first
            },
        )

    /**
     * A node of one of the three shapes, every dot minted under a [uid]-unique writer.
     *
     * All three shapes matter: `JsonNode` merges structurally, so a generator that only ever wrote
     * scalars would leave the recursive paths — a nested `ORMap`, a nested `Rga` — out of the law
     * entirely, and those are exactly where a delta that confused two dot spaces would show.
     */
    private fun randomNode(random: Random, uid: Int): JsonNode {
        val writer = "W$uid"
        val value = VALUES.random(random)
        return when (random.nextInt(3)) {
            0 -> scalar(writer, value)
            1 -> objectNode(writer, "p" to scalar(writer, value))
            else -> arrayNode(writer, scalar(writer, value))
        }
    }

    /**
     * A random document, built as the merge of two independently-grown branches so that keys both
     * branches touched carry **more than one** tag.
     *
     * That is the whole point of the shape: a set mints a single tag and supersedes the key's
     * previous ones *from the same replica*, so a document built by one replica alone never has a
     * multi-tag key, [ORMap.put]'s superseded-tags term is always a singleton, and the law would
     * hold vacuously against precisely the defect it exists to catch.
     *
     * Built with [JsonCrdt.setWhole]/[JsonCrdt.removeWhole], which reach [ORMap.putWhole] and
     * [ORMap.removeWhole] — no `putPatch`, no `removePatch` — so the generator never depends on the
     * mechanism the law is testing. That claim was **false** in the first cut of #2111, where those
     * two delegated to `root.piece { it.put(…) }` and therefore ran the delta path; see the file
     * KDoc.
     */
    private fun randomState(random: Random, uid: () -> Int): JsonCrdt {
        var left = JsonCrdt.empty(alpha)
        var right = JsonCrdt.empty(bravo)
        repeat(random.nextInt(2, 8)) {
            left = left.setWhole(KEYS.random(random), randomNode(random, uid()))
            right = right.setWhole(KEYS.random(random), randomNode(random, uid()))
        }
        var merged = left.piece(right).withReplica(charlie)
        repeat(random.nextInt(0, 3)) {
            val key = KEYS.random(random)
            merged = if (random.nextBoolean()) {
                merged.setWhole(key, randomNode(random, uid()))
            } else {
                merged.removeWhole(key)
            }
        }
        return merged
    }

    /**
     * One random op script run by three replicas writing concurrently, emitted **twice**: once as
     * the minimal deltas and once as the whole documents [JsonCrdt.setWhole] produces. Each op's
     * full document is delivered eagerly to a random subset of peers, so later operations supersede
     * tags minted elsewhere.
     */
    private fun randomStream(
        random: Random,
        uid: () -> Int,
        withRemoves: Boolean,
        onRemove: () -> Unit = {},
    ): Pair<List<JsonCrdt>, List<JsonCrdt>> {
        val replicas = listOf(alpha, bravo, charlie)
        val local = replicas.associateWith { JsonCrdt.empty(it) }.toMutableMap()
        val deltas = mutableListOf<JsonCrdt>()
        val fullStates = mutableListOf<JsonCrdt>()

        repeat(random.nextInt(3, 10)) {
            val author = replicas.random(random)
            val key = KEYS.random(random)
            val state = local.getValue(author)

            val advanced = if (withRemoves && key in state.keys && random.nextInt(3) == 0) {
                onRemove()
                deltas += state.remove(key).delta
                state.removeWhole(key)
            } else {
                val node = randomNode(random, uid())
                deltas += state.set(key, node).delta
                state.setWhole(key, node)
            }
            local[author] = advanced
            fullStates += advanced

            replicas.filter { it != author && random.nextBoolean() }.forEach { peer ->
                local[peer] = local.getValue(peer).piece(advanced)
            }
        }
        return deltas to fullStates
    }

    private fun fold(states: List<JsonCrdt>): JsonCrdt =
        states.fold(JsonCrdt.empty(alpha)) { acc, state -> acc.piece(state) }

    /** A document of [keyCount] scalar keys, all written by [alpha]. */
    private fun documentOfSize(keyCount: Int): JsonCrdt =
        (0 until keyCount).fold(JsonCrdt.empty(alpha)) { doc, index ->
            doc.setWhole("k-$index", scalar("W-$index", "v-$index"))
        }

    /** A one-key document whose value is a [JsonNode.Object] of [fieldCount] fields. */
    private fun documentWithSubtreeOfSize(fieldCount: Int): JsonCrdt {
        val subtree = (0 until fieldCount).fold(ORMap.empty<String, JsonNode>()) { map, index ->
            map.piece { it.put(alpha, "f-$index", scalar("W-$index", "v-$index")) }
        }
        return JsonCrdt.empty(alpha).setWhole(SUBTREE_KEY, JsonNode.Object(subtree))
    }

    /**
     * Asserts a frame's encoded size does not grow with the document it was built from: [large]
     * must be within [FLAT_TOLERANCE_PERCENT]% of [small], measured across an order of magnitude.
     * [whole] is reported for scale only — it is deliberately *not* what the assertion compares
     * against.
     *
     * [carriesTheWrite] is the vacuity arm, and it is the caller's job because only the caller
     * knows what its frame should contain. It has to be a claim that can be **false**: an earlier
     * version asserted `small.isNotEmpty() && large.isNotEmpty()`, which no input can violate —
     * `Cbor.encodeToByteArray` always emits at least a map header, so a frame carrying nothing at
     * all would have passed as "flat". Decode the frame and assert the write is in it.
     */
    private fun assertFlat(
        small: ByteArray,
        large: ByteArray,
        whole: ByteArray,
        what: String,
        carriesTheWrite: () -> Unit,
    ) {
        assertAll(
            {
                assertTrue(
                    large.size * 100 <= small.size * FLAT_TOLERANCE_PERCENT,
                    "$what: the frame must be flat in document size, but grew from ${small.size} b " +
                        "to ${large.size} b (the whole document at that size is ${whole.size} b)",
                )
            },
            carriesTheWrite,
        )
    }

    /** A frame as a receiver sees it — off the wire and back into a document. */
    private fun decode(frame: ByteArray): JsonCrdt =
        cbor.decodeFromByteArray(JsonCrdt.serializer(), frame)

    /** Fails if too few trials exercised a key carrying concurrent tags. */
    private fun assertNonVacuous(multiTagTrials: Int, mutator: String) {
        assertTrue(
            multiTagTrials >= MIN_MULTI_TAG_TRIALS,
            "$mutator law ran vacuously: only $multiTagTrials of $LAW_TRIALS trials mutated a key " +
                "carrying more than one tag, so the superseded-tags term was never exercised",
        )
    }

    private companion object {
        /** Trials per law test. */
        const val LAW_TRIALS = 400

        /** Trials per delivery-order test. */
        const val CONVERGENCE_TRIALS = 200

        /**
         * The floor the generator must clear for a law test to mean anything. **Measured: 139 of
         * 400 for `set` and 136 of 400 for `remove`** on seed 2111. Set at roughly half of that, so
         * an incidental generator tweak does not red-light the suite, but a generator that stopped
         * producing concurrent tags — and with it every case in which [ORMap.put]'s superseded-tags
         * term does any work — fails loudly instead of passing vacuously.
         */
        const val MIN_MULTI_TAG_TRIALS = 65

        /** A small pool, so branches collide and keys accumulate concurrent tags. */
        val KEYS = listOf("a", "b", "c", "d", "e", "f")

        /** Scalar payloads. Small — the value lattice is not what these tests are about. */
        val VALUES = listOf("p", "q", "r", "s")

        /** The single key the scenario tests use. */
        const val KEY = "k"

        /** The key holding a nested object when a test needs the stored subtree to be expensive. */
        const val SUBTREE_KEY = "profile"

        /** The field a nested write adds, and the leaf it writes there. */
        const val FRESH_FIELD = "fresh"

        /** The two sizes the frame tests measure across — an order of magnitude apart. */
        const val SMALL_STATE = 100
        const val LARGE_STATE = 1_000

        /**
         * How much a frame may grow across that order of magnitude. Not zero: the minted dot's
         * sequence number is a varint, so it costs a byte more at the larger size. Anything beyond
         * this is a term that scales with the document.
         */
        const val FLAT_TOLERANCE_PERCENT = 120

        /**
         * The factor the rebuild-the-enclosing-object spelling must grow the frame by across
         * [SMALL_STATE]→[LARGE_STATE] fields. **Measured at 12,507 b → 127,553 b, 10.2×.** Set well
         * below that, so the assertion fails when the frame has gone *flat* — which would mean a
         * path-addressed mutator landed and #2469 is done — rather than on encoding noise.
         */
        const val NESTED_GROWTH_FLOOR = 4
    }
}
