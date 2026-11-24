package us.tractat.kuilt.heddle

import kotlin.random.Random

/**
 * Random histories on a live engine, and random read-only questions to ask of what they leave: the
 * fixture [LocalHeddleViewTest] and [LocalHeddlePreviewApplyTest] share, so a view and a preview
 * are held to the engine over the same states.
 */
internal object ViewHistories {

    /**
     * `root → {acme (3/2), solo, pool, tenants}`. `acme` holds a group `batch → {x, y (2)}` and a
     * lane `interactive`; `pool` and `tenants` are given a template, and only `tenants`
     * ever gets one, so `pool` stays a leaf the topology does not declare: a name that only the
     * topology the view keeps can turn away.
     */
    fun tree(acmeWeight: Weight): Topology = Topology(
        "root",
        listOf(
            Topology.Group(
                "acme",
                acmeWeight,
                listOf(Topology.Group("batch", Weight.ONE, listOf(Topology.Lane("x"), Topology.Lane("y", Weight.of(2)))), Topology.Lane("interactive")),
            ),
            Topology.Lane("solo"),
            Topology.Group("pool", Weight.ONE, emptyList(), template = Topology.ChildTemplate(Weight.ONE)),
            Topology.Group("tenants", Weight.of(1, 2), emptyList(), template = Topology.ChildTemplate(Weight.ONE)),
        ),
    )

    val before: Topology = tree(Weight.of(3, 2))
    val after: Topology = tree(Weight.of(5, 2))
    val tenant: GroupId = before.attachedChildId(listOf("tenants"), "t1")
    val leaves: List<GroupId> = listOf(
        before.groupId("acme", "batch", "x"), before.groupId("acme", "batch", "y"), before.groupId("acme", "interactive"), before.groupId("solo"), tenant,
    )

    /** What a caller may name as waiting: every leaf, plus `pool`, a leaf that is no lane, and `acme`, a group with active children. */
    val names: List<GroupId> = (leaves + before.groupId("pool") + before.groupId("acme")).sorted()

    /** One question, asked of an engine, a view and a restored engine alike. */
    class Question(
        val waiting: Set<GroupId>,
        val eligible: Set<GroupId>,
        val reservation: Long,
        val counts: Map<GroupId, Int>,
        val reservations: List<Long>,
        val queues: Map<GroupId, List<Long>>,
        val totalSlots: Long,
        /** The names [answers] asks [LocalHeddle.explain] and [LocalHeddle.entitlement] about, in order. */
        val names: List<GroupId>,
    )

    /** A random question over [names]. */
    fun question(rng: Random, names: List<GroupId> = ViewHistories.names): Question {
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
            names = names,
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
        for (name in q.names) {
            add(explain(name, q.waiting, q.eligible, q.reservation))
            add(entitlement(name, q.waiting, q.totalSlots))
        }
    }

    fun LocalHeddle.answers(q: Question): List<Any> = answers(
        q,
        { w, e, r -> previewPicks(w, e, r) },
        { w, e, r -> previewPicks(w, e, r) },
        { l, w, e, r -> explain(l, w, e, r) },
        { l, w, n -> entitlement(l, w, n) },
    )

    fun LocalHeddleView.answers(q: Question): List<Any> = answers(
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
     * The same [seed] and [policy] give the same engine every time, so two calls build twins.
     */
    fun history(seed: Int, policy: LocalPolicy): LocalHeddle {
        val rng = Random(seed)
        val h = LocalHeddle.bootWhole(before, policy, epoch = rng.nextLong(0L, 5L))
        check(h.attach(listOf("tenants"), "t1", generation = 1L).refusals.isEmpty())
        if (seed % 2 == 0) h.reconcileCapacity(1L + rng.nextInt(4)) else h.mintSupply(30L + rng.nextInt(60))
        repeat(STEPS) { step ->
            if (seed % 3 == 0 && step == STEPS / 2) h.apply(after, generation = 2L)
            val open = h.snapshot().leases
            when {
                open.size < 3 || rng.nextInt(4) == 0 -> {
                    val waiting = names.filter { rng.nextBoolean() }.toSet()
                    h.acquire(waiting, waiting.filter { rng.nextInt(3) != 0 }.toSet(), step.toLong(), 1L + rng.nextInt(4))
                }
                rng.nextInt(5) == 0 -> h.extend(open[rng.nextInt(open.size)].id, 1L + rng.nextInt(3), waiting = emptySet())
                rng.nextInt(6) == 0 -> h.cancel(open[rng.nextInt(open.size)].id)
                else -> h.complete(open[rng.nextInt(open.size)].id, rng.nextInt(6).toLong(), waiting = emptySet())
            }
        }
        return h
    }

    const val STEPS = 40
}
