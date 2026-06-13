package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [EphemeralMapTracker]: the stateful wrapper that stamps receive
 * times and drives TTL eviction with an injectable clock.
 */
class EphemeralMapTrackerTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    private fun tracker(ttlMs: Long = 5000L, now: Long = 0L): EphemeralMapTracker<String> {
        var fakeNow = now
        return EphemeralMapTracker(ttlMs = ttlMs, clock = { fakeNow })
    }

    // ---- basic publish / live ----

    @Test
    fun publishedEntryIsLive() {
        var time = 0L
        val t = EphemeralMapTracker<String>(ttlMs = 5000L, clock = { time })
        t.received(EphemeralMap.empty<String>().put(a, "present", clock = 1L))
        assertTrue(a in t.live())
        assertEquals("present", t.live()[a])
    }

    @Test
    fun entryExpires_afterTtl() {
        var time = 0L
        val t = EphemeralMapTracker<String>(ttlMs = 5000L, clock = { time })
        t.received(EphemeralMap.empty<String>().put(a, "here", clock = 1L))
        time = 5000L // exactly at TTL — expired
        assertFalse(a in t.live())
    }

    @Test
    fun entryStillLive_justInsideTtl() {
        var time = 0L
        val t = EphemeralMapTracker<String>(ttlMs = 5000L, clock = { time })
        t.received(EphemeralMap.empty<String>().put(a, "here", clock = 1L))
        time = 4999L
        assertTrue(a in t.live())
    }

    // ---- heartbeat refreshes TTL ----

    @Test
    fun heartbeatResetsExpiry() {
        var time = 0L
        val t = EphemeralMapTracker<String>(ttlMs = 5000L, clock = { time })
        t.received(EphemeralMap.empty<String>().put(a, "here", clock = 1L))
        time = 4000L
        // heartbeat with higher clock — receive time resets to 4000
        t.received(EphemeralMap.empty<String>().put(a, "here", clock = 2L))
        time = 8999L // 4999 ms after heartbeat — still live
        assertTrue(a in t.live())
        time = 9000L // 5000 ms after heartbeat — expired
        assertFalse(a in t.live())
    }

    // ---- graceful departure ----

    @Test
    fun nullStateHidesEntry_evenIfRecent() {
        var time = 0L
        val t = EphemeralMapTracker<String>(ttlMs = 5000L, clock = { time })
        t.received(EphemeralMap.empty<String>().put(a, "here", clock = 1L))
        t.received(EphemeralMap.empty<String>().leave(a, clock = 2L))
        assertFalse(a in t.live())
    }

    // ---- multiple replicas ----

    @Test
    fun independentTtlPerReplica() {
        var time = 0L
        val t = EphemeralMapTracker<String>(ttlMs = 5000L, clock = { time })
        t.received(EphemeralMap.empty<String>().put(a, "early", clock = 1L))
        time = 3000L
        t.received(EphemeralMap.empty<String>().put(b, "late", clock = 1L))
        time = 5500L // a expired (5500 ms since t=0), b still live (2500 ms since t=3000)
        assertFalse(a in t.live())
        assertTrue(b in t.live())
    }

    // ---- stale update does not reset receive time ----

    @Test
    fun olderClockDoesNotUpdateReceiveTime() {
        var time = 0L
        val t = EphemeralMapTracker<String>(ttlMs = 5000L, clock = { time })
        // First update at t=0 with clock=5
        t.received(EphemeralMap.empty<String>().put(a, "v5", clock = 5L))
        time = 4500L
        // Stale update with lower clock — CRDT will keep clock=5; receive time must NOT reset
        t.received(EphemeralMap.empty<String>().put(a, "v3", clock = 3L))
        time = 5500L // 5500ms after t=0 — past TTL, because receive time wasn't reset
        assertFalse(a in t.live())
    }
}
