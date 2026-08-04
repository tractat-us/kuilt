@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Real-threading regression harness for #2083 — a concurrent [GamePresence] declaration must
 * never be silently dropped, and a compound `read-decide-declare` must never act on a stale read.
 *
 * **Why real threads.** Every `declare*` entry point is synchronous and never suspends, so two
 * calls cannot interleave under a virtual-time dispatcher however the scheduler is driven: the
 * slot read, the clock read and the write are one uninterrupted run of the calling thread. The
 * defect exists only *between threads*, so only threads can pin it. Nothing here depends on a
 * sleep or on wall-clock timing — the threads race, and the assertions are on the converged slot
 * afterwards, so both tests are order-independent even though the interleaving is not.
 *
 * The dispatcher stays a [StandardTestDispatcher]: the presence Quilter's background coroutines
 * are still virtual-time-driven and are simply never advanced here. Both tests assert on the
 * *synchronous* result of the declaration — this replica's own slot — not on anything the
 * background loops do. That is also why they read [GamePresence.selfSlot] rather than
 * [GamePresence.spectatorsClosed]: the public flows are `stateIn` collectors that have not run.
 *
 * The lattice mechanism underneath is pinned separately, and deterministically, by
 * [PresenceLostUpdateTest].
 */
class GamePresenceDeclareConcurrencyTest {

    /**
     * Each declaration must advance this replica's slot clock by exactly one, so `N` concurrent
     * declarations leave the clock at `N`.
     *
     * That equality *is* the no-lost-update property. [us.tractat.kuilt.crdt.EphemeralMap]'s
     * join breaks a same-replica tie by keeping the entry already applied, so two declarations
     * that read the same clock `c` and both publish at `c + 1` converge to one survivor and the
     * clock lands short — every unit the clock is short is one declaration the board never saw.
     */
    @Test
    fun concurrentDeclarationsEachAdvanceTheClock() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val seam = InMemoryLoom().host(Pattern("presence-declare-race"))
        val presence = GamePresence(seam, backgroundScope, expectVirtualTime = true)

        val startLine = CyclicBarrier(DECLARE_THREADS)
        (0 until DECLARE_THREADS).map { t ->
            thread(name = "declare-$t") {
                startLine.await()
                // A peer really does toggle its slot: leave publishes `vacate`, a rejoin
                // republishes `present`. Alternating keeps the values distinct so a dropped
                // declaration is a dropped *signal*, not merely a dropped clock tick.
                repeat(DECLARES_PER_THREAD) { i ->
                    if (i % 2 == 0) presence.declarePresent() else presence.declareVacate()
                }
            }
        }.forEach { it.join() }

        val expected = (DECLARE_THREADS * DECLARES_PER_THREAD).toLong()
        val clock = presence.selfSlot()?.clock
        assertEquals(
            expected,
            clock,
            "declarations were silently dropped — the slot clock reached $clock after $expected " +
                "declarations, so ${expected - (clock ?: 0L)} never landed on the board",
        )
    }

    /**
     * A compound `read-decide-declare` must decide on the state it publishes against, so the two
     * monotone host signals — admission-closed and spectators-closed — can never erase each other.
     *
     * [GamePresence.declareSpectatorsClosed] and [GamePresence.declareAdmissionClosed] both run
     * on the host, from two independent coroutines ([launchSpectatorManagement] and
     * [VoterLivenessMonitor]'s eviction loop), and both rewrite the *same* host slot from a value
     * they read first. Either serial order preserves both signals. Any interleaving loses one:
     * the loser publishes a value computed before the winner's write, so a signal its own KDoc
     * calls monotone — "once published it is never retracted" — is retracted.
     *
     * Guarding only the write half is not enough, which is the whole point of #2083: the caller's
     * read stays outside, the two writes then get distinct clocks, and the later one simply
     * overwrites the signal the earlier one published.
     */
    @Test
    fun racingHostSignalsNeverEraseEachOther() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val voters = setOf(NodeId("peer-1"), NodeId("peer-2"))
        val erased = mutableListOf<String>()

        repeat(SIGNAL_RACE_ROUNDS) { round ->
            val seam = InMemoryLoom().host(Pattern("presence-signal-race-$round"))
            val presence = GamePresence(seam, backgroundScope, expectVirtualTime = true)
            presence.declareHost()

            val startLine = CyclicBarrier(SIGNAL_RACERS * 2)
            (0 until SIGNAL_RACERS).flatMap { i ->
                listOf(
                    thread(name = "spectators-closed-$round-$i") {
                        startLine.await()
                        presence.declareSpectatorsClosed()
                    },
                    thread(name = "admission-closed-$round-$i") {
                        startLine.await()
                        presence.declareAdmissionClosed(voters)
                    },
                )
            }.forEach { it.join() }

            // The wire values are asserted literally rather than through the (private) marker
            // constants: the point of the test is that both published signals survive on the
            // slot, and the encoding is what a peer's Quilter actually sees.
            val slot = presence.selfSlot()?.value
            val survived = slot != null && slot.startsWith("admission-closed:") && slot.endsWith(":sc")
            if (!survived) erased += "round $round: $slot"
        }

        assertEquals(
            emptyList(),
            erased,
            "a monotone host signal was retracted in ${erased.size} of $SIGNAL_RACE_ROUNDS rounds — " +
                "the surviving slot value is missing the admission-closed prefix or the ':sc' suffix: " +
                "${erased.take(5)}",
        )
    }

    private companion object {
        /**
         * Enough concurrency to make a collision overwhelmingly likely without making the
         * Quilter's pending-delta buffer (one slot snapshot per declaration) expensive.
         */
        const val DECLARE_THREADS = 6
        const val DECLARES_PER_THREAD = 200

        /**
         * The compound race is one-shot per round — both signals are monotone, so a round's
         * window cannot be re-armed on the same board. Rounds are cheap (a fresh in-memory loom,
         * no virtual time advanced) and each is an independent chance to catch the interleaving.
         */
        const val SIGNAL_RACE_ROUNDS = 200
        const val SIGNAL_RACERS = 3
    }
}
