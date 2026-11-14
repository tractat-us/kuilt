package us.tractat.kuilt.heddle

import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LocalHeddle] forgets a retired edge in the swap that retires it (#3192, design §5.5), so an
 * engine whose runtime children come and go stays the same size however long it runs.
 */
class LocalHeddleForgetTest {

    private val tree = Topology(
        "root",
        listOf(
            Topology.Group("tenants", Weight.ONE, emptyList(), runtimeChildren = Topology.RuntimeChildren(Weight.ONE)),
            Topology.Lane("steady"),
        ),
    )
    private val policy = LocalPolicy(defaultReservation = 2L)
    private val tenants = listOf("tenants")

    private fun engine(): LocalHeddle = LocalHeddle.fromTopology(tree, policy, epoch = 0L).also { it.reconcileCapacity(4L) }

    private fun encodedSize(h: LocalHeddle): Int = Json.encodeToString(LocalHeddleSnapshot.serializer(), h.persist()).length

    /** Attach `u[i]`, serve one lease there, settle it, and detach it: one user arriving and leaving. */
    private fun LocalHeddle.cycle(i: Int) {
        check(attachRuntimeChild(tenants, "u$i", generation = 0L).refusals.isEmpty()) { "attach u$i" }
        val lease = lease(setOf(tree.runtimeChildId(tenants, "u$i")), reservation = 2L, startedAt = i.toLong())
        check(complete(lease.id, 1L + i % 3) is LeaseOutcome.Done) { "complete u$i" }
        check(detachRuntimeChild(tenants, "u$i").refusals.isEmpty()) { "detach u$i" }
    }

    /**
     * Two thousand users arrive, are served once and leave, and the snapshot after the last is
     * within a few digits of the snapshot after the twentieth. Before the engine forgot, every
     * departure left a retired edge behind, about 200 bytes of it.
     */
    @Test
    fun detachAndReattachKeepTheSnapshotBounded() {
        val h = engine()
        repeat(20) { h.cycle(it) }
        val warm = encodedSize(h)
        for (i in 20 until 2_000) h.cycle(i)
        val after = encodedSize(h)
        assertAll(
            { assertTrue(h.snapshot().ledger.leafSpentTotal() >= 2_000L, "the rig: every user was served") },
            { assertTrue(after <= warm + 64, "the snapshot grew from $warm to $after bytes over 1,980 departures") },
        )
    }

    /** The tree [oracle] reloads between: the marker's weight and `steady`'s, each 1 or 2. */
    private fun reloaded(marker: Long, steady: Long): Topology = Topology(
        "root",
        listOf(
            Topology.Group("tenants", Weight.ONE, emptyList(), runtimeChildren = Topology.RuntimeChildren(Weight.of(marker))),
            Topology.Lane("steady", Weight.of(steady)),
        ),
    )

    private val names = listOf("u0", "u1", "u2", "u3", "u4")

    /** One random call, made on [h]: attach, detach, a reload that reweights, acquire, extend, cancel or complete. */
    private fun call(h: LocalHeddle, attached: MutableList<String>, r: Random, at: Long): Any {
        val open = h.snapshot().leases
        return when (r.nextInt(12)) {
            0, 1 -> names[r.nextInt(names.size)].let { n ->
                if (n !in attached) attached += n
                h.attachRuntimeChild(tenants, n, generation = r.nextLong(0L, 3L))
            }
            2, 3 -> if (attached.isEmpty()) "idle" else h.detachRuntimeChild(tenants, attached.removeAt(r.nextInt(attached.size)))
            4 -> h.apply(reloaded(marker = 1L + r.nextInt(2), steady = 1L + r.nextInt(2)), generation = r.nextLong(0L, 3L))
            5, 6, 7 -> {
                val all = (attached.sorted().map { tree.runtimeChildId(tenants, it) } + tree.groupId("steady")).toSet()
                h.acquire(all, all.filterTo(LinkedHashSet()) { r.nextBoolean() }.ifEmpty { all }, at, 1L + r.nextInt(3))
            }
            8 -> if (open.isEmpty()) "idle" else h.extend(open[r.nextInt(open.size)].id, 1L + r.nextInt(2))
            9 -> if (open.isEmpty()) "idle" else h.cancel(open[r.nextInt(open.size)].id)
            else -> if (open.isEmpty()) "idle" else h.complete(open[r.nextInt(open.size)].id, r.nextLong(0L, 6L))
        }
    }

    /**
     * The oracle: the same calls on an engine that forgets and on one that keeps everything
     * ([LocalHeddle.forgetsForTest]) give the same outcomes, picks included, and agree on every
     * live group's holdings, on conservation, on the open leases, and on the allocator's memory of
     * every edge the forgetting engine still holds, which is the only memory it keeps. Every edge
     * either engine adds is numbered above every generation the forgetting engine ever held, so a
     * re-attach never reuses a forgotten id.
     *
     * The draws walk sorted lists, so the trajectory is the same on every target (#2592).
     */
    @Test
    fun anEngineThatForgetsBehavesAsOneThatKeepsEverything() {
        var forgottenAtEnd = 0
        var checked = 0
        for (seed in 0 until 8) {
            val subject = engine()
            val reference = engine().also { it.forgetsForTest = false }
            val attachedS = ArrayList<String>()
            val attachedR = ArrayList<String>()
            val rs = Random(seed)
            val rr = Random(seed)
            var top = -1L
            var held = subject.snapshot().ledger.allEdges()
            repeat(300) { i ->
                val label = "seed $seed call $i"
                assertEquals(call(reference, attachedR, rr, at = i.toLong()), call(subject, attachedS, rs, at = i.toLong()), label)
                val s = subject.snapshot()
                val ref = reference.snapshot()
                val edges = s.ledger.allEdges()
                val added = (edges - held).mapNotNull { generationOf(it) }
                val groups = groupsOf(s.ledger)
                assertAll(
                    { assertTrue(added.all { it > top }, "$label: a new edge at or below generation $top: ${edges - held}") },
                    { assertEquals(groups.map { ref.ledger.holdings(it, LOCAL) }, groups.map { s.ledger.holdings(it, LOCAL) }, "$label: holdings") },
                    { assertEquals(ref.ledger.leafSpentTotal(), s.ledger.leafSpentTotal(), "$label: leafSpentTotal") },
                    { assertEquals(ref.ledger.mintedTotal(), s.ledger.mintedTotal(), "$label: mintedTotal") },
                    { assertEquals(ref.leases, s.leases, "$label: leases") },
                    { assertTrue((s.allocatorMemory.wakeOffsets.keys + s.allocatorMemory.demandingObserved.keys).all { it in edges }, "$label: memory of a forgotten edge") },
                    { assertEquals(ref.allocatorMemory.wakeOffsets.filterKeys { it in edges }, s.allocatorMemory.wakeOffsets, "$label: wake offsets") },
                    { assertEquals(ref.allocatorMemory.demandingObserved.filterKeys { it in edges }, s.allocatorMemory.demandingObserved, "$label: demanding observed") },
                    { assertEquals(emptyList(), s.ledger.validate(), "$label: validate") },
                )
                top = maxOf(top, edges.mapNotNull { generationOf(it) }.maxOrNull() ?: -1L)
                held = edges
                checked++
            }
            forgottenAtEnd += reference.snapshot().ledger.allEdges().size - subject.snapshot().ledger.allEdges().size
        }
        assertAll(
            { assertEquals(8 * 300, checked, "the rig: every call was compared") },
            { assertTrue(forgottenAtEnd >= 100, "the rig: the subject holds $forgottenAtEnd fewer edges than the reference") },
        )
    }

    /**
     * A ledger written by an engine that kept every retired edge, as v0.7.4's did, compacts in the
     * first swap after it is restored, with its books unchanged.
     */
    @Test
    fun aRestoredLedgerThatKeptItsRetiredEdgesCompactsInItsFirstSwap() {
        val writer = engine().also { it.forgetsForTest = false }
        repeat(50) { writer.cycle(it) }
        val kept = writer.persist()
        val h = LocalHeddle.restore(kept, policy, epoch = 1L)
        val before = h.snapshot().ledger
        check(h.attachRuntimeChild(tenants, "late", generation = 0L).refusals.isEmpty())
        val after = h.snapshot()
        val retired = before.allEdges().filter { before.lifecycle(it) == Lifecycle.RETIRED }
        assertAll(
            { assertEquals(50, retired.size, "the rig: the restored ledger holds every departed user's edge") },
            { assertTrue(retired.none { it in after.ledger.allEdges() }, "the first swap forgot them all") },
            { assertEquals(before.holdings(tree.groupId(), LOCAL), after.ledger.holdings(tree.groupId(), LOCAL)) },
            { assertEquals(before.holdings(tree.groupId("tenants"), LOCAL), after.ledger.holdings(tree.groupId("tenants"), LOCAL)) },
            { assertEquals(before.leafSpentTotal(), after.ledger.leafSpentTotal()) },
            { assertTrue((after.allocatorMemory.wakeOffsets.keys + after.allocatorMemory.demandingObserved.keys).none { it in retired }, "and the allocator's memory of them") },
            { assertTrue(Json.encodeToString(LocalHeddleSnapshot.serializer(), h.persist()).length * 4 < Json.encodeToString(LocalHeddleSnapshot.serializer(), kept).length) },
        )
    }

    /**
     * A prepared edge nothing wants carries nothing, so the swap forgets it with only the floor
     * moved: here one for `steady` at weight 2, left by a reload that never activated it. The
     * next swap, an unrelated attach, forgets it; a later reweight of `steady` to 2 prepares a new
     * edge above it rather than adopting it; and the ledger refuses the old id outright.
     */
    @Test
    fun aPreparedEdgeNothingWantsIsForgottenWithOnlyTheFloorMoved() {
        val h = engine()
        val steady = tree.groupId("steady")
        val stale = AttachmentRecord(attachmentIdOf(steady, Weight.of(2), 5L), tree.groupId(), steady, Weight.of(2))
        h.reshapeForTest { l -> l.piece(checkNotNull(l.prepare(stale)).delta) }
        val before = h.snapshot().ledger
        check(h.attachRuntimeChild(tenants, "u", generation = 0L).refusals.isEmpty())
        val after = h.snapshot().ledger
        val reweighted = Topology("root", listOf(tree.children[0], Topology.Lane("steady", Weight.of(2))))
        val reload = h.apply(reweighted, generation = 0L)
        val prepared = reload.applied.filterIsInstance<TopologyStep.Prepare>().map { it.record.id }
        assertAll(
            { assertEquals(Lifecycle.PREPARED, before.lifecycle(stale.id), "the rig: the stale edge is prepared") },
            { assertNull(after.lifecycle(stale.id), "the attach's swap forgot it") },
            { assertEquals(5L, after.forgottenGenerationFloor(), "its generation is still spent") },
            { assertEquals(before.holdings(tree.groupId(), LOCAL), after.holdings(tree.groupId(), LOCAL), "and nothing else moved") },
            { assertNull(after.prepare(stale), "the old id is refused") },
            {
                val fresh = prepared.singleOrNull()
                assertTrue(fresh != null && fresh != stale.id && checkNotNull(generationOf(fresh)) > 5L, "the reweight prepares above it: $reload")
            },
            { assertTrue(reload.refusals.isEmpty(), "and lands: $reload") },
        )
    }

    /** Put a prepared edge into the runtime child [name] at [weight] and [generation], as a snapshot could carry one. */
    private fun LocalHeddle.prepareRuntime(name: String, weight: Long, generation: Long): AttachmentId {
        val child = tree.runtimeChildId(tenants, name)
        val record = AttachmentRecord(attachmentIdOf(child, Weight.of(weight), generation), tree.groupId("tenants"), child, Weight.of(weight))
        reshapeForTest { l -> l.piece(checkNotNull(l.prepare(record)).delta) }
        return record.id
    }

    /**
     * A runtime child's prepared edge is the record that the child is attached, so a swap keeps one
     * that says so: `u`'s, at the marker's weight, which a reload adopts, and `w`'s, at a weight the
     * marker no longer gives, which is still the only edge `w` has. The reload then activates `u`'s,
     * gives `w` an edge at the marker's weight, and only then forgets `w`'s old one, which nothing
     * needs any more.
     */
    @Test
    fun aPreparedEdgeThatStillSaysARuntimeChildIsAttachedIsKept() {
        val h = engine()
        val u = h.prepareRuntime("u", weight = 1L, generation = 5L)
        val w = h.prepareRuntime("w", weight = 2L, generation = 6L)
        check(h.attachRuntimeChild(tenants, "v", generation = 0L).refusals.isEmpty())
        val kept = h.snapshot().ledger
        val reload = h.apply(tree, generation = 0L)
        val after = h.snapshot().ledger
        val wChild = tree.runtimeChildId(tenants, "w")
        assertAll(
            { assertEquals(Lifecycle.PREPARED, kept.lifecycle(u), "the attach of v kept u's adoptable edge") },
            { assertEquals(Lifecycle.PREPARED, kept.lifecycle(w), "and w's, its only one") },
            { assertTrue(reload.refusals.isEmpty(), "the reload lands: $reload") },
            { assertEquals(u, after.lineageOf(tree.runtimeChildId(tenants, "u"))?.last(), "u is attached on the edge it had") },
            {
                val lineage = after.lineageOf(wChild).orEmpty()
                assertTrue(lineage.isNotEmpty() && after.record(lineage.last())?.weight == Weight.ONE, "w is attached at the marker's weight: $lineage")
            },
            { assertNull(after.lifecycle(w), "and w's old prepared edge is forgotten") },
        )
    }

    /**
     * A reload forgets what the topology it installs does not want, not what the one it replaces
     * did not: `u`, attached by a prepared edge at weight 1, is moved to a marker of weight 2, and
     * the weight-1 edge, which the old marker would have adopted, goes in the reload's own swap.
     */
    @Test
    fun aReloadForgetsWhatTheTopologyItInstallsDoesNotWant() {
        val h = engine()
        val old = h.prepareRuntime("u", weight = 1L, generation = 5L)
        val reload = h.apply(reloaded(marker = 2L, steady = 1L), generation = 0L)
        val after = h.snapshot().ledger
        val lineage = after.lineageOf(tree.runtimeChildId(tenants, "u")).orEmpty()
        assertAll(
            { assertTrue(reload.refusals.isEmpty(), "the reload lands: $reload") },
            { assertTrue(lineage.isNotEmpty() && after.record(lineage.last())?.weight == Weight.of(2), "the rig: u is attached at weight 2: $lineage") },
            { assertNull(after.lifecycle(old), "and its weight-1 edge is forgotten") },
        )
    }

    /**
     * Forgetting composes with a rebase (#3329). Three users are served, `u0` is pushed past the
     * rebase threshold, and a pick rebases `tenants`, moving every child's position, `u2`'s
     * included. Then `u2` leaves: its swap forgets its edge and what the allocator held for it,
     * rebase and all. From there an engine that forgets picks exactly as one that keeps
     * everything.
     */
    @Test
    fun forgettingComposesWithARebase() {
        val users = listOf("u0", "u1", "u2")
        val ids = users.map { tree.runtimeChildId(tenants, it) }
        fun twin(forgets: Boolean) = engine().also { h ->
            h.forgetsForTest = forgets
            users.forEach { check(h.attachRuntimeChild(tenants, it, generation = 0L).refusals.isEmpty()) }
            ids.forEach { check(h.complete(h.lease(setOf(it), reservation = 1L).id, 1L) is LeaseOutcome.Done) }
            val edge = checkNotNull(h.snapshot().ledger.lineageOf(ids[0])).last()
            val target = 2 * (positionCeiling(Weight.ONE) / 16)
            h.reshapeForTest { l ->
                val gauge = checkNotNull(l.gauge(edge))
                val floorTicks = target - (l.baseIssuance(edge) - gauge.folded) * TICKS_PER_UNIT
                l.piece(EntitlementLedger.of(gauges = mapOf(edge to Gauge(ticksToVirtual(floorTicks, Weight.ONE), gauge.folded))))
            }
            val rebasesBefore = h.rebasesForTest
            check(h.complete(h.lease(setOf(ids[1]), reservation = 1L).id, 1L) is LeaseOutcome.Done)
            check(h.acquire(ids.toSet(), setOf(ids[1]), startedAt = 1L).let { it is AcquireOutcome.Acquired && h.complete(it.lease.id, 1L) is LeaseOutcome.Done })
            check(h.rebasesForTest > rebasesBefore) { "the rig: the pick rebased" }
        }
        val subject = twin(forgets = true)
        val reference = twin(forgets = false)
        val departed = checkNotNull(subject.snapshot().ledger.lineageOf(ids[2])).last()
        val rememberedBefore = departed in subject.snapshot().allocatorMemory.demandingObserved
        check(subject.detachRuntimeChild(tenants, "u2").refusals.isEmpty())
        check(reference.detachRuntimeChild(tenants, "u2").refusals.isEmpty())
        val after = subject.snapshot()
        val picks = (0 until 40).map { i ->
            listOf(subject, reference).map { h ->
                val out = h.acquire(ids.take(2).toSet(), ids.take(2).toSet(), startedAt = 10L + i)
                check(out is AcquireOutcome.Acquired) { "acquire $i: $out" }
                check(h.complete(out.lease.id, 1L + i % 2) is LeaseOutcome.Done)
                out.lease.leaf
            }
        }
        assertAll(
            { assertTrue(rememberedBefore, "the rig: the allocator remembered u2's edge") },
            { assertNull(after.ledger.lifecycle(departed), "u2's edge is forgotten") },
            { assertTrue(departed !in after.allocatorMemory.wakeOffsets && departed !in after.allocatorMemory.demandingObserved, "and so is its memory") },
            { assertEquals(Lifecycle.RETIRED, reference.snapshot().ledger.lifecycle(departed), "the rig: the reference kept it") },
            { assertEquals(picks.map { it[1] }, picks.map { it[0] }, "the same picks") },
            { assertEquals(reference.snapshot().ledger.holdings(tree.groupId("tenants"), LOCAL), subject.snapshot().ledger.holdings(tree.groupId("tenants"), LOCAL)) },
            { assertEquals(emptyList(), subject.snapshot().ledger.validate()) },
        )
    }
}
