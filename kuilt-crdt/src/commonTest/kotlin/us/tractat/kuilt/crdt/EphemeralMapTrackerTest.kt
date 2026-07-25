package us.tractat.kuilt.crdt

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    // ---- equal-clock tie-break: present-over-null restamps receive time ----

    @Test
    fun equalClock_presentOverNull_refreshesTtl() {
        // Simulates the crash-detector scenario: a departure at clock N arrives
        // first, then a heartbeat from the live peer also at clock N arrives.
        // The heartbeat must win (present beats null) AND re-stamp the receive time.
        var time = 0L
        val t = EphemeralMapTracker<String>(ttlMs = 5000L, clock = { time })
        // Departure arrives at t=0 with clock=3
        t.received(EphemeralMap.empty<String>().leave(a, clock = 3L))
        time = 3000L
        // Live heartbeat arrives at t=3000 with the same clock=3 — present beats null
        t.received(EphemeralMap.empty<String>().put(a, "alive", clock = 3L))
        // Receive time should have been re-stamped to 3000
        time = 7999L // 4999 ms after re-stamp — still live
        assertTrue(a in t.live(), "live peer must remain visible after equal-clock present-over-null merge")
        time = 8000L // 5000 ms after re-stamp — expired
        assertFalse(a in t.live(), "entry must expire exactly at TTL after re-stamp")
    }

    // ---- restart recovery: an expired slot reads as absent (evict-on-read past TTL) ----

    @Test
    fun restartedReplica_becomesVisibleWithinTtl_afterExpiry() {
        // Regression for #1666: a restarted replica (process-local clock from 0) was pinned
        // behind the dead incarnation's higher clock forever, because received() compared the
        // inbound clock against a state that never evicts. Evict-on-read past TTL fixes it.
        var time = 0L
        val t = EphemeralMapTracker<String>(ttlMs = 5000L, clock = { time })
        // Dead incarnation advertises with a HIGH clock, then goes silent.
        t.received(EphemeralMap.empty<String>().put(a, "dead", clock = 100L))
        assertTrue(a in t.live())
        // The peer crashes; its slot ages out past the TTL.
        time = 5000L
        assertFalse(a in t.live(), "crashed peer's slot must expire")
        // The restarted replica re-advertises with a fresh clock from zero (BELOW the dead one).
        t.received(EphemeralMap.empty<String>().put(a, "restarted", clock = 1L))
        // Before the fix this stayed invisible forever (1 < 100). Now the expired slot read as
        // absent, so the lower-clock heartbeat was accepted as fresh.
        assertTrue(a in t.live(), "restarted replica must become visible again within one TTL")
        assertEquals("restarted", t.live()[a])
        // And its TTL now runs from the restart heartbeat, not the dead incarnation's stamp.
        time = 9999L
        assertTrue(a in t.live(), "restart slot still live 4999 ms after its heartbeat")
        time = 10000L
        assertFalse(a in t.live(), "restart slot expires 5000 ms after its heartbeat")
    }

    @Test
    fun nonExpiredLowerClock_isStillDropped() {
        // The evict-on-read path must not weaken the normal stale-drop: a lower-clock delivery
        // for a slot that is still WITHIN its TTL is a stale re-delivery and must be ignored.
        var time = 0L
        val t = EphemeralMapTracker<String>(ttlMs = 5000L, clock = { time })
        t.received(EphemeralMap.empty<String>().put(a, "v100", clock = 100L))
        time = 4999L // still inside the TTL window
        t.received(EphemeralMap.empty<String>().put(a, "v1", clock = 1L))
        assertEquals("v100", t.live()[a], "a within-TTL lower-clock delivery must not overwrite")
    }

    // ---- identical re-delivery must not resurrect an expired slot (#1675) ----

    @Test
    fun identicalRedelivery_doesNotResurrectExpiredSlot() {
        // Regression for #1675 break 1 ("zombie resurrection"): evict-on-read accepted ANY
        // inbound entry for an expired slot as fresh, including a stale re-delivery of the dead
        // incarnation's own frame. A re-delivery that is byte-identical to what we already hold
        // carries no new information and must never re-stamp the TTL.
        var time = 0L
        val t = EphemeralMapTracker<String>(ttlMs = 5000L, clock = { time })
        val frame = EphemeralMap.empty<String>().put(a, "dead", clock = 100L)
        t.received(frame)
        assertTrue(a in t.live())
        // The peer crashes and never restarts; its slot ages out.
        time = 5000L
        assertFalse(a in t.live(), "crashed peer's slot must expire")
        // A relay/anti-entropy round re-delivers the SAME entry after expiry.
        t.received(frame)
        assertFalse(a in t.live(), "an identical re-delivery must not resurrect a dead peer")
    }

    @Test
    fun repeatedIdenticalRedelivery_neverResurrects() {
        // Break 3: repeated post-expiry re-deliveries re-stamped the slot on every round, so the
        // zombie stayed "live" indefinitely rather than for a bounded single TTL.
        var time = 0L
        val t = EphemeralMapTracker<String>(ttlMs = 5000L, clock = { time })
        val frame = EphemeralMap.empty<String>().put(a, "dead", clock = 100L)
        t.received(frame)
        time = 5000L
        repeat(10) {
            t.received(frame)
            time += 1000L
            assertFalse(a in t.live(), "dead peer must stay evicted across repeated re-deliveries")
        }
    }

    @Test
    fun mergedStateRedelivery_evictsSilentPeer() {
        // The shipped-consumer shape: a Quilter-backed presence loop feeds the MERGED map back
        // into received() on every local heartbeat, so a silent peer's unchanged entry is
        // re-delivered every heartbeat interval. Before the guard the silent peer was re-stamped
        // once per interval and could never be TTL-evicted at all.
        var time = 0L
        val t = EphemeralMapTracker<String>(ttlMs = 5000L, clock = { time })
        var selfClock = 1L
        var merged = EphemeralMap.empty<String>()
            .put(a, "self", clock = selfClock)
            .put(b, "peer", clock = 1L) // b heartbeats once, then goes silent
        t.received(merged)
        assertTrue(b in t.live())
        // a heartbeats every ttl/3; each heartbeat re-delivers the merged map, b's slot unchanged.
        repeat(6) {
            time += 5000L / 3
            merged = merged.put(a, "self", clock = ++selfClock)
            t.received(merged)
        }
        assertTrue(a in t.live(), "the heartbeating replica stays live")
        assertFalse(b in t.live(), "a silent replica must be TTL-evicted despite merged re-delivery")
    }

    @Test
    fun restartAfterIdenticalRedelivery_isStillAccepted() {
        // The guard must not weaken #1666 restart recovery: a genuine restart heartbeat differs
        // from the dead entry (per-boot epoch clock, or simply a different counter), so it is
        // still accepted as fresh even after identical re-deliveries have been ignored.
        var time = 0L
        val t = EphemeralMapTracker<String>(ttlMs = 5000L, clock = { time })
        val frame = EphemeralMap.empty<String>().put(a, "dead", clock = 100L)
        t.received(frame)
        time = 5000L
        t.received(frame) // ignored — identical
        assertFalse(a in t.live())
        // Restart: lower counter, but a different entry.
        t.received(EphemeralMap.empty<String>().put(a, "restarted", clock = 1L))
        assertTrue(a in t.live(), "a genuine restart heartbeat must still be accepted")
        assertEquals("restarted", t.live()[a])
    }

    @Test
    fun identicalRedelivery_withinTtl_doesNotExtendTtl() {
        // The pre-expiry path already ignored identical re-deliveries; pin it so the guard's
        // symmetry (before and after expiry, an identical frame is inert) cannot regress.
        var time = 0L
        val t = EphemeralMapTracker<String>(ttlMs = 5000L, clock = { time })
        val frame = EphemeralMap.empty<String>().put(a, "here", clock = 7L)
        t.received(frame)
        time = 4000L
        t.received(frame)
        time = 5000L
        assertFalse(a in t.live(), "TTL must run from the original stamp, not the re-delivery")
    }

    // ---- eviction must never install an entry the lattice orders BELOW the one it replaces ----

    @Test
    fun expiredTombstone_isNotDefeatedByAnOlderPresenceEntry() {
        // #1675 break 2 ("leave() suppression defeated"). The departure tombstone sits at a clock
        // ABOVE the presence entry it replaced, so the lattice already says that presence entry is
        // strictly older. Evicting the expired tombstone and reinstalling the older entry is an
        // ordering INVERSION — it discards causal information the lattice exists to retain, and
        // downgrades the documented permanent departure guarantee to a TTL-bounded one.
        // This case is NOT the ambiguous one: a restart heartbeat and a relayed stale *presence*
        // entry are genuinely indistinguishable, but an entry the tombstone already dominates is
        // provably not news, whoever relayed it.
        var time = 0L
        val t = EphemeralMapTracker<String>(ttlMs = 5000L, clock = { time })
        t.received(EphemeralMap.empty<String>().put(a, "here", clock = 100L))
        t.received(EphemeralMap.empty<String>().leave(a, clock = 101L))
        assertFalse(a in t.live(), "a departed replica is suppressed immediately")

        time = 5000L // the tombstone's slot ages past the TTL
        // A laggard relays A's PRE-departure presence entry — strictly older than the tombstone.
        t.received(EphemeralMap.empty<String>().put(a, "here", clock = 100L))

        assertAll(
            { assertFalse(a in t.live(), "a departed replica must not be resurrected by an older entry") },
            { assertNull(t.snapshot().entries[a]?.value, "the tombstone must survive the merge") },
            { assertEquals(101L, t.snapshot().entries[a]?.clock, "…at its own clock, undisturbed") },
        )
    }

    @Test
    fun expiredPresence_isNotDisplacedByAnOlderTombstone() {
        // #1675, the mirror case. Eviction must never install an entry the lattice orders BELOW
        // the one it replaced: a relayed pre-presence departure (clock 50 < 100) that displaces
        // the presence entry at clock 100 re-opens the slot, and a later relay of that very
        // presence entry then *dominates* the installed tombstone and resurrects the dead peer —
        // a two-frame zombie the identical-re-delivery guard cannot see, because neither frame is
        // identical to what it lands on.
        var time = 0L
        val t = EphemeralMapTracker<String>(ttlMs = 5000L, clock = { time })
        val presenceFrame = EphemeralMap.empty<String>().put(a, "here", clock = 100L)
        t.received(presenceFrame)
        time = 5000L
        t.received(EphemeralMap.empty<String>().leave(a, clock = 50L)) // stale, strictly older
        val clockAfterStaleDeparture = t.snapshot().entries[a]?.clock
        t.received(presenceFrame) // …and now the very frame that tombstone is older than

        assertAll(
            { assertEquals(100L, clockAfterStaleDeparture, "eviction must not install an older entry") },
            { assertFalse(a in t.live(), "the dead peer must stay dead across the pair of stale relays") },
        )
    }

    @Test
    fun equalClock_nullOverPresent_doesNotResetReceiveTime() {
        // A same-clock departure arriving AFTER a presence entry must lose (present wins),
        // and must NOT re-stamp the receive time.
        var time = 0L
        val t = EphemeralMapTracker<String>(ttlMs = 5000L, clock = { time })
        // Presence at t=0 with clock=3
        t.received(EphemeralMap.empty<String>().put(a, "alive", clock = 3L))
        time = 3000L
        // Stale departure with same clock — must not evict nor reset the timer
        t.received(EphemeralMap.empty<String>().leave(a, clock = 3L))
        assertTrue(a in t.live(), "presence must survive same-clock departure arrival")
        time = 4999L // still within original TTL window
        assertTrue(a in t.live(), "receive time must not have been reset by the losing departure")
        time = 5000L // exactly TTL from original stamp at t=0
        assertFalse(a in t.live(), "entry must expire relative to original presence stamp")
    }
}
