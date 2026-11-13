package us.tractat.kuilt.heddle

/**
 * One free slot, one lease: ask who goes next, run their work, and charge what it really cost.
 * The heavy group goes first twice as often as the light one. A job that runs long is charged its
 * overrun; a short one hands the rest back.
 */
@Suppress("unused")
internal fun sampleLocalLease() {
    val tree = twoLanes()
    val heavy = tree.groupId("heavy")
    val light = tree.groupId("light")
    val heddle = LocalHeddle.fromTopology(tree, LocalPolicy(defaultReservation = 3L), epoch = 0L)
    // Four slots: the engine mints its own supply ahead of them.
    heddle.reconcileCapacity(totalSlots = 4L)

    // Both groups have work queued, and this slot can run either.
    val queued = setOf(heavy, light)
    val first = heddle.acquire(waiting = queued, eligible = queued, startedAt = 100L)
    check(first is AcquireOutcome.Acquired)

    // The job overran its 3-unit estimate: all 5 units are charged, 2 of them as overrun.
    check(heddle.complete(first.lease.id, actualCost = 5L) == LeaseOutcome.Done(charged = 5L, overrun = 2L))

    // Over many slots the shares follow the weights, 2 : 1.
    val picks = (0 until 30).map {
        val lease = (heddle.acquire(queued, queued, startedAt = it.toLong()) as AcquireOutcome.Acquired).lease
        heddle.complete(lease.id, actualCost = 3L)
        lease.leaf
    }
    check(picks.count { it == heavy } in 19..21)
}

/**
 * Ask why a group is waiting, and what share it is owed. Explaining moves nothing: the next
 * acquire picks exactly the group explain said it would.
 */
@Suppress("unused")
internal fun sampleExplainWait() {
    val tree = twoLanes()
    val root = tree.groupId()
    val heavy = tree.groupId("heavy")
    val light = tree.groupId("light")
    val heddle = LocalHeddle.fromTopology(tree, LocalPolicy(defaultReservation = 3L), epoch = 0L)
    heddle.reconcileCapacity(totalSlots = 2L)

    val queued = setOf(heavy, light)
    // Light is behind at the pool: at twice the weight, heavy's turn comes due sooner.
    val why = heddle.explain(light, waiting = queued, eligible = queued)
    check(why is WaitExplanation.BehindAtLevel && why.parent == root && why.nextChild == heavy)
    check(heddle.explain(heavy, waiting = queued, eligible = queued) == WaitExplanation.WouldBePickedNext)
    check((heddle.acquire(queued, queued, startedAt = 0L) as AcquireOutcome.Acquired).lease.leaf == heavy)

    // While both stay queued, heavy is owed 2 slots of every 3 and light 1.
    check(heddle.entitlement(heavy, waiting = queued, totalSlots = 3L) == Rational.of(2L))
    check(heddle.entitlement(light, waiting = queued, totalSlots = 3L) == Rational.ONE)
    // A group with nothing queued is simply not waiting.
    check(heddle.explain(light, waiting = setOf(heavy), eligible = setOf(heavy)) == WaitExplanation.NotWaiting)
}

/**
 * After a crash, some work the engine still holds a lease for is gone. Ask which, settle each
 * orphan at the time it ran, capped at what it was granted, and the books balance again.
 */
@Suppress("unused")
internal fun sampleReconcileLeases() {
    val tree = twoLanes()
    val heavy = tree.groupId("heavy")
    val light = tree.groupId("light")
    val heddle = LocalHeddle.fromTopology(tree, LocalPolicy(defaultReservation = 3L), epoch = 0L)
    heddle.reconcileCapacity(totalSlots = 2L)
    val queued = setOf(heavy, light)
    val kept = (heddle.acquire(queued, queued, startedAt = 100L) as AcquireOutcome.Acquired).lease
    val lost = (heddle.acquire(queued, queued, startedAt = 101L) as AcquireOutcome.Acquired).lease

    // The executor came back running only `kept`, and one job the engine never leased.
    val stranger = LeaseId(epoch = 7L, seq = 0L)
    val found = heddle.reconcileLeases(live = setOf(kept.id, stranger), now = 110L)
    check(found.unknown == listOf(stranger))
    val orphan = found.orphaned.single()
    check(orphan.lease == lost && orphan.elapsed == 9L && orphan.granted == 3L)

    // It ran 9 units against the 3 it was granted, which a dead job cannot prove: charge 3.
    check(heddle.complete(orphan.lease.id, orphan.defaultSettlement) == LeaseOutcome.Done(charged = 3L, overrun = 0L))
    check(heddle.reconcileLeases(live = setOf(kept.id), now = 110L).orphaned.isEmpty())
}

/**
 * Change the tree while work is running. A reweight moves the leases open under it onto the new
 * edge, so they complete as if nothing happened; a removal waits for its own leases to finish.
 */
@Suppress("unused")
internal fun sampleLocalReload() {
    val before = Topology("pool", listOf(Topology.Lane("heavy", Weight.of(2)), Topology.Lane("light"), Topology.Lane("spare")))
    val heddle = LocalHeddle.fromTopology(before, LocalPolicy(defaultReservation = 3L), epoch = 0L)
    heddle.reconcileCapacity(totalSlots = 4L)
    val heavy = before.groupId("heavy")
    val spare = before.groupId("spare")
    val running = (heddle.acquire(setOf(heavy), setOf(heavy), startedAt = 0L) as AcquireOutcome.Acquired).lease
    val leftover = (heddle.acquire(setOf(spare), setOf(spare), startedAt = 0L) as AcquireOutcome.Acquired).lease

    // While both leases are open, heavy goes to weight 3 and spare is removed.
    val after = Topology("pool", listOf(Topology.Lane("heavy", Weight.of(3)), Topology.Lane("light")))
    val reload = heddle.apply(after, generation = 1L)

    // Spare is closed to new work and waits for its lease; heavy's lease moved onto its new edge.
    val waiting = reload.refusals.single()
    check(waiting is StepRefusal.Draining && waiting.child == spare && waiting.blockingLeases == listOf(leftover))
    check(heddle.complete(running.id, actualCost = 3L) == LeaseOutcome.Done(charged = 3L, overrun = 0L))
    check(heddle.complete(leftover.id, actualCost = 2L) == LeaseOutcome.Done(charged = 2L, overrun = 0L))

    // Once the drain is over, the same reload finishes the job.
    check(heddle.apply(after, generation = 1L).refusals.isEmpty())
}

/** Two lanes under `pool`, `heavy` at weight 2 beside `light`. */
private fun twoLanes(): Topology = Topology("pool", listOf(Topology.Lane("heavy", Weight.of(2)), Topology.Lane("light")))
