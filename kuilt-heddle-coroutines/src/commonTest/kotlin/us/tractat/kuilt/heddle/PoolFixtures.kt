package us.tractat.kuilt.heddle

import kotlinx.coroutines.test.TestScope
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/** The two-lane farm every pool test leases from: web weighted 3 to mobile's 1, a default reservation of 3. */
internal val farmTopology: Topology = Topology("farm", listOf(Topology.Lane("web", Weight.of(3)), Topology.Lane("mobile")))

internal val web: GroupId = farmTopology.groupId("web")
internal val mobile: GroupId = farmTopology.groupId("mobile")

/** A name the farm does not declare, so no lease can ever end at it. */
internal val nope: GroupId = GroupId("nope")

/** A fresh engine over [topology], booted at epoch 0 with a default reservation of 3. */
internal fun farm(topology: Topology = farmTopology): LocalHeddle =
    LocalHeddle.fromTopology(topology, LocalPolicy(defaultReservation = 3L), epoch = 0L).also {
        check(it.result.refusals.isEmpty()) { "the fixture topology booted with refusals: ${it.result.refusals}" }
    }.heddle

/** The farm with web capped at one open lease, so a web waiter can queue while a slot is free. */
internal val cappedWebTopology: Topology =
    Topology("farm", listOf(Topology.Lane("web", Weight.of(3), maxConcurrent = 1L), Topology.Lane("mobile")))

/** A pool on the test scheduler's clock, one cost unit per virtual second. */
internal fun TestScope.pool(slots: Int, engine: LocalHeddle = farm(), clockStart: Long = 0L): FairSharePool =
    FairSharePool(engine, slots, testScheduler.timeSource, 1.seconds, clockStart)

/** The lease of a [SlotOutcome.Granted], failing the test on anything else. */
internal fun SlotOutcome.granted(): Lease = (this as? SlotOutcome.Granted)?.lease ?: fail("expected a grant, was $this")

/** A failure a test rigs, distinct from anything the pool or the engine throws. */
internal class Boom : Exception("boom")
