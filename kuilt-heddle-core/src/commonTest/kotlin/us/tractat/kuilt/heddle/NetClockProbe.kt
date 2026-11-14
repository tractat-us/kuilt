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

/**
 * SPIKE (#3376, net-service clock): print-only, not for merge. Kept lag (rule 2) against the plain
 * parent clock (rule 5) and the net-service clock (rules 6 and 7), with rule 8 as a control that
 * also counts a departed child's own refund in the clock. The unit-cost rigs are af80's S7/S8/S9/P1/P2
 * plus S1c; the noisy arms are #3238's slot rig.
 */
class NetClockProbe {
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

    private fun kids(weights: List<Long>) = weights.mapIndexed { i, w -> edgeOf(root, GroupId("p/b$i"), w) }

    private fun split(cell: Cell, kids: List<AttachmentRecord>) = kids.joinToString("/") { "${cell.granted[it.child.value] ?: 0}" }

    /** S1c-style: everyone wants [perBurst] units, then everyone idles. */
    private fun bursts(weights: List<Long>, perBurst: Long, episodes: Int = 300): String {
        val ks = kids(weights)
        val cell = Cell(ledger(ks, emptyMap()), ks)
        val al = alloc(1L)
        al.schedule(root, cell, 0L) { Demand.NONE }
        repeat(episodes) { i ->
            cell.mint("m$i", perBurst)
            al.schedule(root, cell, 0L) { want() }
            al.schedule(root, cell, 0L) { Demand.NONE }
        }
        return split(cell, ks)
    }

    /** S7 ([gap] = false) and S8 ([gap] = true): [first] alone 1 unit, (idle), both 1 unit, idle. */
    private fun aloneThenBoth(weights: List<Long>, first: Int, gap: Boolean, episodes: Int = 300): String {
        val ks = kids(weights)
        val cell = Cell(ledger(ks, emptyMap()), ks)
        val al = alloc(1L)
        al.schedule(root, cell, 0L) { Demand.NONE }
        repeat(episodes) { i ->
            cell.mint("f$i", 1L)
            al.schedule(root, cell, 0L) { id -> if (id == ks[first].id) want() else Demand.NONE }
            if (gap) al.schedule(root, cell, 0L) { Demand.NONE }
            cell.mint("r$i", 1L)
            al.schedule(root, cell, 0L) { want() }
            al.schedule(root, cell, 0L) { Demand.NONE }
        }
        return split(cell, ks)
    }

    /** S9: b0 alone 1, b0+b1 1, all three 3, idle. */
    private fun ladder(weights: List<Long>, episodes: Int = 300): String {
        val ks = kids(weights)
        val cell = Cell(ledger(ks, emptyMap()), ks)
        val al = alloc(1L)
        al.schedule(root, cell, 0L) { Demand.NONE }
        repeat(episodes) { i ->
            cell.mint("f$i", 1L)
            al.schedule(root, cell, 0L) { id -> if (id == ks[0].id) want() else Demand.NONE }
            cell.mint("g$i", 1L)
            al.schedule(root, cell, 0L) { id -> if (id != ks[2].id) want() else Demand.NONE }
            cell.mint("r$i", 3L)
            al.schedule(root, cell, 0L) { want() }
            al.schedule(root, cell, 0L) { Demand.NONE }
        }
        return split(cell, ks)
    }

    /** P1: a leaves {a,b,c}; c leaves; a returns to b alone. [pause] = false keeps a asking. Returns a's take of 100. */
    private fun setShrinks(aTicks: Long, pause: Boolean): Long {
        val a = edgeOf(root, GroupId("p/a"))
        val b = edgeOf(root, GroupId("p/b"))
        val c = edgeOf(root, GroupId("p/c"))
        val cell = Cell(ledger(listOf(a, b, c), mapOf(a to aTicks, b to 30L * 16, c to 30L * 16)), listOf(a, b, c))
        val al = alloc(1L, mapOf(a to true, b to true, c to true))
        al.schedule(root, cell, 0L) { id -> if (id == a.id && pause) Demand.NONE else want() }
        al.schedule(root, cell, 0L) { id -> if (id == b.id || (id == a.id && !pause)) want() else Demand.NONE }
        cell.mint("m", 100L)
        al.schedule(root, cell, 0L) { id -> if (id == c.id) Demand.NONE else want() }
        return cell.granted["p/a"] ?: 0L
    }

    /**
     * P2: b's over-reserved grant (10, uses 1) is in flight when a leaves; refunded during a's gap,
     * or [early], before a is seen leaving. The two should agree. Returns a/b of the last 20.
     */
    private fun siblingRefund(early: Boolean): String {
        val a = edgeOf(root, GroupId("p/a"))
        val b = edgeOf(root, GroupId("p/b"))
        val cell = Cell(ledger(listOf(a, b), emptyMap()), listOf(a, b))
        val al = alloc(10L)
        al.schedule(root, cell, 0L) { Demand.NONE }
        cell.mint("m1", 11L)
        al.schedule(root, cell, 0L) { id -> if (id == a.id) want(1) else want(10) }
        if (early) cell.refund(b, 9L)
        cell.mint("m2", 20L)
        al.schedule(root, cell, 0L) { id -> if (id == a.id) Demand.NONE else want(1) }
        if (!early) cell.refund(b, 9L)
        val before = HashMap(cell.granted)
        cell.mint("m3", 20L)
        al.schedule(root, cell, 0L) { want(1) }
        return "${(cell.granted["p/a"] ?: 0) - (before["p/a"] ?: 0)}/${(cell.granted["p/b"] ?: 0) - (before["p/b"] ?: 0)}"
    }

    /** P2c: P2 with a third sibling c asking throughout. Returns a/b/c of the last round's 20 (+ refunded 9). */
    private fun siblingRefundThree(): String {
        val a = edgeOf(root, GroupId("p/a"))
        val b = edgeOf(root, GroupId("p/b"))
        val c = edgeOf(root, GroupId("p/c"))
        val cell = Cell(ledger(listOf(a, b, c), emptyMap()), listOf(a, b, c))
        val al = alloc(10L)
        al.schedule(root, cell, 0L) { Demand.NONE }
        cell.mint("m1", 12L)
        al.schedule(root, cell, 0L) { id -> if (id == b.id) want(10) else want(1) }
        cell.mint("m2", 20L)
        al.schedule(root, cell, 0L) { id -> if (id == a.id) Demand.NONE else want(1) }
        cell.refund(b, 9L)
        val before = HashMap(cell.granted)
        cell.mint("m3", 20L)
        al.schedule(root, cell, 0L) { want(1) }
        return listOf(a, b, c).joinToString("/") { "${(cell.granted[it.child.value] ?: 0) - (before[it.child.value] ?: 0)}" }
    }

    /** §10.6: b's take of an 11-unit burst after idling [idle] episodes while a takes 2 alone. */
    private fun idleLength(idle: Int, first: Long): Long {
        val a = edgeOf(root, GroupId("p/a"))
        val b = edgeOf(root, GroupId("p/b"))
        val cell = Cell(ledger(listOf(a, b), emptyMap()), listOf(a, b))
        val al = alloc(1L)
        al.schedule(root, cell, 0L) { Demand.NONE }
        cell.mint("w", first)
        al.schedule(root, cell, 0L) { want() }
        repeat(idle) { i ->
            cell.mint("i$i", 2L)
            al.schedule(root, cell, 0L) { id -> if (id == a.id) want() else Demand.NONE }
        }
        val before = cell.granted["p/b"] ?: 0
        cell.mint("z", 11L)
        al.schedule(root, cell, 0L) { want() }
        return (cell.granted["p/b"] ?: 0) - before
    }

    private fun slot(arm: Arm, think: Long, costs: List<Long> = listOf(8L)) =
        WakeCreditRig.run(WakeConfig("n", WakeCreditRig.oneLevel(), listOf(LaneSpec(A, arm, think, 4, costs)), horizon = 4_000L)).share(A)

    private fun slotRows(tag: String) {
        for (think in listOf(7L, 4L, 12L)) {
            val h = slot(Arm.HONEST, think)
            val arms = listOf(Arm.OVER_LAST, Arm.UNDER_LAST, Arm.CANCEL_LAST).joinToString(" ") { "$it=${fmt(slot(it, think) - h)}" }
            val noise = listOf(listOf(4L, 4L, 4L, 20L), listOf(2L, 14L)).joinToString(" ") { costs ->
                val n = slot(Arm.NOISE, think, costs)
                val hc = slot(Arm.HONEST, think, costs)
                "noise$costs=${fmt(n - hc)}(n=${fmt(n)} h=${fmt(hc)})"
            }
            val two = listOf(Arm.HONEST, Arm.OVER_LAST).joinToString(" ") { arm ->
                val r = WakeCreditRig.run(
                    WakeConfig(
                        "2L", WakeCreditRig.twoLevel(),
                        listOf(LaneSpec(A1, arm, think, 4, listOf(8L)), LaneSpec(A2, Arm.HONEST, 11L - think, 4, listOf(8L))),
                        horizon = 4_000L,
                    ),
                )
                "2L-$arm[a1=${fmt(r.share(A1))} a2=${fmt(r.share(A2))}]"
            }
            println("NSC $tag slot t$think honest=${fmt(h)} $arms $noise $two")
        }
    }

    @Test
    fun probe() {
        val saved = Triple(spikeUnified, spikeLagRule, spikeLagCap)
        try {
            val variants = listOf(
                Triple("MAIN", false, 0), Triple("KEPT", true, 2), Triple("CLOCK", true, 5),
                Triple("NET", true, 6), Triple("NETALL(control)", true, 8), Triple("NETFIFO", true, 9), Triple("NETREWIND", true, 10),
            )
            for ((name, unified, rule) in variants) {
                spikeUnified = unified
                spikeLagRule = rule
                spikeLagCap = null
                println("NSC $name S1c 2:1 2/burst=${bursts(listOf(2, 1), 2)} S1 1:1 3=${bursts(listOf(1, 1), 3)} S1d 3:1 5=${bursts(listOf(3, 1), 5)} S1e 1:1:1 4=${bursts(listOf(1, 1, 1), 4)}")
                println(
                    "NSC $name S7 2:1 heavy=${aloneThenBoth(listOf(2, 1), 0, false)} light=${aloneThenBoth(listOf(2, 1), 1, false)} " +
                        "3:1 heavy=${aloneThenBoth(listOf(3, 1), 0, false)} light=${aloneThenBoth(listOf(3, 1), 1, false)}",
                )
                println(
                    "NSC $name S8 1:1=${aloneThenBoth(listOf(1, 1), 0, true)} 2:1 heavy=${aloneThenBoth(listOf(2, 1), 0, true)} light=${aloneThenBoth(listOf(2, 1), 1, true)} " +
                        "3:1 heavy=${aloneThenBoth(listOf(3, 1), 0, true)} light=${aloneThenBoth(listOf(3, 1), 1, true)}",
                )
                println("NSC $name S9 1:1:1=${ladder(listOf(1, 1, 1))} 3:2:1=${ladder(listOf(3, 2, 1))} 1:2:3=${ladder(listOf(1, 2, 3))}")
                println(
                    "NSC $name P1 debtor(60) paused=${setShrinks(60L * 16, true)} stayed=${setShrinks(60L * 16, false)} " +
                        "owed(0) paused=${setShrinks(0L, true)} stayed=${setShrinks(0L, false)}",
                )
                println("NSC $name P2 refund-in-gap=${siblingRefund(false)} refund-before-leave=${siblingRefund(true)} P2c three=${siblingRefundThree()}")
                println("NSC $name IDLE owed: " + listOf(1, 10, 100, 1000).joinToString { "N=$it:${idleLength(it, 3L)}" } + " level: " + listOf(1, 10, 100, 1000).joinToString { "N=$it:${idleLength(it, 2L)}" })
                slotRows(name)
            }
        } finally {
            spikeUnified = saved.first
            spikeLagRule = saved.second
            spikeLagCap = saved.third
        }
    }
}
