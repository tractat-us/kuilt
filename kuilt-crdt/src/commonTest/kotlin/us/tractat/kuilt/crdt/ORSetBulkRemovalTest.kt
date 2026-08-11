package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ORSet.removeAll] must be *indistinguishable* from removing the same elements one at a
 * time — same resulting elements, same retained causal context, same encoded bytes — while
 * paying one [ORSet.piece] instead of one per element.
 *
 * Equivalence is the load-bearing property, not the speed. The delta goes on the wire
 * verbatim, so a context that named one dot more or fewer than the per-element path would be
 * a semantic change dressed up as an optimisation: one dot too many retires a peer's
 * concurrent add (add-wins lost), one too few resurrects an element on the next merge.
 */
@OptIn(ExperimentalSerializationApi::class)
class ORSetBulkRemovalTest {

    private val alpha = ReplicaId("alpha")
    private val bravo = ReplicaId("bravo")
    private val charlie = ReplicaId("charlie")

    private val cbor = Cbor {}
    private val setSerializer = ORSet.serializer(String.serializer())

    private fun bytes(set: ORSet<String>): ByteArray = cbor.encodeToByteArray(setSerializer, set)

    /** The per-element fold `removeAll` replaces — the O(n·|set|) spelling. */
    private fun foldRemove(set: ORSet<String>, elements: Set<String>): ORSet<String> =
        elements.fold(set) { acc, element -> acc.piece { it.remove(element) } }

    /**
     * A set grown by two replicas independently and then merged, so elements both branches
     * touched carry **more than one** dot. A set grown by one replica alone never does, and
     * every multi-dot term below would go untested.
     */
    private fun multiReplicaState(random: Random): ORSet<String> {
        var left = ORSet.empty<String>()
        var right = ORSet.empty<String>()
        repeat(random.nextInt(3, 9)) {
            left = left.addWhole(alpha, ELEMENTS.random(random))
            right = right.addWhole(bravo, ELEMENTS.random(random))
        }
        return left.piece(right)
    }

    // ── equivalence with the per-element fold ─────────────────────────────────────

    @Test
    fun removingInBulkIsIndistinguishableFromRemovingOneAtATime() {
        val random = Random(31)
        var multiDotTrials = 0

        repeat(TRIALS) { trial ->
            val state = multiReplicaState(random)
            val victims = ELEMENTS.filter { random.nextBoolean() }.toSet()
            if (victims.any { state.dotsOn(it).size > 1 }) multiDotTrials++

            val viaFold = foldRemove(state, victims)
            val viaBulk = state.piece { it.removeAll(victims) }

            assertEquals(viaFold, viaBulk, "trial $trial: bulk removal of $victims must equal the fold")
            assertTrue(
                bytes(viaFold).contentEquals(bytes(viaBulk)),
                "trial $trial: states are equal but encode differently — the anti-entropy hash would diverge",
            )
        }

        assertTrue(
            multiDotTrials >= MIN_MULTI_DOT_TRIALS,
            "vacuous: only $multiDotTrials of $TRIALS trials removed an element carrying concurrent dots",
        )
    }

    /**
     * The same equivalence at the level of the **delta**, which is what actually travels: the
     * join of the per-element deltas must equal the single bulk delta. A receiver that absorbed
     * either must therefore be unable to tell which one was sent.
     */
    @Test
    fun theBulkDeltaEqualsTheJoinOfThePerElementDeltas() {
        val state = multiReplicaState(Random(5))
        val victims = state.elements

        val joined = victims
            .map { state.remove(it).delta }
            .fold(ORSet.empty<String>()) { acc, delta -> acc.piece(delta) }
        val bulk = state.removeAll(victims).delta

        assertAll(
            { assertEquals(joined, bulk, "the bulk delta must equal the join of the per-element deltas") },
            { assertTrue(bytes(joined).contentEquals(bytes(bulk)), "and must encode identically") },
        )
    }

    // ── the retained-context property this method exists for ──────────────────────

    /**
     * The reason `removeAll` retains the context rather than starting from a fresh set: a peer
     * that re-merges its pre-clear copy must be **dominated**, not resurrect every element.
     * This is the property `WarpSpanExporter.clear()` depends on.
     */
    @Test
    fun aPeersReMergeOfThePreRemovalStateIsDominated() {
        val snapshot = multiReplicaState(Random(17))
        val cleared = snapshot.piece { it.removeAll(snapshot.elements) }

        assertAll(
            { assertEquals(emptySet(), cleared.elements, "removing every element must empty the set") },
            {
                assertEquals(
                    emptySet(),
                    cleared.piece(snapshot).elements,
                    "re-merging the pre-removal state must not resurrect anything",
                )
            },
            {
                assertEquals(
                    emptySet(),
                    snapshot.piece(cleared).elements,
                    "and the merge must be order-independent",
                )
            },
        )
    }

    /**
     * Add-wins survives the bulk path. A concurrent add mints a dot the remover never witnessed,
     * so it is absent from the bulk delta's context and lives through the join — in either order.
     *
     * This is precisely what a naive "empty the store, keep the **whole** context" implementation
     * would destroy: that context witnesses dots the remover never saw retired, so it would take
     * the concurrent add down with it.
     */
    @Test
    fun aConcurrentAddSurvivesABulkRemovalInEitherOrder() {
        val start = ORSet.empty<String>()
            .addWhole(alpha, "a")
            .addWhole(alpha, "b")
            .addWhole(alpha, "c")

        // Both computed against `start`, so neither witnesses the other.
        val removal = start.removeAll(setOf("a", "b", "c")).delta
        val concurrent = start.add(bravo, "b").delta

        val removeFirst = start.piece(removal).piece(concurrent)
        val addFirst = start.piece(concurrent).piece(removal)

        assertAll(
            { assertTrue(removeFirst.contains("b"), "the concurrent add must win when the removal lands first") },
            { assertTrue(addFirst.contains("b"), "the concurrent add must win when the add lands first") },
            { assertEquals(setOf("b"), removeFirst.elements, "and the uncontested elements must still go") },
            { assertTrue(bytes(removeFirst).contentEquals(bytes(addFirst)), "both orders must encode identically") },
        )
    }

    /**
     * The bulk delta must retire dots belonging to the **named** elements and no others — the
     * same over-claiming failure `remove`'s KDoc warns about, one level up.
     */
    @Test
    fun aBulkRemovalNeverRetiresAnUnnamedElementsDots() {
        val start = ORSet.empty<String>()
            .addWhole(alpha, "doomed")
            .addWhole(bravo, "bystander")
            .addWhole(charlie, "also-doomed")

        val cleared = start.piece { it.removeAll(setOf("doomed", "also-doomed")) }

        assertEquals(setOf("bystander"), cleared.elements)
    }

    // ── edge cases ────────────────────────────────────────────────────────────────

    @Test
    fun removingNothingOrOnlyAbsentElementsYieldsTheLatticeIdentity() {
        val state = multiReplicaState(Random(3))

        assertAll(
            {
                assertEquals(
                    ORSet.empty<String>(),
                    state.removeAll(emptySet()).delta,
                    "the empty removal's delta is bottom: empty store, empty context",
                )
            },
            { assertEquals(state, state.piece { it.removeAll(emptySet()) }, "absorbing it must change nothing") },
            {
                assertEquals(
                    ORSet.empty<String>(),
                    state.removeAll(setOf("never-added", "nor-this")).delta,
                    "removing only absent elements is bottom too",
                )
            },
            {
                assertTrue(
                    bytes(state).contentEquals(bytes(state.piece { it.removeAll(setOf("never-added")) })),
                    "absorbing it must not even change the encoding",
                )
            },
        )
    }

    @Test
    fun removingAMixOfPresentAndAbsentElementsDropsOnlyThePresentOnes() {
        val state = ORSet.empty<String>().addWhole(alpha, "here").addWhole(bravo, "also-here")
        val cleared = state.piece { it.removeAll(setOf("here", "never-added")) }

        assertEquals(setOf("also-here"), cleared.elements)
    }

    private companion object {
        const val TRIALS = 200

        /**
         * The floor the generator must clear. **Measured: 138 of 200** on seed 31; set at roughly
         * half, so an incidental generator tweak does not red-light the suite but a generator that
         * stopped producing concurrent dots fails loudly instead of passing vacuously.
         */
        const val MIN_MULTI_DOT_TRIALS = 70

        /** A small pool, so the two branches collide and elements accumulate concurrent dots. */
        val ELEMENTS = listOf("a", "b", "c", "d", "e", "f")
    }
}
