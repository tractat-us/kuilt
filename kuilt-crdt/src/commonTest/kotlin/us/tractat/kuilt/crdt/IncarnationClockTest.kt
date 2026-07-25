package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for [IncarnationClock]: the epoch-over-counter packing that makes a restarted replica
 * out-clock its own dead incarnation in an [EphemeralMap] slot.
 */
class IncarnationClockTest {

    @Test
    fun baseStartsCounterAtZero() {
        assertEquals(0L, IncarnationClock.base(0L))
        assertEquals(1L shl 32, IncarnationClock.base(1L))
    }

    @Test
    fun higherEpochDominatesEverythingALowerEpochCanReach() {
        // The whole point: no number of publishes in boot N can reach boot N+1's first clock.
        val boot = IncarnationClock.base(7L)
        val nextBootFirst = IncarnationClock.next(IncarnationClock.base(8L))
        val bootCeiling = IncarnationClock.base(8L) - 1 // the last clock boot 7 could ever reach
        assertTrue(bootCeiling >= IncarnationClock.next(boot), "the counter must have room to advance")
        assertTrue(nextBootFirst > bootCeiling, "a restart must out-clock any clock its predecessor could reach")
    }

    @Test
    fun nextAdvancesWithinTheEpoch() {
        var clock = IncarnationClock.base(3L)
        val epochBits = clock shr IncarnationClock.COUNTER_BITS
        repeat(1000) {
            val previous = clock
            clock = IncarnationClock.next(clock)
            assertTrue(clock > previous, "the clock must strictly increase")
        }
        assertEquals(epochBits, clock shr IncarnationClock.COUNTER_BITS, "advancing must not change the epoch")
    }

    @Test
    fun nextFailsRatherThanCarryingIntoTheEpochBits() {
        // #1675 (c): a silent carry would borrow the next boot's epoch, so the next restart would
        // stop dominating and quietly regress to TTL-bounded recovery. Fail loudly instead.
        val lastOfEpoch = IncarnationClock.base(5L) - 1
        assertFailsWith<IllegalStateException> { IncarnationClock.next(lastOfEpoch) }
    }

    @Test
    fun baseRejectsAnEpochThatWouldNotFit() {
        assertFailsWith<IllegalArgumentException> { IncarnationClock.base(-1L) }
        assertFailsWith<IllegalArgumentException> { IncarnationClock.base(1L shl 31) }
    }

    @Test
    fun aRestartOutClocksItsDeadIncarnationInAnEphemeralMap() {
        // End-to-end: the packing is what makes put() take the restart's entry without waiting
        // for TTL eviction, even though the restart's per-boot counter is far lower.
        val a = ReplicaId("A")
        var dead = IncarnationClock.base(1L)
        var map = EphemeralMap.empty<String>()
        repeat(500) {
            dead = IncarnationClock.next(dead)
            map = map.put(a, "boot-1", dead)
        }
        val restart = IncarnationClock.next(IncarnationClock.base(2L))
        map = map.put(a, "boot-2", restart)
        assertEquals("boot-2", map.entries[a]?.value, "the restart must win by clock alone")
    }
}
