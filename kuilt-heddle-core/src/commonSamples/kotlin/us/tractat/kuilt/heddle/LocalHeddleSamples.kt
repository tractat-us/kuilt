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
    val (heddle, booted) = LocalHeddle.fromTopology(tree, LocalPolicy(defaultReservation = 3L), epoch = 0L)
    check(booted.refusals.isEmpty())
    // Four slots: the engine mints its own supply ahead of them.
    heddle.reconcileCapacity(totalSlots = 4L)

    // Both groups have work queued, and this slot can run either.
    val queued = setOf(heavy, light)
    val first = heddle.acquire(waiting = queued, eligible = queued, startedAt = 100L)
    check(first is AcquireOutcome.Acquired)

    // The job overran its 3-unit estimate: all 5 units are charged, 2 of them as overrun.
    check(heddle.complete(first.lease.id, actualCost = 5L, waiting = emptySet()) == LeaseOutcome.Done(charged = 5L, overrun = 2L))

    // Over many slots the shares follow the weights, 2 : 1.
    val picks = (0 until 30).map {
        val lease = (heddle.acquire(queued, queued, startedAt = it.toLong()) as AcquireOutcome.Acquired).lease
        heddle.complete(lease.id, actualCost = 3L, waiting = emptySet())
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
    val (heddle, booted) = LocalHeddle.fromTopology(tree, LocalPolicy(defaultReservation = 3L), epoch = 0L)
    check(booted.refusals.isEmpty())
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
    val (heddle, booted) = LocalHeddle.fromTopology(tree, LocalPolicy(defaultReservation = 1L), epoch = 0L)
    check(booted.refusals.isEmpty())
    heddle.reconcileCapacity(totalSlots = 4L)
    val queued = setOf(deploys, builds)

    val deploy = (heddle.acquire(queued, queued, startedAt = 0L) as AcquireOutcome.Acquired).lease
    check(deploy.leaf == deploys)
    // Deploys weigh three times as much, but one is already running, its ceiling.
    check(heddle.explain(deploys, waiting = queued, eligible = queued) == WaitExplanation.AtCeiling(deploys, limit = 1L))
    check(heddle.entitlement(builds, waiting = queued, totalSlots = 4L) == Rational.of(4L))
    check((heddle.acquire(queued, queued, startedAt = 1L) as AcquireOutcome.Acquired).lease.leaf == builds)

    heddle.complete(deploy.id, actualCost = 1L, waiting = emptySet())
    // Deploys compete again, a third of a build ahead, as they were when they reached the ceiling.
    val back = heddle.explain(deploys, waiting = queued, eligible = queued)
    check(back is WaitExplanation.BehindAtLevel && back.leafSideVirtualService == Rational.of(4L, 3L) && back.front == Rational.of(5L, 4L))
    check(heddle.explain(builds, waiting = queued, eligible = queued) == WaitExplanation.WouldBePickedNext)
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
    val (heddle, booted) = LocalHeddle.fromTopology(tree, LocalPolicy(defaultReservation = 3L), epoch = 0L)
    check(booted.refusals.isEmpty())
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
    check(heddle.complete(orphan.lease.id, orphan.defaultSettlement, waiting = emptySet()) == LeaseOutcome.Done(charged = 3L, overrun = 0L))
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
    val (heddle, booted) = LocalHeddle.fromTopology(tree, policy, epoch = 0L)
    check(booted.refusals.isEmpty())
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
    check(restarted.complete(running.id, actualCost = 3L, waiting = emptySet()) == LeaseOutcome.Done(charged = 3L, overrun = 0L))
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
    val (heddle, booted) = LocalHeddle.fromTopology(before, LocalPolicy(defaultReservation = 3L), epoch = 0L)
    check(booted.refusals.isEmpty())
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
    check(heddle.complete(running.id, actualCost = 3L, waiting = emptySet()) == LeaseOutcome.Done(charged = 3L, overrun = 0L))
    check(heddle.complete(leftover.id, actualCost = 2L, waiting = emptySet()) == LeaseOutcome.Done(charged = 2L, overrun = 0L))

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
    val (heddle, booted) = LocalHeddle.fromTopology(farm, policy, epoch = 0L)
    check(booted.refusals.isEmpty())

    // Nothing to hand out until the engine knows how many agents there are.
    val both = setOf(web, mobile)
    check(heddle.acquire(waiting = both, eligible = both, startedAt = 0L) == AcquireOutcome.SupplyExhausted)
    heddle.reconcileCapacity(totalSlots = 4L)

    // One build: lease a slot, run it, and charge the minutes it took.
    fun build(waiting: Set<GroupId>, startedAt: Long, minutes: Long = 3L): GroupId {
        val out = heddle.acquire(waiting, eligible = waiting, startedAt = startedAt)
        check(out is AcquireOutcome.Acquired)
        check(heddle.complete(out.lease.id, actualCost = minutes, waiting = emptySet()) is LeaseOutcome.Done)
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
    check(heddle.complete(ninth.lease.id, actualCost = 9L, waiting = emptySet()) == LeaseOutcome.Done(charged = 9L, overrun = 6L))
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
    // Mobile comes back 3 behind web, as it left: still owed web's long build, not the twelve it sat out.
    check(heddle.explain(mobile, waiting = both, eligible = both) == WaitExplanation.WouldBePickedNext)
    val webAhead = heddle.explain(web, waiting = both, eligible = both)
    check(webAhead is WaitExplanation.BehindAtLevel && webAhead.leafSideVirtualService == Rational.of(21L))
    // The line is (3 · 21 + 18) / 4: mobile sits at 18.
    check(webAhead.front == Rational.of(81L, 4L))
    check((0 until 8).map { build(both, startedAt = 66L + it * 3L) } == listOf(mobile, web, mobile, web, web, web, mobile, web))

    // The next free agent is a Mac, which only mobile builds use. Mobile's last build took
    // 7 minutes, a better estimate than the default.
    val estimated = heddle.acquire(waiting = both, eligible = setOf(mobile), startedAt = 90L, reservation = 7L)
    check(estimated is AcquireOutcome.Acquired && estimated.lease.reserved == 7L)
    // Still running at 7 minutes, it asks for 3 more, then finishes at 8.
    check(heddle.extend(estimated.lease.id, amount = 3L, waiting = emptySet()) == LeaseOutcome.Done(charged = 0L, overrun = 0L))
    check(heddle.complete(estimated.lease.id, actualCost = 8L, waiting = emptySet()) == LeaseOutcome.Done(charged = 8L, overrun = 0L))
    // A build cancelled before it ran is charged nothing.
    val cancelled = heddle.acquire(both, both, startedAt = 98L)
    check(cancelled is AcquireOutcome.Acquired)
    check(heddle.cancel(cancelled.lease.id) == LeaseOutcome.Done(charged = 0L, overrun = 0L))
    // Someone started a mobile build by hand. Admit it: no pick, but it is charged like any other.
    // Naming who else is waiting keeps the scheduler's view of the queue as a pick would leave it.
    val byHand = heddle.admit(mobile, waiting = both, startedAt = 99L)
    check(byHand is AcquireOutcome.Acquired)
    check(heddle.complete(byHand.lease.id, actualCost = 4L, waiting = emptySet()) == LeaseOutcome.Done(charged = 4L, overrun = 1L))

    // A web build is running when the admin saves a new tree: web drops to two parts, and a new
    // group shares one part among contributors' forks, which the program names as they open.
    val running = heddle.acquire(setOf(web), setOf(web), startedAt = 100L)
    check(running is AcquireOutcome.Acquired)
    val grown = draft("farm") {
        group("web", Weight.of(2)) { lane("builds") }
        group("mobile") { lane("builds") }
        group("forks") { template() }
    }
    check(heddle.apply(grown, generation = 1L).refusals.isEmpty())
    // The running build moved onto web's new weight, and finishes as if nothing happened.
    check(heddle.complete(running.lease.id, actualCost = 3L, waiting = emptySet()) == LeaseOutcome.Done(charged = 3L, overrun = 0L))

    // A contributor opens a fork. Attach it, and it can be leased like any lane.
    check(heddle.attach(listOf("forks"), "ana", generation = 2L).refusals.isEmpty())
    val ana = grown.attachedChildId(listOf("forks"), "ana")
    val forkBuild = heddle.acquire(setOf(ana), setOf(ana), startedAt = 103L)
    check(forkBuild is AcquireOutcome.Acquired && forkBuild.lease.leaf == ana)
    // The fork closes while its build runs. It takes no new builds, and waits for this one.
    val closing = heddle.detach(listOf("forks"), "ana").refusals.single()
    check(closing is StepRefusal.Draining && closing.child == ana && closing.blockingLeases == listOf(forkBuild.lease))
    check(heddle.acquire(setOf(ana), setOf(ana), startedAt = 104L) is AcquireOutcome.NoLiveLineage)
    check(heddle.complete(forkBuild.lease.id, actualCost = 3L, waiting = emptySet()) == LeaseOutcome.Done(charged = 3L, overrun = 0L))
    // Its last build is done, so the same detach now finishes.
    check(heddle.detach(listOf("forks"), "ana").refusals.isEmpty())

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
    check(heddle.complete(orphan.lease.id, orphan.defaultSettlement, waiting = emptySet()) == LeaseOutcome.Done(charged = 3L, overrun = 0L))
    // Settled once, it is gone: a second settlement changes nothing.
    check(heddle.complete(orphan.lease.id, actualCost = 14L, waiting = emptySet()) == LeaseOutcome.Unknown)

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
    check(restored.complete(kept.id, actualCost = 3L, waiting = emptySet()) == LeaseOutcome.Done(charged = 3L, overrun = 0L))
    // A lease settled before the restart is named as one from an older epoch.
    check(restored.complete(lost.id, actualCost = 3L, waiting = emptySet()) == LeaseOutcome.Stale(currentEpoch = 1L))
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
    val (heddle, booted) = LocalHeddle.fromTopology(farm, LocalPolicy(defaultReservation = 3L), epoch = 0L)
    check(booted.refusals.isEmpty())
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
        if (estimate > 3L) check(heddle.extend(out.lease.id, amount = estimate - 3L, waiting = emptySet()) is LeaseOutcome.Done)
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
    val (heddle, booted) = LocalHeddle.fromTopology(tree, policy, epoch = 0L)
    check(booted.refusals.isEmpty())
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

/**
 * The whole loop in one place, from an admin's config file to a restart: check the tree, start the
 * engine, attach a child the program found, lease and charge slots, then persist, restore under a
 * raised boot counter, and settle the work that did not survive.
 */
@Suppress("unused")
internal fun sampleFairShareQueue() {
    // The admin's file: web gets three parts to mobile's one, and forks are attached as they open.
    val text = """
        {"root": "farm", "children": [
          {"type": "lane", "name": "web", "weight": "3"},
          {"type": "lane", "name": "mobile"},
          {"type": "group", "name": "forks", "template": {"maxConcurrent": 1}}
        ]}
    """
    val parsed = Topology.from(Json.decodeFromString<TopologyDocument>(text))
    // Invalid would carry every problem in the file, each with its address, for the admin to fix.
    check(parsed is TopologyParse.Valid)
    val farm = parsed.topology
    val policy = LocalPolicy(defaultReservation = 3L)
    // The epoch is a boot counter you persist and raise on every start.
    val (heddle, booted) = LocalHeddle.fromTopology(farm, policy, epoch = 0L)
    check(booted.refusals.isEmpty())
    heddle.reconcileCapacity(totalSlots = 4L)

    // A contributor opens a fork while the program runs.
    check(heddle.attach(listOf("forks"), "ana", generation = 1L).refusals.isEmpty())
    val web = farm.groupId("web")
    val mobile = farm.groupId("mobile")
    val ana = farm.attachedChildId(listOf("forks"), "ana")

    // Each free slot: lease it, run the work, charge what it really cost.
    val queued = setOf(web, mobile, ana)
    val first = heddle.acquire(waiting = queued, eligible = queued, startedAt = 0L)
    check(first is AcquireOutcome.Acquired)
    check(heddle.complete(first.lease.id, actualCost = 4L, waiting = emptySet()) is LeaseOutcome.Done)
    val running = (heddle.acquire(queued, queued, startedAt = 4L) as AcquireOutcome.Acquired).lease
    val lost = (heddle.acquire(queued, queued, startedAt = 4L) as AcquireOutcome.Acquired).lease

    // Under the scheduling lock, persist. Encode and store the text exactly as it comes.
    val stored = Json.encodeToString(LocalHeddleSnapshot.serializer(), heddle.persist())

    // After the restart: decode, restore under the raised counter, and settle what did not survive.
    val decoded = LocalHeddle.decode(stored, Json)
    check(decoded is RestoreResult.Restored)
    val restored = LocalHeddle.restore(decoded.snapshot, policy, epoch = 1L)
    val orphan = restored.reconcileLeases(live = setOf(running.id), now = 10L).orphaned.single()
    check(orphan.lease == lost)
    check(restored.complete(orphan.lease.id, orphan.defaultSettlement, waiting = emptySet()) == LeaseOutcome.Done(charged = 3L, overrun = 0L))
    check(restored.complete(running.id, actualCost = 3L, waiting = emptySet()) == LeaseOutcome.Done(charged = 3L, overrun = 0L))
}

/**
 * Work another scheduler already started: [LocalHeddle.admit] charges it to its group with no pick,
 * and is never refused for supply or at a ceiling, since the work is already running. Only a name
 * no lease can end at is refused.
 */
@Suppress("unused")
internal fun sampleAdmitExternal() {
    val farm = draft("farm") {
        lane("web", Weight.of(3))
        lane("mobile", maxConcurrent = 1)
        group("forks") { template() }
    }
    val web = farm.groupId("web")
    val mobile = farm.groupId("mobile")
    val (heddle, booted) = LocalHeddle.fromTopology(farm, LocalPolicy(defaultReservation = 3L), epoch = 0L)
    check(booted.refusals.isEmpty())

    // No capacity is reconciled, so there is nothing to hand out; an admitted build is charged anyway.
    val both = setOf(web, mobile)
    check(heddle.acquire(both, both, startedAt = 0L) == AcquireOutcome.SupplyExhausted)
    val byHand = heddle.admit(mobile, waiting = both, startedAt = 0L)
    check(byHand is AcquireOutcome.Acquired)

    // Mobile is now at its ceiling of one, and a second build started by hand is still charged.
    val another = heddle.admit(mobile, waiting = both, startedAt = 1L)
    check(another is AcquireOutcome.Acquired)
    check(heddle.complete(another.lease.id, actualCost = 5L, waiting = emptySet()) == LeaseOutcome.Done(charged = 5L, overrun = 2L))

    // A group with a template and nothing attached is not somewhere a build can run.
    val forks = farm.groupId("forks")
    check(heddle.admit(forks, waiting = setOf(forks), startedAt = 2L) == AcquireOutcome.NoLiveLineage(forks))
}

/**
 * Every refusal is a value, and a refused call changes nothing. A paused lane leaves nothing
 * eligible; an extend on a path a reload is closing is refused while its complete still settles; a
 * settled id is unknown, and after a restart stale; and a weight too fine for one full lease is
 * refused when it is applied, while its sibling lands.
 *
 * `PositionCeiling` is not shown, because nothing returns it today: a pick compares the full
 * 128-bit position, so no position refuses one. The arm is kept for the ledger's `Long` lifetime
 * counters, which throw for now.
 */
@Suppress("unused")
internal fun sampleRefusals() {
    val pool = draft("pool") {
        lane("live")
        lane("paused", maxConcurrent = 0)
        lane("retiring")
    }
    val live = pool.groupId("live")
    val paused = pool.groupId("paused")
    val retiring = pool.groupId("retiring")
    val policy = LocalPolicy(defaultReservation = 3L)
    val (heddle, booted) = LocalHeddle.fromTopology(pool, policy, epoch = 0L)
    check(booted.refusals.isEmpty())
    heddle.reconcileCapacity(totalSlots = 2L)

    // A ceiling of 0 pauses a lane: with only it eligible for this slot, there is nobody to pick.
    val all = setOf(live, paused, retiring)
    check(heddle.acquire(waiting = all, eligible = setOf(paused), startedAt = 0L) == AcquireOutcome.NothingEligible)
    check(heddle.explain(paused, waiting = all, eligible = all) == WaitExplanation.AtCeiling(paused, limit = 0L))

    // A reload removes `retiring` while its lease runs: the extend is refused, the complete settles.
    val lease = (heddle.acquire(setOf(retiring), setOf(retiring), startedAt = 0L) as AcquireOutcome.Acquired).lease
    val without = draft("pool") {
        lane("live")
        lane("paused", maxConcurrent = 0)
    }
    check(heddle.apply(without, generation = 1L).refusals.single() is StepRefusal.Draining)
    check(heddle.extend(lease.id, amount = 3L, waiting = emptySet()) is LeaseOutcome.PathDraining)
    check(heddle.complete(lease.id, actualCost = 3L, waiting = emptySet()) == LeaseOutcome.Done(charged = 3L, overrun = 0L))

    // Settled once, the id is unknown to this engine; after a restart it is from an older epoch.
    check(heddle.complete(lease.id, actualCost = 3L, waiting = emptySet()) == LeaseOutcome.Unknown)
    val restarted = LocalHeddle.restore(heddle.persist(), policy, epoch = 1L)
    check(restarted.complete(lease.id, actualCost = 3L, waiting = emptySet()) == LeaseOutcome.Stale(currentEpoch = 1L))

    // Keep every weight's denominator at or below 30,719. A finer one is refused when it is
    // applied, and the rest of the reload lands.
    val finer = draft("pool") {
        lane("live")
        lane("paused", maxConcurrent = 0)
        lane("fine", Weight.of(1, 30_720))
        lane("coarse", Weight.of(1, 2))
    }
    val refused = restarted.apply(finer, generation = 2L).refusals.single()
    check(refused is StepRefusal.Refused && refused.child == finer.groupId("fine"))
    val coarse = finer.groupId("coarse")
    check(restarted.acquire(setOf(coarse), setOf(coarse), startedAt = 10L) is AcquireOutcome.Acquired)
}

/**
 * A boot reports what it leaves out, as a reload does. The weights of `builds`'s lanes do not fit
 * together, so the boot leaves out `builds` and everything beneath it, whatever the lanes are
 * called, and runs `docs`. Applying a tree that fits brings `builds` back.
 */
@Suppress("unused")
internal fun sampleLocalBootRefusal() {
    val tree = draft("farm") {
        group("builds") {
            lane("fast")
            lane("fine", Weight.of(1, 30_720))
        }
        lane("docs")
    }
    val (heddle, booted) = LocalHeddle.fromTopology(tree, LocalPolicy(defaultReservation = 3L), epoch = 0L)
    val left = setOf(tree.groupId("builds"), tree.groupId("builds", "fast"), tree.groupId("builds", "fine"))
    check(booted.refusals.map { it.child }.toSet() == left)
    check(booted.refusals.all { it is StepRefusal.Refused })

    // The rest of the tree runs.
    heddle.reconcileCapacity(totalSlots = 1L)
    val docs = tree.groupId("docs")
    val lease = (heddle.acquire(setOf(docs), setOf(docs), startedAt = 0L) as AcquireOutcome.Acquired).lease
    heddle.complete(lease.id, actualCost = 3L, waiting = emptySet())

    // A tree whose weights fit brings `builds` back, as any reload would.
    val fits = draft("farm") {
        group("builds") {
            lane("fast")
            lane("fine", Weight.of(1, 30_000))
        }
        lane("docs")
    }
    check(heddle.apply(fits, generation = 1L).refusals.isEmpty())
    val fast = fits.groupId("builds", "fast")
    check(heddle.acquire(setOf(fast), setOf(fast), startedAt = 1L) is AcquireOutcome.Acquired)
}

/**
 * An admin screen asks a stored snapshot who goes next, given each lane's own queued jobs, with no
 * engine to start. The view answers exactly as the engine that took the snapshot.
 */
@Suppress("unused")
internal fun sampleSnapshotPreviewQueues() {
    val farm = draft("farm") {
        group("web", Weight.of(3)) { lane("builds") }
        group("mobile") { lane("builds") }
    }
    val web = farm.groupId("web", "builds")
    val mobile = farm.groupId("mobile", "builds")
    val policy = LocalPolicy(defaultReservation = 3L)
    val (heddle, booted) = LocalHeddle.fromTopology(farm, policy, epoch = 0L)
    check(booted.refusals.isEmpty())
    heddle.reconcileCapacity(totalSlots = 4L)
    val stored = Json.encodeToString(LocalHeddleSnapshot.serializer(), heddle.persist())

    // On the admin screen: each lane's queued builds by their estimates in minutes, the next one first.
    val queues = mapOf(web to listOf(3L, 12L, 3L, 3L), mobile to listOf(3L, 3L, 3L))
    val decoded = LocalHeddle.decode(stored, Json)
    check(decoded is RestoreResult.Restored)
    val view = decoded.snapshot.view(policy)
    val both = setOf(web, mobile)
    check(view.previewPicks(queues, eligible = both) == listOf(web, mobile, web, mobile, web, web, mobile))
    check(view.previewPicks(queues, eligible = both) == heddle.previewPicks(queues, eligible = both))
}

/**
 * Repositories the program attaches as it finds them: the template's ceiling lets each run one job
 * at once, and the group's own ceiling lets them run two all together. `explain` names whichever
 * ceiling holds a repository back, and the slot goes to a sibling.
 */
@Suppress("unused")
internal fun sampleTemplateCeiling() {
    val ci = draft("ci") {
        group("repos", maxConcurrent = 2) { template(maxConcurrent = 1) }
        lane("nightly")
    }
    val (heddle, booted) = LocalHeddle.fromTopology(ci, LocalPolicy(defaultReservation = 1L), epoch = 0L)
    check(booted.refusals.isEmpty())
    heddle.reconcileCapacity(totalSlots = 4L)
    val (a, b, c) = listOf("a", "b", "c").mapIndexed { i, name ->
        check(heddle.attach(listOf("repos"), name, generation = i + 1L).refusals.isEmpty())
        ci.attachedChildId(listOf("repos"), name)
    }
    val nightly = ci.groupId("nightly")
    val all = setOf(a, b, c, nightly)

    // `a` runs one job, its own ceiling.
    check((heddle.acquire(setOf(a), setOf(a), startedAt = 0L) as AcquireOutcome.Acquired).lease.leaf == a)
    check(heddle.explain(a, waiting = all, eligible = all) == WaitExplanation.AtCeiling(a, limit = 1L))
    // `b` runs one too, which puts the group at its ceiling of two: `c` waits on the group.
    check((heddle.acquire(setOf(b), setOf(b), startedAt = 0L) as AcquireOutcome.Acquired).lease.leaf == b)
    check(heddle.explain(c, waiting = all, eligible = all) == WaitExplanation.AtCeiling(ci.groupId("repos"), limit = 2L))
    // So the next slot goes to the nightly lane, however much the repositories have queued.
    check((heddle.acquire(all, all, startedAt = 1L) as AcquireOutcome.Acquired).lease.leaf == nightly)
}

/**
 * A status line for the person whose build is waiting: where their next builds sit in line, why
 * another team goes first, and what share they are owed. Every answer is a read, so asking moves
 * nothing, and a preview finishes no build, so a lane under a ceiling shows up no more often than
 * its ceiling allows.
 */
@Suppress("unused")
internal fun sampleQueueStatus() {
    val farm = draft("farm") {
        group("web", Weight.of(3)) { lane("builds") }
        group("mobile") { lane("builds") }
    }
    val web = farm.groupId("web", "builds")
    val mobile = farm.groupId("mobile", "builds")
    val (heddle, booted) = LocalHeddle.fromTopology(farm, LocalPolicy(defaultReservation = 3L), epoch = 0L)
    check(booted.refusals.isEmpty())
    heddle.reconcileCapacity(totalSlots = 4L)

    // The names a person knows each part of the tree by.
    val names = mapOf(
        farm.groupId() to "the farm",
        farm.groupId("web") to "Web",
        web to "Web",
        farm.groupId("mobile") to "Mobile",
        mobile to "Mobile",
    )

    // One sentence for each answer explain can give. The compiler checks that none is missing.
    fun why(lane: GroupId, waiting: Set<GroupId>, eligible: Set<GroupId>): String =
        when (val answer = heddle.explain(lane, waiting, eligible)) {
            WaitExplanation.WouldBePickedNext -> "Next up."
            WaitExplanation.NotWaiting -> "Nothing queued."
            WaitExplanation.NotEligible -> "This agent cannot run it."
            WaitExplanation.SupplyExhausted -> "No agent time is free yet."
            is WaitExplanation.NoLiveLineage -> "Its part of the tree is being changed."
            is WaitExplanation.AtCeiling -> "${names.getValue(answer.group)} already runs ${answer.limit} at once, its limit."
            is WaitExplanation.PositionCeiling -> "Scheduling is stuck at ${names.getValue(answer.parent)}."
            is WaitExplanation.BehindAtLevel -> "${names.getValue(answer.nextChild)} goes first at ${names.getValue(answer.parent)}."
        }

    val both = setOf(web, mobile)
    // Where do mobile's next builds sit in line? Second and sixth.
    val line = heddle.previewPicks(mapOf(web to 8, mobile to 8), both, List(8) { 3L })
    check(line.withIndex().filter { it.value == mobile }.map { it.index + 1 } == listOf(2, 6))
    check(why(mobile, waiting = both, eligible = both) == "Web goes first at the farm.")
    // Of the four agents, mobile is owed one while both teams have builds queued.
    check(heddle.entitlement(mobile, waiting = both, totalSlots = 4L) == Rational.ONE)
    // With web's queue empty, mobile is owed all four.
    check(heddle.entitlement(mobile, waiting = setOf(mobile), totalSlots = 4L) == Rational.of(4L))
    // The answers change only when something real happens. Web takes the next agent:
    check((heddle.acquire(both, both, startedAt = 0L) as AcquireOutcome.Acquired).lease.leaf == web)
    check(why(mobile, waiting = both, eligible = both) == "Next up.")
    check(why(mobile, waiting = setOf(web), eligible = setOf(web)) == "Nothing queued.")
    check(why(mobile, waiting = both, eligible = setOf(web)) == "This agent cannot run it.")

    // A preview finishes no build, so deploys, capped at one at a time, appear once.
    val pool = draft("pool") {
        lane("deploys", Weight.of(3), maxConcurrent = 1)
        lane("builds")
    }
    val deploys = pool.groupId("deploys")
    val builds = pool.groupId("builds")
    val (capped, cappedBoot) = LocalHeddle.fromTopology(pool, LocalPolicy(defaultReservation = 1L), epoch = 0L)
    check(cappedBoot.refusals.isEmpty())
    capped.reconcileCapacity(totalSlots = 4L)
    val queued = mapOf(deploys to 3, builds to 3)
    check(capped.previewPicks(queued, queued.keys, List(6) { 1L }) == listOf(deploys, builds, builds, builds))
}

/**
 * A hold reason a status page or a metric can compare from one moment to the next: the answer's
 * kind and its group ids. The numbers a `BehindAtLevel` carries move on every pick, so comparing
 * whole answers would report a new reason every time.
 */
@Suppress("unused")
internal fun sampleWaitReason() {
    val farm = draft("farm") {
        group("web", Weight.of(3)) { lane("builds") }
        group("mobile") { lane("builds") }
    }
    val web = farm.groupId("web", "builds")
    val mobile = farm.groupId("mobile", "builds")
    val (heddle, booted) = LocalHeddle.fromTopology(farm, LocalPolicy(defaultReservation = 3L), epoch = 0L)
    check(booted.refusals.isEmpty())
    val both = setOf(web, mobile)

    // Until the engine knows how many agents there are, nobody goes next.
    check(heddle.explain(mobile, waiting = both, eligible = both) == WaitExplanation.SupplyExhausted)
    heddle.reconcileCapacity(totalSlots = 4L)

    // The reason, without the numbers: the kind, and every group id the answer names.
    fun reason(answer: WaitExplanation): Pair<WaitExplanation.Kind, List<GroupId>> =
        answer.kind to when (answer) {
            WaitExplanation.NotWaiting, WaitExplanation.NotEligible, WaitExplanation.SupplyExhausted,
            WaitExplanation.WouldBePickedNext -> emptyList()
            is WaitExplanation.NoLiveLineage -> listOf(answer.at)
            is WaitExplanation.AtCeiling -> listOf(answer.group)
            is WaitExplanation.PositionCeiling -> listOf(answer.parent)
            is WaitExplanation.BehindAtLevel -> listOf(answer.parent, answer.nextChild)
        }

    fun build() {
        val out = heddle.acquire(both, both, startedAt = 0L)
        check(out is AcquireOutcome.Acquired)
        heddle.complete(out.lease.id, actualCost = 3L, waiting = emptySet())
    }
    repeat(2) { build() } // web, then mobile
    val before = heddle.explain(mobile, waiting = both, eligible = both)
    build() // web again
    val after = heddle.explain(mobile, waiting = both, eligible = both)
    // The numbers moved, so the whole answers differ. The reason did not: web is still ahead at the farm.
    check(before != after)
    check(reason(before) == reason(after))
    check(reason(after) == (WaitExplanation.Kind.BEHIND_AT_LEVEL to listOf(farm.groupId(), farm.groupId("web"))))
}

/** Two lanes under `pool`, `heavy` at weight 2 beside `light`. */
private fun twoLanes(): Topology = Topology("pool", listOf(Topology.Lane("heavy", Weight.of(2)), Topology.Lane("light")))
