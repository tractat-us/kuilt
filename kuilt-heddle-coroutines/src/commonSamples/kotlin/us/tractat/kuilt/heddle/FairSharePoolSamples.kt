package us.tractat.kuilt.heddle

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

/**
 * Two lanes share one build agent. Each build waits for its lane's turn, runs, and is charged the
 * time it held the agent, even if it fails.
 */
@Suppress("unused")
internal suspend fun sampleWithSlot() {
    val farm = Topology("farm", listOf(Topology.Lane("web", Weight.of(3)), Topology.Lane("mobile")))
    val web = farm.groupId("web")
    val mobile = farm.groupId("mobile")
    val (heddle, booted) = LocalHeddle.fromTopology(farm, LocalPolicy(defaultReservation = 3L), epoch = 0L)
    check(booted.refusals.isEmpty())
    val pool = FairSharePool(heddle, slots = 1, TimeSource.Monotonic, costUnit = 1.minutes)

    val built = mutableListOf<String>()
    coroutineScope {
        // One agent: whichever lane's turn comes second waits for the first build to finish.
        launch { built += pool.withSlot(web) { "web build" } }
        launch { built += pool.withSlot(mobile) { "mobile build" } }
    }
    check(built.sorted() == listOf("mobile build", "web build"))

    // A name the farm does not declare is refused before anything runs.
    val refused = try {
        pool.withSlot(GroupId("nightly")) { "never runs" }
    } catch (e: SlotRefusedException) {
        "refused: ${e.reason}"
    }
    check(refused.startsWith("refused"))
}

/**
 * Two lanes share a build farm's two agents. Each build waits for its lane's turn, and is charged
 * what it really took.
 */
@Suppress("unused")
internal suspend fun sampleAcquireSlot() {
    val farm = Topology("farm", listOf(Topology.Lane("web", Weight.of(3)), Topology.Lane("mobile")))
    val web = farm.groupId("web")
    val mobile = farm.groupId("mobile")
    val (heddle, booted) = LocalHeddle.fromTopology(farm, LocalPolicy(defaultReservation = 3L), epoch = 0L)
    check(booted.refusals.isEmpty())
    // Two agents, and one unit of cost is one minute on an agent. The pool owns the engine now.
    val pool = FairSharePool(heddle, slots = 2, TimeSource.Monotonic, costUnit = 1.minutes)

    // Waits until fair share grants web a slot: at once, on an idle farm.
    val build = pool.acquire(web)
    check(build is SlotOutcome.Granted)
    // The build took five minutes against the default estimate of three: two are overrun.
    check(pool.complete(build.lease.id, actualCost = 5L) == LeaseOutcome.Done(charged = 5L, overrun = 2L, unrecorded = 0L))

    // A name the farm does not declare is refused, never left waiting.
    check(pool.acquire(GroupId("nightly")) is SlotOutcome.Refused)

    val next = pool.acquire(mobile)
    check(next is SlotOutcome.Granted)
    pool.cancel(next.lease.id)
}

/**
 * Web may hold only one of two agents, so its second build waits with an agent free. A reload
 * lifts the cap through the pool, and the pool grants the waiting build once the reload is in.
 */
@Suppress("unused")
internal suspend fun sampleReloadWhileWaiting() {
    val capped = Topology("farm", listOf(Topology.Lane("web", Weight.of(3), maxConcurrent = 1L), Topology.Lane("mobile")))
    val web = capped.groupId("web")
    val (heddle, booted) = LocalHeddle.fromTopology(capped, LocalPolicy(defaultReservation = 3L), epoch = 0L)
    check(booted.refusals.isEmpty())
    val pool = FairSharePool(heddle, slots = 2, TimeSource.Monotonic, costUnit = 1.minutes)

    val first = pool.acquire(web)
    check(first is SlotOutcome.Granted)
    coroutineScope {
        val second = async(start = CoroutineStart.UNDISPATCHED) { pool.acquire(web) }
        check(!second.isCompleted) // web is at its cap

        // Everything that touches the engine goes through the pool, which hands over a view of its
        // queues too: why web waits, as the pool's next pick sees it.
        val why = pool.withEngine { engine, view -> engine.explain(web, view.waiting, view.waiting) }
        check(why == WaitExplanation.AtCeiling(web, limit = 1L))

        // A reload, a snapshot, a search for orphans: the same way in.
        val uncapped = Topology("farm", listOf(Topology.Lane("web", Weight.of(3)), Topology.Lane("mobile")))
        val reload = pool.withEngine { engine, _ -> engine.apply(uncapped, generation = 1L) }
        check(reload.refusals.isEmpty())

        val granted = second.await()
        check(granted is SlotOutcome.Granted)
        pool.complete(granted.lease.id, actualCost = 3L)
    }
    pool.complete(first.lease.id, actualCost = 3L)

    // Lowering the count closes nothing: it only holds back the next grant.
    pool.resize(1)
}

/**
 * The farm's program crashes while both agents are busy. The restored engine still holds both
 * leases, so the new pool starts with every slot taken, and the builds that held them are gone:
 * nothing will ever complete them. Settle them as orphans and the next build is granted.
 */
@Suppress("unused")
internal suspend fun sampleRestartAPool() {
    val farm = Topology("farm", listOf(Topology.Lane("web", Weight.of(3)), Topology.Lane("mobile")))
    val web = farm.groupId("web")
    val mobile = farm.groupId("mobile")
    val policy = LocalPolicy(defaultReservation = 3L)
    val (heddle, booted) = LocalHeddle.fromTopology(farm, policy, epoch = 0L)
    check(booted.refusals.isEmpty())
    // A test clock, so the sample can let two minutes pass.
    val clock = TestTimeSource()
    val before = FairSharePool(heddle, slots = 2, clock, costUnit = 1.minutes)
    check(before.acquire(web) is SlotOutcome.Granted)
    check(before.acquire(mobile) is SlotOutcome.Granted)
    clock += 2.minutes

    // Persist the pool's clock beside the snapshot. now() takes no lock, so it is safe in the block.
    val (snapshot, stoppedAt) = before.withEngine { engine, _ -> engine.persist() to before.now() }

    // After the crash: restore under a raised epoch, your persisted boot counter, and start the
    // pool's clock where it stopped. Both leases are adopted: every slot is taken.
    val restored = LocalHeddle.restore(snapshot, policy, epoch = snapshot.epoch + 1)
    val pool = FairSharePool(restored, slots = 2, clock, costUnit = 1.minutes, clockStart = stoppedAt)

    coroutineScope {
        val next = async(start = CoroutineStart.UNDISPATCHED) { pool.acquire(web) }
        check(!next.isCompleted) // both slots are held by builds that died with the old program

        // None of the old builds is running, so name none as live, and settle each orphan at the
        // default: the two minutes it ran. With the default clockStart of 0, now() would read 0,
        // and every default settlement would read 0 too.
        val found = pool.withEngine { engine, _ -> engine.reconcileLeases(live = emptySet(), now = pool.now()) }
        check(found.orphaned.map { it.defaultSettlement } == listOf(2L, 2L))
        found.orphaned.forEach { pool.complete(it.lease.id, it.defaultSettlement) }

        val granted = next.await()
        check(granted is SlotOutcome.Granted)
        pool.complete(granted.lease.id, actualCost = 3L)
    }
}
