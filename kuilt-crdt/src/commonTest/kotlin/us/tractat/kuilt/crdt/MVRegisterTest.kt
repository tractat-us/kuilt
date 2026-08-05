package us.tractat.kuilt.crdt

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MVRegisterTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")
    private val c = ReplicaId("C")
    private val json = Json { allowStructuredMapKeys = true } // DotFun's keys are Dots

    @Test
    fun emptyHasNoValue() {
        assertEquals(emptySet(), MVRegister.empty<String>().values)
    }

    @Test
    fun setThenRead() {
        assertEquals(setOf("x"), MVRegister.empty<String>().set(a, "x").values)
    }

    @Test
    fun concurrentWritesKeepBothValues() {
        val base = MVRegister.empty<String>()
        val x = base.set(a, "x")
        val y = base.set(b, "y")
        assertEquals(setOf("x", "y"), x.piece(y).values)
    }

    @Test
    fun aLaterWriteResolvesTheConflict() {
        val base = MVRegister.empty<String>()
        val conflicted = base.set(a, "x").piece(base.set(b, "y")) // {x, y}
        val resolved = conflicted.set(a, "z") // observes both, supersedes
        assertEquals(setOf("z"), resolved.values)
    }

    @Test
    fun roundTripsThroughJson() {
        val r = MVRegister.empty<String>().set(a, "x")
        val ser = MVRegister.serializer(String.serializer())
        assertEquals(r, json.decodeFromString(ser, json.encodeToString(ser, r)))
    }

    // ── associativity (#2086) ─────────────────────────────────────────────────────

    /**
     * The simplest shape the existing coverage cannot build: three states on **one** causal line.
     *
     * Each write mints a fresh dot and drops every dot it has observed, so `second` carries a
     * retirement of `first`'s dot while `first` still holds it live — the "one operand retired what
     * another still holds" position that decides whether a join is associative. An independent-states
     * generator cannot reach it: drawing three states from three disjoint single-replica namespaces
     * means no dot it generates is ever superseded across the triple.
     */
    @Test
    fun pieceIsAssociativeAcrossAChainOfWritesOnOneReplica() {
        val first = MVRegister.empty<String>().set(a, "v1")
        val second = first.set(a, "v2")
        val third = second.set(a, "v3")

        assertAll(
            *associativityChecks("write chain", first, second, third),
            {
                assertEquals(
                    setOf("v3"),
                    first.piece(second).piece(third).values,
                    "the newest write observed both older ones, so only it may survive",
                )
            },
            {
                assertEquals(
                    setOf("v2"),
                    first.piece(second).values,
                    "vacuity guard: merging an ancestor back in must not resurrect its value, or the " +
                        "retirement this test exists for never happened",
                )
            },
        )
    }

    /**
     * Three replicas write concurrently off a shared ancestor, and the ancestor itself is merged
     * back in as a fourth, stale peer.
     *
     * The stale peer is the interesting operand: it still holds the dot every branch retired, while
     * each branch's context already witnesses it. A merge that took the union of the two stores would
     * resurrect it, and whether the union happens can depend on the grouping — which is exactly how
     * `ORMap.piece` fails.
     */
    @Test
    fun pieceIsAssociativeAcrossConcurrentWritesFromThreeReplicas() {
        val base = MVRegister.empty<String>().set(a, "base")
        val fromA = base.set(a, "a2")
        val fromB = base.set(b, "b1")
        val fromC = base.set(c, "c1")

        val merged = fromA.piece(fromB).piece(fromC)

        assertAll(
            *associativityChecks("three concurrent writes", fromA, fromB, fromC),
            *associativityChecks("stale ancestor", fromA, fromB, base),
            {
                assertEquals(
                    setOf("a2", "b1", "c1"),
                    merged.values,
                    "vacuity guard: all three concurrent writes must stay live, or the multi-value " +
                        "region this test exists for was never built",
                )
            },
            {
                assertEquals(
                    setOf("a2"),
                    base.piece(fromA).values,
                    "the ancestor's value was superseded and must not survive the merge",
                )
            },
        )
    }

    /**
     * A stale peer that shares a **live** dot with the newest state.
     *
     * `A` wrote once. `C` wrote twice — superseding its own first write — and then merged in `A`'s
     * write, which it had not seen. So the newest state holds `A`'s dot *and* a newer `C` dot, while
     * the middle state holds the `C` dot the newest state retired. Grouping decides whether the
     * newest state is ever compared against a store that already shares a dot with it, so this is
     * the position a join that turns permissive once the two stores overlap gets wrong — the
     * transplanted `ORMap` bug shape, and the shortest triple that catches it.
     */
    @Test
    fun pieceIsAssociativeWhenAStalePeerSharesALiveDotWithTheNewestState() {
        val fromA = MVRegister.empty<String>().set(a, "a1")
        val cFirst = MVRegister.empty<String>().set(c, "c1")
        val cSecond = cFirst.set(c, "c2").piece(fromA) // holds a1 and c2; has retired c1

        assertAll(
            *associativityChecks("stale peer sharing a live dot", fromA, cFirst, cSecond),
            {
                assertEquals(
                    setOf("a1", "c2"),
                    cSecond.values,
                    "vacuity guard: the newest state must share a live dot with the stale peer",
                )
            },
            {
                assertEquals(
                    setOf("a1", "c2"),
                    fromA.piece(cFirst).piece(cSecond).values,
                    "c1 was superseded before the merge and must not come back",
                )
            },
        )
    }

    /**
     * Associativity when a single write retires **several** concurrent values at once.
     *
     * A register written only by one replica never holds more than one value, so the starting state
     * is deliberately the merge of two branches; the resolving write then supersedes two dots in one
     * step rather than one, and `fromA` remains as a peer that still holds one of them.
     */
    @Test
    fun pieceIsAssociativeWhenOneWriteRetiresSeveralConcurrentValues() {
        val fromA = MVRegister.empty<String>().set(a, "a1")
        val fromB = MVRegister.empty<String>().set(b, "b1")
        val bothLive = fromA.piece(fromB)
        val resolved = bothLive.set(c, "c1") // retires a1 and b1 together

        assertAll(
            {
                assertEquals(
                    setOf("a1", "b1"),
                    bothLive.values,
                    "vacuity guard: the starting state must carry concurrent values",
                )
            },
            *associativityChecks("multi-value then resolved", bothLive, resolved, fromA),
            *associativityChecks("multi-value, stale first", fromA, resolved, fromB),
            {
                assertEquals(
                    setOf("c1"),
                    bothLive.piece(resolved).piece(fromA).values,
                    "the resolving write observed both values, so neither may survive",
                )
            },
        )
    }

    /**
     * The general law, over **causally related** trajectories rather than independent states.
     *
     * This is the coverage an independent-states generator cannot give. The JVM-only jqwik surface
     * deleted in #2101 folded each of its three operands independently from `empty()` and pinned
     * each to its own replica, so no state was ever a causal ancestor of another and no dot was ever
     * superseded across the triple. Its `ORMap` sibling was built the same way and passed on an
     * `ORMap` that *is* non-associative (#2086), so that design was not evidence of anything. Here
     * every snapshot comes from one shared history of writes and merges, dots are unique by
     * construction, and every ordered triple of snapshots is checked in both groupings.
     *
     * Values are globally unique tokens, so the live value set is in bijection with the live dot set
     * — which is what lets the vacuity guards below count retirements without reaching inside the
     * register.
     */
    @Test
    fun pieceIsAssociativeOverCausallyRelatedTrajectories() {
        val random = Random(2086)
        var triplesChecked = 0
        var multiValueSnapshots = 0
        var retiringMerges = 0

        repeat(TRAJECTORY_TRIALS) { trial ->
            val live = REPLICAS.associateWith { MVRegister.empty<String>() }.toMutableMap()
            val snapshots = mutableListOf<MVRegister<String>>()
            var written = 0

            repeat(random.nextInt(8, 13)) {
                val author = REPLICAS.random(random)
                val state = live.getValue(author)
                live[author] = when (random.nextInt(6)) {
                    // A replica only ever advances its own state, so its context never rewinds and
                    // `nextDot` never repeats a dot — the precondition every causal CRDT rests on.
                    0, 1, 2 -> state.set(author, "${author.value}$written".also { written++ })
                    3, 4 -> state.piece(live.getValue(REPLICAS.random(random)))
                    else -> if (snapshots.isEmpty()) state else state.piece(snapshots.random(random))
                }
                snapshots += live.getValue(author)
            }

            multiValueSnapshots += snapshots.count { it.values.size > 1 }
            for (x in snapshots) {
                for (y in snapshots) {
                    if (x.piece(y).values.size < (x.values + y.values).size) retiringMerges++
                }
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
                    multiValueSnapshots >= MIN_MULTI_VALUE_SNAPSHOTS,
                    "vacuous: only $multiValueSnapshots snapshots across $TRAJECTORY_TRIALS trajectories " +
                        "held concurrent values, so the conflict region was barely exercised",
                )
            },
            {
                assertTrue(
                    retiringMerges >= MIN_RETIRING_MERGES,
                    "vacuous: only $retiringMerges merges dropped a value one side still held, so the " +
                        "shape that broke ORMap — a retirement meeting a peer that never saw it — was " +
                        "barely exercised",
                )
            },
            { assertTrue(triplesChecked > 0, "the trajectory generator produced no triples at all") },
        )
    }

    /**
     * Where the guarantees actually stop: one replica **forking its own state** breaks commutativity,
     * and associativity survives it anyway.
     *
     * [DotContext.nextDot] documents the precondition — one `DotContext` per logical replica — and
     * this is what violating it costs. Both writes below mint the *same* dot with *different* values,
     * so the two stores disagree about what that dot names. `DotFun.join` keeps a doubly-held dot's
     * value from its left-hand operand, which makes the merge order-dependent. It stays associative
     * because both groupings still resolve the dot to the leftmost operand that holds it.
     *
     * Pinned rather than left implicit because a fork is not exotic: it is what a replica that
     * restarts from a stale snapshot does. The failure is silent — a lost write, not an exception —
     * and the law suites cannot see it, since they give each state its own replica id.
     */
    @Test
    fun forkingOneReplicaBreaksCommutativityButNotAssociativity() {
        val seeded = MVRegister.empty<String>().set(b, "seed")
        val forkedX = seeded.set(a, "x") // mints dot (A, 1) -> "x"
        val forkedY = seeded.set(a, "y") // mints the SAME dot (A, 1) -> "y"

        assertAll(
            { assertEquals(setOf("x"), forkedX.piece(forkedY).values, "the left operand's value wins") },
            { assertEquals(setOf("y"), forkedY.piece(forkedX).values, "…so the merge is order-dependent") },
            {
                assertNotEquals(
                    forkedX.piece(forkedY),
                    forkedY.piece(forkedX),
                    "a forked replica is outside DotContext.nextDot's stated precondition, and this is " +
                        "the cost: commutativity is lost",
                )
            },
            *associativityChecks("forked replica", forkedX, forkedY, seeded),
            *associativityChecks("forked replica, merged", forkedX.piece(forkedY), forkedY, seeded),
        )
    }

    /** Associativity for each of the six orderings of one triple: both groupings must agree. */
    private fun associativityChecks(
        label: String,
        first: MVRegister<String>,
        second: MVRegister<String>,
        third: MVRegister<String>,
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
                        "  a⊔(b⊔c) = ${describe(right)}" + contextOnlyNote(left, right),
                )
            }
        }.toTypedArray()
    }

    /**
     * Renders a register's live values. Every test here writes each value exactly once, so the live
     * values name the live dots and are the whole store — everything a divergent join can get wrong.
     */
    private fun describe(register: MVRegister<String>): String =
        register.values.sorted().joinToString(prefix = "{", postfix = "}")

    /**
     * The one thing [describe] cannot show. Two registers can hold the same values and still be
     * unequal if their causal contexts differ, and then the rendering above prints twice alike; say
     * so rather than leaving the reader to wonder why two identical-looking states compared unequal.
     */
    private fun contextOnlyNote(left: MVRegister<String>, right: MVRegister<String>): String =
        if (left != right && left.values == right.values) {
            "\n  (the live values agree — the two states differ only in their causal context)"
        } else {
            ""
        }

    private companion object {
        /** Trajectories per run of the general associativity law. */
        const val TRAJECTORY_TRIALS = 120

        /**
         * Floors the trajectory generator must clear. **Measured on seed 2086: 141 snapshots held
         * concurrent values and 3,254 merges dropped a value one operand still held, across 121,254
         * triples.** Each floor sits at roughly half its measurement, so an incidental tweak does not
         * red-light the suite, while a generator that stopped producing conflicts — or stopped making
         * a retirement meet a peer that never saw it, the shape that broke `ORMap` — fails loudly
         * instead of passing vacuously.
         */
        const val MIN_MULTI_VALUE_SNAPSHOTS = 70
        const val MIN_RETIRING_MERGES = 1600

        val REPLICAS = listOf(ReplicaId("A"), ReplicaId("B"), ReplicaId("C"))
    }
}
