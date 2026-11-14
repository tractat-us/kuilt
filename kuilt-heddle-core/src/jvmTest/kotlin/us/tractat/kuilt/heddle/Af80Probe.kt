package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.Patch
import kotlin.test.Test

/** Reviewer probe (af80): print-only, not for merge. */
class Af80Probe {
    private val root = GroupId("p")
    private fun want(max: Long = 1L) = Demand(targetOutstanding = 1_000_000L, maximumUsefulGrant = max)

    private class Cell(override var current: EntitlementLedger, private val watched: List<AttachmentRecord>) : LedgerCell {
        val granted = HashMap<String, Long>()
        override fun mutateOrSkip(transform: (EntitlementLedger) -> Patch<EntitlementLedger>?): Boolean {
            val patch = transform(current) ?: return false
            val before = current
            current = current.piece(patch.delta)
            watched.forEach {
                val d = current.baseIssuance(it.id) - before.baseIssuance(it.id)
                if (d > 0) granted[it.child.value] = (granted[it.child.value] ?: 0L) + d
            }
            return true
        }
        fun mint(tag: String, n: Long) { current = current.piece(current.mint(MintId(tag), GroupId("p"), LOCAL, n).delta) }
        fun refund(e: AttachmentRecord, n: Long) { current = current.piece(checkNotNull(current.release(LOCAL, e.id, n)) { "release refused" }.delta) }
    }

    private fun ledger(children: List<AttachmentRecord>, seats: Map<AttachmentRecord, Long>): EntitlementLedger {
        val l = shapedLedger(root, children)
        if (seats.isEmpty()) return l
        return l.piece(EntitlementLedger.of(gauges = seats.map { (e, t) -> e.id to Gauge(ticksToVirtual(t, e.weight), 0L) }.toMap()))
    }

    private fun alloc(q: Long, observed: Map<AttachmentRecord, Boolean> = emptyMap()) = FairShareAllocator(
        LOCAL, PolicyConfig(quantum = q),
        AllocatorMemory(wakeOffsets = emptyMap(), demandingObserved = observed.mapKeys { it.key.id }),
    )

    /** P2: sibling b's over-reserved grant (10, uses 1) is in flight when a is seen leaving; refunded [early] (before) or late. */
    private fun siblingInFlight(early: Boolean): Map<String, Long> {
        val a = edgeOf(root, GroupId("p/a"))
        val b = edgeOf(root, GroupId("p/b"))
        val cell = Cell(ledger(listOf(a, b), emptyMap()), listOf(a, b))
        val al = alloc(10L)
        al.schedule(root, cell, 0L) { Demand.NONE }
        cell.mint("m1", 11L)
        al.schedule(root, cell, 0L) { id -> if (id == a.id) want(1) else want(10) }
        val r1 = HashMap(cell.granted)
        if (early) cell.refund(b, 9L)
        cell.mint("m2", 20L)
        al.schedule(root, cell, 0L) { id -> if (id == a.id) Demand.NONE else want(1) }
        if (!early) cell.refund(b, 9L)
        val before = HashMap(cell.granted)
        cell.mint("m3", 20L)
        al.schedule(root, cell, 0L) { want(1) }
        return mapOf(
            "r1a" to (r1["p/a"] ?: 0), "r1b" to (r1["p/b"] ?: 0),
            "a" to ((cell.granted["p/a"] ?: 0) - (before["p/a"] ?: 0)), "b" to ((cell.granted["p/b"] ?: 0) - (before["p/b"] ?: 0)),
        )
    }

    /** P1: a leaves {a,b,c}; c leaves; a returns to b alone, nobody having moved. [pause]=false keeps a demanding throughout. */
    private fun setShrinks(aTicks: Long, pause: Boolean): Map<String, Long> {
        val a = edgeOf(root, GroupId("p/a"))
        val b = edgeOf(root, GroupId("p/b"))
        val c = edgeOf(root, GroupId("p/c"))
        val cell = Cell(ledger(listOf(a, b, c), mapOf(a to aTicks, b to 30L * 16, c to 30L * 16)), listOf(a, b, c))
        val al = alloc(1L, mapOf(a to true, b to true, c to true))
        al.schedule(root, cell, 0L) { id -> if (id == a.id && pause) Demand.NONE else want() }
        al.schedule(root, cell, 0L) { id -> if (id == b.id || (id == a.id && !pause)) want() else Demand.NONE }
        cell.mint("m", 100L)
        al.schedule(root, cell, 0L) { id -> if (id == c.id) Demand.NONE else want() }
        return cell.granted.toSortedMap()
    }

    /** S8: [first] alone 1 unit, everyone idle, both 1 unit, everyone idle. */
    private fun aloneThenPause(weights: List<Long>, first: Int, episodes: Int = 300): Map<String, Long> {
        val kids = weights.mapIndexed { i, w -> edgeOf(root, GroupId("p/b$i"), w) }
        val cell = Cell(ledger(kids, emptyMap()), kids)
        val al = alloc(1L)
        al.schedule(root, cell, 0L) { Demand.NONE }
        repeat(episodes) { i ->
            cell.mint("f$i", 1L)
            al.schedule(root, cell, 0L) { id -> if (id == kids[first].id) want() else Demand.NONE }
            al.schedule(root, cell, 0L) { Demand.NONE }
            cell.mint("r$i", 1L)
            al.schedule(root, cell, 0L) { want() }
            al.schedule(root, cell, 0L) { Demand.NONE }
        }
        return cell.granted.toSortedMap()
    }

    /** S9: three children, staggered returns: b0 alone 1, then b0+b1 1, then all 3 units, then idle. */
    private fun ladder(weights: List<Long>, episodes: Int = 300): Map<String, Long> {
        val kids = weights.mapIndexed { i, w -> edgeOf(root, GroupId("p/b$i"), w) }
        val cell = Cell(ledger(kids, emptyMap()), kids)
        val al = alloc(1L)
        al.schedule(root, cell, 0L) { Demand.NONE }
        repeat(episodes) { i ->
            cell.mint("f$i", 1L)
            al.schedule(root, cell, 0L) { id -> if (id == kids[0].id) want() else Demand.NONE }
            cell.mint("g$i", 1L)
            al.schedule(root, cell, 0L) { id -> if (id != kids[2].id) want() else Demand.NONE }
            cell.mint("r$i", 3L)
            al.schedule(root, cell, 0L) { want() }
            al.schedule(root, cell, 0L) { Demand.NONE }
        }
        return cell.granted.toSortedMap()
    }

    @Test
    fun probe() {
        val saved = Triple(spikeUnified, spikeLagRule, spikeLagCap)
        try {
            val variants = listOf(Triple("MAIN", false, 0), Triple("UNIFIED", true, 0), Triple("PLANNED", true, 2), Triple("NOFSTAR", true, 1), Triple("NOSHIFT", true, 3), Triple("CLOCK", true, 5), Triple("CLOCK-mainfront", false, 5))
            for ((name, unified, rule) in variants) {
                spikeUnified = unified
                spikeLagRule = rule
                spikeLagCap = null
                println("AF80 $name P2 sibling in flight at departure: refund late=${siblingInFlight(false)} early=${siblingInFlight(true)}")
                println("AF80 $name P1 debtor(60) set shrinks: paused=${setShrinks(60L * 16, true)} stayed=${setShrinks(60L * 16, false)}")
                println("AF80 $name P1 owed(0) set shrinks: paused=${setShrinks(0L, true)} stayed=${setShrinks(0L, false)}")
                println(
                    "AF80 $name S8 alone,idle,both,idle 1:1 b0 first=${aloneThenPause(listOf(1, 1), 0)} 2:1 heavy first=${aloneThenPause(listOf(2, 1), 0)} " +
                        "2:1 light first=${aloneThenPause(listOf(2, 1), 1)} 3:1 heavy=${aloneThenPause(listOf(3, 1), 0)} 3:1 light=${aloneThenPause(listOf(3, 1), 1)}",
                )
                println("AF80 $name S9 ladder 1:1:1=${ladder(listOf(1, 1, 1))} 3:2:1=${ladder(listOf(3, 2, 1))} 1:2:3=${ladder(listOf(1, 2, 3))}")
            }
            spikeUnified = true
            spikeLagRule = 2
            for (cap in listOf(0L, 1L, 2L)) {
                spikeLagCap = cap
                println(
                    "AF80 PLANNED cap=$cap P2 late=${siblingInFlight(false)} S8 2:1 heavy=${aloneThenPause(listOf(2, 1), 0)} light=${aloneThenPause(listOf(2, 1), 1)} " +
                        "S9 3:2:1=${ladder(listOf(3, 2, 1))}",
                )
            }
        } finally {
            spikeUnified = saved.first
            spikeLagRule = saved.second
            spikeLagCap = saved.third
        }
    }
}
