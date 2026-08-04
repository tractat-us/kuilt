package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The delta-mutator law for [LWWMap], asserted **on encoded bytes**:
 *
 * ```
 * X.piece(mᵟ(X)) == m(X)
 * ```
 *
 * for every state `X` and every mutator `m` — [LWWMap.set] against [LWWMap.setDelta],
 * [LWWMap.remove] against [LWWMap.removeDelta].
 *
 * **Why bytes and not just `equals`.** Two states can compare equal and still encode two ways. The
 * anti-entropy gate hashes the state *as it appears on the wire*, so a delta path that left two
 * peers logically equal but bytewise different would silently stop that gate engaging for the pair
 * — and every round drawing them would fall back to shipping full states, which is the cost this
 * whole mechanism exists to avoid. `equals` cannot see that; a byte comparison can.
 *
 * **Why this type needs no causal reasoning.** Unlike `ORSet`/`ORMap`, [LWWMap] has no causal
 * context: [LWWMap.piece] is a per-key max of independent `(timestamp, replica)` tags, and
 * `LWWRegister.set` *replaces* rather than merges, so the cell a delta carries is the very cell
 * [LWWMap.set] would write. The delta is one cell and nothing else. Two shapes are nonetheless easy
 * to get wrong, and both are pinned below: a removal's delta is a one-cell **tombstone** map rather
 * than an empty one, and the law's domain is exactly a write whose tag *dominates* the key's
 * current tag — which is what [LWWMap.set]'s own precondition already requires.
 */
@OptIn(ExperimentalSerializationApi::class)
class LWWMapDeltaMutatorLawTest {

    private val alpha = ReplicaId("alpha")
    private val bravo = ReplicaId("bravo")

    private val cbor = Cbor {}
    private val mapSerializer = LWWMap.serializer(String.serializer(), String.serializer())

    private fun bytes(map: LWWMap<String, String>): ByteArray = cbor.encodeToByteArray(mapSerializer, map)

    // ── the law ───────────────────────────────────────────────────────────────────

    @Test
    fun setDeltaSatisfiesTheMutatorLawOnBytes() {
        val random = Random(11)
        var contended = 0
        var tieBreaks = 0

        repeat(LAW_TRIALS) { trial ->
            val clock = MonotoneClock()
            val state = randomState(random, clock)
            val key = KEYS.random(random)
            val tag = dominatingTag(state, key, clock, random)
            val value = VALUES.random(random)
            if (state.tags.containsKey(key)) contended++
            if (state.tags[key]?.timestamp == tag.timestamp) tieBreaks++

            val viaFull = state.map.set(tag.replica, tag.timestamp, key, value)
            val viaDelta = state.map.piece(state.map.setDelta(tag.replica, tag.timestamp, key, value))

            assertEquals(viaFull, viaDelta, "trial $trial: set law by equality, key=$key tag=$tag")
            assertTrue(
                bytes(viaFull).contentEquals(bytes(viaDelta)),
                "trial $trial: set law by bytes, key=$key tag=$tag — states are equal but encode differently",
            )
        }

        assertNonVacuous(contended, tieBreaks, "set")
    }

    @Test
    fun removeDeltaSatisfiesTheMutatorLawOnBytes() {
        val random = Random(11)
        var contended = 0
        var tieBreaks = 0

        repeat(LAW_TRIALS) { trial ->
            val clock = MonotoneClock()
            val state = randomState(random, clock)
            val key = KEYS.random(random)
            val tag = dominatingTag(state, key, clock, random)
            if (state.tags.containsKey(key)) contended++
            if (state.tags[key]?.timestamp == tag.timestamp) tieBreaks++

            val viaFull = state.map.remove(tag.replica, tag.timestamp, key)
            val viaDelta = state.map.piece(state.map.removeDelta(tag.replica, tag.timestamp, key))

            assertEquals(viaFull, viaDelta, "trial $trial: remove law by equality, key=$key tag=$tag")
            assertTrue(
                bytes(viaFull).contentEquals(bytes(viaDelta)),
                "trial $trial: remove law by bytes, key=$key tag=$tag — states are equal but encode differently",
            )
        }

        assertNonVacuous(contended, tieBreaks, "remove")
    }

    // ── the shape that is easy to get wrong ───────────────────────────────────────

    /**
     * A removal's delta is a one-cell **tombstone** map, never an empty one.
     *
     * The empty map is this lattice's identity: joining it changes nothing, so a delta of that
     * shape would carry no removal at all and the write would never leave its author. It is the
     * natural mis-transcription of `ORSet`/`ORMap`'s remove delta — whose store *is* empty, with
     * the retired dots travelling in the causal context [LWWMap] does not have — so it is pinned
     * here as a named negative control rather than left to a reviewer's eye.
     */
    @Test
    fun aRemoveDeltaIsAOneCellTombstoneNotAnEmptyMap() {
        val converged = LWWMap.empty<String, String>()
            .set(alpha, 1L, "lang", "en")
            .set(alpha, 2L, "tz", "UTC")

        val delta = converged.removeDelta(bravo, 3L, "lang").delta
        val emptyShape = LWWMap.empty<String, String>()
        val received = converged.piece(delta)

        assertAll(
            { assertNull(received["lang"], "the tombstone must reach a converged peer") },
            { assertEquals("UTC", received["tz"], "and must touch no other key") },
            {
                assertEquals(
                    "en",
                    converged.piece(emptyShape)["lang"],
                    "negative control: an empty-map delta is the identity and carries no removal at all",
                )
            },
            {
                assertFalse(
                    bytes(delta).contentEquals(bytes(emptyShape)),
                    "a removal's delta must not encode as the empty map",
                )
            },
            {
                assertTrue(
                    bytes(delta).contentEquals(bytes(LWWMap.empty<String, String>().remove(bravo, 3L, "lang"))),
                    "the delta is exactly the one-cell map a remove on an empty map produces",
                )
            },
        )
    }

    /**
     * [LWWMap.remove] records a tombstone even for a key it has never seen, so its delta does too.
     * This is where the analogy with `ORSet.removeDelta` — whose no-op *is* the lattice identity,
     * because there are no dots to retire — stops holding.
     */
    @Test
    fun removingAKeyThatWasNeverSetStillShipsATombstone() {
        val map = LWWMap.empty<String, String>().set(alpha, 1L, "tz", "UTC")
        val delta = map.removeDelta(bravo, 5L, "lang").delta
        val peerWithAnEarlierSet = LWWMap.empty<String, String>().set(alpha, 2L, "lang", "en")

        assertAll(
            { assertEquals(map.remove(bravo, 5L, "lang"), map.piece(delta), "the law still holds for an absent key") },
            {
                assertTrue(
                    bytes(map.remove(bravo, 5L, "lang")).contentEquals(bytes(map.piece(delta))),
                    "…and holds on bytes",
                )
            },
            { assertNotEquals(map, map.piece(delta), "absorbing it is not a no-op: the tombstone is recorded") },
            {
                assertNull(
                    peerWithAnEarlierSet.piece(delta)["lang"],
                    "an earlier-tagged concurrent set must still lose to the shipped tombstone",
                )
            },
        )
    }

    // ── the precondition, and the law's exact domain ──────────────────────────────

    /**
     * [LWWMap.set]'s tag-uniqueness precondition is exactly as load-bearing on the delta path as on
     * the full-state path — neither weakened nor waived.
     *
     * Honour it and the two paths agree byte for byte. Violate it — reuse one `(replica, timestamp)`
     * for two different values on one key — and they disagree, because `LWWRegister.piece` breaks an
     * equal tag with `else -> this` while [LWWMap.set] assigns unconditionally. That disagreement is
     * the documented non-determinism, not a new hazard: the delta form must not be read as making a
     * reused tag safe.
     */
    @Test
    fun theDeltaFormNeitherWeakensNorWaivesTheTagUniquenessPrecondition() {
        val honoured = LWWMap.empty<String, String>().set(alpha, 1L, "k", "first")

        val freshFull = honoured.set(alpha, 2L, "k", "second")
        val freshDelta = honoured.piece(honoured.setDelta(alpha, 2L, "k", "second"))

        val reusedFull = honoured.set(alpha, 1L, "k", "different")
        val reusedDelta = honoured.piece(honoured.setDelta(alpha, 1L, "k", "different"))

        assertAll(
            { assertEquals(freshFull, freshDelta, "a unique tag: the paths agree") },
            { assertTrue(bytes(freshFull).contentEquals(bytes(freshDelta)), "…and agree on bytes") },
            { assertEquals("different", reusedFull["k"], "set assigns, so the reused tag overwrites locally") },
            { assertEquals("first", reusedDelta["k"], "the join keeps the incumbent on an equal tag") },
            {
                assertNotEquals(
                    reusedFull,
                    reusedDelta,
                    "a reused tag still breaks the law — the precondition is unchanged, not relaxed",
                )
            },
        )
    }

    /**
     * The law's domain is exactly a write whose tag beats the key's current one, and outside it **no
     * delta can exist** — not this one, not a cleverer one.
     *
     * A delta is *joined*, and a join can only move up the lattice. [LWWMap.set] *assigns*, so a
     * write with a losing tag moves the writer's own state strictly **down**: below its starting
     * point, hence below anything reachable by joining. The delta path is where the full path ends
     * up anyway — one merge with any peer still holding the winning tag takes the assignment away,
     * which is precisely the silent drop this map's clock-skew warning already describes.
     */
    @Test
    fun aWriteWhoseTagLosesHasNoDeltaAndBothPathsConvergeAnyway() {
        val converged = LWWMap.empty<String, String>().set(bravo, 10L, "k", "winner")

        val laggingFull = converged.set(alpha, 5L, "k", "lagging")
        val laggingDelta = converged.piece(converged.setDelta(alpha, 5L, "k", "lagging"))

        assertAll(
            { assertEquals("lagging", laggingFull["k"], "set assigns, so a losing write shows up locally…") },
            { assertEquals("winner", laggingDelta["k"], "…while a delta is joined and can only move up") },
            {
                assertEquals(
                    converged,
                    laggingFull.piece(converged),
                    "the assigned state is strictly below where it started — no join reaches it",
                )
            },
            { assertEquals(converged, laggingDelta, "the delta path already sits at that limit") },
            {
                assertTrue(
                    bytes(converged).contentEquals(bytes(laggingFull.piece(converged))),
                    "so both paths reach the same bytes after one merge",
                )
            },
        )
    }

    // ── delivery order, and the size that does not grow ───────────────────────────

    /**
     * Random op streams from three replicas, every delta delivered **shuffled and duplicated**, byte
     * compared against the author's own **full-mutator** fold.
     *
     * The reference is deliberately built by [LWWMap.set]/[LWWMap.remove] rather than by folding the
     * same deltas in a different order: comparing a delta path against itself would pass whatever
     * the delta happened to be, and pin nothing.
     */
    @Test
    fun deltasConvergeUnderShuffledAndDuplicatedDeliveryAndMatchTheMutatorPath() {
        val random = Random(23)
        var removeDeltas = 0

        repeat(CONVERGENCE_TRIALS) { trial ->
            val clock = MonotoneClock()
            val deltas = mutableListOf<LWWMap<String, String>>()
            var author = LWWMap.empty<String, String>()

            repeat(random.nextInt(3, 12)) {
                val key = KEYS.random(random)
                val replica = REPLICAS.random(random)
                val timestamp = clock.tick()
                if (random.nextInt(REMOVE_IN) == 0) {
                    removeDeltas++
                    deltas += author.removeDelta(replica, timestamp, key).delta
                    author = author.remove(replica, timestamp, key)
                } else {
                    val value = VALUES.random(random)
                    deltas += author.setDelta(replica, timestamp, key, value).delta
                    author = author.set(replica, timestamp, key, value)
                }
            }

            val jumbled = (deltas + deltas).shuffled(random)
            val receiver = jumbled.fold(LWWMap.empty<String, String>()) { acc, delta -> acc.piece(delta) }

            assertTrue(
                bytes(receiver).contentEquals(bytes(author)),
                "trial $trial: ${deltas.size} deltas, delivered shuffled and duplicated, must encode " +
                    "identically to the author's mutator-path state (receiver=${receiver.entries}, " +
                    "author=${author.entries})",
            )
        }

        assertTrue(removeDeltas > 0, "vacuous: no remove delta was generated in $CONVERGENCE_TRIALS trials")
    }

    /**
     * The property the whole change buys: one write's frame is **flat** in the size of the map,
     * while `Patch(map.set(…))` is O(keys). "Smaller than before" would pass a change that
     * reintroduced O(keys) with a better constant; identical bytes at every size will not.
     */
    @Test
    fun theDeltasEncodedSizeDoesNotGrowWithTheMap() {
        val maps = MAP_SIZES.map { n ->
            (1..n).fold(LWWMap.empty<String, String>()) { map, i -> map.set(alpha, i.toLong(), "k$i", "v$i") }
        }
        val deltaSizes = maps.map { bytes(it.setDelta(bravo, LATE_TIMESTAMP, "k1", "changed").delta).size }
        val fullSizes = maps.map { bytes(it.set(bravo, LATE_TIMESTAMP, "k1", "changed")).size }

        assertAll(
            {
                assertEquals(
                    1,
                    deltaSizes.toSet().size,
                    "one write's delta must encode to the same size at every map size, got " +
                        "$deltaSizes for $MAP_SIZES keys",
                )
            },
            {
                assertTrue(
                    fullSizes.last() > fullSizes.first() * MAP_SIZES.last() / MAP_SIZES.first() / 2,
                    "the full-state path must still be O(keys) — that is what is being replaced (got $fullSizes)",
                )
            },
            {
                assertTrue(
                    deltaSizes.last() * 2 < fullSizes.first(),
                    "even at the smallest map size the delta must be a fraction of the full state " +
                        "(delta=${deltaSizes.last()}, full=${fullSizes.first()})",
                )
            },
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    /** A write's identity — exactly what [LWWMap]'s join compares. */
    private data class Tag(val timestamp: Long, val replica: ReplicaId)

    /** A monotone timestamp source: the per-replica clock [LWWMap.set]'s precondition asks for. */
    private class MonotoneClock {
        private var next = 1L
        fun tick(): Long = next++
    }

    /** A random map, plus the tag currently winning on each key — which only the generator knows. */
    private class Generated(val map: LWWMap<String, String>, val tags: Map<String, Tag>)

    /**
     * A random map built from a small key pool, so keys collide and most writes land on a cell that
     * is already occupied. A generator over fresh keys would make every join a plain insertion,
     * never a tag comparison, and the law would hold vacuously.
     */
    private fun randomState(random: Random, clock: MonotoneClock): Generated {
        var map = LWWMap.empty<String, String>()
        val tags = mutableMapOf<String, Tag>()
        repeat(random.nextInt(1, 8)) {
            val key = KEYS.random(random)
            val replica = REPLICAS.random(random)
            val tag = Tag(clock.tick(), replica)
            map = if (random.nextInt(REMOVE_IN) == 0) {
                map.remove(replica, tag.timestamp, key)
            } else {
                map.set(replica, tag.timestamp, key, VALUES.random(random))
            }
            tags[key] = tag
        }
        return Generated(map, tags)
    }

    /**
     * A tag that beats [key]'s current one — usually by timestamp, and sometimes, when the incumbent
     * leaves room, by the replica-id tie-break at an **equal** timestamp. Both are dominating writes;
     * only the second exercises `LWWRegister.piece`'s tie-break branch.
     */
    private fun dominatingTag(state: Generated, key: String, clock: MonotoneClock, random: Random): Tag {
        val incumbent = state.tags[key]
        if (incumbent != null && random.nextInt(TIE_BREAK_IN) == 0) {
            val higher = REPLICAS.filter { it.value > incumbent.replica.value }
            if (higher.isNotEmpty()) return Tag(incumbent.timestamp, higher.random(random))
        }
        return Tag(clock.tick(), REPLICAS.random(random))
    }

    /** Fails if too few trials wrote over an occupied cell, or too few used the tie-break branch. */
    private fun assertNonVacuous(contended: Int, tieBreaks: Int, mutator: String) {
        assertAll(
            {
                assertTrue(
                    contended >= MIN_CONTENDED_TRIALS,
                    "$mutator law ran vacuously: only $contended of $LAW_TRIALS trials wrote over a key that " +
                        "already carried a cell, so the join never compared two tags",
                )
            },
            {
                assertTrue(
                    tieBreaks >= MIN_TIE_BREAK_TRIALS,
                    "$mutator law ran vacuously: only $tieBreaks of $LAW_TRIALS trials wrote at an equal " +
                        "timestamp, so the replica-id tie-break was never exercised",
                )
            },
        )
    }

    private companion object {
        /** Trials per law test. */
        const val LAW_TRIALS = 400

        /** Trials per delivery-order test. */
        const val CONVERGENCE_TRIALS = 200

        /**
         * The floor the generator must clear for a law test to mean anything: trials whose write
         * landed on a key that already carried a cell, so the join resolved a real contest between
         * two tags rather than inserting a fresh one. **Measured on seed 11: 228 of 400 for `set`,
         * 209 for `remove`.** Set at roughly half, so an incidental generator tweak does not
         * red-light the suite, but a generator that drifted onto fresh keys — and with it every
         * case where the per-key max does any work — fails loudly instead of passing vacuously.
         */
        const val MIN_CONTENDED_TRIALS = 100

        /**
         * Likewise for the equal-timestamp tie-break, the one branch of `LWWRegister.piece` that a
         * strictly-increasing clock never reaches. **Measured on seed 11: 45 of 400 for `set`, 46
         * for `remove`.**
         */
        const val MIN_TIE_BREAK_TRIALS = 20

        /** A small pool, so writes land on occupied cells rather than always inserting. */
        val KEYS = listOf("lang", "tz", "theme", "seat", "ready")

        val VALUES = listOf("en", "fr", "de", "UTC", "dark", "light")

        val REPLICAS = listOf(ReplicaId("alpha"), ReplicaId("bravo"), ReplicaId("charlie"))

        /** One write in this many is a removal, in both the generator and the convergence stream. */
        const val REMOVE_IN = 4

        /** One dominating tag in this many is an equal-timestamp tie-break rather than a later one. */
        const val TIE_BREAK_IN = 3

        /** Map sizes for the flat-frame-size property. */
        val MAP_SIZES = listOf(10, 100, 1_000)

        /** A timestamp past every one the size sweep issues, so the metered write dominates. */
        const val LATE_TIMESTAMP = 1_000_000L
    }
}
