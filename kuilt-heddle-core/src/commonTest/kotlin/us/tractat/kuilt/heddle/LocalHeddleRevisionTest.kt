package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [LocalHeddle.revision] moves on every call that can change the engine, stays put on every read
 * and on a call that throws, and a restored engine's is above every one the persisted engine had.
 */
class LocalHeddleRevisionTest {
    private val farm = draft("farm") {
        group("web", Weight.of(3)) { lane("builds") }
        group("mobile") { lane("builds") }
        group("forks") { template() }
    }
    private val web = farm.groupId("web", "builds")
    private val mobile = farm.groupId("mobile", "builds")
    private val both = setOf(web, mobile)
    private val policy = LocalPolicy(defaultReservation = 3L)

    /** web drops to two parts: a reload with something in it to apply. */
    private val reweighted = draft("farm") {
        group("web", Weight.of(2)) { lane("builds") }
        group("mobile") { lane("builds") }
        group("forks") { template() }
    }

    /** An engine with supply, an attached fork and one open lease, so every mutating call has something to act on. */
    private class Fixture(val heddle: LocalHeddle, val lease: LeaseId)

    private fun fixture(): Fixture {
        val heddle = LocalHeddle.fromTopology(farm, policy, epoch = 0L).heddle
        heddle.reconcileCapacity(totalSlots = 4L)
        check(heddle.attach(listOf("forks"), "ana", generation = 1L).refusals.isEmpty())
        val lease = assertIs<AcquireOutcome.Acquired>(heddle.acquire(both, both, startedAt = 0L)).lease.id
        return Fixture(heddle, lease)
    }

    /** Every public call that can change a [LocalHeddle], each run once on a fresh [fixture]. */
    private val mutators: List<Pair<String, (Fixture) -> Unit>> = listOf(
        "acquire" to { f -> f.heddle.acquire(both, both, startedAt = 1L) },
        "admit" to { f -> f.heddle.admit(mobile, waiting = both, startedAt = 1L) },
        "extend" to { f -> f.heddle.extend(f.lease, amount = 3L, waiting = both) },
        "complete" to { f -> f.heddle.complete(f.lease, actualCost = 3L, waiting = both) },
        "cancel" to { f -> f.heddle.cancel(f.lease) },
        "reconcileCapacity" to { f -> f.heddle.reconcileCapacity(totalSlots = 8L) },
        "apply" to { f -> f.heddle.apply(reweighted, generation = 2L) },
        "attach" to { f -> f.heddle.attach(listOf("forks"), "ben", generation = 2L) },
        "detach" to { f -> f.heddle.detach(listOf("forks"), "ana") },
    )

    @Test
    fun everyMutatingCallMovesTheRevision() {
        assertAll(
            *mutators.map { (name, call) ->
                {
                    val f = fixture()
                    val before = f.heddle.revision
                    call(f)
                    val after = f.heddle.revision
                    assertTrue(after > before, "$name moved the revision: $before -> $after")
                    assertEquals(before.epoch, after.epoch, "$name kept the epoch")
                }
            }.toTypedArray(),
        )
    }

    @Test
    fun readsLeaveTheRevisionWhereItIs() {
        val f = fixture()
        val before = f.heddle.revision
        f.heddle.snapshot()
        f.heddle.persist()
        f.heddle.previewPicks(mapOf(web to 2, mobile to 2), both, listOf(3L, 3L))
        f.heddle.previewPicks(mapOf(web to listOf(3L), mobile to listOf(3L)), both)
        f.heddle.explain(mobile, waiting = both, eligible = both)
        f.heddle.entitlement(web, waiting = both, totalSlots = 4L)
        f.heddle.reconcileLeases(live = setOf(f.lease), now = 5L)
        assertEquals(before, f.heddle.revision)
    }

    @Test
    fun aCallThatThrowsLeavesTheRevisionWhereItIs() {
        val f = fixture()
        val before = f.heddle.revision
        assertFailsWith<IllegalArgumentException> { f.heddle.acquire(both, both, startedAt = 1L, reservation = 0L) }
        assertEquals(before, f.heddle.revision)
    }

    @Test
    fun aRestoredEngineIsAboveEveryRevisionThePersistedOneHad() {
        val f = fixture()
        val persisted = f.heddle.revision
        val restored = LocalHeddle.restore(f.heddle.persist(), policy, epoch = 1L)
        assertAll(
            { assertTrue(restored.revision > persisted, "${restored.revision} is above $persisted") },
            { assertEquals(1L, restored.revision.epoch) },
            // The rig: the persisted engine had counted past the restored one, so only the epoch orders them.
            { assertTrue(persisted.count > restored.revision.count, "$persisted counted past ${restored.revision}") },
        )
    }
}
