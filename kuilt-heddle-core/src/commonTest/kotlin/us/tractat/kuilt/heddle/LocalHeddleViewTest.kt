package us.tractat.kuilt.heddle

import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [LocalHeddleSnapshot.view] (#3321): a reader holding only a snapshot asks `previewPicks`, `explain`
 * and `entitlement` and gets the engine's own answers, with no engine and nothing moved.
 */
class LocalHeddleViewTest {

    /**
     * `root → {acme (3/2), solo, pool, tenants}`. `acme` holds a group `batch → {x, y (2)}` and a
     * lane `interactive`; `pool` and `tenants` are marked for runtime children, and only `tenants`
     * ever gets one, so `pool` stays a leaf the topology does not declare: a name that only the
     * topology the view keeps can turn away.
     */
    private fun tree(acmeWeight: Weight): Topology = Topology(
        "root",
        listOf(
            Topology.Group(
                "acme",
                acmeWeight,
                listOf(Topology.Group("batch", Weight.ONE, listOf(Topology.Lane("x"), Topology.Lane("y", Weight.of(2)))), Topology.Lane("interactive")),
            ),
            Topology.Lane("solo"),
            Topology.Group("pool", Weight.ONE, emptyList(), runtimeChildren = Topology.RuntimeChildren(Weight.ONE)),
            Topology.Group("tenants", Weight.of(1, 2), emptyList(), runtimeChildren = Topology.RuntimeChildren(Weight.ONE)),
        ),
    )

    private val before = tree(Weight.of(3, 2))
    private val after = tree(Weight.of(5, 2))
    private val tenant = before.runtimeChildId(listOf("tenants"), "t1")
    private val leaves = listOf(before.groupId("acme", "batch", "x"), before.groupId("acme", "batch", "y"), before.groupId("acme", "interactive"), before.groupId("solo"), tenant)

    /** What a caller may name as waiting: every leaf, plus `pool`, a leaf that is no lane, and `acme`, a group with active children. */
    private val names = (leaves + before.groupId("pool") + before.groupId("acme")).sorted()

    private fun encode(snapshot: LocalHeddleSnapshot): String = Json.encodeToString(LocalHeddleSnapshot.serializer(), snapshot)

    /** One question, asked of an engine, a view and a restored engine alike. */
    private class Question(
        val waiting: Set<GroupId>,
        val eligible: Set<GroupId>,
        val reservation: Long,
        val counts: Map<GroupId, Int>,
        val reservations: List<Long>,
        val queues: Map<GroupId, List<Long>>,
        val totalSlots: Long,
    )

    private fun question(rng: Random): Question {
        val waiting = names.filter { rng.nextInt(3) != 0 }.toSet()
        val eligible = waiting.filter { rng.nextInt(4) != 0 }.toSet()
        val counts = names.associateWith { rng.nextInt(4) }
        return Question(
            waiting = waiting,
            eligible = eligible,
            reservation = 1L + rng.nextInt(4),
            counts = counts,
            reservations = List(counts.values.sum()) { 1L + rng.nextInt(5) },
            queues = names.associateWith { List(rng.nextInt(4)) { 1L + rng.nextInt(7) } },
            totalSlots = 1L + rng.nextInt(12),
        )
    }

    /**
     * Every answer the three read-only calls give to [q], in order: both previews, then for each name
     * its explanation and its entitlement. Each arm is one call on whichever surface is asked.
     */
    private fun answers(
        q: Question,
        byCount: (Map<GroupId, Int>, Set<GroupId>, List<Long>) -> List<GroupId>,
        byQueue: (Map<GroupId, List<Long>>, Set<GroupId>, Long) -> List<GroupId>,
        explain: (GroupId, Set<GroupId>, Set<GroupId>, Long) -> WaitExplanation,
        entitlement: (GroupId, Set<GroupId>, Long) -> Rational,
    ): List<Any> = buildList {
        add(byCount(q.counts, q.eligible, q.reservations))
        add(byQueue(q.queues, q.eligible, q.reservation))
        for (name in names) {
            add(explain(name, q.waiting, q.eligible, q.reservation))
            add(entitlement(name, q.waiting, q.totalSlots))
        }
    }

    private fun LocalHeddle.answers(q: Question): List<Any> = answers(
        q,
        { w, e, r -> previewPicks(w, e, r) },
        { w, e, r -> previewPicks(w, e, r) },
        { l, w, e, r -> explain(l, w, e, r) },
        { l, w, n -> entitlement(l, w, n) },
    )

    private fun LocalHeddleView.answers(q: Question): List<Any> = answers(
        q,
        { w, e, r -> previewPicks(w, e, r) },
        { w, e, r -> previewPicks(w, e, r) },
        { l, w, e, r -> explain(l, w, e, r) },
        { l, w, n -> entitlement(l, w, n) },
    )

    /**
     * A random history on a live engine: leases opened, extended, completed and cancelled with the
     * waiting set moving, `t1` attached, and on some seeds a reload that reweights `acme`. Even seeds
     * reconcile a capacity; odd ones hold a fixed supply laid down ahead, so some answers run out.
     */
    private fun history(seed: Int, policy: LocalPolicy): LocalHeddle {
        val rng = Random(seed)
        val h = LocalHeddle.fromTopology(before, policy, epoch = rng.nextLong(0L, 5L))
        check(h.attachRuntimeChild(listOf("tenants"), "t1", generation = 1L).refusals.isEmpty())
        if (seed % 2 == 0) h.reconcileCapacity(1L + rng.nextInt(4)) else h.mintSupply(30L + rng.nextInt(60))
        repeat(STEPS) { step ->
            if (seed % 3 == 0 && step == STEPS / 2) h.apply(after, generation = 2L)
            val open = h.snapshot().leases
            when {
                open.size < 3 || rng.nextInt(4) == 0 -> {
                    val waiting = names.filter { rng.nextBoolean() }.toSet()
                    h.acquire(waiting, waiting.filter { rng.nextInt(3) != 0 }.toSet(), step.toLong(), 1L + rng.nextInt(4))
                }
                rng.nextInt(5) == 0 -> h.extend(open[rng.nextInt(open.size)].id, 1L + rng.nextInt(3))
                rng.nextInt(6) == 0 -> h.cancel(open[rng.nextInt(open.size)].id)
                else -> h.complete(open[rng.nextInt(open.size)].id, rng.nextInt(6).toLong())
            }
        }
        return h
    }

    /**
     * Across random histories and questions, a snapshot's view answers exactly as the engine that
     * took it, and as an engine restored from it under `epoch + 1`, the workaround the view replaces.
     * Neither the engine nor the snapshot changes. Two control arms keep it honest: a view over the
     * same snapshot with its wake memory forgotten, and one with its capacity forgotten, each answer
     * differently on some seed, so the match rests on the view reading both.
     */
    @Test
    fun aViewAnswersAsTheEngineAndAsARestore() {
        val kinds = HashSet<WaitExplanation.Kind>()
        var picked = 0
        var memoryMattered = 0
        var capacityMattered = 0
        for (seed in 0 until SEEDS) {
            val policy = LocalPolicy(defaultReservation = 2L + seed % 3)
            val h = history(seed, policy)
            val rng = Random(seed * 7919 + 1)
            repeat(QUESTIONS) { round ->
                val q = question(rng)
                val persisted = h.persist()
                val json = encode(persisted)
                val engineState = h.snapshot()

                val fromEngine = h.answers(q)
                val fromView = persisted.view(policy).answers(q)
                val fromRestore = LocalHeddle.restore(persisted, policy, epoch = persisted.epoch + 1).answers(q)

                val forgetful = LocalHeddleSnapshot(
                    persisted.epoch, persisted.ledger, AllocatorMemory.EMPTY, persisted.totalSlots, persisted.open, persisted.lastApplied,
                )
                if (forgetful.view(policy).answers(q) != fromEngine) memoryMattered++
                val unsized = LocalHeddleSnapshot(
                    persisted.epoch, persisted.ledger, persisted.allocatorMemory, 0L, persisted.open, persisted.lastApplied,
                )
                if (unsized.view(policy).answers(q) != fromEngine) capacityMattered++

                assertAll(
                    { assertEquals(fromEngine, fromView, "seed $seed, question $round: the view answers as the engine") },
                    { assertEquals(fromEngine, fromRestore, "seed $seed, question $round: and as a restore under epoch + 1") },
                    { assertEquals(persisted, h.persist(), "seed $seed, question $round: the engine is as it was") },
                    { assertEquals(engineState, h.snapshot(), "seed $seed, question $round: its state too") },
                    { assertEquals(json, encode(persisted), "seed $seed, question $round: and so is the snapshot") },
                )
                fromEngine.filterIsInstance<WaitExplanation>().mapTo(kinds) { it.kind }
                picked += fromEngine.take(2).sumOf { (it as List<*>).size }
            }
        }
        assertAll(
            { assertTrue(picked > SEEDS, "the previews picked something: $picked") },
            {
                assertTrue(
                    kinds.containsAll(
                        listOf(
                            WaitExplanation.Kind.NOT_WAITING, WaitExplanation.Kind.NOT_ELIGIBLE, WaitExplanation.Kind.NO_LIVE_LINEAGE,
                            WaitExplanation.Kind.SUPPLY_EXHAUSTED, WaitExplanation.Kind.BEHIND_AT_LEVEL, WaitExplanation.Kind.WOULD_BE_PICKED_NEXT,
                        ),
                    ),
                    "every explanation but a ceiling came up: $kinds",
                )
            },
            { assertTrue(memoryMattered > 0, "the control: forgetting the wake memory changes some answer: $memoryMattered") },
            { assertTrue(capacityMattered > 0, "the control: forgetting the capacity changes some answer: $capacityMattered") },
        )
    }

    /**
     * `pool` is a leaf the topology marks for runtime children and declares no lane: a view turns
     * it away as the engine does, because it leases by the topology the snapshot kept. The rig: the
     * ledger alone reads `pool` as a leaf with a live lineage.
     */
    @Test
    fun aViewLeasesByTheTopologyTheSnapshotKept() {
        val policy = LocalPolicy(defaultReservation = 1L)
        val h = LocalHeddle.fromTopology(before, policy, epoch = 0L).also { it.reconcileCapacity(2L) }
        val pool = before.groupId("pool")
        val view = h.persist().view(policy)
        val ledger = h.snapshot().ledger
        assertAll(
            { assertTrue(ledger.isLeaf(pool) && ledger.lineageOf(pool) != null, "the rig: the ledger reads pool as a reachable leaf") },
            { assertEquals(WaitExplanation.NoLiveLineage(pool), view.explain(pool, setOf(pool), setOf(pool))) },
            { assertEquals(Rational.ZERO, view.entitlement(pool, setOf(pool), totalSlots = 4L)) },
            { assertEquals(emptyList(), view.previewPicks(mapOf(pool to 3), setOf(pool), listOf(1L, 1L, 1L))) },
            { assertEquals(h.explain(pool, setOf(pool), setOf(pool)), view.explain(pool, setOf(pool), setOf(pool))) },
        )
    }

    /** A view sizes the snapshot's capacity with the policy it is given, as a restore does, and refuses one that cannot. */
    @Test
    fun aViewRefusesAPolicyThatCannotSizeTheCapacity() {
        val h = LocalHeddle.fromTopology(before, LocalPolicy(defaultReservation = 1L), epoch = 0L).also { it.reconcileCapacity(1_000L) }
        val greedy = LocalPolicy(defaultReservation = LocalHeddle.MAX_RESERVATION, supply = SupplyPolicy(lowWatermarkSlots = 1L, targetSlots = 1L shl 20))
        val persisted = h.persist()
        assertAll(
            { assertFailsWith<IllegalArgumentException> { persisted.view(greedy) } },
            { assertFailsWith<IllegalArgumentException> { LocalHeddle.restore(persisted, greedy, epoch = 1L) } },
        )
    }

    private companion object {
        const val SEEDS = 60
        const val STEPS = 40
        const val QUESTIONS = 4
    }
}
