package us.tractat.kuilt.crdt

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EphemeralMapTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")
    private val c = ReplicaId("C")

    // ---- CRDT state: put / merge ----

    @Test
    fun emptyMapHasNoEntries() {
        assertTrue(EphemeralMap.empty<String>().entries.isEmpty())
    }

    @Test
    fun putAddsEntry() {
        val m = EphemeralMap.empty<String>().put(a, "cursor", clock = 1L)
        assertEquals("cursor", m.entries[a]?.value)
        assertEquals(1L, m.entries[a]?.clock)
    }

    @Test
    fun laterClockWins_sameReplica() {
        val m1 = EphemeralMap.empty<String>().put(a, "old", clock = 1L)
        val m2 = EphemeralMap.empty<String>().put(a, "new", clock = 2L)
        assertEquals("new", m1.piece(m2).entries[a]?.value)
        assertEquals("new", m2.piece(m1).entries[a]?.value) // commutative
    }

    @Test
    fun earlierClockIsIgnored_sameReplica() {
        val m1 = EphemeralMap.empty<String>().put(a, "new", clock = 5L)
        val m2 = EphemeralMap.empty<String>().put(a, "old", clock = 3L)
        assertEquals("new", m1.piece(m2).entries[a]?.value)
    }

    @Test
    fun replicasAreIndependent() {
        val mA = EphemeralMap.empty<String>().put(a, "alpha", clock = 1L)
        val mB = EphemeralMap.empty<String>().put(b, "beta", clock = 1L)
        val merged = mA.piece(mB)
        assertEquals("alpha", merged.entries[a]?.value)
        assertEquals("beta", merged.entries[b]?.value)
    }

    @Test
    fun unknownReplicaReturnsNull() {
        assertNull(EphemeralMap.empty<String>().entries[a])
    }

    // ---- graceful departure: null value + higher clock ----

    @Test
    fun nullValueSignifiesGracefulDeparture() {
        val present = EphemeralMap.empty<String>().put(a, "here", clock = 1L)
        val departed = present.leave(a, clock = 2L)
        assertNull(departed.entries[a]?.value)
    }

    @Test
    fun departureWithHigherClockWinsOverPresence() {
        val present = EphemeralMap.empty<String>().put(a, "here", clock = 1L)
        val departed = EphemeralMap.empty<String>().leave(a, clock = 2L)
        val merged = present.piece(departed)
        assertNull(merged.entries[a]?.value)
    }

    @Test
    fun presenceWithHigherClockWinsOverStaleDeparture() {
        val departed = EphemeralMap.empty<String>().leave(a, clock = 1L)
        val rejoined = EphemeralMap.empty<String>().put(a, "back", clock = 3L)
        val merged = departed.piece(rejoined)
        assertEquals("back", merged.entries[a]?.value)
    }

    // ---- lattice laws ----

    @Test
    fun pieceIsIdempotent() {
        val m = EphemeralMap.empty<String>().put(a, "x", clock = 1L)
        assertEquals(m, m.piece(m))
    }

    @Test
    fun pieceIsCommutative() {
        val mA = EphemeralMap.empty<String>().put(a, "x", clock = 1L)
        val mB = EphemeralMap.empty<String>().put(b, "y", clock = 2L)
        assertEquals(mA.piece(mB), mB.piece(mA))
    }

    @Test
    fun pieceIsAssociative() {
        val mA = EphemeralMap.empty<String>().put(a, "x", clock = 1L)
        val mB = EphemeralMap.empty<String>().put(b, "y", clock = 2L)
        val mC = EphemeralMap.empty<String>().put(c, "z", clock = 3L)
        assertEquals(mA.piece(mB).piece(mC), mA.piece(mB.piece(mC)))
    }

    // ---- adversarial tie-break: equal clocks, present vs null ----
    // These cases were not covered by the disjoint-replica tests above.
    // A crash-detector tombstone minted at seenClock+1 can collide with a live
    // peer's next heartbeat if both increment from the same base.

    @Test
    fun equalClock_presentBeatsNull_bothMergeOrders() {
        val present = EphemeralMap.empty<String>().put(a, "alive", clock = 3L)
        val departed = EphemeralMap.empty<String>().leave(a, clock = 3L)
        // Both orders must produce the same result: "present" wins.
        val forwardMerge = present.piece(departed)
        val reverseMerge = departed.piece(present)
        assertAll(
            { assertEquals("alive", forwardMerge.entries[a]?.value, "present.piece(departed) must keep presence") },
            { assertEquals("alive", reverseMerge.entries[a]?.value, "departed.piece(present) must keep presence") },
            { assertEquals(forwardMerge, reverseMerge, "piece must be commutative at equal clock") },
        )
    }

    @Test
    fun equalClock_nullVsNull_isNoOp_bothMergeOrders() {
        val d1 = EphemeralMap.empty<String>().leave(a, clock = 2L)
        val d2 = EphemeralMap.empty<String>().leave(a, clock = 2L)
        assertAll(
            { assertNull(d1.piece(d2).entries[a]?.value, "null piece null must stay null") },
            { assertEquals(d1.piece(d2), d2.piece(d1), "null piece null must be commutative") },
        )
    }

    @Test
    fun equalClock_associativity_withConflict() {
        // A = present clock 2, B = departed clock 2, C = unrelated replica
        val present = EphemeralMap.empty<String>().put(a, "here", clock = 2L)
        val departed = EphemeralMap.empty<String>().leave(a, clock = 2L)
        val other = EphemeralMap.empty<String>().put(c, "z", clock = 1L)
        assertEquals(
            present.piece(departed).piece(other),
            present.piece(departed.piece(other)),
            "piece must be associative with a present/null conflict in the group",
        )
    }

    // ---- TTL eviction ----

    @Test
    fun liveEntriesAreVisibleBeforeExpiry() {
        val m = EphemeralMap.empty<String>().put(a, "alive", clock = 1L)
        val receiveTime = mapOf(a to 1000L)
        val live = m.live(receiveTime, now = 1000L, ttlMs = 5000L)
        assertEquals("alive", live[a])
    }

    @Test
    fun expiredEntryIsEvicted() {
        val m = EphemeralMap.empty<String>().put(a, "stale", clock = 1L)
        val receiveTime = mapOf(a to 0L)
        val live = m.live(receiveTime, now = 6000L, ttlMs = 5000L)
        assertFalse(a in live)
    }

    @Test
    fun nullEntryIsNeverVisible_evenIfRecent() {
        val m = EphemeralMap.empty<String>().leave(a, clock = 1L)
        val receiveTime = mapOf(a to 0L)
        val live = m.live(receiveTime, now = 0L, ttlMs = 5000L)
        assertFalse(a in live)
    }

    @Test
    fun entryWithNoReceiveTimeIsConsideredExpired() {
        val m = EphemeralMap.empty<String>().put(a, "unknown", clock = 1L)
        val live = m.live(receiveTime = emptyMap(), now = 0L, ttlMs = 5000L)
        assertFalse(a in live)
    }

    @Test
    fun expiryBoundaryIsExclusive() {
        // exactly at TTL boundary — expired
        val m = EphemeralMap.empty<String>().put(a, "edge", clock = 1L)
        val receiveTime = mapOf(a to 0L)
        assertFalse(a in m.live(receiveTime, now = 5000L, ttlMs = 5000L))
        // just inside TTL — live
        assertTrue(a in m.live(receiveTime, now = 4999L, ttlMs = 5000L))
    }

    @Test
    fun mixedFreshAndStaleEntries() {
        val m = EphemeralMap.empty<String>()
            .put(a, "fresh", clock = 1L)
            .put(b, "stale", clock = 1L)
        val receiveTime = mapOf(a to 5000L, b to 0L)
        val live = m.live(receiveTime, now = 5100L, ttlMs = 5000L)
        assertTrue(a in live)
        assertFalse(b in live)
    }

    // ---- serialization ----

    @Test
    fun roundTripsThroughJson() {
        val m = EphemeralMap.empty<String>()
            .put(a, "cursor", clock = 1L)
            .put(b, "scroll", clock = 2L)
        val ser = EphemeralMap.serializer(String.serializer())
        assertEquals(m, Json.decodeFromString(ser, Json.encodeToString(ser, m)))
    }

    @Test
    fun departureRoundTripsThroughJson() {
        val m = EphemeralMap.empty<String>().leave(a, clock = 3L)
        val ser = EphemeralMap.serializer(String.serializer())
        assertEquals(m, Json.decodeFromString(ser, Json.encodeToString(ser, m)))
    }
}
