package us.tractat.kuilt.crdt

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ORSetTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")
    private val c = ReplicaId("C")

    @Test
    fun addThenContains() {
        val s = ORSet.empty<String>().piece { it.add(a, "card") }
        assertTrue(s.contains("card"))
        assertEquals(setOf("card"), s.elements)
    }

    @Test
    fun removeMakesAbsent() {
        val s = ORSet.empty<String>().piece { it.add(a, "card") }.piece { it.remove("card") }
        assertFalse(s.contains("card"))
        assertEquals(emptySet(), s.elements)
    }

    @Test
    fun addWinsOverConcurrentRemove() {
        // shared start: A added "card"
        val start = ORSet.empty<String>().piece { it.add(a, "card") }
        val alice = start.piece { it.remove("card") }     // Alice removes what she saw
        val bob = start.piece { it.add(b, "card") }       // Bob concurrently re-adds
        val merged = alice.piece(bob)
        assertTrue(merged.contains("card"))  // add wins
    }

    @Test
    fun removeWinsWhenNothingConcurrentlyAdded() {
        val start = ORSet.empty<String>().piece { it.add(a, "card") }
        val alice = start.piece { it.remove("card") }
        // Bob did nothing new; merging the removal with the stale-present state drops it
        val merged = alice.piece(start)
        assertFalse(merged.contains("card"))
    }

    @Test
    fun mergeIsCommutative() {
        val start = ORSet.empty<String>().piece { it.add(a, "card") }
        val alice = start.piece { it.remove("card") }
        val bob = start.piece { it.add(b, "card") }
        assertEquals(alice.piece(bob), bob.piece(alice))
    }

    @Test
    fun roundTripsThroughJson() {
        val s = ORSet.empty<String>().piece { it.add(a, "x") }.piece { it.add(b, "y") }
        val ser = ORSet.serializer(String.serializer())
        assertEquals(s, Json.decodeFromString(ser, Json.encodeToString(ser, s)))
    }

    // ── associativity (#2086) ─────────────────────────────────────────────────────

    /**
     * The shape that proved [ORMap.piece] non-associative: one replica adds an element, removes it,
     * and adds it again, and the three states are merged in the two groupings.
     *
     * `remove` drops the element from the store while the context keeps its dots, so `b` is *not*
     * simply "less than" `a` — it carries a retirement `a` does not. On `ORMap` the re-added value
     * then survives or not depending on whether the merge ever put the old and new states side by
     * side with both holding the key, and the two groupings disagree. `ORSet` has no value lattice
     * under the dots, so the same trajectory has to come out the same either way — this test is what
     * says so rather than assuming it.
     */
    @Test
    fun pieceIsAssociativeAcrossARemoveBetweenTwoAdds() {
        val added = ORSet.empty<String>().piece { it.add(a, "k") }
        val removed = added.piece { it.remove("k") }
        val reAdded = removed.piece { it.add(a, "k") }

        assertAll(
            *associativityChecks("add/remove/re-add", added, removed, reAdded),
            {
                assertEquals(
                    setOf("k"),
                    added.piece(removed).piece(reAdded).elements,
                    "the re-add is the newest event, so the element must be present",
                )
            },
        )
    }

    /**
     * Add-wins across a partition, three ways. `A` removes the element it saw; `B` concurrently
     * re-adds it, minting a dot `A`'s removal never witnessed; `C` is a third replica carrying its
     * own concurrent add plus an untouched bystander. The bystander is here so a merge that
     * over-claimed a context would take it down and be visible.
     */
    @Test
    fun pieceIsAssociativeAcrossAConcurrentAddAndRemove() {
        val start = ORSet.empty<String>().piece { it.add(a, "bystander") }.piece { it.add(a, "card") }
        val remover = start.piece { it.remove("card") }
        val reAdder = start.piece { it.add(b, "card") }
        val thirdAdder = start.piece { it.add(c, "card") }

        val merged = remover.piece(reAdder).piece(thirdAdder)

        assertAll(
            *associativityChecks("concurrent add/remove", remover, reAdder, thirdAdder),
            // The common ancestor is a *stale* peer: it still holds the dot the remover retired,
            // while the re-adder's context already witnesses it. That combination — both sides
            // holding the element, one of them holding a dot the other has retired — is the only
            // position where a merge can be tempted to keep a superseded dot, so a triple without
            // it cannot tell a correct join from one that unions the two sides.
            *associativityChecks("stale ancestor", remover, reAdder, start),
            { assertTrue(merged.contains("card"), "add must win over a concurrent remove") },
            { assertTrue(merged.contains("bystander"), "no merge may retire an unrelated element") },
            {
                assertEquals(
                    2,
                    merged.dotsOn("card").size,
                    "vacuity guard: the two concurrent adds must both survive as dots, or the " +
                        "multi-dot case this test exists for was never built",
                )
            },
        )
    }

    /**
     * Associativity where the element already carries **more than one dot** before the trajectory
     * starts. A set grown by a single replica never has a multi-dot element — [ORSet.add] supersedes
     * the element's previous dots — so the starting state is deliberately the merge of two branches,
     * and a removal then has several dots to retire at once rather than one.
     */
    @Test
    fun pieceIsAssociativeWhenAnElementCarriesConcurrentDots() {
        val fromA = ORSet.empty<String>().piece { it.add(a, "card") }
        val fromB = ORSet.empty<String>().piece { it.add(b, "card") }
        val twoDots = fromA.piece(fromB)

        val removed = twoDots.piece { it.remove("card") }           // retires both dots at once
        val reAddedByC = twoDots.piece { it.add(c, "card") }        // supersedes both, mints a third
        val stale = fromA                              // a peer that never saw B's add

        assertAll(
            {
                assertEquals(
                    2,
                    twoDots.dotsOn("card").size,
                    "vacuity guard: the starting state must carry concurrent dots",
                )
            },
            *associativityChecks("multi-dot", removed, reAddedByC, stale),
            *associativityChecks("multi-dot, stale first", stale, removed, reAddedByC),
        )
    }

    /**
     * The general law, over **causally related** trajectories rather than independent states.
     *
     * This is the coverage the surfaces that already exist cannot give. `ORSetLawsPropertyTest`
     * (jvmTest, jqwik) generates its three states from three disjoint single-replica namespaces, so
     * no state it produces is ever an ancestor of another and no dot is ever superseded across the
     * triple — the sibling `ORMapLawsPropertyTest` is built the same way and passes on an `ORMap`
     * that *is* non-associative. `CrdtConvergenceSuite` asserts only that replicas agree after a
     * full exchange, which a non-associative join still satisfies because the divergence heals on
     * the next merge. Here every snapshot comes from one shared history of adds, removes, merges and
     * shipped deltas, and every ordered triple of snapshots is checked in both groupings.
     */
    @Test
    fun pieceIsAssociativeOverCausallyRelatedTrajectories() {
        val random = Random(2086)
        var triplesChecked = 0
        var multiDotSnapshots = 0
        var reAddAfterRemove = 0

        repeat(TRAJECTORY_TRIALS) { trial ->
            val live = REPLICAS.associateWith { ORSet.empty<String>() }.toMutableMap()
            val snapshots = mutableListOf<ORSet<String>>()
            val removedAnywhere = mutableSetOf<String>()

            repeat(random.nextInt(8, 13)) {
                val author = REPLICAS.random(random)
                val element = ELEMENTS.random(random)
                val state = live.getValue(author)

                live[author] = when (random.nextInt(8)) {
                    0, 1, 2 -> {
                        if (element in removedAnywhere) reAddAfterRemove++
                        state.addWhole(author, element)
                    }
                    3, 4 -> {
                        if (state.contains(element)) removedAnywhere += element
                        state.removeWhole(element)
                    }
                    5, 6 -> state.piece(live.getValue(REPLICAS.random(random)))
                    7 -> if (random.nextBoolean()) {
                        if (element in removedAnywhere) reAddAfterRemove++
                        state.piece { it.add(author, element) }
                    } else {
                        if (state.contains(element)) removedAnywhere += element
                        state.piece { it.remove(element) }
                    }
                    else -> state
                }
                snapshots += live.getValue(author)
            }

            if (snapshots.any { snapshot -> ELEMENTS.any { snapshot.dotsOn(it).size > 1 } }) {
                multiDotSnapshots++
            }

            for (x in snapshots) {
                for (y in snapshots) {
                    for (z in snapshots) {
                        triplesChecked++
                        assertEquals(
                            x.piece(y).piece(z),
                            x.piece(y.piece(z)),
                            "trial $trial: associativity failed for " +
                                "a=${describe(x)} b=${describe(y)} c=${describe(z)}",
                        )
                    }
                }
            }
        }

        assertAll(
            {
                assertTrue(
                    multiDotSnapshots >= MIN_MULTI_DOT_TRIALS,
                    "vacuous: only $multiDotSnapshots of $TRAJECTORY_TRIALS trajectories produced an " +
                        "element carrying concurrent dots",
                )
            },
            {
                assertTrue(
                    reAddAfterRemove >= MIN_RE_ADDS,
                    "vacuous: only $reAddAfterRemove re-adds followed a remove, so the shape that " +
                        "broke ORMap was barely exercised",
                )
            },
            { assertTrue(triplesChecked > 0, "the trajectory generator produced no triples at all") },
        )
    }

    /** Associativity for each of the six orderings of one triple: both groupings must agree. */
    private fun associativityChecks(
        label: String,
        first: ORSet<String>,
        second: ORSet<String>,
        third: ORSet<String>,
    ): Array<() -> Unit> {
        val orderings = listOf(
            Triple(first, second, third),
            Triple(first, third, second),
            Triple(second, first, third),
            Triple(second, third, first),
            Triple(third, first, second),
            Triple(third, second, first),
        )
        return orderings.mapIndexed { index, (x, y, z) ->
            {
                val left = x.piece(y).piece(z)
                val right = x.piece(y.piece(z))
                assertEquals(
                    left,
                    right,
                    "$label: ordering $index — (a⊔b)⊔c and a⊔(b⊔c) disagree.\n" +
                        "  a       = ${describe(x)}\n" +
                        "  b       = ${describe(y)}\n" +
                        "  c       = ${describe(z)}\n" +
                        "  (a⊔b)⊔c = ${describe(left)}\n" +
                        "  a⊔(b⊔c) = ${describe(right)}",
                )
            }
        }.toTypedArray()
    }

    /**
     * Renders a set **with its dots**. [ORSet.toString] prints only the elements, so two states that
     * differ solely in which dots back an element — the usual way a broken join diverges, and the
     * shape of every failure this file is here to catch — would otherwise print identically and make
     * the assertion unreadable.
     */
    private fun describe(set: ORSet<String>): String =
        set.elements.sorted().joinToString(prefix = "{", postfix = "}") { element ->
            "$element↦${set.dotsOn(element).map { "${it.replica.value}${it.seq}" }.sorted()}"
        }

    private companion object {
        /** Trajectories per run of the general associativity law. */
        const val TRAJECTORY_TRIALS = 120

        /**
         * Floors the trajectory generator must clear. **Measured on seed 2086: 27 of 120
         * trajectories carried a multi-dot element, and 58 adds landed on an element some replica
         * had already removed, across 132,294 triples.** Each floor sits at roughly half its
         * measurement, so an incidental tweak does not red-light the suite, while a generator that
         * stopped producing concurrent dots — or stopped re-adding after a remove, the shape that
         * broke `ORMap` — fails loudly instead of passing vacuously.
         */
        const val MIN_MULTI_DOT_TRIALS = 12
        const val MIN_RE_ADDS = 25

        val REPLICAS = listOf(ReplicaId("A"), ReplicaId("B"), ReplicaId("C"))

        /** A small pool, so branches collide and elements accumulate concurrent dots. */
        val ELEMENTS = listOf("x", "y")
    }
}
