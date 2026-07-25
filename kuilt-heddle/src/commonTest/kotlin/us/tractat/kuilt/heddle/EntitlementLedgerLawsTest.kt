package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The standard zoo lattice-law property suite for [EntitlementLedger]: [piece] is
 * idempotent, commutative, and associative, and applying the same *set* of states
 * in any order converges to the same value by structural equality.
 *
 * These are the only properties this phase asserts — the ledger is inert (no
 * mutators, no economics) — but they are the load-bearing ones: convergence under
 * kuilt's drop/duplicate/reorder fabric is exactly the three laws plus
 * order-independent absorption. Randomized over a small fixed pool of edges,
 * replicas, groups, mints, and paths, with a seeded [Random] so failures reproduce.
 */
class EntitlementLedgerLawsTest {

    private val edges = listOf("e1", "e2", "e3").map(::AttachmentId)
    private val replicas = listOf("r1", "r2", "r3").map(::ReplicaId)
    private val groups = listOf("g1", "g2", "g3").map(::GroupId)
    private val mintIds = listOf("m1", "m2", "m3").map(::MintId)
    private val pathKeys = listOf(PathKey.ROOT) + edges.map(PathKey::of)

    private fun randomGCounter(rnd: Random): GCounter =
        GCounter.of(*replicas.filter { rnd.nextBoolean() }.map { it to rnd.nextLong(0L, 1_000L) }.toTypedArray())

    private fun randomEdgeCounters(rnd: Random): Map<AttachmentId, GCounter> =
        edges.filter { rnd.nextBoolean() }.associateWith { randomGCounter(rnd) }

    private fun randomRecord(id: AttachmentId, rnd: Random): AttachmentRecord =
        AttachmentRecord(
            id = id,
            parent = groups.random(rnd),
            child = groups.random(rnd),
            weight = Weight.of(rnd.nextLong(1L, 8L), rnd.nextLong(1L, 8L)),
            initialVirtualTime = rnd.nextLong(0L, 1_000L),
        )

    private fun randomLedger(rnd: Random): EntitlementLedger =
        EntitlementLedger.of(
            // Occasionally emit a *divergent* set (>1 record) under one id, so the laws
            // are exercised on the grow-only-set-union path that retains conflicts.
            records = edges.filter { rnd.nextBoolean() }.associateWith { id ->
                List(rnd.nextInt(1, 3)) { randomRecord(id, rnd) }.toSet()
            },
            minted = mintIds.filter { rnd.nextBoolean() }.associateWith {
                MintRecord(replicas.random(rnd), rnd.nextLong(0L, 1_000L))
            },
            issued = randomEdgeCounters(rnd),
            returned = randomEdgeCounters(rnd),
            leafSpent = randomEdgeCounters(rnd),
            rollupSpent = randomEdgeCounters(rnd),
            transfers = pathKeys.filter { rnd.nextBoolean() }.associateWith {
                replicas.filter { rnd.nextBoolean() }.associateWith { randomGCounter(rnd) }
            },
            // The relocation families (#1691) are ordinary GCounter maps, so the laws must hold
            // over them by the same product-of-lattices argument — parameterise them in too.
            issuedRelocIn = randomEdgeCounters(rnd),
            leafRelocIn = randomEdgeCounters(rnd),
            leafRelocOut = randomEdgeCounters(rnd),
            rollupRelocIn = randomEdgeCounters(rnd),
            rollupRelocOut = randomEdgeCounters(rnd),
        )

    @Test
    fun pieceIsIdempotent() {
        val rnd = Random(0x1DE)
        repeat(ITERATIONS) {
            val a = randomLedger(rnd)
            assertEquals(a, a.piece(a))
        }
    }

    @Test
    fun pieceIsCommutative() {
        val rnd = Random(0xC0)
        repeat(ITERATIONS) {
            val a = randomLedger(rnd)
            val b = randomLedger(rnd)
            assertEquals(a.piece(b), b.piece(a))
        }
    }

    @Test
    fun pieceIsAssociative() {
        val rnd = Random(0xA550C)
        repeat(ITERATIONS) {
            val a = randomLedger(rnd)
            val b = randomLedger(rnd)
            val c = randomLedger(rnd)
            assertEquals(a.piece(b).piece(c), a.piece(b.piece(c)))
        }
    }

    @Test
    fun applyingTheSameSetInAnyOrderConverges() {
        val rnd = Random(0xC04E5CE)
        repeat(ITERATIONS) {
            val states = List(rnd.nextInt(2, 6)) { randomLedger(rnd) }
            // Fold-merge the states in several independent shuffles; every order must
            // reach the identical merged ledger (structural ==).
            val canonical = states.reduce { acc, s -> acc.piece(s) }
            repeat(4) {
                val shuffled = states.shuffled(rnd).reduce { acc, s -> acc.piece(s) }
                assertEquals(canonical, shuffled)
            }
        }
    }

    /**
     * Monotonicity, the property the whole representation rests on: **no** stored counter slot
     * — base or relocation — ever falls under [EntitlementLedger.piece]. A net decrease is
     * expressed only by a second grow-only counter cancelling the first (#1691), never by a
     * decrement, so the merge stays a join and duplicate/reordered delivery stays absorbing.
     */
    @Test
    fun pieceNeverLowersAStoredCounterSlotInAnyFamily() {
        val rnd = Random(0x30A7)
        repeat(ITERATIONS) {
            val a = randomLedger(rnd)
            val b = randomLedger(rnd)
            val merged = a.piece(b)
            for (family in CounterFamily.entries) {
                for (e in edges) {
                    for (r in replicas) {
                        val before = a.storedSlot(family, e, r)
                        val after = merged.storedSlot(family, e, r)
                        assertTrue(after >= before, "$family slot ($e, $r) fell $before → $after under piece")
                        assertEquals(
                            maxOf(before, b.storedSlot(family, e, r)),
                            after,
                            "$family slot ($e, $r) is not the per-slot max of the two operands",
                        )
                    }
                }
            }
            // The lattice-order statement of the same fact: a ⊑ a ⊔ b.
            assertEquals(merged, merged.piece(a), "a is absorbed by a.piece(b) — a ⊑ a ⊔ b")
        }
    }

    @Test
    fun mergingWithZeroIsIdentity() {
        val rnd = Random(0x2E20)
        repeat(ITERATIONS) {
            val a = randomLedger(rnd)
            assertEquals(a, a.piece(EntitlementLedger.ZERO))
            assertEquals(a, EntitlementLedger.ZERO.piece(a))
        }
    }

    private companion object {
        const val ITERATIONS = 200
    }
}
