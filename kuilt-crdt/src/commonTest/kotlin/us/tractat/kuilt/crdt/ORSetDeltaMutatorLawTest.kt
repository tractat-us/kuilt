package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The delta-mutator law for [ORSet], asserted **on encoded bytes**:
 *
 * ```
 * X.piece(mᵟ(X)) == m(X)
 * ```
 *
 * for every state `X` and every mutator `m` — [ORSet.add]'s delta against [ORSet.addWhole],
 * [ORSet.remove]'s against [ORSet.removeWhole], [ORSet.removeAll]'s against [ORSet.removeAllWhole].
 * The reference side is deliberately the internal whole-state form: comparing the delta path
 * against itself would prove nothing.
 *
 * **Why bytes and not just `equals`.** Two states can compare equal and still encode two ways. The
 * anti-entropy gate hashes the state *as it appears on the wire*, so a delta path that left two
 * peers logically equal but bytewise different would silently stop that gate engaging for the pair
 * — and every round drawing them would fall back to shipping full states, which is the cost this
 * whole mechanism exists to avoid. `equals` cannot see that; a byte comparison can.
 *
 * Also pinned here: the two delta shapes proposed in #2044, each reconstructed from public API as a
 * negative control, so that reinstating either in production turns a named test red rather than
 * quietly diverging replicas.
 */
@OptIn(ExperimentalSerializationApi::class)
class ORSetDeltaMutatorLawTest {

    private val alpha = ReplicaId("alpha")
    private val bravo = ReplicaId("bravo")
    private val charlie = ReplicaId("charlie")

    private val cbor = Cbor {}
    private val setSerializer = ORSet.serializer(String.serializer())

    private fun bytes(set: ORSet<String>): ByteArray = cbor.encodeToByteArray(setSerializer, set)

    // ── the law ───────────────────────────────────────────────────────────────────

    @Test
    fun addDeltaSatisfiesTheMutatorLawOnBytes() {
        val random = Random(11)
        var concurrentDotTrials = 0

        repeat(LAW_TRIALS) { trial ->
            val state = randomState(random)
            val element = ELEMENTS.random(random)
            if (state.dotsOn(element).size > 1) concurrentDotTrials++

            val viaFull = state.addWhole(charlie, element)
            val viaDelta = state.piece(state.add(charlie, element))

            assertEquals(viaFull, viaDelta, "trial $trial: add law by equality, element=$element")
            assertTrue(
                bytes(viaFull).contentEquals(bytes(viaDelta)),
                "trial $trial: add law by bytes, element=$element — states are equal but encode differently",
            )
        }

        assertNonVacuous(concurrentDotTrials, "add")
    }

    @Test
    fun removeDeltaSatisfiesTheMutatorLawOnBytes() {
        val random = Random(11)
        var concurrentDotTrials = 0

        repeat(LAW_TRIALS) { trial ->
            val state = randomState(random)
            val element = ELEMENTS.random(random)
            if (state.dotsOn(element).size > 1) concurrentDotTrials++

            val viaFull = state.removeWhole(element)
            val viaDelta = state.piece(state.remove(element))

            assertEquals(viaFull, viaDelta, "trial $trial: remove law by equality, element=$element")
            assertTrue(
                bytes(viaFull).contentEquals(bytes(viaDelta)),
                "trial $trial: remove law by bytes, element=$element — states are equal but encode differently",
            )
        }

        assertNonVacuous(concurrentDotTrials, "remove")
    }

    @Test
    fun removeAllDeltaSatisfiesTheMutatorLawOnBytes() {
        val random = Random(11)
        var concurrentDotTrials = 0

        repeat(LAW_TRIALS) { trial ->
            val state = randomState(random)
            val victims = ELEMENTS.filter { random.nextBoolean() }.toSet()
            if (victims.any { state.dotsOn(it).size > 1 }) concurrentDotTrials++

            val viaFull = state.removeAllWhole(victims)
            val viaDelta = state.piece(state.removeAll(victims))

            assertEquals(viaFull, viaDelta, "trial $trial: removeAll law by equality, victims=$victims")
            assertTrue(
                bytes(viaFull).contentEquals(bytes(viaDelta)),
                "trial $trial: removeAll law by bytes, victims=$victims — states are equal but encode differently",
            )
        }

        assertNonVacuous(concurrentDotTrials, "removeAll")
    }

    @Test
    fun removingAnAbsentElementYieldsTheLatticeIdentity() {
        val state = ORSet.empty<String>().addWhole(alpha, "kept").addWhole(bravo, "also-kept")
        val identity = state.remove("never-added")

        assertAll(
            { assertEquals(state, state.piece(identity), "absorbing it must change nothing") },
            {
                assertTrue(
                    bytes(state).contentEquals(bytes(state.piece(identity))),
                    "absorbing it must not even change the encoding",
                )
            },
            {
                assertEquals(
                    ORSet.empty<String>(),
                    identity.delta,
                    "the delta itself is bottom: empty store, empty context",
                )
            },
        )
    }

    // ── the two #2044 shapes, as negative controls ────────────────────────────────

    /**
     * #2044's `add` delta is `Causal(DotMap(mapOf(e to DotSet(setOf(dot)))), DotContext.of(dot))`
     * — the minted dot and nothing else. That is exactly what building the delta on a *fresh* set
     * produces, so the shape is reconstructible from public API and needs no production method.
     *
     * It forgets every dot the re-add supersedes, the receiver keeps them, and a later remove —
     * a *correct* one — retires only the dot the remover knows about. The element comes back.
     */
    @Test
    fun anAddDeltaThatOmitsSupersededDotsResurrectsARemovedElement() {
        val issueShapeReAdd: (ORSet<String>) -> Patch<ORSet<String>> = { _ ->
            // A fresh set has an empty context, so `add` here mints (alpha,1) and witnesses only
            // that — #2044's shape, with none of the dots the re-add actually supersedes.
            ORSet.empty<String>().add(alpha, ELEMENT)
        }

        assertAll(
            {
                assertTrue(
                    replayReAddThenRemove(issueShapeReAdd),
                    "negative control: #2044's add delta must leave the element alive on the receiver",
                )
            },
            {
                assertFalse(
                    replayReAddThenRemove { state -> state.add(alpha, ELEMENT) },
                    "add's delta must carry the superseded dots, or a later remove resurrects the element",
                )
            },
        )
    }

    /**
     * #2044's `remove` delta is "the empty store with the context unchanged". That is not the delta
     * of *a* removal — it is the full state of a set from which **everything** has been removed, so
     * it too is reconstructible from public API: remove every element and keep the result.
     *
     * Joining it retires every dot the receiver holds, because the sender's context witnesses all
     * of them.
     */
    @Test
    fun aRemoveDeltaCarryingTheWholeContextWouldWipeTheReceiver() {
        val five = listOf("e1", "e2", "e3", "e4", "e5")
        val converged = five.fold(ORSet.empty<String>()) { set, element -> set.addWhole(alpha, element) }

        val patch = converged.remove("e3")
        val sender = converged.removeWhole("e3")  // the author's own whole-state mutator
        val receiver = converged.piece(patch)     // a converged peer absorbing the delta

        // The issue's shape: everything removed, context untouched.
        val issueShape = sender.elements.fold(sender) { set, element -> set.removeWhole(element) }

        assertAll(
            {
                assertEquals(
                    emptySet<String>(),
                    converged.piece(issueShape).elements,
                    "negative control: #2044's remove delta must wipe all five",
                )
            },
            {
                assertEquals(
                    setOf("e1", "e2", "e4", "e5"),
                    receiver.elements,
                    "the remove delta must retire e3's dots and no others",
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

    // ── delivery-order independence ───────────────────────────────────────────────

    /**
     * Three replicas, random op streams, every delta delivered **shuffled and duplicated**. The
     * result must be byte-identical to the straight in-order fold. This is what licenses the
     * design's claim that no causal delivery, buffering or de-duplication is required above the
     * lattice: a delta is an element of the same semilattice as the state.
     */
    @Test
    fun deltasConvergeUnderShuffledAndDuplicatedDelivery() {
        val random = Random(23)
        var removeDeltas = 0

        repeat(CONVERGENCE_TRIALS) { trial ->
            val replicas = listOf(alpha, bravo, charlie)
            val local = replicas.associateWith { ORSet.empty<String>() }.toMutableMap()
            val deltas = mutableListOf<ORSet<String>>()

            repeat(random.nextInt(3, 10)) {
                val author = replicas.random(random)
                val element = ELEMENTS.random(random)
                val state = local.getValue(author)

                val patch = if (state.contains(element) && random.nextInt(3) == 0) {
                    removeDeltas++
                    state.remove(element)
                } else {
                    state.add(author, element)
                }
                local[author] = state.piece(patch)
                deltas += patch.delta

                // Deliver eagerly to some peers, so later ops supersede dots minted elsewhere.
                replicas.filter { it != author && random.nextBoolean() }.forEach { peer ->
                    local[peer] = local.getValue(peer).piece(patch)
                }
            }

            val inOrder = deltas.fold(ORSet.empty<String>()) { acc, delta -> acc.piece(delta) }
            val jumbled = (deltas + deltas).shuffled(random)
            val outOfOrder = jumbled.fold(ORSet.empty<String>()) { acc, delta -> acc.piece(delta) }

            assertTrue(
                bytes(inOrder).contentEquals(bytes(outOfOrder)),
                "trial $trial: ${deltas.size} deltas, delivered shuffled and duplicated, " +
                    "must encode identically to the in-order fold (in-order=${inOrder.elements}, " +
                    "jumbled=${outOfOrder.elements})",
            )
        }

        assertTrue(
            removeDeltas > 0,
            "vacuous: no remove delta was generated in $CONVERGENCE_TRIALS trials",
        )
    }

    /**
     * A remove delta applied **before** the add it retires. `DotContext` is dot-exact — a
     * contiguous prefix per replica plus a cloud for the rest — so an early remove simply parks
     * the retired dot in the cloud and the late add is dropped on arrival. No buffering, no
     * causal-delivery requirement.
     *
     * The reference is built with the **full mutators**, not by folding the same deltas, so this
     * is the byte-level law generalised over delivery order rather than a comparison of a delta
     * path against itself.
     */
    @Test
    fun aRemoveDeltaAppliedBeforeTheAddItRetiresConverges() {
        // A converged peer holding an unrelated element and an older copy of the one under test.
        val peer = ORSet.empty<String>().addWhole(bravo, "bystander").addWhole(bravo, ELEMENT)

        // The author re-adds the element and then removes it, by the whole-state mutators.
        val author = peer.addWhole(alpha, ELEMENT).removeWhole(ELEMENT)

        // The same two operations as deltas — the re-add supersedes bravo's dot.
        val add = peer.add(alpha, ELEMENT).delta
        val remove = peer.addWhole(alpha, ELEMENT).remove(ELEMENT).delta

        val orders = mapOf(
            "add,remove" to listOf(add, remove),
            "remove,add" to listOf(remove, add),
            "add,remove,add" to listOf(add, remove, add),
            "remove,add,remove" to listOf(remove, add, remove),
            "remove,remove,add" to listOf(remove, remove, add),
        )

        val checks: List<() -> Unit> = orders.map { (name, order) ->
            {
                val folded = order.fold(peer) { acc, delta -> acc.piece(delta) }
                assertTrue(
                    bytes(folded).contentEquals(bytes(author)),
                    "order [$name] must encode identically to the author's mutator-path state " +
                        "(got ${folded.elements}, author has ${author.elements})",
                )
            }
        }
        assertAll(*checks.toTypedArray())
    }

    /**
     * Add-wins survives the delta path. A concurrent add mints a dot the remover never witnessed,
     * so it is absent from the remove delta's context and lives through the join — in either order,
     * to byte-identical states.
     */
    @Test
    fun aConcurrentAddSurvivesARemoveDeltaInEitherOrder() {
        // "bystander" is here so the remove delta's context is a strict subset of the sender's
        // history: a delta that over-claimed would take the bystander down with it.
        val start = ORSet.empty<String>().addWhole(alpha, "bystander").addWhole(alpha, ELEMENT)
        val removal = start.remove(ELEMENT).delta       // alpha retires the dot it can see
        val concurrent = start.add(bravo, ELEMENT).delta // bravo re-adds, minting a fresh dot

        val removeFirst = start.piece(removal).piece(concurrent)
        val addFirst = start.piece(concurrent).piece(removal)

        assertAll(
            { assertTrue(removeFirst.contains(ELEMENT), "add must win when the remove lands first") },
            { assertTrue(addFirst.contains(ELEMENT), "add must win when the add lands first") },
            {
                assertTrue(
                    removeFirst.contains("bystander") && addFirst.contains("bystander"),
                    "a remove delta must not retire dots belonging to any other element",
                )
            },
            {
                assertTrue(
                    bytes(removeFirst).contentEquals(bytes(addFirst)),
                    "both orders must encode identically",
                )
            },
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    /**
     * Replays the #2044 resurrection scenario with [reAddDelta] standing in for the re-add's delta,
     * and reports whether the element survives on the receiver after a *correct* remove.
     *
     * 1. `alpha` and `bravo` converge on `{e ↦ (bravo,1)}`.
     * 2. `alpha` re-adds `e` locally and ships [reAddDelta].
     * 3. `alpha` removes `e` and ships a correct [ORSet.remove] delta.
     *
     * The author ends up without `e` under either delta shape; the question is the receiver.
     */
    private fun replayReAddThenRemove(reAddDelta: (ORSet<String>) -> Patch<ORSet<String>>): Boolean {
        val start = ORSet.empty<String>().addWhole(bravo, ELEMENT)
        var author = start
        var receiver = start

        val readd = reAddDelta(author)
        author = author.addWhole(alpha, ELEMENT)
        receiver = receiver.piece(readd)

        val removal = author.remove(ELEMENT)
        author = author.piece(removal)
        receiver = receiver.piece(removal)

        assertFalse(author.contains(ELEMENT), "the author must always drop the element it removed")
        return receiver.contains(ELEMENT)
    }

    /**
     * A random state, built as the merge of two independently-grown branches so that elements both
     * branches touched carry **more than one** dot.
     *
     * That is the whole point of the shape: an add mints a single dot and supersedes the element's
     * previous ones, so a state built by one replica alone never has a multi-dot element,
     * [ORSet.add]'s superseded-dots term is always a singleton, and the law would hold vacuously
     * against precisely the defect it exists to catch.
     *
     * Built with the whole-state mutators so the generator never depends on the mechanism the law
     * is testing.
     */
    private fun randomState(random: Random): ORSet<String> {
        var left = ORSet.empty<String>()
        var right = ORSet.empty<String>()
        repeat(random.nextInt(2, 8)) {
            left = left.addWhole(alpha, ELEMENTS.random(random))
            right = right.addWhole(bravo, ELEMENTS.random(random))
        }
        var merged = left.piece(right)
        repeat(random.nextInt(0, 3)) {
            val element = ELEMENTS.random(random)
            merged =
                if (random.nextBoolean()) merged.addWhole(charlie, element) else merged.removeWhole(element)
        }
        return merged
    }

    /** Fails if too few trials exercised an element carrying concurrent dots. */
    private fun assertNonVacuous(concurrentDotTrials: Int, mutator: String) {
        assertTrue(
            concurrentDotTrials >= MIN_CONCURRENT_DOT_TRIALS,
            "$mutator law ran vacuously: only $concurrentDotTrials of $LAW_TRIALS trials mutated an " +
                "element carrying more than one dot, so the superseded-dots term was never exercised",
        )
    }

    private companion object {
        /** Trials per law test. */
        const val LAW_TRIALS = 400

        /** Trials per delivery-order test. */
        const val CONVERGENCE_TRIALS = 200

        /**
         * The floor the generator must clear for a law test to mean anything. **Measured: 113 of
         * 400** on seed 11 for the single-element arms, and 204 of 400 for [ORSet.removeAll],
         * which draws a random subset and so hits a multi-dot element more often. Set at roughly
         * half of the smaller of those, so an incidental generator tweak does not
         * red-light the suite, but a generator that stopped producing concurrent dots — and with it
         * every case in which [ORSet.add]'s superseded-dots term does any work — fails loudly
         * instead of passing vacuously.
         */
        const val MIN_CONCURRENT_DOT_TRIALS = 60

        /** A small pool, so branches collide and elements accumulate concurrent dots. */
        val ELEMENTS = listOf("a", "b", "c", "d", "e", "f")

        /** The single element the scenario tests use. */
        const val ELEMENT = "e"
    }
}
