package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The delta-mutator law for [ORMap], asserted **on encoded bytes**:
 *
 * ```
 * X.piece(mᵟ(X)) == m(X)
 * ```
 *
 * for every state `X` and every mutator `m` — [ORMap.put] against [ORMap.putDelta], [ORMap.remove]
 * against [ORMap.removeDelta].
 *
 * **Why bytes and not just `equals`.** Two states can compare equal and still encode two ways. The
 * anti-entropy gate hashes the state *as it appears on the wire*, so a delta path that left two
 * peers logically equal but bytewise different would silently stop that gate engaging for the pair
 * — and every round drawing them would fall back to shipping full states, which is the cost this
 * whole mechanism exists to avoid. `equals` cannot see that; a byte comparison can.
 *
 * **Why the law alone is not enough here.** `Patch(map.put(…))` — the whole state — satisfies the
 * law perfectly; that is what ships today. So does a delta carrying the sender's *locally merged*
 * value instead of the caller's. Both converge and both throw the saving away, and no law, no
 * convergence property and no negative control in this file can tell. The law tests are therefore
 * paired with tests that measure **what actually goes on the wire** — see
 * [aPutDeltasFrameIsFlatInMapSize] and its two siblings, which are the only assertions standing
 * between this change and a no-op that looks fully pinned.
 *
 * Also pinned here: the two delta shapes proposed in #2044, each reconstructed from public API as a
 * negative control, so that reinstating either in production turns a named test red rather than
 * quietly diverging replicas.
 *
 * **One property [ORMap] does not have, and this suite does not claim.** Delivery order is
 * irrelevant to which keys are present and to their tags, but *not* to a key's value: [ORMap.piece]
 * is not associative on the value axis, a pre-existing defect reproducible on `main` with no delta
 * code at all (#2086). See [aRemoveDeltaRacingAPutDeltaIsOrderDependentOnTheValueAxis], which pins
 * the behaviour as measured rather than asserting a convergence [ORMap] cannot deliver.
 */
@OptIn(ExperimentalSerializationApi::class)
class ORMapDeltaMutatorLawTest {

    private val alpha = ReplicaId("alpha")
    private val bravo = ReplicaId("bravo")
    private val charlie = ReplicaId("charlie")

    private val cbor = Cbor {}
    private val gsetMapSerializer = ORMap.serializer(String.serializer(), GSet.serializer(String.serializer()))
    private val orsetMapSerializer = ORMap.serializer(String.serializer(), ORSet.serializer(String.serializer()))

    private fun bytes(map: ORMap<String, GSet<String>>): ByteArray =
        cbor.encodeToByteArray(gsetMapSerializer, map)

    private fun nestedBytes(map: ORMap<String, ORSet<String>>): ByteArray =
        cbor.encodeToByteArray(orsetMapSerializer, map)

    // ── the law ───────────────────────────────────────────────────────────────────

    @Test
    fun putDeltaSatisfiesTheMutatorLawOnBytes() {
        val random = Random(11)
        var multiTagTrials = 0

        repeat(LAW_TRIALS) { trial ->
            val state = randomState(random)
            val key = KEYS.random(random)
            val value = GSet.of(VALUES.random(random))
            if (state.tagsOn(key).size > 1) multiTagTrials++

            val viaFull = state.put(charlie, key, value)
            val viaDelta = state.piece(state.putDelta(charlie, key, value))

            assertEquals(viaFull, viaDelta, "trial $trial: put law by equality, key=$key")
            assertTrue(
                bytes(viaFull).contentEquals(bytes(viaDelta)),
                "trial $trial: put law by bytes, key=$key — states are equal but encode differently",
            )
        }

        assertNonVacuous(multiTagTrials, "put")
    }

    @Test
    fun removeDeltaSatisfiesTheMutatorLawOnBytes() {
        val random = Random(11)
        var multiTagTrials = 0

        repeat(LAW_TRIALS) { trial ->
            val state = randomState(random)
            val key = KEYS.random(random)
            if (state.tagsOn(key).size > 1) multiTagTrials++

            val viaFull = state.remove(key)
            val viaDelta = state.piece(state.removeDelta(key))

            assertEquals(viaFull, viaDelta, "trial $trial: remove law by equality, key=$key")
            assertTrue(
                bytes(viaFull).contentEquals(bytes(viaDelta)),
                "trial $trial: remove law by bytes, key=$key — states are equal but encode differently",
            )
        }

        assertNonVacuous(multiTagTrials, "remove")
    }

    @Test
    fun removingAnAbsentKeyYieldsTheLatticeIdentity() {
        val state = ORMap.empty<String, GSet<String>>()
            .put(alpha, "kept", GSet.of("x"))
            .put(bravo, "also-kept", GSet.of("y"))
        val identity = state.removeDelta("never-put")

        assertAll(
            {
                assertEquals(
                    ORMap.empty<String, GSet<String>>(),
                    identity.delta,
                    "the delta itself is bottom: empty store, empty context",
                )
            },
            { assertEquals(state, state.piece(identity), "absorbing it must change nothing") },
            {
                assertTrue(
                    bytes(state).contentEquals(bytes(state.piece(identity))),
                    "absorbing it must not even change the encoding",
                )
            },
        )
    }

    // ── what actually goes on the wire ────────────────────────────────────────────

    /**
     * [ORMap.put] merges the new value into the one already stored, because a put is additive over
     * the value lattice. The **delta must carry the caller's value**, not that merged result:
     * `ORMapEntry.join` re-does the merge at the receiver against *the receiver's* value, which is
     * the one that matters there.
     *
     * Shipping the sender's merged value still converges — no law here can see it — and it is
     * precisely the regression this test exists for: the delta would be O(stored value) again, and
     * on a nested `ORMap<K, ORSet<X>>` that is most of the saving gone.
     *
     * The pin is that two states differing *only* in how much is already stored under the key
     * produce **byte-identical** deltas.
     */
    @Test
    fun putDeltaCarriesTheSuppliedValueNotTheLocallyMergedOne() {
        val contributed = GSet.of("fresh")
        val small = ORMap.empty<String, GSet<String>>().put(alpha, KEY, GSet.of("x", "y"))
        val large = ORMap.empty<String, GSet<String>>()
            .put(alpha, KEY, GSet.of(*Array(BULKY_VALUE_SIZE) { "e$it" }))

        val fromSmall = small.putDelta(bravo, KEY, contributed)
        val fromLarge = large.putDelta(bravo, KEY, contributed)

        assertAll(
            {
                assertEquals(
                    contributed,
                    fromLarge.delta[KEY],
                    "the delta must carry the caller's value verbatim, not the locally merged one",
                )
            },
            {
                assertTrue(
                    bytes(fromSmall.delta).contentEquals(bytes(fromLarge.delta)),
                    "a put delta's bytes must not depend on how much is already stored under the key " +
                        "(${bytes(fromSmall.delta).size} b vs ${bytes(fromLarge.delta).size} b)",
                )
            },
            {
                // …and the receiver still lands on the merged value, because it re-does the merge.
                assertEquals(
                    large.put(bravo, KEY, contributed),
                    large.piece(fromLarge),
                    "the receiver re-does the value merge, so the law still holds",
                )
            },
            {
                assertTrue(
                    bytes(large.put(bravo, KEY, contributed)).contentEquals(bytes(large.piece(fromLarge))),
                    "…byte-for-byte",
                )
            },
        )
    }

    /**
     * A remove delta carries the removed key's tags and **nothing else** — not the sender's context,
     * not the tags of any other key. The pin is again byte-level: two states differing only in how
     * many *other* keys they hold produce identical remove deltas.
     */
    @Test
    fun removeDeltaCarriesOnlyTheRemovedKeysTags() {
        val small = bulkyMap(otherKeys = 1)
        val large = bulkyMap(otherKeys = BULKY_KEY_COUNT)

        val fromSmall = small.removeDelta(KEY)
        val fromLarge = large.removeDelta(KEY)

        assertAll(
            {
                assertTrue(
                    bytes(fromSmall.delta).contentEquals(bytes(fromLarge.delta)),
                    "a remove delta's bytes must not depend on the rest of the map " +
                        "(${bytes(fromSmall.delta).size} b vs ${bytes(fromLarge.delta).size} b)",
                )
            },
            {
                assertEquals(
                    emptySet<String>(),
                    fromLarge.delta.keys,
                    "a remove delta's store is bottom",
                )
            },
            {
                assertEquals(
                    large.remove(KEY),
                    large.piece(fromLarge),
                    "absorbing it must retire exactly that key",
                )
            },
        )
    }

    // ── the frame is flat in state size ───────────────────────────────────────────

    /**
     * The point of the whole change, and **the one thing the law cannot see**: a delta's frame is
     * *flat* in the size of the state it was built from. `Patch(map.put(…))` — the whole state —
     * satisfies `X.piece(mᵟ(X)) == m(X)` perfectly, so every law test, every convergence test and
     * every negative control in this file stays green while nothing at all has been saved.
     *
     * Measured at two map sizes an order of magnitude apart. Flatness, not "smaller than the full
     * state", is the invariant to assert: the latter would pass a change that reintroduced
     * O(entries) with a better constant.
     */
    @Test
    fun aPutDeltasFrameIsFlatInMapSize() {
        val small = mapOfSize(SMALL_STATE)
        val large = mapOfSize(LARGE_STATE)

        assertFlat(
            small = bytes(small.putDelta(bravo, "k-0", GSet.of("fresh")).delta),
            large = bytes(large.putDelta(bravo, "k-0", GSet.of("fresh")).delta),
            fullState = bytes(large.put(bravo, "k-0", GSet.of("fresh"))),
            what = "put delta over a $SMALL_STATE-key vs a $LARGE_STATE-key map",
        )
    }

    /** The same for [ORMap.removeDelta]. */
    @Test
    fun aRemoveDeltasFrameIsFlatInMapSize() {
        val small = mapOfSize(SMALL_STATE)
        val large = mapOfSize(LARGE_STATE)

        assertFlat(
            small = bytes(small.removeDelta("k-0").delta),
            large = bytes(large.removeDelta("k-0").delta),
            fullState = bytes(large.remove("k-0")),
            what = "remove delta over a $SMALL_STATE-key vs a $LARGE_STATE-key map",
        )
    }

    /**
     * …and flat in the size of the **value already stored under the key**, on the nested
     * `ORMap<K, ORSet<X>>` shape where that term dominates.
     *
     * This is the assertion that makes [putDeltaCarriesTheSuppliedValueNotTheLocallyMergedOne] a
     * performance guard rather than a stylistic one. Shipping the sender's locally merged value
     * converges perfectly well; it just puts the receiver's own history back on the wire, which is
     * an O(value) frame wearing a delta's name.
     */
    @Test
    fun aPutDeltasFrameIsFlatInTheStoredValuesSize() {
        val contributed = ORSet.empty<String>().add(bravo, "fresh")
        val small = nestedMapWithValueOfSize(SMALL_STATE)
        val large = nestedMapWithValueOfSize(LARGE_STATE)

        assertFlat(
            small = nestedBytes(small.putDelta(bravo, KEY, contributed).delta),
            large = nestedBytes(large.putDelta(bravo, KEY, contributed).delta),
            fullState = nestedBytes(large.put(bravo, KEY, contributed)),
            what = "put delta over a $SMALL_STATE-element vs a $LARGE_STATE-element nested value",
        )
    }

    // ── the two #2044 shapes, as negative controls ────────────────────────────────

    /**
     * #2044's `put` delta is `Causal(DotMap(mapOf(k to ORMapEntry(DotSet(setOf(dot)), v))),
     * DotContext.of(dot))` — the minted tag and nothing else. That is exactly what building the
     * delta on a *fresh* map produces, so the shape is reconstructible from public API and needs no
     * production method.
     *
     * It forgets every tag the re-put supersedes, the receiver keeps them, and a later remove — a
     * *correct* one — retires only the tag the remover knows about. The key comes back.
     */
    @Test
    fun aPutDeltaThatOmitsSupersededTagsResurrectsARemovedKey() {
        val issueShapeRePut: (ORMap<String, GSet<String>>) -> Patch<ORMap<String, GSet<String>>> = { _ ->
            // A fresh map has an empty context, so `put` here mints (alpha,1) and witnesses only
            // that — #2044's shape, with none of the tags the re-put actually supersedes.
            Patch(ORMap.empty<String, GSet<String>>().put(alpha, KEY, GSet.of("v2")))
        }

        assertAll(
            {
                assertTrue(
                    replayRePutThenRemove(issueShapeRePut),
                    "negative control: #2044's put delta must leave the key alive on the receiver",
                )
            },
            {
                assertFalse(
                    replayRePutThenRemove { state -> state.putDelta(alpha, KEY, GSet.of("v2")) },
                    "putDelta must carry the superseded tags, or a later remove resurrects the key",
                )
            },
        )
    }

    /**
     * #2044's `remove` delta is "the empty store with the context unchanged". That is not the delta
     * of *a* removal — it is the full state of a map from which **everything** has been removed, so
     * it too is reconstructible from public API: remove every key and keep the result.
     *
     * Joining it retires every tag the receiver holds, because the sender's context witnesses all
     * of them.
     */
    @Test
    fun aRemoveDeltaCarryingTheWholeContextWouldWipeTheReceiver() {
        val four = listOf("k1", "k2", "k3", "k4")
        val converged = four.fold(ORMap.empty<String, GSet<String>>()) { map, key ->
            map.put(alpha, key, GSet.of("v-$key"))
        }

        val patch = converged.removeDelta("k3")
        val sender = converged.remove("k3")     // the author's own mutator
        val receiver = converged.piece(patch)   // a converged peer absorbing the delta

        // The issue's shape: everything removed, context untouched.
        val issueShape = sender.keys.fold(sender) { map, key -> map.remove(key) }

        assertAll(
            {
                assertEquals(
                    emptySet<String>(),
                    converged.piece(issueShape).keys,
                    "negative control: #2044's remove delta must wipe all four",
                )
            },
            {
                assertEquals(
                    setOf("k1", "k2", "k4"),
                    receiver.keys,
                    "removeDelta must retire k3's tags and no others",
                )
            },
            {
                assertTrue(
                    bytes(sender).contentEquals(bytes(receiver)),
                    "sender and receiver must encode identically after the remove",
                )
            },
        )
    }

    // ── nested values ─────────────────────────────────────────────────────────────

    /**
     * A nested value has its **own** dot space, unrelated to the map's tags. Here the inner
     * [ORSet]'s dots are `(charlie,1)` and `(charlie,2)` — the very same `(replica, seq)` pairs
     * that tag two *other* keys of the enclosing map. A delta that confused the two spaces would
     * put those inner dots into the map's causal context and retire the two innocent keys on every
     * receiver.
     *
     * This is the shape `JsonCrdt` is built on — `JsonNode.Object` wraps `ORMap<String, JsonNode>`
     * — so a mistake here reaches a good deal further than [ORMap].
     */
    @Test
    fun aNestedValuesDotSpaceDoesNotLeakIntoTheMapsContext() {
        val innerByCharlie = ORSet.empty<String>().add(charlie, "c1").add(charlie, "c2")
        val map = ORMap.empty<String, ORSet<String>>()
            .put(charlie, "other", ORSet.empty<String>().add(alpha, "x"))   // map tag (charlie,1)
            .put(charlie, "third", ORSet.empty<String>().add(alpha, "y"))   // map tag (charlie,2)
            .put(alpha, KEY, innerByCharlie)                                // map tag (alpha,1)

        val contributed = ORSet.empty<String>().add(bravo, "b1")            // one element, one inner dot
        val patch = map.putDelta(bravo, KEY, contributed)
        val viaFull = map.put(bravo, KEY, contributed)
        val viaDelta = map.piece(patch)

        assertAll(
            {
                assertEquals(
                    setOf(KEY, "other", "third"),
                    viaDelta.keys,
                    "keys whose map tags collide with the nested value's dots must survive",
                )
            },
            { assertEquals(viaFull, viaDelta, "nested put law by equality") },
            {
                assertTrue(
                    nestedBytes(viaFull).contentEquals(nestedBytes(viaDelta)),
                    "nested put law by bytes",
                )
            },
            {
                assertEquals(
                    setOf("c1", "c2", "b1"),
                    assertNotNull(viaDelta[KEY]).elements,
                    "the receiver re-does the nested merge, so it holds every element",
                )
            },
            {
                assertEquals(
                    setOf("b1"),
                    assertNotNull(patch.delta[KEY]).elements,
                    "the delta carries only the contributed value — this is where the saving lives",
                )
            },
        )
    }

    /** Removing a nested key retires the map's tags for it and leaves every other key intact. */
    @Test
    fun removingANestedKeyRetiresOnlyItsMapTags() {
        val map = ORMap.empty<String, ORSet<String>>()
            .put(charlie, "other", ORSet.empty<String>().add(charlie, "x"))
            .put(alpha, KEY, ORSet.empty<String>().add(charlie, "c1"))

        val patch = map.removeDelta(KEY)
        val viaFull = map.remove(KEY)
        val viaDelta = map.piece(patch)

        assertAll(
            { assertEquals(setOf("other"), viaDelta.keys, "only the removed key goes") },
            {
                assertEquals(
                    setOf("x"),
                    assertNotNull(viaDelta["other"]).elements,
                    "the surviving key's nested value is untouched",
                )
            },
            { assertEquals(viaFull, viaDelta, "nested remove law by equality") },
            {
                assertTrue(
                    nestedBytes(viaFull).contentEquals(nestedBytes(viaDelta)),
                    "nested remove law by bytes",
                )
            },
        )
    }

    // ── delivery-order independence ───────────────────────────────────────────────

    /**
     * Three replicas, random **put** streams, every delta delivered **shuffled and duplicated**, and
     * the result compared byte-for-byte against the same op script folded through the **full
     * mutators** — the path that ships today. This is what licenses the design's claim that no
     * causal delivery, buffering or de-duplication is required above the lattice: a delta is an
     * element of the same semilattice as the state.
     *
     * The reference is deliberately the full-mutator fold and not the in-order *delta* fold. A delta
     * fold compared against itself is self-consistent under any mutation of the delta shape — it
     * passes just as happily when both sides are equally wrong, and pins nothing.
     */
    @Test
    fun putDeltasConvergeUnderShuffledAndDuplicatedDelivery() {
        val random = Random(23)

        repeat(CONVERGENCE_TRIALS) { trial ->
            val (deltas, fullStates) = randomStream(random, withRemoves = false)

            val reference = fold(fullStates)
            val outOfOrder = fold((deltas + deltas).shuffled(random))

            assertTrue(
                bytes(reference).contentEquals(bytes(outOfOrder)),
                "trial $trial: ${deltas.size} put deltas, delivered shuffled and duplicated, must " +
                    "encode identically to the full-mutator fold (reference=${reference.keys}, " +
                    "jumbled=${outOfOrder.keys})",
            )
        }
    }

    /**
     * The same with removes mixed in, again against the **full-mutator fold**. Key presence and the
     * map's tags agree under any delivery order, with any repeats — that is the observed-remove
     * lattice doing its job, and it is the part the delta shapes are responsible for.
     *
     * The **values** do not agree, and that is not this change's doing: [ORMap.piece] is not
     * associative on the value axis, because [ORMap.remove] discards a key's value while
     * `ORMapEntry.join` merges it whenever both sides still hold the key. Reproducible on `main`
     * with no delta code at all — see #2086, and
     * [aRemoveDeltaRacingAPutDeltaIsOrderDependentOnTheValueAxis] for the minimal case. Asserting
     * byte-identity here would be asserting a property [ORMap] does not have, so this test asserts
     * the one it does.
     */
    @Test
    fun removeDeltasConvergeOnKeyPresenceAndTagsUnderShuffledDelivery() {
        val random = Random(29)
        var removeDeltas = 0

        repeat(CONVERGENCE_TRIALS) { trial ->
            val (deltas, fullStates) = randomStream(random, withRemoves = true) { removeDeltas++ }

            val reference = fold(fullStates)
            val outOfOrder = fold((deltas + deltas).shuffled(random))

            assertEquals(
                tagsByKey(reference),
                tagsByKey(outOfOrder),
                "trial $trial: ${deltas.size} deltas, delivered shuffled and duplicated, must agree " +
                    "with the full-mutator fold on which keys are present and on their tags",
            )
        }

        assertTrue(
            removeDeltas > 0,
            "vacuous: no remove delta was generated in $CONVERGENCE_TRIALS trials",
        )
    }

    /**
     * A remove delta applied **before** the put it retires. [DotContext] is dot-exact — a contiguous
     * prefix per replica plus a cloud for the rest — so an early remove simply parks the retired tag
     * in the cloud and the late put is dropped on arrival. No buffering, no causal-delivery
     * requirement.
     *
     * The reference is built with the **full mutators**, not by folding the same deltas, so this is
     * the byte-level law generalised over delivery order rather than a comparison of a delta path
     * against itself.
     */
    @Test
    fun aRemoveDeltaAppliedBeforeThePutItRetiresConverges() {
        // A converged peer holding an unrelated key and an older copy of the one under test.
        val peer = ORMap.empty<String, GSet<String>>()
            .put(bravo, "bystander", GSet.of("b"))
            .put(bravo, KEY, GSet.of("v1"))

        // The author re-puts the key and then removes it, by the full mutators.
        val author = peer.put(alpha, KEY, GSet.of("v2")).remove(KEY)

        // The same two operations as deltas — the re-put supersedes bravo's tag.
        val put = peer.putDelta(alpha, KEY, GSet.of("v2")).delta
        val remove = peer.put(alpha, KEY, GSet.of("v2")).removeDelta(KEY).delta

        val orders = mapOf(
            "put,remove" to listOf(put, remove),
            "remove,put" to listOf(remove, put),
            "put,remove,put" to listOf(put, remove, put),
            "remove,put,remove" to listOf(remove, put, remove),
            "remove,remove,put" to listOf(remove, remove, put),
        )

        val checks: List<() -> Unit> = orders.map { (name, order) ->
            {
                val folded = order.fold(peer) { acc, delta -> acc.piece(delta) }
                assertTrue(
                    bytes(folded).contentEquals(bytes(author)),
                    "order [$name] must encode identically to the author's mutator-path state " +
                        "(got ${folded.keys}, author has ${author.keys})",
                )
            }
        }
        assertAll(*checks.toTypedArray())
    }

    /**
     * Add-wins on the key survives the delta path. A concurrent put mints a tag the remover never
     * witnessed, so it is absent from the remove delta's context and lives through the join — in
     * either order, with the same tags.
     */
    @Test
    fun aConcurrentPutDeltaSurvivesARemoveDeltaInEitherOrder() {
        val start = racingStart()
        val removal = start.removeDelta(KEY).delta                       // alpha retires the tag it can see
        val concurrent = start.putDelta(bravo, KEY, GSet.of("v2")).delta // bravo re-puts, minting a fresh tag

        val removeFirst = start.piece(removal).piece(concurrent)
        val putFirst = start.piece(concurrent).piece(removal)

        assertAll(
            { assertTrue(KEY in removeFirst.keys, "the put must win when the remove lands first") },
            { assertTrue(KEY in putFirst.keys, "the put must win when the put lands first") },
            {
                assertTrue(
                    "bystander" in removeFirst.keys && "bystander" in putFirst.keys,
                    "a remove delta must not retire tags belonging to any other key",
                )
            },
            {
                assertEquals(
                    tagsByKey(removeFirst),
                    tagsByKey(putFirst),
                    "both orders must agree on which keys are present and on their tags",
                )
            },
        )
    }

    /**
     * **A pre-existing [ORMap] defect, pinned rather than papered over — see #2086.** [ORMap.piece]
     * is not associative on the value axis: [ORMap.remove] drops a key's value along with its tags,
     * while `ORMapEntry.join` merges the two values whenever both sides still hold the key. So
     * whether the removed replica's value survives depends on the order the two operations are
     * applied in.
     *
     * It reproduces on `main` with **no delta code at all** — `start.piece(removed).piece(rePut)`
     * against `start.piece(removed.piece(rePut))`, where `rePut` is a peer that removed the key and
     * then put it back. What the delta form changes is how easy it is to reach: because a put delta
     * carries the *supplied* value rather than the sender's locally merged one (deliberately — that
     * is where the saving lives), **any** remove racing a put on the same key now lands here,
     * instead of only a peer that did remove-then-put locally.
     *
     * The two peers do reconcile: the next anti-entropy round joins their full states and unions the
     * values. Until then their `Quilter.stateRoot()`s disagree and #1955's gate is off for the pair.
     *
     * This test asserts the behaviour as measured. If #2086 changes it, this test is meant to go red.
     */
    @Test
    fun aRemoveDeltaRacingAPutDeltaIsOrderDependentOnTheValueAxis() {
        val start = racingStart()
        val removal = start.removeDelta(KEY).delta
        val concurrent = start.putDelta(bravo, KEY, GSet.of("v2")).delta

        val removeFirst = start.piece(removal).piece(concurrent)
        val putFirst = start.piece(concurrent).piece(removal)

        assertAll(
            {
                assertEquals(
                    setOf("v2"),
                    assertNotNull(removeFirst[KEY]).elements,
                    "remove-then-put: the removed replica's value is gone, so the delta's value stands alone",
                )
            },
            {
                assertEquals(
                    setOf("v1", "v2"),
                    assertNotNull(putFirst[KEY]).elements,
                    "put-then-remove: the key was still present when the delta landed, so the values merged",
                )
            },
            {
                assertFalse(
                    bytes(removeFirst).contentEquals(bytes(putFirst)),
                    "if these now encode identically, #2086 has been fixed — delete this test, " +
                        "restore the byte assertion in aConcurrentPutDeltaSurvivesARemoveDeltaInEitherOrder, " +
                        "and drop the concurrent-remove caveat from ORMap.putDelta's KDoc",
                )
            },
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    /**
     * Replays the #2044 resurrection scenario with [rePutDelta] standing in for the re-put's delta,
     * and reports whether the key survives on the receiver after a *correct* remove.
     *
     * 1. `alpha` and `bravo` converge on `{k ↦ (bravo,1)}`.
     * 2. `alpha` re-puts `k` locally and ships [rePutDelta].
     * 3. `alpha` removes `k` and ships a correct [ORMap.removeDelta].
     *
     * The author ends up without `k` under either delta shape; the question is the receiver.
     */
    private fun replayRePutThenRemove(
        rePutDelta: (ORMap<String, GSet<String>>) -> Patch<ORMap<String, GSet<String>>>,
    ): Boolean {
        val start = ORMap.empty<String, GSet<String>>().put(bravo, KEY, GSet.of("v1"))
        var author = start
        var receiver = start

        val rePut = rePutDelta(author)
        author = author.put(alpha, KEY, GSet.of("v2"))
        receiver = receiver.piece(rePut)

        val removal = author.removeDelta(KEY)
        author = author.piece(removal)
        receiver = receiver.piece(removal)

        assertFalse(KEY in author.keys, "the author must always drop the key it removed")
        return KEY in receiver.keys
    }

    /**
     * A random state, built as the merge of two independently-grown branches so that keys both
     * branches touched carry **more than one** tag.
     *
     * That is the whole point of the shape: [ORMap.put] mints a single tag and supersedes the key's
     * previous ones, so a state built by one replica alone never has a multi-tag key,
     * [ORMap.putDelta]'s superseded-tags term is always a singleton, and the law would hold
     * vacuously against precisely the defect it exists to catch.
     */
    private fun randomState(random: Random): ORMap<String, GSet<String>> {
        var left = ORMap.empty<String, GSet<String>>()
        var right = ORMap.empty<String, GSet<String>>()
        repeat(random.nextInt(2, 8)) {
            left = left.put(alpha, KEYS.random(random), GSet.of(VALUES.random(random)))
            right = right.put(bravo, KEYS.random(random), GSet.of(VALUES.random(random)))
        }
        var merged = left.piece(right)
        repeat(random.nextInt(0, 3)) {
            val key = KEYS.random(random)
            merged = if (random.nextBoolean()) {
                merged.put(charlie, key, GSet.of(VALUES.random(random)))
            } else {
                merged.remove(key)
            }
        }
        return merged
    }

    /**
     * The starting point for the two remove-races-put tests: `alpha` holds [KEY] and one bystander.
     * The bystander is there so a remove delta's context is a strict subset of the sender's history
     * — a delta that over-claimed would take the bystander down with it.
     */
    private fun racingStart(): ORMap<String, GSet<String>> =
        ORMap.empty<String, GSet<String>>()
            .put(alpha, "bystander", GSet.of("b"))
            .put(alpha, KEY, GSet.of("v1"))

    /**
     * One random op script run by three replicas writing concurrently, emitted **twice**: once as
     * the minimal deltas and once as the whole-state patches the full mutators produce today. Each
     * op's full state is delivered eagerly to a random subset of peers, so later operations
     * supersede tags minted elsewhere.
     *
     * Replicas advance along the **full-mutator** path, which makes that stream the independent
     * reference the delta stream is checked against. The two paths agree at every step anyway — that
     * is the law — so the deltas are derived from exactly the states their authors held.
     */
    private fun randomStream(
        random: Random,
        withRemoves: Boolean,
        onRemove: () -> Unit = {},
    ): Pair<List<ORMap<String, GSet<String>>>, List<ORMap<String, GSet<String>>>> {
        val replicas = listOf(alpha, bravo, charlie)
        val local = replicas.associateWith { ORMap.empty<String, GSet<String>>() }.toMutableMap()
        val deltas = mutableListOf<ORMap<String, GSet<String>>>()
        val fullStates = mutableListOf<ORMap<String, GSet<String>>>()

        repeat(random.nextInt(3, 10)) {
            val author = replicas.random(random)
            val key = KEYS.random(random)
            val state = local.getValue(author)

            val advanced = if (withRemoves && key in state.keys && random.nextInt(3) == 0) {
                onRemove()
                deltas += state.removeDelta(key).delta
                state.remove(key)
            } else {
                val value = GSet.of(VALUES.random(random))
                deltas += state.putDelta(author, key, value).delta
                state.put(author, key, value)
            }
            local[author] = advanced
            fullStates += advanced

            replicas.filter { it != author && random.nextBoolean() }.forEach { peer ->
                local[peer] = local.getValue(peer).piece(advanced)
            }
        }
        return deltas to fullStates
    }

    private fun fold(deltas: List<ORMap<String, GSet<String>>>): ORMap<String, GSet<String>> =
        deltas.fold(ORMap.empty()) { acc, delta -> acc.piece(delta) }

    /** Every present key with the map tags currently on it — the observed-remove lattice's content. */
    private fun tagsByKey(map: ORMap<String, GSet<String>>): Map<String, Set<Dot>> =
        map.keys.associateWith { map.tagsOn(it) }

    /** A map of [keyCount] keys, each holding a small value, all put by one replica. */
    private fun mapOfSize(keyCount: Int): ORMap<String, GSet<String>> =
        (0 until keyCount).fold(ORMap.empty()) { map, index ->
            map.put(alpha, "k-$index", GSet.of("v-$index"))
        }

    /** A one-key nested map whose value is an [ORSet] of [elementCount] elements. */
    private fun nestedMapWithValueOfSize(elementCount: Int): ORMap<String, ORSet<String>> {
        val value = (0 until elementCount).fold(ORSet.empty<String>()) { set, index ->
            set.add(charlie, "e-$index")
        }
        return ORMap.empty<String, ORSet<String>>().put(alpha, KEY, value)
    }

    /**
     * Asserts a delta's encoded size does not grow with the state it was built from: [large] must be
     * within [FLAT_TOLERANCE_PERCENT]% of [small], measured across an order of magnitude. [fullState]
     * is reported for scale only — it is deliberately *not* what the assertion compares against.
     */
    private fun assertFlat(small: ByteArray, large: ByteArray, fullState: ByteArray, what: String) {
        assertTrue(
            large.size * 100 <= small.size * FLAT_TOLERANCE_PERCENT,
            "$what: the delta must be flat in state size, but grew from ${small.size} b to " +
                "${large.size} b (the full state at that size is ${fullState.size} b)",
        )
    }

    /** [KEY] plus [otherKeys] unrelated keys, all tagged by the same replica in the same order. */
    private fun bulkyMap(otherKeys: Int): ORMap<String, GSet<String>> {
        val withTarget = ORMap.empty<String, GSet<String>>().put(alpha, KEY, GSet.of("v1"))
        return (0 until otherKeys).fold(withTarget) { map, index ->
            map.put(bravo, "other-$index", GSet.of("v-$index"))
        }
    }

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
         * The floor the generator must clear for a law test to mean anything. **Measured: 103 of
         * 400 for `put` and 102 of 400 for `remove`** on seed 11. Set at roughly half of that, so an
         * incidental generator tweak does not red-light the suite, but a generator that stopped
         * producing concurrent tags — and with it every case in which [ORMap.putDelta]'s
         * superseded-tags term does any work — fails loudly instead of passing vacuously.
         */
        const val MIN_MULTI_TAG_TRIALS = 50

        /** A small pool, so branches collide and keys accumulate concurrent tags. */
        val KEYS = listOf("a", "b", "c", "d", "e", "f")

        /** Value payloads. Small — the value lattice is not what these tests are about. */
        val VALUES = listOf("p", "q", "r", "s")

        /** The single key the scenario tests use. */
        const val KEY = "k"

        /** Elements stashed under [KEY] when a test needs the stored value to be expensive. */
        const val BULKY_VALUE_SIZE = 200

        /** Unrelated keys added when a test needs the rest of the map to be expensive. */
        const val BULKY_KEY_COUNT = 200

        /** The two state sizes the flat-frame tests measure across — an order of magnitude apart. */
        const val SMALL_STATE = 100
        const val LARGE_STATE = 1_000

        /**
         * How much a delta's frame may grow across that order of magnitude. Not zero: the minted
         * dot's sequence number is a varint, so it costs a byte more at the larger size. Anything
         * beyond this is a term that scales with the state.
         */
        const val FLAT_TOLERANCE_PERCENT = 120
    }
}
