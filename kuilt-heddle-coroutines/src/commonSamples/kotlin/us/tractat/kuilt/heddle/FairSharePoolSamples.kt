package us.tractat.kuilt.heddle

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
