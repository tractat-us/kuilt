@file:Suppress("ForbiddenImport") // deliberate: real OS-thread concurrency probe — PeerRoster's read-modify-write is atomic under any test dispatcher, which is exactly what makes a missing lock invisible.

package us.tractat.kuilt.mdns

import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real OS-thread concurrency probe — a lost update needs two announces to overlap on genuinely different threads.
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.runConcurrencyStress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real-threaded probe for [PeerRoster] (#2655).
 *
 * [PeerRoster] holds its lattice in a plain `var`:
 *
 * ```kotlin
 * orSet = orSet.piece { it.add(replicaId, peerId) }
 * _peers.value = orSet.elements
 * ```
 *
 * That is a read-modify-write with **no lock at all**, and a second read of the same `var` on the
 * line under it. Two concurrent [PeerRoster.announce] calls both read the same `orSet`, both build a
 * successor from it, and the later store discards the earlier one — a peer that announced itself is
 * simply gone. This is a different family from the tear-time collapses in the same sweep: there is
 * no latch and no lifecycle here, just an unguarded shared mutable read-modify-write in a module
 * that is not single-threaded.
 *
 * It is a **public** primitive with no in-tree production caller — a consumer wires its own mDNS
 * announce and goodbye observations into it — so its thread-safety is a contract obligation rather
 * than a claim about a shipped code path. Bonjour/NSD callbacks are not promised to arrive on one
 * thread, which is precisely the wiring the KDoc invites.
 *
 * Under virtual time this cannot red: a test dispatcher serialises every call, and a read-modify-
 * write is *correct* whenever nothing runs between the read and the write.
 */
class PeerRosterConcurrencyTest {

    /**
     * Every announce made concurrently must survive, exactly as it would if the same calls were
     * made one after another.
     *
     * Each writer owns a **disjoint** block of ids, so the expected set is total and any shortfall
     * names the specific announces that were lost — not a merge that resolved differently.
     */
    @Test
    fun everyConcurrentAnnounceSurvivesTheOneRacingIt() = runConcurrencyStress { stage ->
        val losses = mutableListOf<String>()
        var completed = 0
        var announcesMade = 0
        repeat(ITERATIONS) { iter ->
            val roster = PeerRoster(ReplicaId("local"))
            stage.at("iter=$iter announce race") { "iter=$iter peers=${roster.peers.value.size}" }
            coroutineScope {
                val ready = CompletableDeferred<Unit>()
                val writers = (0 until WRITERS).map { writer ->
                    async(Dispatchers.Default) {
                        ready.await()
                        repeat(PER_WRITER) { n -> roster.announce(peerId(writer, n)) }
                        PER_WRITER
                    }
                }
                ready.complete(Unit)
                announcesMade += writers.awaitAll().sum()
            }

            // Every writer has been joined, so this read is FINAL — a peer missing here is missing
            // for good, and the discovery layer above will never learn about it from this roster.
            val settled = roster.peers.value
            val missing = expected - settled
            if (missing.isNotEmpty()) {
                losses += "iter=$iter lost=${missing.size}/${expected.size} e.g. ${missing.take(3).map { it.value }}"
            }
            completed++
        }

        // The same announces, made one at a time on one thread. This is what makes the arm above a
        // CONCURRENCY assertion rather than an assertion about `ORSet`: if the lattice itself
        // dropped an add, the sequential roster would be short too and the red above would be
        // blaming the wrong layer.
        val sequential = PeerRoster(ReplicaId("local"))
        (0 until WRITERS).forEach { writer -> repeat(PER_WRITER) { n -> sequential.announce(peerId(writer, n)) } }

        assertAll(
            {
                assertEquals(
                    ITERATIONS,
                    completed,
                    "rig precondition: the race loop must complete every iteration it claims. An arm " +
                        "asserting an ABSENCE has to prove it did the work",
                )
            },
            {
                assertEquals(
                    ITERATIONS * WRITERS * PER_WRITER,
                    announcesMade,
                    "rig precondition: every writer must have made all of its announces. A writer " +
                        "that returned early leaves fewer overlapping read-modify-writes than the " +
                        "arm claims to have driven",
                )
            },
            {
                assertEquals(
                    expected,
                    sequential.peers.value,
                    "rig precondition: the same announces made SEQUENTIALLY must all survive, or the " +
                        "concurrent arm below is measuring the lattice rather than the missing lock",
                )
            },
            {
                assertTrue(
                    WRITERS > 1 && PER_WRITER > 1,
                    "rig precondition: more than one writer, each making more than one announce — " +
                        "a single writer cannot race anything and a single announce each leaves no " +
                        "read-modify-write to interleave with",
                )
            },
            {
                assertEquals(
                    emptyList(),
                    losses.take(MAX_REPORTED_LOSSES),
                    "concurrent announces were lost outright in ${losses.size} of $ITERATIONS " +
                        "iterations (#2655). `orSet = orSet.piece { … }` is an unguarded read-modify-" +
                        "write: two announces read the same lattice, build two successors from it, " +
                        "and the later store discards the earlier — so a peer that announced itself " +
                        "on the network never appears in `peers`, permanently, with nothing left to " +
                        "correct it. mDNS re-announces would eventually paper over it; a goodbye " +
                        "lost the same way would not",
                )
            },
        )
    }

    private fun peerId(writer: Int, n: Int) = PeerId("peer-$writer-$n")

    private val expected: Set<PeerId> =
        (0 until WRITERS).flatMapTo(mutableSetOf()) { w -> (0 until PER_WRITER).map { peerId(w, it) } }

    private companion object {
        /**
         * A **detection floor, not a timing assertion** — the wall-clock cap belongs to
         * `runConcurrencyStress`. Modest, because unlike the tear-time windows in this sweep a lost
         * update needs no nanosecond alignment: any overlap at all loses one of the two writes.
         */
        const val ITERATIONS = 200

        /** Concurrent writers per iteration. */
        const val WRITERS = 4

        /** Announces per writer — each one a fresh read-modify-write of the same `var`. */
        const val PER_WRITER = 250

        /** Cap on the failure message only; `losses.size` still reports the true total. */
        const val MAX_REPORTED_LOSSES = 10
    }
}
