package us.tractat.kuilt.examples

import us.tractat.kuilt.crdt.Causal
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.DotContext
import us.tractat.kuilt.crdt.DotSet
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Example: causal add-wins semantics using [Causal] + [DotSet].
 *
 * [Causal] is the foundational lattice primitive that underpins ORSet, MVRegister,
 * ORMap, and the whole delta-state zoo. It pairs a [DotStore] (here a [DotSet]) with
 * a [DotContext] that records every dot ever witnessed by a replica. The causal join
 * ([Causal.piece]) implements the key invariant:
 *
 * - A dot present in *both* stores survives the merge.
 * - A dot present in **one** store but *witnessed by the other replica's context*
 *   (i.e. the other replica removed it) is **dropped**.
 * - A dot present in one store and **not** in the other's context (i.e. the other
 *   replica never saw it) is **kept** — this is the add-wins rule.
 *
 * ## Why the Causal lattice matters
 *
 * The add-wins guarantee is what makes ORSet and ORMap useful: a concurrent `remove`
 * can only remove the exact dots it has observed. A concurrent `add` that mints a
 * fresh dot is invisible to the remover, so it survives. Understanding this at the
 * [Causal] level makes it clear why the higher-level CRDTs behave the way they do.
 *
 * ## API surface exercised
 *
 * - [Causal] constructor with a [DotSet] store and a [DotContext]
 * - [DotContext.of] to build a context from known dots
 * - [Causal.piece] for the causal join
 * - [DotSet.dots] and [DotSet.isBottom] to inspect the resulting store
 */
class CausalAddWinsTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    @Test
    fun `add-wins when the adder's dot is unknown to the remover`() {
        // Scenario: both replicas started with dot (A,1) present.
        //   Alice removed it — her store is now empty but her context records (A,1).
        //   Bob concurrently added a new dot (B,1) that Alice has never seen.
        val alice = Causal(
            store = DotSet(emptySet()),
            context = DotContext.of(Dot(a, 1L)),
        )
        val bob = Causal(
            store = DotSet(setOf(Dot(a, 1L), Dot(b, 1L))),
            context = DotContext.of(Dot(a, 1L), Dot(b, 1L)),
        )

        val merged = alice.piece(bob)

        // (A,1): Alice witnessed and removed it → dropped.
        // (B,1): Alice never witnessed it → kept (add-wins).
        assertEquals(setOf(Dot(b, 1L)), merged.store.dots)
        assertFalse(merged.store.isBottom, "at least one dot must survive")
    }

    @Test
    fun `remove-wins when the remover had already witnessed the dot`() {
        // Alice's context now includes (B,1) as well — she had seen both dots before
        // performing the remove. Her empty store means she removed everything she saw.
        val alice = Causal(
            store = DotSet(emptySet()),
            context = DotContext.of(Dot(a, 1L), Dot(b, 1L)),
        )
        val bob = Causal(
            store = DotSet(setOf(Dot(a, 1L), Dot(b, 1L))),
            context = DotContext.of(Dot(a, 1L), Dot(b, 1L)),
        )

        val merged = alice.piece(bob)

        // Alice's context witnessed both — both are dropped by the join.
        assertTrue(merged.store.isBottom, "all dots must be removed")
    }

    @Test
    fun `piece is commutative — merge order does not change the result`() {
        val alice = Causal(
            store = DotSet(emptySet()),
            context = DotContext.of(Dot(a, 1L)),
        )
        val bob = Causal(
            store = DotSet(setOf(Dot(a, 1L), Dot(b, 1L))),
            context = DotContext.of(Dot(a, 1L), Dot(b, 1L)),
        )

        assertEquals(alice.piece(bob), bob.piece(alice))
    }

    @Test
    fun `piece is idempotent — merging a replica with itself is a no-op`() {
        val state = Causal(
            store = DotSet(setOf(Dot(a, 1L))),
            context = DotContext.of(Dot(a, 1L)),
        )

        assertEquals(state, state.piece(state))
    }

    @Test
    fun `three-way merge converges regardless of pairing order`() {
        val c = ReplicaId("C")

        // All three replicas have disjoint dots — nobody has seen anybody else.
        val peerA = Causal(DotSet(setOf(Dot(a, 1L))), DotContext.of(Dot(a, 1L)))
        val peerB = Causal(DotSet(setOf(Dot(b, 1L))), DotContext.of(Dot(b, 1L)))
        val peerC = Causal(DotSet(setOf(Dot(c, 1L))), DotContext.of(Dot(c, 1L)))

        val leftFirst = peerA.piece(peerB).piece(peerC)
        val rightFirst = peerA.piece(peerB.piece(peerC))

        // All three dots survive — none was ever removed.
        assertEquals(leftFirst, rightFirst)
        assertEquals(setOf(Dot(a, 1L), Dot(b, 1L), Dot(c, 1L)), leftFirst.store.dots)
    }
}
