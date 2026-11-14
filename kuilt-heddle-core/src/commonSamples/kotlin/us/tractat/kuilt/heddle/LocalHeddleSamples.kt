package us.tractat.kuilt.heddle

import kotlinx.serialization.json.Json

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
 * Run no more than one deploy at once, however idle the rest of the pool is: a ceiling. While a
 * deploy runs, the next slot goes to builds, `explain` names the ceiling, and builds are owed every
 * slot. Once the deploy finishes, deploys compete again.
 */
@Suppress("unused")
internal fun sampleConcurrencyCeiling() {
    val tree = draft("pool") {
        lane("deploys", Weight.of(3), maxConcurrent = 1)
        lane("builds")
    }
    val deploys = tree.groupId("deploys")
    val builds = tree.groupId("builds")
    val heddle = LocalHeddle.fromTopology(tree, LocalPolicy(defaultReservation = 1L), epoch = 0L)
    heddle.reconcileCapacity(totalSlots = 4L)
    val queued = setOf(deploys, builds)

    val deploy = (heddle.acquire(queued, queued, startedAt = 0L) as AcquireOutcome.Acquired).lease
    check(deploy.leaf == deploys)
    // Deploys weigh three times as much, but one is already running, its ceiling.
    check(heddle.explain(deploys, waiting = queued, eligible = queued) == WaitExplanation.AtCeiling(deploys, limit = 1L))
    check(heddle.entitlement(builds, waiting = queued, totalSlots = 4L) == Rational.of(4L))
    check((heddle.acquire(queued, queued, startedAt = 1L) as AcquireOutcome.Acquired).lease.leaf == builds)

    heddle.complete(deploy.id, actualCost = 1L)
    check(heddle.explain(deploys, waiting = queued, eligible = queued) == WaitExplanation.WouldBePickedNext)
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
 * Save the engine before a restart and carry on after it: the open lease is still valid under its
 * old id, and the next pick is the one the old engine would have made.
 */
@Suppress("unused")
internal fun sampleLocalPersistence() {
    val tree = twoLanes()
    val heavy = tree.groupId("heavy")
    val light = tree.groupId("light")
    val policy = LocalPolicy(defaultReservation = 3L)
    val heddle = LocalHeddle.fromTopology(tree, policy, epoch = 0L)
    heddle.reconcileCapacity(totalSlots = 2L)
    val queued = setOf(heavy, light)
    val running = (heddle.acquire(queued, queued, startedAt = 100L) as AcquireOutcome.Acquired).lease

    // Under your scheduling lock: take the snapshot. Encoding and storing it can happen off the lock.
    val stored = Json.encodeToString(LocalHeddleSnapshot.serializer(), heddle.persist())

    // After the restart: decode, then restore under a later epoch, your persisted boot counter.
    val decoded = LocalHeddle.decode(stored, Json)
    check(decoded is RestoreResult.Restored)
    val restarted = LocalHeddle.restore(decoded.snapshot, policy, epoch = decoded.snapshot.epoch + 1)

    // The lease that was running is still open, and settles as it would have.
    check(restarted.complete(running.id, actualCost = 3L) == LeaseOutcome.Done(charged = 3L, overrun = 0L))
    // New leases are named under the new epoch, so none can repeat an old id.
    check((restarted.acquire(queued, queued, startedAt = 120L) as AcquireOutcome.Acquired).lease.id.epoch == 1L)
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

/**
 * One build farm, start to finish: the worked example the guide's fair-share pages quote in order.
 * Two teams share four build agents three to one, and every claim those pages make about them is a
 * `check` here, so the pages cannot drift from what the engine does.
 */
@Suppress("unused")
internal fun sampleBuildFarm() {
    // Two teams share the farm's build agents. Web gets three parts to mobile's one.
    val farm = draft("farm") {
        group("web", Weight.of(3)) { lane("builds") }
        group("mobile") { lane("builds") }
    }
    val web = farm.groupId("web", "builds")
    val mobile = farm.groupId("mobile", "builds")
    // A build takes about 3 minutes, so a lease reserves 3 when the caller knows no better.
    val policy = LocalPolicy(defaultReservation = 3L)
    // Load the tree into an engine. Lease ids it hands out carry the epoch, 0 here.
    val heddle = LocalHeddle.fromTopology(farm, policy, epoch = 0L)

    // Nothing to hand out until the engine knows how many agents there are.
    val both = setOf(web, mobile)
    check(heddle.acquire(waiting = both, eligible = both, startedAt = 0L) == AcquireOutcome.SupplyExhausted)
    heddle.reconcileCapacity(totalSlots = 4L)

    // One build: lease a slot, run it, and charge the minutes it took.
    fun build(waiting: Set<GroupId>, startedAt: Long, minutes: Long = 3L): GroupId {
        val out = heddle.acquire(waiting, eligible = waiting, startedAt = startedAt)
        check(out is AcquireOutcome.Acquired)
        check(heddle.complete(out.lease.id, actualCost = minutes) is LeaseOutcome.Done)
        return out.lease.leaf
    }

    // Ask who goes next without changing anything, then run the first eight builds.
    val firstEight = listOf(web, mobile, web, web, web, mobile, web, web)
    check(heddle.previewPicks(mapOf(web to 8, mobile to 8), both, List(8) { 3L }) == firstEight)
    check((0 until 8).map { build(both, startedAt = it * 3L) } == firstEight)

    // Mobile is waiting and level with web, so it loses the ninth pick on its deadline.
    check(
        heddle.explain(mobile, waiting = both, eligible = both) ==
            WaitExplanation.BehindAtLevel(
                parent = farm.groupId(),
                nextChild = farm.groupId("web"),
                leafSideVirtualService = Rational.of(6L),
                front = Rational.of(6L),
                eligibleFront = Rational.of(6L),
            ),
    )
    check(heddle.explain(web, waiting = both, eligible = both) == WaitExplanation.WouldBePickedNext)
    // Of four agents, web is owed three and mobile one.
    check(heddle.entitlement(web, waiting = both, totalSlots = 4L) == Rational.of(3L))
    check(heddle.entitlement(mobile, waiting = both, totalSlots = 4L) == Rational.ONE)

    // The ninth build is web's, and it runs 9 minutes against its 3-minute estimate.
    val ninth = heddle.acquire(both, both, startedAt = 24L)
    check(ninth is AcquireOutcome.Acquired && ninth.lease.leaf == web)
    check(heddle.complete(ninth.lease.id, actualCost = 9L) == LeaseOutcome.Done(charged = 9L, overrun = 6L))
    // All nine minutes count: web is now past the line, so mobile goes next.
    check(
        heddle.explain(web, waiting = both, eligible = both) ==
            WaitExplanation.BehindAtLevel(
                parent = farm.groupId(),
                nextChild = farm.groupId("mobile"),
                leafSideVirtualService = Rational.of(9L),
                front = Rational.of(33L, 4L),
                eligibleFront = Rational.of(33L, 4L),
            ),
    )

    // Mobile's queue empties, and web runs twelve builds alone.
    check((0 until 12).map { build(setOf(web), startedAt = 30L + it * 3L) }.all { it == web })
    // Mobile comes back level with web, not owed the twelve builds it sat out.
    val returned = heddle.explain(mobile, waiting = both, eligible = both)
    check(returned is WaitExplanation.BehindAtLevel && returned.leafSideVirtualService == Rational.of(21L))
    check(returned.front == Rational.of(21L))
    check((0 until 8).map { build(both, startedAt = 66L + it * 3L) } == firstEight)

    // The next free agent is a Mac, which only mobile builds use. Mobile's last build took
    // 7 minutes, a better estimate than the default.
    val estimated = heddle.acquire(waiting = both, eligible = setOf(mobile), startedAt = 90L, reservation = 7L)
    check(estimated is AcquireOutcome.Acquired && estimated.lease.reserved == 7L)
    // Still running at 7 minutes, it asks for 3 more, then finishes at 8.
    check(heddle.extend(estimated.lease.id, amount = 3L) == LeaseOutcome.Done(charged = 0L, overrun = 0L))
    check(heddle.complete(estimated.lease.id, actualCost = 8L) == LeaseOutcome.Done(charged = 8L, overrun = 0L))
    // A build cancelled before it ran is charged nothing.
    val cancelled = heddle.acquire(both, both, startedAt = 98L)
    check(cancelled is AcquireOutcome.Acquired)
    check(heddle.cancel(cancelled.lease.id) == LeaseOutcome.Done(charged = 0L, overrun = 0L))
    // Someone started a mobile build by hand. Admit it: no pick, but it is charged like any other.
    // Naming who else is waiting keeps the scheduler's view of the queue as a pick would leave it.
    val byHand = heddle.admit(mobile, waiting = both, startedAt = 99L)
    check(byHand is AcquireOutcome.Acquired)
    check(heddle.complete(byHand.lease.id, actualCost = 4L) == LeaseOutcome.Done(charged = 4L, overrun = 1L))

    // A web build is running when the admin saves a new tree: web drops to two parts, and a new
    // group shares one part among contributors' forks, which the program names as they open.
    val running = heddle.acquire(setOf(web), setOf(web), startedAt = 100L)
    check(running is AcquireOutcome.Acquired)
    val grown = draft("farm") {
        group("web", Weight.of(2)) { lane("builds") }
        group("mobile") { lane("builds") }
        group("forks") { runtimeChildren() }
    }
    check(heddle.apply(grown, generation = 1L).refusals.isEmpty())
    // The running build moved onto web's new weight, and finishes as if nothing happened.
    check(heddle.complete(running.lease.id, actualCost = 3L) == LeaseOutcome.Done(charged = 3L, overrun = 0L))

    // A contributor opens a fork. Attach it, and it can be leased like any lane.
    check(heddle.attachRuntimeChild(listOf("forks"), "ana", generation = 2L).refusals.isEmpty())
    val ana = grown.runtimeChildId(listOf("forks"), "ana")
    val forkBuild = heddle.acquire(setOf(ana), setOf(ana), startedAt = 103L)
    check(forkBuild is AcquireOutcome.Acquired && forkBuild.lease.leaf == ana)
    // The fork closes while its build runs. It takes no new builds, and waits for this one.
    val closing = heddle.detachRuntimeChild(listOf("forks"), "ana").refusals.single()
    check(closing is StepRefusal.Draining && closing.child == ana && closing.blockingLeases == listOf(forkBuild.lease))
    check(heddle.acquire(setOf(ana), setOf(ana), startedAt = 104L) is AcquireOutcome.NoLiveLineage)
    check(heddle.complete(forkBuild.lease.id, actualCost = 3L) == LeaseOutcome.Done(charged = 3L, overrun = 0L))
    // Its last build is done, so the same detach now finishes.
    check(heddle.detachRuntimeChild(listOf("forks"), "ana").refusals.isEmpty())

    // Two builds are running when the agent host restarts. It comes back running only one of
    // them, and one build the engine never leased.
    val kept = (heddle.acquire(both, both, startedAt = 110L) as AcquireOutcome.Acquired).lease
    val lost = (heddle.acquire(both, both, startedAt = 111L) as AcquireOutcome.Acquired).lease
    val stranger = LeaseId(epoch = 0L, seq = 999L)
    val found = heddle.reconcileLeases(live = setOf(kept.id, stranger), now = 125L)
    check(found.unknown == listOf(stranger))
    val orphan = found.orphaned.single()
    check(orphan.lease == lost && orphan.elapsed == 14L && orphan.granted == 3L)
    // It ran 14 minutes on a 3-minute lease, which a lost build cannot prove: charge the 3.
    check(heddle.complete(orphan.lease.id, orphan.defaultSettlement) == LeaseOutcome.Done(charged = 3L, overrun = 0L))
    // Settled once, it is gone: a second settlement changes nothing.
    check(heddle.complete(orphan.lease.id, actualCost = 14L) == LeaseOutcome.Unknown)

    // Now the engine's own program restarts, with `kept` still running. Under your scheduling
    // lock, take a snapshot, then encode it and store the text exactly as it comes.
    val stored = Json.encodeToString(LocalHeddleSnapshot.serializer(), heddle.persist())

    // After the restart, decode it and restore under a higher epoch: keep a boot counter, raise it.
    val decoded = LocalHeddle.decode(stored, Json)
    check(decoded is RestoreResult.Restored && decoded.snapshot.epoch == 0L)
    val restored = LocalHeddle.restore(decoded.snapshot, policy, epoch = 1L)
    // It would pick exactly as the old engine would have.
    val ahead = mapOf(web to 8, mobile to 8)
    check(restored.previewPicks(ahead, both, List(8) { 3L }) == heddle.previewPicks(ahead, both, List(8) { 3L }))
    // The build that was running finishes on the new engine, charged as it would have been.
    check(restored.complete(kept.id, actualCost = 3L) == LeaseOutcome.Done(charged = 3L, overrun = 0L))
    // A lease settled before the restart is named as one from an older epoch.
    check(restored.complete(lost.id, actualCost = 3L) == LeaseOutcome.Stale(currentEpoch = 1L))
    // The agent count came back too, so it leases at once, and new ids carry the new epoch.
    val next = restored.acquire(both, both, startedAt = 130L)
    check(next is AcquireOutcome.Acquired && next.lease.id.epoch == 1L)
}

/**
 * Who goes next when each lane knows its own next job: the preview takes each lane's queue of
 * estimates, and each pick reserves the slot's default and then extends to the job, as a caller
 * running the queues would. Previewing moves nothing, and the real loop matches it.
 */
@Suppress("unused")
internal fun samplePreviewQueues() {
    val farm = draft("farm") {
        group("web", Weight.of(3)) { lane("builds") }
        group("mobile") { lane("builds") }
    }
    val web = farm.groupId("web", "builds")
    val mobile = farm.groupId("mobile", "builds")
    val heddle = LocalHeddle.fromTopology(farm, LocalPolicy(defaultReservation = 3L), epoch = 0L)
    heddle.reconcileCapacity(totalSlots = 4L)

    // Each lane's queued builds, by their estimates in minutes, the next one first.
    val queues = mapOf(web to listOf(3L, 12L, 3L, 3L), mobile to listOf(3L, 3L, 3L))
    val both = setOf(web, mobile)
    val before = heddle.snapshot()
    val preview = heddle.previewPicks(queues, eligible = both)
    check(preview == listOf(web, mobile, web, mobile, web, web, mobile))
    check(heddle.snapshot() == before)
    // Were every build 3 minutes, mobile's second would run sixth. Web's 12-minute build spends
    // four builds' worth of web's share, so mobile's moves up to fourth.
    check(heddle.previewPicks(mapOf(web to 4, mobile to 3), both, List(7) { 3L }) == listOf(web, mobile, web, web, web, mobile, mobile))

    // The real loop: lease a slot at the default, extend it to the winner's own estimate, pop it.
    val remaining = queues.mapValues { (_, queue) -> ArrayDeque(queue) }
    val picked = preview.indices.map {
        val waiting = both.filterTo(HashSet()) { remaining.getValue(it).isNotEmpty() }
        val out = heddle.acquire(waiting, eligible = waiting, startedAt = it.toLong())
        check(out is AcquireOutcome.Acquired)
        val estimate = remaining.getValue(out.lease.leaf).removeFirst()
        if (estimate > 3L) check(heddle.extend(out.lease.id, amount = estimate - 3L) is LeaseOutcome.Done)
        out.lease.leaf
    }
    check(picked == preview)
}

/**
 * An admin screen holding only the last stored snapshot asks who goes next, why a lane waits, and
 * what it is owed, with no engine to start. The view answers as the engine that took the snapshot.
 */
@Suppress("unused")
internal fun sampleSnapshotView() {
    val tree = twoLanes()
    val heavy = tree.groupId("heavy")
    val light = tree.groupId("light")
    val policy = LocalPolicy(defaultReservation = 3L)
    val heddle = LocalHeddle.fromTopology(tree, policy, epoch = 0L)
    heddle.reconcileCapacity(totalSlots = 2L)
    val stored = Json.encodeToString(LocalHeddleSnapshot.serializer(), heddle.persist())

    // On the admin screen: decode the stored snapshot and view it with the engine's policy.
    val decoded = LocalHeddle.decode(stored, Json)
    check(decoded is RestoreResult.Restored)
    val view = decoded.snapshot.view(policy)

    val queued = setOf(heavy, light)
    check(view.previewPicks(mapOf(heavy to 2, light to 1), eligible = queued, reservations = List(3) { 3L }) == listOf(heavy, light, heavy))
    check(view.explain(heavy, waiting = queued, eligible = queued) == WaitExplanation.WouldBePickedNext)
    check(view.entitlement(light, waiting = queued, totalSlots = 3L) == Rational.ONE)
    // The engine gives the same answers, and asking either moved nothing.
    check(heddle.previewPicks(mapOf(heavy to 2, light to 1), queued, List(3) { 3L }) == listOf(heavy, light, heavy))
    check((heddle.acquire(queued, queued, startedAt = 0L) as AcquireOutcome.Acquired).lease.leaf == heavy)
}

/** Two lanes under `pool`, `heavy` at weight 2 beside `light`. */
private fun twoLanes(): Topology = Topology("pool", listOf(Topology.Lane("heavy", Weight.of(2)), Topology.Lane("light")))
