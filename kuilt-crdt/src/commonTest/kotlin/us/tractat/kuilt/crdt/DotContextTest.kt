package us.tractat.kuilt.crdt

import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DotContextTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    @Test
    fun emptyContainsNothing() {
        assertFalse(DotContext.EMPTY.contains(Dot(a, 1L)))
    }

    @Test
    fun addThenContains() {
        val ctx = DotContext.EMPTY.add(Dot(a, 1L))
        assertTrue(ctx.contains(Dot(a, 1L)))
        assertFalse(ctx.contains(Dot(a, 2L)))
    }

    @Test
    fun nextDotMintsTheNextSeq() {
        assertEquals(Dot(a, 1L), DotContext.EMPTY.nextDot(a))
        assertEquals(Dot(a, 2L), DotContext.of(Dot(a, 1L)).nextDot(a))
    }

    @Test
    fun contiguousDotsCompactRegardlessOfOrder() {
        val forward = DotContext.EMPTY.add(Dot(a, 1L)).add(Dot(a, 2L))
        val backward = DotContext.EMPTY.add(Dot(a, 2L)).add(Dot(a, 1L))
        assertEquals(forward, backward)
        assertEquals(DotContext.of(Dot(a, 1L), Dot(a, 2L)), forward)
    }

    @Test
    fun gapStaysInCloudUntilFilled() {
        val withGap = DotContext.EMPTY.add(Dot(a, 3L)) // missing (A,1),(A,2)
        assertTrue(withGap.contains(Dot(a, 3L)))
        assertFalse(withGap.contains(Dot(a, 2L)))
        assertEquals(Dot(a, 1L), withGap.nextDot(a))
        val filled = withGap.add(Dot(a, 1L)).add(Dot(a, 2L))
        assertEquals(DotContext.of(Dot(a, 1L), Dot(a, 2L), Dot(a, 3L)), filled)
        assertTrue(filled.contains(Dot(a, 2L)))
    }

    /**
     * [DotContext.witnessing] builds in one pass what folding [DotContext.add] builds in n, and
     * the two must be **indistinguishable** — same vector, same cloud, hence the same equality and
     * the same bytes. The one-pass build is what keeps a bulk delta's context linear rather than
     * quadratic; the fold is the definition it has to reproduce.
     *
     * Randomised over gaps, duplicates, several replicas and shuffled input, because those are
     * exactly the shapes that separate "compacts into the vector" from "parks in the cloud".
     *
     * The input is a **[List], not a [Set]** — deliberately. Deduplicating it made every trial's
     * seqs distinct, and with distinct sorted seqs [DotContext.witnessing]'s frontier guard
     * `seqs[index] <= frontier + 1` can only ever be satisfied as `==`, so the duplicate-absorbing
     * arm was dead in all 400 trials (issue #2250). Both sides accept repeats: the fold because
     * [DotContext.add] is idempotent, the one-pass build because it takes a `Collection<Dot>`
     * rather than a `Set` — and [DotContext.of], which is public, hands it exactly that.
     */
    @Test
    fun witnessingBuildsInOnePassWhatFoldingAddBuildsInN() {
        val random = Random(9)
        val replicas = listOf(a, b, ReplicaId("C"))
        var cloudyCases = 0
        var duplicateCases = 0

        repeat(400) { trial ->
            val dots = List(random.nextInt(0, 40)) {
                Dot(replicas.random(random), random.nextInt(1, 12).toLong())
            }.shuffled(random)

            val folded = dots.fold(DotContext.EMPTY) { context, dot -> context.add(dot) }
            val onePass = DotContext.witnessing(dots)
            if (onePass.cloud.isNotEmpty()) cloudyCases++
            if (dots.size > dots.toSet().size) duplicateCases++

            assertEquals(folded, onePass, "trial $trial: one-pass build must equal the fold over $dots")
        }

        // Measured on seed 9: 384 of 400 cloudy, 317 of 400 with a repeat. Both floored at roughly
        // half, so a generator that stopped producing either shape fails loudly rather than
        // passing vacuously.
        assertAll(
            // Gaps: the shape the cloud arm exists for.
            { assertTrue(cloudyCases > 190, "vacuous: only $cloudyCases of 400 trials left anything in the cloud") },
            // Repeats: the shape the frontier guard's `<=` exists for. This floor is the one #2250
            // lacked — the cloud floor above is fully satisfied by an all-distinct generator, so it
            // is structurally unable to notice that no trial ever repeats a dot.
            { assertTrue(duplicateCases > 150, "vacuous: only $duplicateCases of 400 trials repeated a dot") },
        )
    }

    /**
     * The frontier walk's duplicate-absorbing arm, named. [DotContext.witnessing]'s guard is
     * `seqs[index] <= frontier + 1` rather than `== frontier + 1` precisely so a repeated seq is
     * absorbed instead of stalling the walk and stranding the rest of the run in the cloud.
     *
     * A repeat is inside the contract, not hypothetical: the parameter is a `Collection<Dot>`, and
     * the public [DotContext.of] passes a caller's `vararg` straight through. Written out by hand
     * alongside the randomised trial above because a literal case names the shape in the failure
     * message, and pins the three positions a repeat can take relative to the frontier — the
     * randomiser reaches them, but nothing makes it keep doing so.
     */
    @Test
    fun witnessingAbsorbsRepeatedDotsLikeTheFold() {
        val c = ReplicaId("C")
        val cases = listOf(
            // Repeat at the frontier, unsorted, with a gap behind it: the repeat must leave the
            // frontier at 1 and (A,3) in the cloud, not walk the frontier forward to 3.
            listOf(Dot(a, 3L), Dot(a, 1L), Dot(a, 1L)),
            // Repeat inside a contiguous run: the run still compacts whole, frontier 3.
            listOf(Dot(a, 1L), Dot(a, 2L), Dot(a, 2L), Dot(a, 3L)),
            // Repeat with no frontier to stand on: both copies collapse to one cloud dot.
            listOf(Dot(a, 2L), Dot(a, 2L)),
            // Repeats interleaved across replicas, so a per-replica frontier cannot borrow another's.
            listOf(Dot(a, 1L), Dot(b, 1L), Dot(a, 1L), Dot(b, 2L), Dot(c, 5L), Dot(c, 5L)),
        )

        assertAll(
            *cases.map { dots ->
                {
                    // The rig asserts its own precondition: a case that stopped repeating a dot
                    // would still pass the equality below, silently, for the reason #2250 records.
                    assertTrue(dots.size > dots.toSet().size, "rig exercises no repeat: $dots")
                    assertEquals(
                        dots.fold(DotContext.EMPTY) { context, dot -> context.add(dot) },
                        DotContext.witnessing(dots),
                        "one-pass build must equal the fold over $dots",
                    )
                }
            }.toTypedArray(),
        )
    }

    @Test
    fun addIsIdempotent() {
        val once = DotContext.EMPTY.add(Dot(a, 1L))
        assertEquals(once, once.add(Dot(a, 1L)))
    }

    @Test
    fun pieceUnionsHistories() {
        val left = DotContext.of(Dot(a, 1L), Dot(a, 2L))
        val right = DotContext.of(Dot(b, 1L))
        val merged = left.piece(right)
        assertTrue(merged.contains(Dot(a, 2L)))
        assertTrue(merged.contains(Dot(b, 1L)))
        assertEquals(DotContext.of(Dot(a, 1L), Dot(a, 2L), Dot(b, 1L)), merged)
    }

    @Test
    fun pieceCompactsAcrossOperands() {
        val merged = DotContext.of(Dot(a, 1L)).piece(DotContext.of(Dot(a, 2L)))
        assertEquals(DotContext.of(Dot(a, 1L), Dot(a, 2L)), merged)
    }

    @Test
    fun roundTripsThroughJson() {
        val ctx = DotContext.of(Dot(a, 1L), Dot(a, 2L), Dot(b, 4L))
        val encoded = Json.encodeToString(DotContext.serializer(), ctx)
        assertEquals(ctx, Json.decodeFromString(DotContext.serializer(), encoded))
    }
}
