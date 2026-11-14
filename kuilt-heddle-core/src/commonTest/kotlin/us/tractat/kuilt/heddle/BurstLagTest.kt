package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Weights hold when work arrives in short bursts with idle gaps between them (#3376).
 *
 * The rigs are the design review's (S1c, S7), on one [FairShareAllocator] with a quantum of 1 and
 * every unit minted at the parent. Each burst mints its supply, the children it names want it, and
 * then everyone idles a round, so the parent drains completely between bursts. Fair share is what
 * a fluid scheduler gives over the stretches each child actually wanted service: a unit handed out
 * while only one child asks is that child's, and a child that was not asking earns nothing for it
 * (design §10.6).
 *
 * On `main` each wake clamps the waker to the front, which forgives the lag a short burst leaves
 * behind, so S1c splits 300/300 and S7 hands the light child nothing. The ignored arms pin the
 * fix: a child keeps the lag it had when it stopped asking. [idlingLongerEarnsNothing] is live: it
 * holds on `main` and must keep holding once the fix lands.
 */
class BurstLagTest {
    private val root = GroupId("p")
    private val wants = Demand(targetOutstanding = 1_000_000L, maximumUsefulGrant = 1L)

    /** A ledger cell that counts each child's grants: a grant is a rise in the edge's base issuance. */
    private class Counting(override var current: EntitlementLedger, private val watched: List<AttachmentRecord>) : LedgerCell {
        val granted = LinkedHashMap<String, Int>().apply { watched.forEach { put(it.child.value, 0) } }

        override fun mutateOrSkip(transform: (EntitlementLedger) -> Patch<EntitlementLedger>?): Boolean {
            val patch = transform(current) ?: return false
            val before = current
            current = current.piece(patch.delta)
            for (edge in watched) {
                if (current.baseIssuance(edge.id) > before.baseIssuance(edge.id)) granted[edge.child.value] = granted.getValue(edge.child.value) + 1
            }
            return true
        }

        fun mint(tag: String, units: Long) {
            current = current.piece(current.mint(MintId(tag), GroupId("p"), LOCAL, units).delta)
        }
    }

    private class Rig(val kids: List<AttachmentRecord>, val cell: Counting, val allocator: FairShareAllocator)

    private fun rig(weights: List<Long>): Rig {
        val kids = weights.mapIndexed { i, w -> edgeOf(root, GroupId("p/b$i"), w) }
        val rig = Rig(kids, Counting(shapedLedger(root, kids), kids), FairShareAllocator(LOCAL, PolicyConfig(quantum = 1L)))
        // Seen idle once before the first burst, as a lane that has just been created is.
        rig.allocator.schedule(root, rig.cell, 0L) { Demand.NONE }
        return rig
    }

    private fun Rig.round(asking: (AttachmentId) -> Boolean) {
        allocator.schedule(root, cell, 0L) { id -> if (asking(id)) wants else Demand.NONE }
    }

    /** [bursts] times: mint [perBurst], every child wants it, then everyone idles a round. */
    private fun together(weights: List<Long>, perBurst: Long, bursts: Int = BURSTS): Map<String, Int> {
        val rig = rig(weights)
        repeat(bursts) { i ->
            rig.cell.mint("m$i", perBurst)
            rig.round { true }
            rig.round { false }
        }
        return rig.cell.granted
    }

    /** [bursts] times: one unit only child [first] wants, then [rest] units every child wants, then everyone idles. */
    private fun staggered(weights: List<Long>, first: Int, rest: Long, bursts: Int = BURSTS): Map<String, Int> {
        val rig = rig(weights)
        repeat(bursts) { i ->
            rig.cell.mint("f$i", 1L)
            rig.round { it == rig.kids[first].id }
            rig.cell.mint("r$i", rest)
            rig.round { true }
            rig.round { false }
        }
        return rig.cell.granted
    }

    private fun near(label: String, fair: Map<String, Int>, delivered: Map<String, Int>): () -> Unit = {
        assertEquals(fair.values.sum(), delivered.values.sum(), "$label: the rig handed out every unit it minted")
        for ((child, share) in fair) {
            val got = delivered.getValue(child)
            assertTrue(kotlin.math.abs(got - share) <= TOLERANCE, "$label: $child got $got of a fair $share, delivered $delivered")
        }
    }

    /** S1c: weights 2:1, two units a burst, both asking throughout. Fair is 400 / 200; `main` gives 300 / 300. */
    @Ignore // #3376: the wake clamp forgives the lag a short burst leaves; un-ignore with the fix.
    @Test
    fun shortBurstsHoldTheWeights() {
        assertAll(
            near("S1c 2:1, 2 a burst", mapOf("p/b0" to 400, "p/b1" to 200), together(listOf(2, 1), 2L)),
            near("S1 1:1, 3 a burst", mapOf("p/b0" to 450, "p/b1" to 450), together(listOf(1, 1), 3L)),
            near("S1d 3:1, 5 a burst", mapOf("p/b0" to 1125, "p/b1" to 375), together(listOf(3, 1), 5L)),
            near("S1e 1:1:1, 4 a burst", mapOf("p/b0" to 400, "p/b1" to 400, "p/b2" to 400), together(listOf(1, 1, 1), 4L)),
        )
    }

    /**
     * S7: weights 2:1, the heavy child asks alone for the first unit of each burst, then both ask for
     * one more. The first unit is uncontested, so it is the heavy child's; the second splits 2:1. Fair
     * is 500 / 100, and `main` gives 600 / 0. The issue's "about 400 / 200" counts the first unit as
     * contested, which would credit the light child for a stretch it was not asking: the spike's
     * no-clamp control lands exactly there.
     */
    @Ignore // #3376: the wake clamp forgives the lag a short burst leaves; un-ignore with the fix.
    @Test
    fun aChildThatAlwaysAsksSecondStillGetsItsShare() {
        assertAll(
            near("S7 2:1 heavy first", mapOf("p/b0" to 500, "p/b1" to 100), staggered(listOf(2, 1), first = 0, rest = 1L)),
            near("S7b 2:1 light first", mapOf("p/b0" to 200, "p/b1" to 400), staggered(listOf(2, 1), first = 1, rest = 1L)),
            near("S7c 1:1, 1 then 2", mapOf("p/b0" to 600, "p/b1" to 300), staggered(listOf(1, 1), first = 0, rest = 2L)),
        )
    }

    /**
     * a and b equal. A burst of [first] units both want, then b sits out [idle] rounds in which a
     * takes 2 units alone, then a burst of [last] both want: b's take of that last burst.
     */
    private fun takeAfterIdling(idle: Int, first: Long, last: Long): Int {
        val rig = rig(listOf(1, 1))
        val (a, _) = rig.kids
        rig.cell.mint("first", first)
        rig.round { true }
        repeat(idle) { i ->
            rig.cell.mint("idle$i", 2L)
            rig.round { it == a.id }
        }
        val before = rig.cell.granted.getValue("p/b1")
        val handedOut = rig.cell.granted.values.sum()
        rig.cell.mint("last", last)
        rig.round { true }
        check(rig.cell.granted.values.sum() == handedOut + last.toInt()) { "the last burst was handed out in full" }
        return rig.cell.granted.getValue("p/b1") - before
    }

    /**
     * §10.6, as a property: how long b sat out changes nothing about what it gets when it is back.
     * Holds on `main`, where the clamp forgives everything, and must hold once a waker keeps its lag.
     * The control that makes it falsifiable is a waker that keeps its raw position: on the spike it
     * took 7 of 11 after one idle round and all 11 after ten.
     */
    @Test
    fun idlingLongerEarnsNothing() {
        val random = Random(SEED)
        val cases = List(SAMPLES) { Triple(random.nextLong(2L, 8L), random.nextLong(5L, 20L), random.nextInt(2, 400)) }
        assertAll(
            *cases.map { (first, last, idle) ->
                {
                    val short = takeAfterIdling(1, first, last)
                    val long = takeAfterIdling(idle, first, last)
                    assertEquals(short, long, "first=$first last=$last: b's take after 1 idle round against $idle")
                }
            }.toTypedArray(),
            { assertEquals(SAMPLES, cases.size, "every sample ran") },
        )
    }

    /** b leaves owed half a unit, a having won the last tie. It is still owed when it comes back. */
    @Ignore // #3376: the wake clamp forgives the lag a short burst leaves; un-ignore with the fix.
    @Test
    fun lagOwedWhenLeavingIsStillOwedOnReturn() {
        assertAll(
            { assertEquals(6, takeAfterIdling(1, first = 3L, last = 11L), "owed: b wins the tie it lost") },
            { assertEquals(6, takeAfterIdling(1000, first = 3L, last = 11L), "owed, after a long idle") },
            { assertEquals(5, takeAfterIdling(1000, first = 2L, last = 11L), "level: a wins the tie") },
        )
    }

    private companion object {
        const val BURSTS = 300

        /** Units either way of fair, over the whole run: the quantum at the end of the last burst. */
        const val TOLERANCE = 2
        const val SEED = 3376L
        const val SAMPLES = 40
    }
}
