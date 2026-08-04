package us.tractat.kuilt.crdt

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import us.tractat.kuilt.test.assertAll

class LWWMapTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")
    private val c = ReplicaId("C")

    @Test
    fun emptyMap() {
        assertEquals(emptyMap<String, String>(), LWWMap.empty<String, String>().entries)
    }

    @Test
    fun setReturnsTheValue() {
        val m = LWWMap.empty<String, String>().set(a, 10L, "lang", "en")
        assertEquals("en", m["lang"])
        assertEquals(mapOf("lang" to "en"), m.entries)
    }

    @Test
    fun perKeyLwwSemantics_laterWins() {
        val m1 = LWWMap.empty<String, String>().set(a, 10L, "lang", "en")
        val m2 = LWWMap.empty<String, String>().set(b, 20L, "lang", "fr")
        assertEquals("fr", m1.piece(m2)["lang"])
        assertEquals("fr", m2.piece(m1)["lang"]) // commutative
    }

    @Test
    fun differentKeysComposeIndependently() {
        val m1 = LWWMap.empty<String, String>().set(a, 10L, "lang", "en")
        val m2 = LWWMap.empty<String, String>().set(b, 5L, "tz", "UTC")
        val merged = m1.piece(m2)
        assertEquals("en", merged["lang"])
        assertEquals("UTC", merged["tz"])
    }

    @Test
    fun missingKeyReturnsNull() {
        assertNull(LWWMap.empty<String, String>()["nope"])
    }

    @Test
    fun removeHidesTheKeyFromReads() {
        val m = LWWMap.empty<String, String>()
            .set(a, 10L, "lang", "en")
            .remove(a, 20L, "lang")
        assertAll(
            { assertNull(m["lang"]) },
            { assertEquals(emptyMap<String, String>(), m.entries) },
        )
    }

    @Test
    fun removeThenConcurrentPut_laterPutWins() {
        val base = LWWMap.empty<String, String>().set(a, 10L, "lang", "en")
        val removed = base.remove(a, 20L, "lang")
        val rewritten = base.set(b, 30L, "lang", "fr")
        assertAll(
            { assertEquals("fr", removed.piece(rewritten)["lang"]) },
            { assertEquals("fr", rewritten.piece(removed)["lang"]) },
        )
    }

    @Test
    fun putThenConcurrentRemove_laterRemoveWins() {
        val base = LWWMap.empty<String, String>().set(a, 10L, "lang", "en")
        val rewritten = base.set(b, 20L, "lang", "fr")
        val removed = base.remove(a, 30L, "lang")
        assertAll(
            { assertNull(removed.piece(rewritten)["lang"]) },
            { assertNull(rewritten.piece(removed)["lang"]) },
        )
    }

    @Test
    fun removeVsPutSameTimestamp_tieBreaksOnReplicaId() {
        // Same ts=20; B > A lexicographically, so B's remove beats A's put — both directions.
        val base = LWWMap.empty<String, String>().set(a, 10L, "lang", "en")
        val putByA = base.set(a, 20L, "lang", "fr")
        val removedByB = base.remove(b, 20L, "lang")
        assertAll(
            { assertNull(putByA.piece(removedByB)["lang"]) },
            { assertNull(removedByB.piece(putByA)["lang"]) },
        )
    }

    @Test
    fun removeOfAbsentKeyStillBeatsAnEarlierConcurrentPut() {
        // The remove must leave a tombstone even when the key was never set locally,
        // so a concurrent earlier-timestamped put arriving later still loses.
        val removed = LWWMap.empty<String, String>().remove(b, 20L, "lang")
        val put = LWWMap.empty<String, String>().set(a, 10L, "lang", "en")
        assertAll(
            { assertNull(removed.piece(put)["lang"]) },
            { assertNull(put.piece(removed)["lang"]) },
        )
    }

    @Test
    fun mergeWithTombstonesIsIdempotentAndCommutative() {
        val m1 = LWWMap.empty<String, String>()
            .set(a, 10L, "lang", "en")
            .remove(a, 20L, "lang")
        val m2 = LWWMap.empty<String, String>().set(b, 15L, "lang", "fr")
        assertAll(
            { assertEquals(m1, m1.piece(m1)) },
            { assertEquals(m1.piece(m2), m2.piece(m1)) },
            { assertNull(m1.piece(m2)["lang"]) },
        )
    }

    @Test
    fun deltaStateCarriesTheTombstone() {
        // A stale replica that only absorbs the post-remove state converges to removed.
        val base = LWWMap.empty<String, String>().set(a, 10L, "lang", "en")
        val removed = base.remove(a, 20L, "lang")
        assertNull(base.piece(removed)["lang"])
    }

    @Test
    fun tombstoneSurvivesJsonRoundTrip() {
        val m = LWWMap.empty<String, String>()
            .set(a, 10L, "lang", "en")
            .remove(a, 20L, "lang")
        val ser = LWWMap.serializer(String.serializer(), String.serializer())
        val decoded = Json.decodeFromString(ser, Json.encodeToString(ser, m))
        val stale = LWWMap.empty<String, String>().set(b, 15L, "lang", "fr")
        assertAll(
            { assertEquals(m, decoded) },
            { assertNull(decoded.piece(stale)["lang"]) }, // tombstone still wins after the wire
        )
    }

    @Test
    fun roundTripsThroughJson() {
        val m = LWWMap.empty<String, String>()
            .set(a, 10L, "lang", "en")
            .set(b, 20L, "tz", "UTC")
        val ser = LWWMap.serializer(String.serializer(), String.serializer())
        assertEquals(m, Json.decodeFromString(ser, Json.encodeToString(ser, m)))
    }

    // ── associativity of the join ─────────────────────────────────────────────────
    //
    // `(x ⊔ y) ⊔ z == x ⊔ (y ⊔ z)`: what lets a peer absorb whatever it is handed in whatever
    // grouping the network happens to deliver.
    //
    // `LWWMapLawsPropertyTest` (jvmTest) already asserts this law and already passes — but it is
    // green over a region that cannot contain a counterexample, so it is not evidence. Its provider
    // folds every generated state independently from `empty()`, so no operand is ever a **causal
    // ancestor** of another; and it derives each value deterministically from `(replica, timestamp,
    // key)`, so a repeated tag always carries the same write. `ORMapLawsPropertyTest` is green in
    // exactly the same way on an `ORMap` that is provably non-associative (#2086), where the
    // counterexample is a three-state ancestor chain — `a`, `a.remove(k)`, `a.remove(k).put(k, …)`
    // — that its generator cannot construct. Seven of the fourteen property suites share the design.
    //
    // So these tests deliberately cover what that one cannot: ancestor chains (below), reused tags,
    // and writes that move a replica DOWN the lattice (#2087). Each triple resolves through a
    // mechanism that could plausibly be order-sensitive, and each asserts on the encoded form as
    // well as on `equals`, because #1955's anti-entropy gate compares state *hashes*: two bracketings
    // that agree by value and disagree by bytes would switch that gate off for the pair.

    /**
     * The [ORMap] shape, applied to this type: a `remove` sits between two concurrent `set`s of one
     * key and wins.
     *
     * [LWWMap] survives it for a structural reason worth naming — [LWWMap.remove] retains a
     * tombstone **cell**, so the join's key set is a plain union and no merge can discard something
     * a differently-bracketed merge would have kept. Had `remove` deleted the key instead, the
     * removal would simply vanish into the lattice identity.
     */
    @Test
    fun pieceIsAssociativeAcrossAWinningTombstoneBetweenTwoSetsOfOneKey() {
        val early = LWWMap.empty<String, String>().set(a, 10L, "lang", "en")
        val removed = LWWMap.empty<String, String>().remove(c, 30L, "lang")
        val late = LWWMap.empty<String, String>().set(b, 20L, "lang", "fr")

        assertAll(
            { assertOrderIndependent(early, removed, late, "a winning tombstone bracketed between two sets") },
            { assertNull(early.piece(removed).piece(late)["lang"], "vacuity guard: the tombstone must win") },
        )
    }

    /**
     * The mirror image: a `set` between two concurrent `remove`s, revived by the largest tag. The
     * previous test would still pass if tombstones simply always won; this one would not.
     */
    @Test
    fun pieceIsAssociativeAcrossAWinningSetBetweenTwoTombstones() {
        val removedEarly = LWWMap.empty<String, String>().remove(a, 10L, "lang")
        val revived = LWWMap.empty<String, String>().set(c, 30L, "lang", "fr")
        val removedLate = LWWMap.empty<String, String>().remove(b, 20L, "lang")

        assertAll(
            { assertOrderIndependent(removedEarly, revived, removedLate, "a winning set between two tombstones") },
            { assertEquals("fr", removedEarly.piece(revived).piece(removedLate)["lang"], "vacuity guard") },
        )
    }

    /**
     * Three writes to one key at the **same timestamp** from three replicas: the only branch of
     * `LWWRegister.piece` a strictly-increasing clock never reaches, and the one where "pick the
     * larger tag" stops being decided by the tag's numeric half.
     */
    @Test
    fun pieceIsAssociativeWhenAnEqualTimestampIsBrokenOnReplicaId() {
        val byA = LWWMap.empty<String, String>().set(a, 7L, "lang", "en")
        val byB = LWWMap.empty<String, String>().set(b, 7L, "lang", "fr")
        val byC = LWWMap.empty<String, String>().set(c, 7L, "lang", "de")

        assertAll(
            { assertOrderIndependent(byA, byB, byC, "one timestamp, three replicas — the tie-break decides") },
            { assertEquals("de", byA.piece(byB).piece(byC)["lang"], "vacuity guard: C is the largest replica id") },
        )
    }

    /**
     * An operand that is strictly **below** where its own replica started.
     *
     * [LWWMap.set] assigns rather than joins, so a write whose tag loses moves the writer's state
     * down the lattice — the anomaly filed as #2087. That is a place a join could plausibly become
     * order-sensitive, since one operand is no longer above anything it once held, so the triple is
     * pinned here rather than assumed benign. It is not: associativity is unaffected. #2087 is a
     * defect of the *mutators*; associativity is a property of [LWWMap.piece] alone.
     */
    @Test
    fun pieceIsAssociativeAcrossAWriteWhoseTagLosesAndMovesItsReplicaDownTheLattice() {
        val converged = LWWMap.empty<String, String>().set(b, 30L, "seat", "north")
        val regressed = converged.set(a, 5L, "seat", "south")
        val peer = LWWMap.empty<String, String>().set(c, 20L, "seat", "east")

        assertAll(
            { assertEquals("south", regressed["seat"], "#2087: the losing write shows up locally…") },
            { assertEquals(converged, regressed.piece(converged), "…from strictly below — no join reaches it") },
            { assertOrderIndependent(regressed, converged, peer, "an operand below its own starting point") },
            { assertEquals("north", regressed.piece(converged).piece(peer)["seat"], "vacuity guard") },
        )
    }

    /**
     * One three-way join in which the three keys resolve three different ways at once — by
     * timestamp, by the equal-timestamp replica tie-break, and by a tombstone. A per-key lattice
     * that was order-sensitive only in the presence of a *mixture* would pass every single-key test
     * above and fail here.
     */
    @Test
    fun pieceIsAssociativeWhenEachKeyResolvesADifferentWay() {
        val peerA = LWWMap.empty<String, String>()
            .set(a, 10L, "lang", "en").set(a, 7L, "tz", "UTC").set(a, 10L, "theme", "dark")
        val peerB = LWWMap.empty<String, String>()
            .set(b, 20L, "lang", "fr").set(b, 7L, "tz", "CET").set(b, 15L, "theme", "light")
        val peerC = LWWMap.empty<String, String>()
            .set(c, 5L, "lang", "de").set(c, 7L, "tz", "JST").remove(c, 30L, "theme")

        assertAll(
            { assertOrderIndependent(peerA, peerB, peerC, "three keys resolving three different ways") },
            {
                assertEquals(
                    mapOf("lang" to "fr", "tz" to "JST"),
                    peerA.piece(peerB).piece(peerC).entries,
                    "vacuity guard: lang by timestamp, tz by the replica tie-break, theme by tombstone",
                )
            },
        )
    }

    /**
     * The `ORMap` counterexample transplanted **as an ancestor chain**, exhaustively over its tags:
     * `start`, `start.remove(k)`, and `start.remove(k).set(k, …)` — each state derived from the
     * previous one, all six orderings, every combination of three timestamps and three replicas.
     *
     * This is the region `LWWMapLawsPropertyTest` structurally cannot reach, and it is where
     * `ORMap`'s violation lives. [LWWMap] has no violation to find here for a structural reason:
     * unlike `ORMap`, it carries **no causal context** — a state is exhaustively described by its
     * per-key `(timestamp, origin, value)` cells and records nothing about where it came from — so a
     * derived state is just another map, and provenance cannot change how it joins.
     */
    @Test
    fun pieceIsAssociativeOverTheOrMapCounterexampleShapeAsAnAncestorChain() {
        var chains = 0
        var derivedStatesBelowTheirAncestor = 0

        for (first in CHAIN_TAGS) {
            for (second in CHAIN_TAGS) {
                for (third in CHAIN_TAGS) {
                    val start = LWWMap.empty<String, String>().set(first.second, first.first, "k", "v1")
                    val removed = start.remove(second.second, second.first, "k")
                    val rePut = removed.set(third.second, third.first, "k", "v2")
                    chains++
                    if (removed.piece(start) != removed) derivedStatesBelowTheirAncestor++
                    if (rePut.piece(removed) != rePut) derivedStatesBelowTheirAncestor++
                    assertAssociativeInEveryOrdering(
                        start,
                        removed,
                        rePut,
                        "ancestor chain set→remove→set, tags $first → $second → $third",
                    )
                }
            }
        }

        assertAll(
            { assertEquals(CHAIN_TAGS.size * CHAIN_TAGS.size * CHAIN_TAGS.size, chains, "every tag triple covered") },
            {
                assertNotVacuous(
                    derivedStatesBelowTheirAncestor,
                    1,
                    "chain steps landing BELOW their own ancestor (the #2087 down-move the chain must contain)",
                )
            },
        )
    }

    /**
     * Randomised **trajectories**: each state is reached *from* the previous one, so every operand in
     * every triple is a causal ancestor or descendant of the others — again, the region the existing
     * property suite cannot generate, searched here rather than argued.
     *
     * Only `equals` is compared, not the encoded form: at this triple count the encoding would
     * dominate the runtime on every target, and the encoded axis is already asserted by every other
     * test above.
     */
    @Test
    fun pieceIsAssociativeOverRandomisedAncestorChains() {
        val random = Random(CHAIN_SEED)
        var triples = 0
        var downMoves = 0

        repeat(CHAIN_TRAJECTORIES) {
            val chain = ArrayList<LWWMap<String, String>>(CHAIN_LENGTH + 1)
            var current = LWWMap.empty<String, String>()
            chain += current
            repeat(CHAIN_LENGTH) {
                val next = current.applied(randomOp(random))
                if (next.piece(current) != next) downMoves++
                current = next
                chain += current
            }
            chain.forEach { x ->
                chain.forEach { y ->
                    chain.forEach { z ->
                        triples++
                        assertEquals(x.piece(y.piece(z)), x.piece(y).piece(z), "not associative in a chain: $chain")
                    }
                }
            }
        }

        val statesPerChain = CHAIN_LENGTH + 1
        assertAll(
            {
                assertEquals(
                    CHAIN_TRAJECTORIES * statesPerChain * statesPerChain * statesPerChain,
                    triples,
                    "every triple of every trajectory must be searched",
                )
            },
            { assertNotVacuous(downMoves, MIN_CHAIN_DOWN_MOVES, "chain steps landing below their own ancestor") },
        )
    }

    /**
     * Randomised op streams from three replicas, split across three peers, each peer folded twice —
     * once in tag order and once shuffled, so some peers regress per #2087 — with every grouping and
     * every ordering required to agree, on state and on encoded form.
     *
     * The tag-ordered fold is additionally checked against an **oracle** computed outside the type
     * (per key, the value of the largest `(timestamp, replica)` tag). Order-independence alone would
     * be satisfied by a join that returned the empty map; the oracle pins what the join must compute.
     *
     * The counters exist because this is the test most easily made vacuous: a generator drifting onto
     * fresh keys per peer would make every join a plain insertion and the law would hold for free.
     */
    @Test
    fun pieceIsAssociativeOverRandomisedOpStreamsSplitAcrossThreePeers() {
        val random = Random(ASSOCIATIVITY_SEED)
        var contestedKeys = 0
        var replicaTieBreaks = 0
        var tombstoneWinners = 0
        var regressedPeers = 0

        repeat(ASSOCIATIVITY_TRIALS) { trial ->
            val ops = randomOps(random)
            val buckets = assignToPeers(ops, random)
            val monotone = buckets.map { fold(it.sortedWith(TAG_ORDER)) }
            val jumbled = buckets.map { fold(it.shuffled(random)) }

            val byKey = ops.groupBy { it.key }
            contestedKeys += byKey.count { (_, sameKey) -> sameKey.size > 1 }
            replicaTieBreaks += byKey.count { (_, sameKey) -> sameKey.distinctBy { it.timestamp }.size < sameKey.size }
            tombstoneWinners += byKey.count { (_, sameKey) -> sameKey.maxWith(TAG_ORDER).value == null }
            regressedPeers += buckets.indices.count { monotone[it] != jumbled[it] }

            assertOrderIndependent(monotone[0], monotone[1], monotone[2], "trial $trial, tag-ordered folds: $ops")
            assertOrderIndependent(jumbled[0], jumbled[1], jumbled[2], "trial $trial, shuffled folds: $ops")
            assertEquals(
                oracle(ops),
                monotone[0].piece(monotone[1]).piece(monotone[2]).entries,
                "trial $trial: the join must land on the largest tag per key, ops=$ops",
            )
        }

        assertAll(
            { assertNotVacuous(contestedKeys, MIN_CONTESTED_KEYS, "keys carrying two or more competing writes") },
            { assertNotVacuous(replicaTieBreaks, MIN_REPLICA_TIE_BREAKS, "keys decided by the replica-id tie-break") },
            { assertNotVacuous(tombstoneWinners, MIN_TOMBSTONE_WINNERS, "keys whose winning write is a tombstone") },
            { assertNotVacuous(regressedPeers, MIN_REGRESSED_PEERS, "peers moved down the lattice by a losing write") },
        )
    }

    /**
     * Associativity holds even where [LWWMap.set]'s tag-uniqueness precondition is **violated** —
     * exhaustively, over every triple drawn from twelve one-cell maps whose `(replica, timestamp)`
     * tags repeat with different payloads.
     *
     * The reason is worth stating, because it is what makes the guarantee robust rather than lucky:
     * `LWWRegister.piece` keeps `this` on an equal tag, which makes it a *leftmost* argmax over tags,
     * and a leftmost argmax is associative whether or not the order it maximises over is total.
     *
     * What a repeated tag costs instead is **commutativity** — asserted here as a non-vacuity guard,
     * so this test fails if a future change makes the reused-tag case symmetric and quietly turns the
     * exhaustive sweep into a sweep over well-behaved inputs. The observable shape is pinned by
     * [oneTagCarryingTwoValuesCostsCommutativityNotAssociativity].
     */
    @Test
    fun pieceStaysAssociativeEvenWhereTheTagUniquenessPreconditionIsViolated() {
        val atoms = listOf(a, b).flatMap { replica ->
            listOf(1L, 2L).flatMap { timestamp ->
                listOf(
                    LWWMap.empty<String, String>().set(replica, timestamp, "k", "x"),
                    LWWMap.empty<String, String>().set(replica, timestamp, "k", "y"),
                    LWWMap.empty<String, String>().remove(replica, timestamp, "k"),
                )
            }
        }
        val nonAssociative = atoms.flatMap { x ->
            atoms.flatMap { y -> atoms.map { z -> Triple(x, y, z) } }
        }.filter { (x, y, z) -> x.piece(y).piece(z) != x.piece(y.piece(z)) }
        val orderDependentPairs = atoms
            .flatMap { x -> atoms.map { y -> x to y } }
            .count { (x, y) -> x.piece(y) != y.piece(x) }

        assertAll(
            {
                assertEquals(
                    emptyList(),
                    nonAssociative.take(COUNTEREXAMPLES_TO_REPORT).map { (x, y, z) -> "$x ⊔ $y ⊔ $z" },
                    "${nonAssociative.size} of ${atoms.size * atoms.size * atoms.size} triples are non-associative",
                )
            },
            { assertNotVacuous(orderDependentPairs, 1, "reused-tag pairs whose two merge orders disagree") },
        )
    }

    /**
     * What a reused `(replica, timestamp)` actually costs: the two merge orders disagree.
     *
     * `LWWRegister.piece` breaks an equal tag with `else -> this`, so the **left** operand survives.
     * That is the documented non-determinism behind [LWWMap.set]'s tag-uniqueness precondition, and
     * it is a commutativity failure, not an associativity one — pinned here so the distinction
     * survives a future reading of "LWWMap's join is order-independent", which it is only while the
     * precondition holds.
     */
    @Test
    fun oneTagCarryingTwoValuesCostsCommutativityNotAssociativity() {
        val wroteX = LWWMap.empty<String, String>().set(a, 5L, "k", "x")
        val wroteY = LWWMap.empty<String, String>().set(a, 5L, "k", "y")
        val removed = LWWMap.empty<String, String>().remove(a, 5L, "k")

        assertAll(
            { assertEquals("x", wroteX.piece(wroteY)["k"], "an equal tag keeps the left operand…") },
            { assertEquals("y", wroteY.piece(wroteX)["k"], "…so the two merge orders disagree") },
            { assertNotEquals(wroteX.piece(wroteY), wroteY.piece(wroteX), "the join is not commutative here") },
            { assertEquals("x", wroteX.piece(removed)["k"], "a same-tag tombstone loses from the right…") },
            { assertNull(removed.piece(wroteX)["k"], "…and wins from the left") },
            { assertAssociative(wroteX, wroteY, removed, "yet no bracketing of the three can differ") },
            { assertAssociative(removed, wroteY, wroteX, "in either direction") },
        )
    }

    // ── associativity helpers ─────────────────────────────────────────────────────

    /**
     * `(x ⊔ y) ⊔ z == x ⊔ (y ⊔ z)`, by value **and** by encoded form, for the one given ordering.
     *
     * The encoded check is not redundant: #1955's anti-entropy gate compares hashes of the state as
     * it goes on the wire, so two bracketings that are `equals` but encode differently would silently
     * disable that gate for the pair.
     */
    private fun assertAssociative(
        x: LWWMap<String, String>,
        y: LWWMap<String, String>,
        z: LWWMap<String, String>,
        because: String,
    ): LWWMap<String, String> {
        val leftGrouped = x.piece(y).piece(z)
        val rightGrouped = x.piece(y.piece(z))
        assertEquals(rightGrouped, leftGrouped, "not associative — $because")
        assertEquals(encoded(rightGrouped), encoded(leftGrouped), "associative by value, not by bytes — $because")
        return leftGrouped
    }

    /**
     * Both bracketings agree in each of the six orderings — associativity alone, which is all that
     * survives a violated tag-uniqueness precondition. Returns the six results for a caller that
     * also wants to compare them.
     */
    private fun assertAssociativeInEveryOrdering(
        x: LWWMap<String, String>,
        y: LWWMap<String, String>,
        z: LWWMap<String, String>,
        because: String,
    ): List<LWWMap<String, String>> = listOf(
        Triple(x, y, z), Triple(x, z, y), Triple(y, x, z),
        Triple(y, z, x), Triple(z, x, y), Triple(z, y, x),
    ).map { (p, q, r) -> assertAssociative(p, q, r, because) }

    /**
     * All six orderings and both bracketings of a three-way join agree — associativity *and*
     * commutativity, which together are the property a replicator actually relies on. Only valid for
     * operands honouring [LWWMap.set]'s tag-uniqueness precondition; outside it, use
     * [assertAssociativeInEveryOrdering], which is all that survives.
     */
    private fun assertOrderIndependent(
        x: LWWMap<String, String>,
        y: LWWMap<String, String>,
        z: LWWMap<String, String>,
        because: String,
    ) {
        val merged = assertAssociativeInEveryOrdering(x, y, z, because)
        merged.forEach { assertEquals(merged.first(), it, "not commutative — $because") }
        merged.forEach { assertEquals(encoded(merged.first()), encoded(it), "commutative by value, not bytes — $because") }
    }

    private fun encoded(map: LWWMap<String, String>): String = Json.encodeToString(ASSOCIATIVITY_SERIALIZER, map)

    private fun assertNotVacuous(observed: Int, floor: Int, what: String) {
        assertTrue(
            observed >= floor,
            "ran vacuously: $observed $what, below the floor of $floor — the inputs have drifted onto " +
                "cases where the join does no work, so the law above is being asserted for free",
        )
    }

    /** One write: exactly the `(key, replica, timestamp) → value-or-tombstone` [LWWMap] records. */
    private data class Op(val key: String, val replica: ReplicaId, val timestamp: Long, val value: String?)

    private fun LWWMap<String, String>.applied(op: Op): LWWMap<String, String> =
        if (op.value == null) remove(op.replica, op.timestamp, op.key)
        else set(op.replica, op.timestamp, op.key, op.value)

    private fun fold(ops: List<Op>): LWWMap<String, String> =
        ops.fold(LWWMap.empty()) { map, op -> map.applied(op) }

    /**
     * A stream of writes over a small key pool and a small timestamp range, so keys collide and
     * equal timestamps from different replicas are common. The generator enforces the tag-uniqueness
     * precondition — one `(key, replica, timestamp)` is written at most once — because the reused-tag
     * case is a documented contract violation with its own tests above, and mixing it in here would
     * make the commutativity half of [assertOrderIndependent] fail for the wrong reason.
     */
    private fun randomOps(random: Random): List<Op> {
        val used = mutableSetOf<Triple<String, ReplicaId, Long>>()
        return buildList {
            repeat(random.nextInt(MIN_OPS, MAX_OPS)) {
                val op = randomOp(random)
                if (used.add(Triple(op.key, op.replica, op.timestamp))) add(op)
            }
        }
    }

    /**
     * One write over the same small space. Used un-deduplicated by the ancestor-chain search, where
     * a repeated tag is *wanted*: associativity is the only law claimed there, and it is the law
     * that survives the precondition being violated.
     */
    private fun randomOp(random: Random): Op {
        val key = KEYS.random(random)
        val replica = REPLICAS.random(random)
        val timestamp = random.nextLong(1L, TIMESTAMP_CEILING)
        val tombstone = random.nextInt(REMOVE_IN) == 0
        return Op(key, replica, timestamp, if (tombstone) null else VALUES.random(random))
    }

    private fun assignToPeers(ops: List<Op>, random: Random): List<List<Op>> {
        val buckets = List(PEERS) { mutableListOf<Op>() }
        ops.forEach { buckets[random.nextInt(PEERS)] += it }
        return buckets
    }

    /** What the join must produce, computed outside the type: per key, the largest tag's value. */
    private fun oracle(ops: List<Op>): Map<String, String> =
        ops.groupBy { it.key }
            .mapNotNull { (key, sameKey) -> sameKey.maxWith(TAG_ORDER).value?.let { key to it } }
            .toMap()

    private companion object {
        val ASSOCIATIVITY_SERIALIZER = LWWMap.serializer(String.serializer(), String.serializer())

        /** [LWWMap]'s own tag order: timestamp first, replica id as the tie-break. */
        val TAG_ORDER: Comparator<Op> = compareBy({ it.timestamp }, { it.replica.value })

        const val ASSOCIATIVITY_SEED = 31
        const val ASSOCIATIVITY_TRIALS = 300
        const val PEERS = 3

        /** A small pool, so writes contend on a cell rather than always inserting a fresh one. */
        val KEYS = listOf("lang", "tz", "theme")
        val VALUES = listOf("en", "fr", "de", "UTC", "dark", "light")
        val REPLICAS = listOf(ReplicaId("A"), ReplicaId("B"), ReplicaId("C"))

        const val MIN_OPS = 3
        const val MAX_OPS = 10

        /** Small enough that two replicas routinely pick the same timestamp for one key. */
        const val TIMESTAMP_CEILING = 6L

        /** One write in this many is a removal. */
        const val REMOVE_IN = 4

        // Floors for the non-vacuity counters, set at roughly half of what seed 31 measures, so an
        // incidental generator tweak does not red-light the suite but a drift onto uncontested inputs
        // does. Measured on seed 31 over 300 trials: contested keys 526, replica tie-breaks 162,
        // tombstone winners 206, regressed peers 144.
        const val MIN_CONTESTED_KEYS = 260
        const val MIN_REPLICA_TIE_BREAKS = 80
        const val MIN_TOMBSTONE_WINNERS = 100
        const val MIN_REGRESSED_PEERS = 70

        const val COUNTEREXAMPLES_TO_REPORT = 5

        // ── ancestor-chain search ────────────────────────────────────────────────
        //
        // The region `LWWMapLawsPropertyTest` cannot generate: states derived from one another
        // rather than folded independently from `empty()`.

        /** Every `(timestamp, replica)` the chain search walks each of its three positions over. */
        val CHAIN_TAGS = listOf(1L, 2L, 3L).flatMap { timestamp ->
            listOf(ReplicaId("A"), ReplicaId("B"), ReplicaId("C")).map { timestamp to it }
        }

        const val CHAIN_SEED = 71
        const val CHAIN_TRAJECTORIES = 150
        const val CHAIN_LENGTH = 6

        /**
         * Chain steps that land strictly below the state they were derived from — the #2087
         * down-move. Without them the trajectories would all be monotone climbs and the chain search
         * would cover nothing the independent generators do not already reach. **Measured on seed
         * 71: 244 of the 900 steps taken.**
         */
        const val MIN_CHAIN_DOWN_MOVES = 120
    }
}
