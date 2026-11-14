package us.tractat.kuilt.heddle

import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * A call that throws leaves the engine exactly as it was: what it reads, what its snapshot encodes,
 * and what it does next.
 *
 * The failure is injected through [LocalHeddle.afterPickForTest], at the nested level of a two-level
 * descent: by then the root's pick has run, so the wake memory has moved and the root's delegation
 * has landed on the working ledger. That is the shape of the overflow that wedged 0.7.4, where the
 * wake offsets a failed pick had written stayed behind and every later pick at the parent hit them
 * again, through a snapshot and a restore too.
 */
class LocalHeddleAtomicityTest {

    private val topology = draft("atomic") {
        lane("a", Weight.of(3))
        lane("b", Weight.ONE)
        group("g", Weight.of(2)) {
            lane("x", Weight.ONE)
            lane("y", Weight.of(5, 3))
        }
    }
    private val a = topology.groupId("a")
    private val b = topology.groupId("b")
    private val g = topology.groupId("g")
    private val x = topology.groupId("g", "x")
    private val y = topology.groupId("g", "y")

    private fun engine() = LocalHeddle.fromTopology(topology, LocalPolicy(defaultReservation = 1L), epoch = 1L)
        .apply { reconcileCapacity(1L) }

    /** A history in which b and the nested lanes idle and wake, so the wake memory is not empty. */
    private fun LocalHeddle.warmUp() {
        val sets = listOf(setOf(a, b), setOf(a), setOf(a, x, y), setOf(b, x), setOf(a, b, y))
        repeat(20) { i ->
            val waiting = sets[i % sets.size]
            val lease = (acquire(waiting, waiting, startedAt = i.toLong()) as AcquireOutcome.Acquired).lease
            complete(lease.id, actualCost = 1L + i % 4)
        }
    }

    private fun LocalHeddle.bytes(): ByteArray = Cbor.encodeToByteArray(LocalHeddleSnapshot.serializer(), persist())

    @Test
    fun anAcquireThatThrowsPartWayChangesNothing() {
        val failing = engine().apply { warmUp() }
        val twin = engine().apply { warmUp() }
        val stateBefore = failing.snapshot()
        val bytesBefore = failing.bytes()

        // Only the nested group's eligible lanes wait, so the descent must go root → g → leaf.
        val waiting = setOf(x, y)
        var fired = 0
        var stateMidCall: LocalHeddleState? = null
        failing.afterPickForTest = { group ->
            if (group == g) {
                fired++
                stateMidCall = failing.snapshot()
                throw IllegalStateException("injected after the pick at ${group.value}")
            }
        }
        assertFailsWith<IllegalStateException> { failing.acquire(waiting, waiting, startedAt = 100L) }
        failing.afterPickForTest = {}

        val next = failing.acquire(waiting, waiting, startedAt = 101L)
        val twinNext = twin.acquire(waiting, waiting, startedAt = 101L)
        assertAll(
            { assertEquals(1, fired, "the injected failure fired once") },
            // The rig: part-way through, the call had changed the engine, so an unchanged engine
            // afterwards is the rollback's doing and not a call that never got going.
            { assertNotEquals(stateBefore, stateMidCall, "the failing call had moved the engine before it threw") },
            { assertEquals(stateBefore, failing.snapshot(), "the state is as it was before the call") },
            { assertContentEquals(bytesBefore, failing.bytes(), "the snapshot encodes as it did before the call") },
            { assertEquals(twinNext, next, "the next call does what it would have done had the failed one never run") },
            { assertContentEquals(twin.bytes(), failing.bytes(), "and leaves the engine where the twin's is") },
        )
    }
}
