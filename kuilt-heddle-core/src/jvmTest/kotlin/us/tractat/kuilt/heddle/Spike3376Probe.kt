package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.heddle.WakeCreditRig.A
import us.tractat.kuilt.heddle.WakeCreditRig.A1
import us.tractat.kuilt.heddle.WakeCreditRig.A2
import us.tractat.kuilt.heddle.WakeCreditRig.Arm
import us.tractat.kuilt.heddle.WakeCreditRig.LaneSpec
import us.tractat.kuilt.heddle.WakeCreditRig.WakeConfig
import us.tractat.kuilt.heddle.WakeCreditRig.fmt
import kotlin.test.Test

/** SPIKE (#3376): print-only. S1/S7 rigs from af173's probe, a §10.6 idle-length arm, and #3238's slot rig. */
class Spike3376Probe {
    private val root = GroupId("p")
    private val wants = Demand(targetOutstanding = 1_000_000L, maximumUsefulGrant = 1L)

    private class Cell(override var current: EntitlementLedger, private val watched: List<AttachmentRecord>) : LedgerCell {
        val granted = HashMap<String, Int>()
        override fun mutateOrSkip(transform: (EntitlementLedger) -> Patch<EntitlementLedger>?): Boolean {
            val patch = transform(current) ?: return false
            val before = current
            current = current.piece(patch.delta)
            watched.filter { current.baseIssuance(it.id) > before.baseIssuance(it.id) }.forEach { granted[it.child.value] = (granted[it.child.value] ?: 0) + 1 }
            return true
        }
        fun mint(tag: String, n: Long) { current = current.piece(current.mint(MintId(tag), GroupId("p"), LOCAL, n).delta) }
    }

    private fun ledger(children: List<AttachmentRecord>, seats: Map<AttachmentRecord, Long>): EntitlementLedger {
        val l = shapedLedger(root, children)
        if (seats.isEmpty()) return l
        return l.piece(EntitlementLedger.of(gauges = seats.map { (e, t) -> e.id to Gauge(ticksToVirtual(t, e.weight), 0L) }.toMap()))
    }

    private fun alloc(observed: Map<AttachmentRecord, Boolean>) = FairShareAllocator(
        LOCAL, PolicyConfig(quantum = 1L),
        AllocatorMemory(wakeOffsets = emptyMap(), demandingObserved = observed.mapKeys { it.key.id }),
    )

    private fun bursts(weights: List<Long>, perBurst: Long, steady: Boolean, episodes: Int = 300): Map<String, Int> {
        val kids = weights.mapIndexed { i, w -> edgeOf(root, GroupId("p/b$i"), w) }
        val c = edgeOf(root, GroupId("p/steady"))
        val all = if (steady) kids + c else kids
        val cell = Cell(ledger(all, emptyMap()), all)
        val a = alloc(emptyMap())
        val steadyDemand = { id: AttachmentId -> if (steady && id == c.id) wants else Demand.NONE }
        a.schedule(root, cell, 0L, steadyDemand)
        repeat(episodes) { i ->
            cell.mint("m$i", perBurst)
            a.schedule(root, cell, 0L) { wants }
            a.schedule(root, cell, 0L, steadyDemand)
        }
        return cell.granted.toSortedMap()
    }

    private fun staggered(weights: List<Long>, first: Int, rest: Long, episodes: Int = 300): Map<String, Int> {
        val kids = weights.mapIndexed { i, w -> edgeOf(root, GroupId("p/b$i"), w) }
        val cell = Cell(ledger(kids, emptyMap()), kids)
        val a = alloc(emptyMap())
        a.schedule(root, cell, 0L) { Demand.NONE }
        repeat(episodes) { i ->
            cell.mint("f$i", 1L)
            a.schedule(root, cell, 0L) { id -> if (id == kids[first].id) wants else Demand.NONE }
            cell.mint("r$i", rest)
            a.schedule(root, cell, 0L) { wants }
            a.schedule(root, cell, 0L) { Demand.NONE }
        }
        return cell.granted.toSortedMap()
    }

    /**
     * §10.6: a and b equal, a 3-unit burst both want (b leaves owed), then b idles [idle] episodes
     * while a takes 2 units alone in each, then a 10-unit burst both want. b's take of that last burst.
     */
    private fun idleLength(idle: Int): Int {
        val a = edgeOf(root, GroupId("p/a"))
        val b = edgeOf(root, GroupId("p/b"))
        val cell = Cell(ledger(listOf(a, b), emptyMap()), listOf(a, b))
        val al = alloc(emptyMap())
        al.schedule(root, cell, 0L) { Demand.NONE }
        cell.mint("w", 3L)
        al.schedule(root, cell, 0L) { wants }
        repeat(idle) { i ->
            cell.mint("i$i", 2L)
            al.schedule(root, cell, 0L) { id -> if (id == a.id) wants else Demand.NONE }
        }
        val before = cell.granted["p/b"] ?: 0
        cell.mint("z", 10L)
        al.schedule(root, cell, 0L) { wants }
        return (cell.granted["p/b"] ?: 0) - before
    }

    private fun oneShot(seats: Map<AttachmentRecord, Long>, newborns: List<AttachmentRecord>, observed: Map<AttachmentRecord, Boolean>, idle: Set<AttachmentRecord>, supply: Long): Map<String, Int> {
        val all = seats.keys.toList() + newborns
        val cell = Cell(ledger(all, seats), all)
        cell.mint("m", supply)
        alloc(observed).schedule(root, cell, 0L) { id -> if (idle.any { it.id == id }) Demand.NONE else wants }
        return cell.granted.toSortedMap()
    }

    private fun slotRows(tag: String) {
        for (think in listOf(7L, 4L)) {
            val runs = listOf(Arm.HONEST, Arm.OVER_LAST, Arm.CANCEL_LAST, Arm.UNDER_LAST).associateWith { arm ->
                WakeCreditRig.run(WakeConfig("1L t$think $arm", WakeCreditRig.oneLevel(), listOf(LaneSpec(A, arm, think, 4, listOf(8L))), horizon = 4_000L))
            }
            val h = runs.getValue(Arm.HONEST).share(A)
            println("SPIKE $tag slot1L t$think honest=${fmt(h)} " + runs.filterKeys { it != Arm.HONEST }.entries.joinToString(" ") { (arm, r) -> "$arm=${fmt(r.share(A) - h)}" })
            val noise = listOf(listOf(4L, 4L, 4L, 20L), listOf(2L, 14L)).joinToString(" ") { costs ->
                val r = WakeCreditRig.run(WakeConfig("n", WakeCreditRig.oneLevel(), listOf(LaneSpec(A, Arm.NOISE, think, 4, costs)), horizon = 4_000L))
                "noise$costs=${fmt(r.share(A))}"
            }
            println("SPIKE $tag slot1L t$think $noise")
            val two = listOf(Arm.HONEST, Arm.OVER_LAST, Arm.CANCEL_LAST).associateWith { arm ->
                WakeCreditRig.run(
                    WakeConfig(
                        "2L", WakeCreditRig.twoLevel(),
                        listOf(LaneSpec(A1, arm, think, 4, listOf(8L)), LaneSpec(A2, Arm.HONEST, 11L - think, 4, listOf(8L))),
                        horizon = 4_000L,
                    ),
                )
            }
            println(
                "SPIKE $tag slot2L t$think " + two.entries.joinToString(" ") { (arm, r) -> "$arm[a1=${fmt(r.share(A1))} a2=${fmt(r.share(A2))}]" },
            )
        }
    }

    @Test
    fun probe() {
        val a = edgeOf(root, GroupId("p/a")); val b = edgeOf(root, GroupId("p/b")); val cc = edgeOf(root, GroupId("p/c")); val n = edgeOf(root, GroupId("p/n"))
        val variants = listOf(
            Triple("MAIN", false, 0), Triple("MAIN+keep", false, 1), Triple("MAIN+keepK", false, 2),
            Triple("UNIFIED", true, 0), Triple("UNIFIED+keep", true, 1), Triple("UNIFIED+keepK", true, 2),
        )
        try {
            for ((name, unified, rule) in variants) {
                spikeUnified = unified
                spikeLagRule = rule
                println("SPIKE $name S1 two equal, 3/burst: ${bursts(listOf(1, 1), 3, steady = false)}")
                println("SPIKE $name S1b two equal + steady, 4/burst: ${bursts(listOf(1, 1), 4, steady = true)}")
                println("SPIKE $name S1c 2:1, 2/burst: ${bursts(listOf(2, 1), 2, steady = false)}")
                println("SPIKE $name S1d 3:1, 5/burst: ${bursts(listOf(3, 1), 5, steady = false)}")
                println("SPIKE $name S1e three equal, 4/burst: ${bursts(listOf(1, 1, 1), 4, steady = false)}")
                println("SPIKE $name S1f 2:1 + steady, 3/burst: ${bursts(listOf(2, 1), 3, steady = true)}")
                println("SPIKE $name S7 staggered 2:1 heavy first: ${staggered(listOf(2, 1), 0, 1)}")
                println("SPIKE $name S7b staggered 2:1 light first: ${staggered(listOf(2, 1), 1, 1)}")
                println("SPIKE $name S7c staggered 1:1 b0 first 1+2: ${staggered(listOf(1, 1), 0, 2)}")
                println("SPIKE $name S7d staggered 1:1 b1 first 1+2: ${staggered(listOf(1, 1), 1, 2)}")
                println("SPIKE $name IDLE b's take of 10 after idling N: " + listOf(1, 10, 100, 1000).joinToString { "N=$it:${idleLength(it)}" })
                println("SPIKE $name S2a: ${oneShot(mapOf(a to 16000L, b to 0L), emptyList(), mapOf(a to false, b to false), emptySet(), 100)}")
                println("SPIKE $name S3: ${oneShot(mapOf(a to 1600L, b to 0L, cc to 160L), emptyList(), mapOf(a to false, b to false, cc to false), setOf(cc), 100)}")
                println("SPIKE $name S4b: ${oneShot(mapOf(b to 0L, a to 0L), listOf(n), mapOf(b to true, a to false), emptySet(), 150)}")
                println("SPIKE $name S6: ${oneShot(mapOf(b to 0L, a to 0L), listOf(n), mapOf(b to false, a to false), emptySet(), 150)}")
                slotRows(name)
            }
            spikeUnified = true
            spikeLagRule = 2
            for (cap in listOf(0L, 1L, 4L, 8L)) {
                spikeLagCap = cap
                for (counts in listOf(false, true)) {
                    spikeCapCountsOutstanding = counts
                    val tag = "UNIFIED+keepK cap=$cap outstanding=$counts"
                    println("SPIKE $tag S1c: ${bursts(listOf(2, 1), 2, steady = false)} S7: ${staggered(listOf(2, 1), 0, 1)}")
                    slotRows(tag)
                }
            }
        } finally {
            spikeUnified = false
            spikeLagRule = 0
            spikeLagCap = null
            spikeCapCountsOutstanding = false
        }
    }
}
