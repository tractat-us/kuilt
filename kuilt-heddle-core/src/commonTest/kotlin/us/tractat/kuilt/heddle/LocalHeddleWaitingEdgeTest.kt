package us.tractat.kuilt.heddle

import kotlinx.serialization.json.Json
import us.tractat.kuilt.heddle.Topology.ChildTemplate
import us.tractat.kuilt.heddle.Topology.Group
import us.tractat.kuilt.heddle.Topology.Lane
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The two edges of the `waiting` argument a consumer has to pass to every [LocalHeddle] call that
 * charges, since #3482 made it required on [LocalHeddle.admit], [LocalHeddle.extend] and
 * [LocalHeddle.complete].
 *
 * **A name the engine has never seen is the same as no name.** A waiting set that also names a
 * group with no edge in the ledger (an id from no topology, a declared-looking path nobody
 * declared, or an attached-child id under a template group, with that child not attached) gives
 * the same outcome, the same ledger, the same wake memory and the same later picks as the set
 * without it. That holds on every call, with or without ceilings. It does not rest on
 * [isLeasable]: an unattached child under a template is one [Leasable.ByTopology] declares. It
 * rests on every demand view being keyed by an edge's child ([LocalHeddle.demands]), so a name no
 * edge leads to can never be wanted. The lockstep rig below checks it call by call.
 *
 * **An empty waiting set on a charge is safe, and costs the queued sibling only the charge's own
 * credit.** An [LocalHeddle.extend] or an overrunning [LocalHeddle.complete] with `emptySet()`,
 * while sibling `b` really has work queued, throws nothing, leaves the books balanced, and the
 * next [LocalHeddle.acquire] with the true set leases. What it costs `b` is this: the call sees `b`
 * leave, so `b` comes back as a **returner**, moved by its parent's clock. It is not clamped level
 * as a waker. Its standing from before the call is kept exactly, so the picks that follow are the
 * picks of a run where the charge never happened. What it loses is the credit the true set would
 * have given it for the charge: at 1:1 with a 100-unit charge, it gets 50 fewer of the next 200
 * picks, so it nets 100 fewer than `a`. That is no bigger than its lag when it was behind or level
 * going in. When it was **ahead** going in, the loss is bigger than its lag. A `b` that took 10
 * extra slots is owed a run of 91 under the true set, and loses 100: it repays what it was ahead.
 */
class LocalHeddleWaitingEdgeTest {

    // ── Claim 1: an unknown name is no name ───────────────────────────────────────────────────

    /** `root → {a, b:2, tenants (template) → !t1, pool (template, empty)}`. */
    private fun tree(ceilings: Boolean): Topology = Topology(
        "root",
        listOf(
            Lane("a", maxConcurrent = if (ceilings) 1L else null),
            Lane("b", Weight.of(2)),
            Group("tenants", Weight.ONE, emptyList(), template = ChildTemplate(Weight.ONE, maxConcurrent = if (ceilings) 1L else null)),
            Group("pool", Weight.ONE, emptyList(), template = ChildTemplate(Weight.ONE)),
        ),
    )

    /** The engine of [t] with `t1` attached under `tenants` and three slots reconciled. */
    private fun bootedWithT1(t: Topology): LocalHeddle {
        val h = LocalHeddle.bootWhole(t, LocalPolicy(defaultReservation = 2L), epoch = 0L)
        val attached = h.attach(listOf("tenants"), "t1", generation = 0L)
        check(attached.refusals.isEmpty()) { "the rig: t1 attaches, got ${attached.refusals}" }
        h.reconcileCapacity(totalSlots = 3L)
        return h
    }

    /** What the lockstep rig did, so a rig that never reached a case fails. */
    private class Tally {
        val kinds = IntArray(4)
        var overruns = 0
        var chargesWithEmptyTrueSet = 0
        var cappedAcquires = 0
        var acquired = 0
    }

    /**
     * Two engines over [t], driven by the same seeded calls: `control` gets each call's waiting set,
     * `subject` gets it with [unknown] added. After every call the outcomes and the whole engine
     * state ([LocalHeddle.snapshot]: ledger, open leases, wake memory) must be equal; then 40 picks
     * with every known leaf waiting must agree, and so must the two persisted snapshots.
     */
    private fun lockstep(t: Topology, unknown: Set<GroupId>, seed: Int, tally: Tally, label: String) {
        val known = listOf(t.groupId("a"), t.groupId("b"), t.attachedChildId(listOf("tenants"), "t1"))
        val control = bootedWithT1(t)
        val subject = bootedWithT1(t)
        val rng = Random(seed)
        fun same(at: String, left: Any, right: Any) {
            if (left != right) fail("$label, seed $seed $at: the set with unknown names gave $right, without them $left")
        }
        repeat(STEPS) { step ->
            val open = control.snapshot().leases
            val drawn = rng.nextInt(4)
            val kind = if (open.isEmpty() && drawn >= 2) 0 else drawn
            val waiting = known.filter { rng.nextInt(3) != 0 }.toSet()
            val at = "step $step, kind $kind, waiting ${waiting.map { it.value }}"
            when (kind) {
                0 -> {
                    val eligible = waiting.filter { rng.nextBoolean() }.toSet()
                    val reservation = 1L + rng.nextInt(3)
                    val left = control.acquire(waiting, eligible, startedAt = step.toLong(), reservation)
                    val right = subject.acquire(waiting + unknown, eligible, startedAt = step.toLong(), reservation)
                    same(at, left, right)
                    if (left is AcquireOutcome.Acquired) tally.acquired++
                    if (left == AcquireOutcome.NothingEligible && eligible.isNotEmpty()) tally.cappedAcquires++
                }
                1 -> {
                    val leaf = known[rng.nextInt(known.size)]
                    val reservation = 1L + rng.nextInt(3)
                    same(
                        at,
                        control.admit(leaf, waiting + leaf, startedAt = step.toLong(), reservation),
                        subject.admit(leaf, waiting + leaf + unknown, startedAt = step.toLong(), reservation),
                    )
                }
                2 -> {
                    val lease = open[rng.nextInt(open.size)]
                    val amount = 1L + rng.nextInt(3)
                    same(at, control.extend(lease.id, amount, waiting), subject.extend(lease.id, amount, waiting + unknown))
                    if (waiting.isEmpty()) tally.chargesWithEmptyTrueSet++
                }
                else -> {
                    val lease = open[rng.nextInt(open.size)]
                    val cost = rng.nextInt(lease.reserved.toInt() + 4).toLong()
                    val left = control.complete(lease.id, cost, waiting)
                    same(at, left, subject.complete(lease.id, cost, waiting + unknown))
                    if (left is LeaseOutcome.Done && left.overrun > 0L) {
                        tally.overruns++
                        if (waiting.isEmpty()) tally.chargesWithEmptyTrueSet++
                    }
                }
            }
            tally.kinds[kind]++
            same(at, control.snapshot(), subject.snapshot())
        }
        val all = known.toSet()
        repeat(40) { i ->
            val left = control.acquire(all, all, startedAt = 1_000L + i)
            same("follow-up pick $i", left, subject.acquire(all + unknown, all, startedAt = 1_000L + i))
            if (left is AcquireOutcome.Acquired) {
                same("follow-up complete $i", control.complete(left.lease.id, 2L, all), subject.complete(left.lease.id, 2L, all + unknown))
            }
        }
        same("persisted", control.persist(), subject.persist())
    }

    /** Every unknown name the claim covers, on [t]. */
    private fun unknownNames(t: Topology): Map<String, GroupId> = mapOf(
        "an id from no topology" to GroupId("nowhere"),
        "a path under the root nobody declared" to GroupId(t.groupId().value + "/zz"),
        "an unattached child of a template group with a child attached" to t.attachedChildId(listOf("tenants"), "ghost"),
        "an unattached child of a template group with none attached" to t.attachedChildId(listOf("pool"), "ghost"),
    )

    private fun unknownNamesRow(ceilings: Boolean): () -> Unit = {
        val t = tree(ceilings)
        val ledger = bootedWithT1(t).snapshot().ledger
        val names = unknownNames(t)
        val edgeChildren = ledger.allEdges().mapNotNull { ledger.record(it)?.child }.toSet()
        val tally = Tally()
        val sets = names.map { (label, id) -> label to setOf(id) } + listOf("every one of them" to names.values.toSet())
        val leasable = Leasable.ByTopology(t)
        assertAll(
            *sets.mapIndexed { i, (label, unknown) ->
                { for (seed in 0 until SEEDS) lockstep(t, unknown, seed * 31 + i, tally, "ceilings $ceilings, $label") }
            }.toTypedArray(),
            { assertTrue(names.values.none { it in edgeChildren }, "the rig: no unknown name is a child of any edge") },
            {
                assertTrue(
                    leasable.declares(names.getValue("an unattached child of a template group with a child attached")),
                    "the rig: the topology declares an unattached child under a template, so isLeasable alone does not drop it",
                )
            },
            { assertTrue(tally.kinds.all { it > 0 }, "every call kind ran: ${tally.kinds.toList()}") },
            { assertTrue(tally.acquired > 0 && tally.overruns > 0, "acquired ${tally.acquired}, overran ${tally.overruns}") },
            { assertTrue(tally.chargesWithEmptyTrueSet > 0, "a charge ran with only the unknown names waiting: ${tally.chargesWithEmptyTrueSet}") },
            {
                if (ceilings) {
                    assertTrue(tally.cappedAcquires > 0, "the rig: a ceiling refused some acquire, ${tally.cappedAcquires}")
                }
            },
        )
    }

    @Test
    fun anUnknownNameInWaitingIsTheSameAsNoName() {
        assertAll(
            { unknownNamesRow(ceilings = false)() },
            { unknownNamesRow(ceilings = true)() },
        )
    }

    // ── Claim 2: an empty set on a charge is safe, and its cost is the charge's credit ─────────

    /** Which charge carries the empty set: an [LocalHeddle.extend], or a [LocalHeddle.complete] past its reservation. */
    private enum class Charge { EXTEND, OVERRUN }

    /** Where a persist and restore falls: none, with the lease open before the charge, or after it. */
    private enum class Restart { NONE, BEFORE_CHARGE, AFTER_CHARGE }

    /** What the charge observes: the true set `{a, b}`, `emptySet()`, or no charge at all (the lease completes at cost 1). */
    private enum class Arm { TRUE_SET, EMPTY_SET, NO_CHARGE }

    /** Where `b` stands going in: [slots] extra slots to `a` (b waiting, not eligible), none, or to `b`. */
    private enum class Standing(val slots: Int) { BEHIND(10), LEVEL(0), AHEAD(-10) }

    private class Trace(val picks: List<GroupId>, val bObservedDemanding: Boolean?, val checks: Int, val b: GroupId) {
        val bTurns: Int get() = picks.count { it == b }
        val bLeadRun: Int get() = picks.takeWhile { it == b }.size
    }

    private val policy = LocalPolicy(defaultReservation = 1L)

    private fun restored(h: LocalHeddle): LocalHeddle {
        val text = Json.encodeToString(LocalHeddleSnapshot.serializer(), h.persist())
        val snapshot = assertIs<RestoreResult.Restored>(LocalHeddle.decode(text, Json)).snapshot
        return LocalHeddle.restore(snapshot, policy, epoch = snapshot.epoch + 1)
    }

    /**
     * The invariants [LocalHeddleApplyPropertyTest] checks after every operation: the ledger
     * validates clean; every open lease charges its leaf's lineage, across no retired edge; no
     * group holds a negative amount, and a decoded copy holds the same; minted is everything held
     * plus everything spent; and everything held below the root is an open reservation.
     */
    private fun assertBooksBalance(h: LocalHeddle, root: GroupId, at: String) {
        val s = h.snapshot()
        val l = s.ledger
        val conflicts = l.validate()
        if (conflicts.isNotEmpty()) fail("conflicts at $at: $conflicts")
        for (lease in s.leases) {
            val path = h.capturedPath(lease.id)
            if (path != l.lineageOf(lease.leaf)) fail("lease ${lease.id} charges $path, not ${lease.leaf.value}'s lineage at $at")
            if (path.orEmpty().any { l.lifecycle(it) == Lifecycle.RETIRED }) fail("lease ${lease.id} crosses a retired edge at $at")
        }
        val groups = groupsOf(l)
        val decoded = Json.decodeFromString(EntitlementLedger.serializer(), Json.encodeToString(EntitlementLedger.serializer(), l))
        for (g in groups) {
            val held = l.holdings(g, LOCAL)
            if (held < 0L) fail("${g.value} holds $held at $at")
            if (held != decoded.holdings(g, LOCAL)) fail("${g.value} holds $held, but ${decoded.holdings(g, LOCAL)} decoded, at $at")
        }
        val held = groups.sumOf { l.holdings(it, LOCAL) }
        if (l.mintedTotal() != held + l.leafSpentTotal()) fail("minted ${l.mintedTotal()} != held $held + spent ${l.leafSpentTotal()} at $at")
        val belowRoot = groups.filter { it != root }.sumOf { l.holdings(it, LOCAL) }
        if (belowRoot != s.leases.sumOf { it.reserved }) fail("held below the root $belowRoot != open reservations at $at")
    }

    /**
     * `root → {a:[wa], b:[wb]}`, two slots. Ten slots with both waiting, then [standing]'s slots,
     * then `a` opens a 1-unit lease while `b` keeps work queued. The lease takes [CHARGE] more,
     * through [charge], observing what [arm] says, with a restart where [restart] puts one. Then
     * [WINDOW] one-unit picks with both waiting. The books are checked after every call.
     */
    private fun run(wa: Long, wb: Long, charge: Charge, restart: Restart, standing: Standing, arm: Arm): Trace {
        val t = Topology("root", listOf(Lane("a", Weight.of(wa)), Lane("b", Weight.of(wb))))
        val (root, a, b) = Triple(t.groupId(), t.groupId("a"), t.groupId("b"))
        val both = setOf(a, b)
        var h = LocalHeddle.bootWhole(t, policy, epoch = 0L)
        h.reconcileCapacity(totalSlots = 2L)
        var checks = 0
        fun balanced(at: String) {
            assertBooksBalance(h, root, at)
            checks++
        }
        fun lease(eligible: Set<GroupId>, at: String): Lease {
            val out = h.acquire(both, eligible, startedAt = 0L, reservation = 1L)
            balanced(at)
            return assertIs<AcquireOutcome.Acquired>(out, "acquire at $at").lease
        }
        fun slot(eligible: Set<GroupId>, at: String) {
            h.complete(lease(eligible, at).id, 1L, waiting = both)
            balanced("$at, complete")
        }
        repeat(10) { slot(both, "warm-up $it") }
        repeat(maxOf(0, standing.slots)) { slot(setOf(a), "a ahead $it") }
        repeat(maxOf(0, -standing.slots)) { slot(setOf(b), "b ahead $it") }
        val la = lease(setOf(a), "a's lease")
        if (restart == Restart.BEFORE_CHARGE) {
            h = restored(h)
            balanced("restored before the charge")
        }
        val observed = if (arm == Arm.EMPTY_SET) emptySet() else both
        when {
            arm == Arm.NO_CHARGE -> assertEquals(LeaseOutcome.Done(1L, 0L, 0L), h.complete(la.id, 1L, waiting = both))
            charge == Charge.EXTEND -> {
                assertEquals(LeaseOutcome.Done(0L, 0L, 0L), h.extend(la.id, CHARGE, waiting = observed))
                balanced("extend")
                // Within its reservation now, so this complete charges nothing new and observes nothing.
                assertEquals(LeaseOutcome.Done(1L + CHARGE, 0L, 0L), h.complete(la.id, 1L + CHARGE, waiting = both))
            }
            else -> assertEquals(LeaseOutcome.Done(1L + CHARGE, CHARGE, 0L), h.complete(la.id, 1L + CHARGE, waiting = observed))
        }
        balanced("the charge")
        val bEdge = checkNotNull(h.snapshot().ledger.lineageOf(b)).single()
        val bObserved = h.allocator.lastObservedDemanding(bEdge)
        if (restart == Restart.AFTER_CHARGE) {
            h = restored(h)
            balanced("restored after the charge")
        }
        val picks = (0 until WINDOW).map { i ->
            val next = lease(both, "window $i")
            h.complete(next.id, 1L, waiting = both)
            balanced("window $i, complete")
            next.leaf
        }
        return Trace(picks, bObserved, checks, b)
    }

    /**
     * Every row: nothing throws and the books balance after every call (inside [run]); the first
     * acquire after the charge leases (every window pick is asserted `Acquired`); only the empty
     * set sees `b` leave; the empty set's picks are exactly the picks with no charge at all, so
     * `b`'s standing going in is untouched; the true set's are not; and a restart, before or after
     * the charge, changes no pick.
     */
    private fun row(wa: Long, wb: Long, charge: Charge, standing: Standing): () -> Unit = {
        val at = "$wa:$wb via $charge, b $standing"
        val traces = Restart.entries.associateWith { r -> Arm.entries.associateWith { arm -> run(wa, wb, charge, r, standing, arm) } }
        val plain = traces.getValue(Restart.NONE)
        val (control, empty, none) = Triple(plain.getValue(Arm.TRUE_SET), plain.getValue(Arm.EMPTY_SET), plain.getValue(Arm.NO_CHARGE))
        assertAll(
            { assertEquals(true, control.bObservedDemanding, "$at: the true set sees b demanding") },
            { assertEquals(false, empty.bObservedDemanding, "$at: the rig, the empty set sees b leave") },
            { assertTrue(control.checks > WINDOW * 2, "$at: the books were checked ${control.checks} times") },
            { assertEquals(none.picks, empty.picks, "$at: the empty set leaves b where it stood, as if nothing were charged") },
            { assertTrue(control.bTurns > empty.bTurns, "$at: the true set credits b for the charge, ${control.bTurns} vs ${empty.bTurns}") },
            *Restart.entries.filter { it != Restart.NONE }.flatMap { r ->
                Arm.entries.map { arm ->
                    { assertEquals(plain.getValue(arm).picks, traces.getValue(r).getValue(arm).picks, "$at, $arm: a restart $r changes no pick") }
                }
            }.toTypedArray(),
        )
    }

    @Test
    fun anEmptySetOnAChargeIsSafeAndLeavesTheSiblingWhereItStood() {
        assertAll(
            *listOf(1L to 1L, 1L to 2L, 2L to 1L).flatMap { (wa, wb) ->
                Charge.entries.flatMap { charge -> Standing.entries.map { standing -> row(wa, wb, charge, standing) } }
            }.toTypedArray(),
        )
    }

    /**
     * The cost in turns, at 1:1 with a 100-unit charge. Under the true set `b` is owed the run of
     * picks it opens the window with: 111, 101 or 91, by its standing. Under the empty set it gets
     * 50 fewer of the next 200 at every standing, so it nets 100 fewer picks than `a`: exactly the
     * charge. Behind or level going in, that is within its lag. Ahead going in (`b` took 10 extra
     * slots, then `a` the lease's one unit), its lag is 91 and it still loses 100.
     */
    @Test
    fun theCostIsTheChargesCreditInTurns() {
        val rows = Charge.entries.flatMap { charge -> Standing.entries.map { standing -> charge to standing } }
        assertAll(
            *rows.map { (charge, standing) ->
                {
                    val control = run(1L, 1L, charge, Restart.NONE, standing, Arm.TRUE_SET)
                    val empty = run(1L, 1L, charge, Restart.NONE, standing, Arm.EMPTY_SET)
                    val at = "via $charge, b $standing"
                    // b's opening run under the true set (its lag), then its turns of the window: true set, empty set.
                    val (lag, turns) = when (standing) {
                        Standing.BEHIND -> 111 to (155 to 105)
                        Standing.LEVEL -> 101 to (150 to 100)
                        Standing.AHEAD -> 91 to (145 to 95)
                    }
                    assertAll(
                        { assertEquals(lag, control.bLeadRun, "$at: b's opening run, true set") },
                        { assertEquals(turns.first, control.bTurns, "$at: b's turns of $WINDOW, true set") },
                        { assertEquals(turns.second, empty.bTurns, "$at: b's turns of $WINDOW, empty set") },
                        { assertEquals(CHARGE.toInt(), 2 * (control.bTurns - empty.bTurns), "$at: the net picks the empty set costs b") },
                    )
                }
            }.toTypedArray(),
        )
    }

    private companion object {
        const val SEEDS = 6
        const val STEPS = 80

        /** What the charging call adds, in cost units. */
        const val CHARGE = 100L

        /** The picks measured after it. */
        const val WINDOW = 200
    }
}
