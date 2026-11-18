package us.tractat.kuilt.heddle

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
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

        // Everything that touches the engine goes through the pool: a reload, a snapshot, an explain.
        val uncapped = Topology("farm", listOf(Topology.Lane("web", Weight.of(3)), Topology.Lane("mobile")))
        val reload = pool.withEngine { it.apply(uncapped, generation = 1L) }
        check(reload.refusals.isEmpty())

        val granted = second.await()
        check(granted is SlotOutcome.Granted)
        pool.complete(granted.lease.id, actualCost = 3L)
    }
    pool.complete(first.lease.id, actualCost = 3L)

    // Lowering the count closes nothing: it only holds back the next grant.
    pool.resize(1)
}
