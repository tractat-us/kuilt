package us.tractat.kuilt.crdt

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A document of MV registers two levels deep: `outer key → field → {dot ↦ value}`. */
private typealias Doc = CausalMap<String, DotMap<String, DotFun<Int>>>

/** An MV-register write: one fresh dot, retiring every dot the register held. */
private fun mvWrite(slot: CausalMap.Slot<DotFun<Int>>, value: Int): CausalMap.Change<DotFun<Int>> =
    CausalMap.Change(DotFun(mapOf(slot.mint() to value)), retired = slot.store.dots)

/** Write [value] at `/[outer]/[field]`, through a slot narrowed onto the field's register. */
private fun Doc.write(replica: ReplicaId, outer: String, field: String, value: Int): Patch<Doc> =
    apply(replica, outer, DotMap()) { slot ->
        val inner = mvWrite(slot.narrow(slot.store.entries[field] ?: DotFun()), value)
        CausalMap.Change(DotMap(mapOf(field to inner.store)), inner.retired)
    }

/** Remove the field `/[outer]/[field]`: no store, every dot it held retired. */
private fun Doc.removeField(replica: ReplicaId, outer: String, field: String): Patch<Doc> =
    apply(replica, outer, DotMap()) { slot ->
        CausalMap.Change(DotMap(), retired = slot.store.entries[field]?.dots.orEmpty())
    }

private fun Doc.valuesAt(outer: String, field: String): Set<Int> =
    this[outer]?.entries?.get(field)?.values?.values?.toSet().orEmpty()

class CausalMapTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")
    private val c = ReplicaId("C")

    private fun Doc.then(mutate: (Doc) -> Patch<Doc>): Doc = piece(mutate(this))

    private val pollByA: Doc = CausalMap.empty<String, DotMap<String, DotFun<Int>>>()
        .then { it.write(a, "poll", "question", 1) }
        .then { it.write(a, "poll", "votes", 3) }

    /**
     * #3126's first probe. A removes the poll and recreates it, writing `votes=5`; B, concurrently,
     * writes `votes=4` on the old poll. Neither saw the other, so both survive as a conflict — on a
     * per-value context A's recreated `votes` reissued B's already-seen dot and the conflict was lost.
     */
    @Test
    fun aConflictSurvivesARemoveAndRecreate() {
        val byA = pollByA
            .then { it.remove("poll") }
            .then { it.write(a, "poll", "question", 1) }
            .then { it.write(a, "poll", "votes", 5) }
        val byB = pollByA.then { it.write(b, "poll", "votes", 4) }
        assertAll(
            { assertEquals(setOf(4, 5), byA.piece(byB).valuesAt("poll", "votes")) },
            { assertEquals(byA.piece(byB), byB.piece(byA)) },
        )
    }

    /**
     * #3126's second probe. A recreates `question`; B, concurrently, removes the old `question`
     * and adds `note`. B's remove retires only what B saw, so A's recreated `question` survives.
     */
    @Test
    fun aRecreatedFieldSurvivesAConcurrentInnerRemove() {
        val base = CausalMap.empty<String, DotMap<String, DotFun<Int>>>()
            .then { it.write(a, "poll", "question", 1) }
        val byA = base.then { it.remove("poll") }.then { it.write(a, "poll", "question", 2) }
        val byB = base.then { it.removeField(b, "poll", "question") }.then { it.write(b, "poll", "note", 9) }
        val merged = byA.piece(byB)
        assertAll(
            { assertEquals(setOf(2), merged.valuesAt("poll", "question")) },
            { assertEquals(setOf(9), merged.valuesAt("poll", "note")) },
            { assertEquals(merged, byB.piece(byA)) },
        )
    }

    /** Iain's ruling on #3126: a remove resets the subtree; only writes it had not seen survive. */
    @Test
    fun aRemoveResetsTheSubtreeAndKeepsOnlyUnseenWrites() {
        val byB = pollByA.then { it.write(b, "poll", "votes", 4) }
        val byA = pollByA.then { it.remove("poll") }
        val merged = byA.piece(byB)
        assertAll(
            { assertEquals(setOf("poll"), merged.keys) },
            { assertEquals(setOf("votes"), merged["poll"]?.entries?.keys) },
            { assertEquals(setOf(4), merged.valuesAt("poll", "votes")) },
        )
    }

    @Test
    fun aRemoveSeenByEveryoneLeavesNothing() {
        val merged = pollByA.then { it.remove("poll") }
        assertAll(
            { assertEquals(emptySet(), merged.keys) },
            { assertNull(merged["poll"]) },
        )
    }

    @Test
    fun removingAnAbsentKeyIsTheIdentity() {
        assertEquals(CausalMap.empty<String, DotMap<String, DotFun<Int>>>(), pollByA.remove("nope").delta)
    }

    /**
     * The frame names only what it touched: the minted dot and the dots it retired. Built by hand,
     * not from the delta path, so a delta that shipped the whole shared context reds here.
     */
    @Test
    fun aWriteDeltaNamesOnlyWhatItMintedAndRetired() {
        val delta = pollByA.write(b, "poll", "votes", 4).delta
        val votesDot = pollByA["poll"]?.entries?.get("votes")?.dots.orEmpty().single()
        val expected = CausalMap.empty<String, DotMap<String, DotFun<Int>>>().then {
            it.write(b, "poll", "votes", 4)
        }
        assertAll(
            { assertEquals(DotContext.of(votesDot, Dot(b, 1L)), delta.context) },
            { assertEquals(setOf("poll"), delta.keys) },
            { assertEquals(expected["poll"], delta["poll"]) },
        )
    }

    /**
     * A write's frame does not grow with the rest of the map. Each extra key is written by its own
     * replica, so a frame that carried the shared context would grow by one version-vector entry
     * per key.
     */
    @Test
    fun aWriteFrameIsFlatInTheSizeOfTheMap() {
        val ser = CausalMap.serializer(String.serializer(), DotMap.serializer(String.serializer(), DotFun.serializer(Int.serializer())))
        fun grown(keys: Int): Doc =
            (0 until keys).fold(pollByA) { doc, i -> doc.then { it.write(ReplicaId("w$i"), "k$i", "f", i) } }
        val small = Cbor.encodeToByteArray(ser, grown(1).write(b, "poll", "votes", 4).delta)
        val large = Cbor.encodeToByteArray(ser, grown(64).write(b, "poll", "votes", 4).delta)
        assertEquals(small.size, large.size)
    }

    /** The delta-mutator law against a hand-built whole state. */
    @Test
    fun aWriteDeltaReproducesTheWholeWrite() {
        val votesDot = pollByA["poll"]?.entries?.get("votes")?.dots.orEmpty().single()
        val questionDot = pollByA["poll"]?.entries?.get("question")?.dots.orEmpty().single()
        val written = pollByA.piece(pollByA.write(b, "poll", "votes", 4))
        val expectedPoll = DotMap<String, DotFun<Int>>(
            mapOf(
                "question" to DotFun(mapOf(questionDot to 1)),
                "votes" to DotFun(mapOf(Dot(b, 1L) to 4)),
            ),
        )
        assertAll(
            { assertEquals(setOf("poll"), written.keys) },
            { assertEquals(expectedPoll, written["poll"]) },
            { assertEquals(DotContext.of(questionDot, votesDot, Dot(b, 1L)), written.context) },
        )
    }

    // ── The checks: each names a shape the #3126 bug takes. ────────────────────────────────────

    /** The old idiom: mint from an empty context, over a field that still holds `(A,1)`. */
    @Test
    fun aLiveDotReissuedWithANewPayloadIsRejected() {
        val reissued = DotContext.EMPTY.nextDot(a)
        assertAll(
            { assertTrue(pollByA.context.contains(reissued), "precondition: the reissued dot is already seen") },
            {
                assertFailsWith<IllegalArgumentException> {
                    pollByA.apply(a, "poll", DotMap()) { _ ->
                        CausalMap.Change(DotMap(mapOf("question" to DotFun(mapOf(reissued to 7)))))
                    }
                }
            },
        )
    }

    /** The same reissued dot, written under a different field: both fields would vanish. */
    @Test
    fun aLiveDotReissuedAtAnotherPositionIsRejected() {
        val reissued = DotContext.EMPTY.nextDot(a)
        assertFailsWith<IllegalArgumentException> {
            pollByA.apply(a, "poll", DotMap()) { _ ->
                CausalMap.Change(DotMap(mapOf("other" to DotFun(mapOf(reissued to 1)))))
            }
        }
    }

    /** After a remove, `(A,1)` is witnessed and held nowhere; writing it again would be a lost put. */
    @Test
    fun aRetiredDotWrittenAgainIsRejected() {
        val removed = pollByA.then { it.remove("poll") }
        val reissued = DotContext.EMPTY.nextDot(a)
        assertFailsWith<IllegalArgumentException> {
            removed.apply(a, "poll", DotMap()) { _ ->
                CausalMap.Change(DotMap(mapOf("question" to DotFun(mapOf(reissued to 2)))))
            }
        }
    }

    @Test
    fun aDotFromAnotherContextIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            pollByA.apply(b, "poll", DotMap()) { _ ->
                CausalMap.Change(DotMap(mapOf("votes" to DotFun(mapOf(Dot(c, 99L) to 1)))))
            }
        }
    }

    @Test
    fun retiringADotTheKeyDoesNotHoldIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            pollByA.apply(b, "elsewhere", DotMap()) { _ ->
                CausalMap.Change(DotMap(), retired = setOf(Dot(a, 1L)))
            }
        }
    }

    @Test
    fun aMintedDotThatIsNotWrittenIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            pollByA.apply(b, "poll", DotMap()) { slot ->
                slot.mint()
                CausalMap.Change(DotMap())
            }
        }
    }

    @Test
    fun aSlotUsedAfterApplyReturnsThrows() {
        var escaped: CausalMap.Slot<DotMap<String, DotFun<Int>>>? = null
        pollByA.apply(b, "poll", DotMap()) { slot ->
            escaped = slot
            CausalMap.Change(DotMap())
        }
        assertFailsWith<IllegalStateException> { escaped?.mint() }
    }

    @Test
    fun aBottomThatHoldsDotsIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            pollByA.apply(b, "poll", DotMap(mapOf("x" to DotFun(mapOf(Dot(b, 1L) to 1))))) { _ ->
                CausalMap.Change(DotMap())
            }
        }
    }

    /** Two dots in one change — a presence dot and a value dot, the shape sub-issue (d) needs. */
    @Test
    fun aChangeMayMintSeveralDots() {
        val delta = CausalMap.empty<String, DotMap<String, DotFun<Int>>>().apply(a, "k", DotMap()) { slot ->
            CausalMap.Change(DotMap(mapOf("p" to DotFun(mapOf(slot.mint() to 0)), "v" to DotFun(mapOf(slot.mint() to 1)))))
        }.delta
        assertEquals(DotContext.of(Dot(a, 1L), Dot(a, 2L)), delta.context)
    }

    // ── A dot names one write. ───────────────────────────────────────────────────────────────

    /**
     * Across a random history of three replicas — writes, field removes and whole-key removes over
     * two outer keys, with concurrent remove and recreate of the same key and random gossip — no
     * dot is ever minted twice, and every replica converges once all have gossiped.
     *
     * Every pick is drawn from a sorted list, so the trajectory is a function of the seed on every
     * target (`forbidHashOrderedSeededDraw`).
     */
    @Test
    fun noDotIsEverMintedTwice() {
        val replicas = listOf(a, b, c)
        val outers = listOf("poll", "note")
        val fields = listOf("question", "votes")
        repeat(8) { seed ->
            val random = Random(seed)
            val states = MutableList(replicas.size) { CausalMap.empty<String, DotMap<String, DotFun<Int>>>() }
            val minted = mutableListOf<Dot>()
            repeat(120) {
                val i = random.nextInt(replicas.size)
                val state = states[i]
                val outer = outers[random.nextInt(outers.size)]
                states[i] = when (random.nextInt(5)) {
                    0, 1 -> {
                        val delta = state.write(replicas[i], outer, fields[random.nextInt(fields.size)], random.nextInt(10)).delta
                        // An MV write re-carries nothing, so every dot its delta stores was minted
                        // by it: a reissued dot shows up here as a duplicate.
                        minted += delta.keys.flatMap { delta[it]?.dots.orEmpty() }
                        state.piece(delta)
                    }
                    2 -> state.piece(state.removeField(replicas[i], outer, fields[random.nextInt(fields.size)]))
                    3 -> state.piece(state.remove(outer))
                    else -> state.piece(states[random.nextInt(replicas.size)])
                }
            }
            val converged = states.reduce { x, y -> x.piece(y) }
            assertAll(
                { assertEquals(minted.size, minted.toSet().size, "seed $seed minted a dot twice") },
                { assertEquals(converged, states.reversed().reduce { x, y -> x.piece(y) }, "seed $seed") },
                { assertTrue(minted.size > 20, "seed $seed minted only ${minted.size} dots") },
            )
        }
    }

    // ── Wire. ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun roundTripsThroughJson() {
        val json = Json { allowStructuredMapKeys = true }
        val ser = CausalMap.serializer(String.serializer(), DotMap.serializer(String.serializer(), DotFun.serializer(Int.serializer())))
        assertEquals(pollByA, json.decodeFromString(ser, json.encodeToString(ser, pollByA)))
    }

    /** A decoded state holds to the same rule `apply` and the join keep: no key maps to bottom. */
    @Test
    fun aDecodedBottomEntryIsRejected() {
        val json = Json { allowStructuredMapKeys = true }
        val inner = Causal.serializer(DotMap.serializer(String.serializer(), DotFun.serializer(Int.serializer())))
        val withBottom = Causal(DotMap(mapOf("empty" to DotFun<Int>())), DotContext.EMPTY)
        val encoded = json.encodeToString(inner, withBottom)
        val ser = CausalMap.serializer(String.serializer(), DotFun.serializer(Int.serializer()))
        assertFailsWith<IllegalArgumentException> { json.decodeFromString(ser, encoded) }
    }

    /** A `CausalMap` encodes as its bare `Causal`, so the dot family's canonical sorts apply. */
    @Test
    fun encodesAsTheBareCausal() {
        val inner = Causal.serializer(DotMap.serializer(String.serializer(), DotMap.serializer(String.serializer(), DotFun.serializer(Int.serializer()))))
        val ser = CausalMap.serializer(String.serializer(), DotMap.serializer(String.serializer(), DotFun.serializer(Int.serializer())))
        assertContentEquals(Cbor.encodeToByteArray(inner, pollByA.causal), Cbor.encodeToByteArray(ser, pollByA))
    }
}
