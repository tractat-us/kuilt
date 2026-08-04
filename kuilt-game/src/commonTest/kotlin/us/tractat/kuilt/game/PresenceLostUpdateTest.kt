package us.tractat.kuilt.game

import us.tractat.kuilt.crdt.EphemeralMap
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the **mechanism** behind #2083 — why the clock read in
 * [GamePresence]'s declaration path must sit inside the Quilter's lock alongside the write.
 *
 * The presence board is an [EphemeralMap], which is *not* dot-based, so nothing is annihilated
 * here: the join keeps the higher clock, and at an equal clock it prefers a value over a
 * departure and otherwise keeps what it already has. Two declarations minted from the **same**
 * snapshot therefore carry the **same** clock, and the second one to arrive is silently dropped —
 * a lost update, not a mutual annihilation.
 *
 * [EphemeralMap]'s own contract says a value-vs-value tie at one replica is "precluded by the
 * monotonic clock". An unlocked read is exactly what makes the precluded case reachable.
 *
 * Deterministic by construction — no threads, no scheduler — so it can never go flaky. It is the
 * companion to [GamePresenceDeclareConcurrencyTest], which drives the same hazard through real
 * threads on a live [GamePresence] and is the gating test; hand-built patches like these bypass
 * any lock, so they can pin the lattice's behaviour but never the guard itself.
 */
class PresenceLostUpdateTest {

    private val replica = ReplicaId("peer-1")

    /**
     * The hazard: two declarations derived from one captured snapshot reuse a clock, and the join
     * keeps whichever landed first — the second declaration is silently dropped.
     */
    @Test
    fun sameSnapshotDeclarationsReuseAClockAndTheSecondIsDropped() {
        val snapshot = EphemeralMap.empty<String>()
        val nextClock = (snapshot.entries[replica]?.clock ?: 0L) + 1L

        val first = snapshot.put(replica, "present", nextClock)
        val second = snapshot.put(replica, "vacate", nextClock)

        assertAll(
            { assertEquals(1L, first.entries[replica]?.clock, "both declarations mint the same clock") },
            { assertEquals(1L, second.entries[replica]?.clock, "both declarations mint the same clock") },
            {
                assertEquals(
                    "present",
                    first.piece(second).entries[replica]?.value,
                    "the equal-clock tie-break keeps the entry already applied — 'vacate' is dropped",
                )
            },
            {
                assertEquals(
                    "vacate",
                    second.piece(first).entries[replica]?.value,
                    "…and symmetrically the other way, so no join order recovers the loser",
                )
            },
        )
    }

    /**
     * The invariant the lock buys: when the second declaration reads the clock the first
     * produced — i.e. the read and the write are one atomic step — the clocks are distinct, the
     * later declaration dominates, and every join order agrees on it.
     */
    @Test
    fun serialisedDeclarationsMintDistinctClocksAndTheLaterOneWins() {
        val snapshot = EphemeralMap.empty<String>()

        val first = snapshot.put(replica, "present", (snapshot.entries[replica]?.clock ?: 0L) + 1L)
        val second = first.put(replica, "vacate", (first.entries[replica]?.clock ?: 0L) + 1L)

        assertAll(
            { assertEquals(2L, second.entries[replica]?.clock, "serialised declarations advance the clock") },
            { assertEquals("vacate", first.piece(second).entries[replica]?.value, "the later declaration wins") },
            { assertEquals("vacate", second.piece(first).entries[replica]?.value, "join order is irrelevant once clocks are distinct") },
        )
    }
}
