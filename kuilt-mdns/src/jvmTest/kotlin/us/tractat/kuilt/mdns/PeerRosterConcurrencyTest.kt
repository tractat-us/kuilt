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
import java.util.concurrent.CountDownLatch
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

    /**
     * The same for [PeerRoster.goodbye], because it is the one that does not heal.
     *
     * A lost announce is papered over by mDNS's next re-announce — the peer is still on the network
     * and still shouting. A lost goodbye is not: the peer has left, it will send nothing more, and
     * the roster goes on naming it until something else evicts it. So this arm is not a symmetry
     * exercise; it is the more consequential half, and leaving it to be inferred from the announce
     * arm would leave the second `withLock` unpinned.
     */
    @Test
    fun everyConcurrentGoodbyeSurvivesTheOneRacingIt() = runConcurrencyStress { stage ->
        val survivors = mutableListOf<String>()
        var completed = 0
        var goodbyesMade = 0
        repeat(ITERATIONS) { iter ->
            val roster = PeerRoster(ReplicaId("local"))
            // Populated one at a time: the arm under test is the concurrent REMOVAL, and seeding it
            // concurrently would let an announce race decide the outcome instead.
            expected.forEach { roster.announce(it) }
            val seeded = roster.peers.value

            stage.at("iter=$iter goodbye race") { "iter=$iter peers=${roster.peers.value.size}" }
            coroutineScope {
                val ready = CompletableDeferred<Unit>()
                val writers = (0 until WRITERS).map { writer ->
                    async(Dispatchers.Default) {
                        ready.await()
                        repeat(PER_WRITER) { n -> roster.goodbye(peerId(writer, n)) }
                        PER_WRITER
                    }
                }
                ready.complete(Unit)
                goodbyesMade += writers.awaitAll().sum()
            }

            val settled = roster.peers.value
            if (settled.isNotEmpty()) {
                survivors += "iter=$iter kept=${settled.size}/${seeded.size} e.g. ${settled.take(3).map { it.value }}"
            }
            completed++
        }

        val sequential = PeerRoster(ReplicaId("local"))
        expected.forEach { sequential.announce(it) }
        expected.forEach { sequential.goodbye(it) }

        assertAll(
            {
                assertEquals(
                    ITERATIONS,
                    completed,
                    "rig precondition: the race loop must complete every iteration it claims",
                )
            },
            {
                assertEquals(
                    ITERATIONS * WRITERS * PER_WRITER,
                    goodbyesMade,
                    "rig precondition: every writer must have made all of its goodbyes",
                )
            },
            {
                assertEquals(
                    emptySet<PeerId>(),
                    sequential.peers.value,
                    "rig precondition: the same goodbyes made SEQUENTIALLY must clear the roster, or " +
                        "the concurrent arm below is measuring the lattice rather than the missing lock",
                )
            },
            {
                assertEquals(
                    expected,
                    roster().also { r -> expected.forEach { r.announce(it) } }.peers.value,
                    "rig precondition: the seeding step must actually POPULATE the roster. Against an " +
                        "empty roster \"nothing survived the goodbyes\" holds by never having held " +
                        "anything — the fixture configured into the one state at which it cannot fail",
                )
            },
            {
                assertEquals(
                    emptyList(),
                    survivors.take(MAX_REPORTED_LOSSES),
                    "concurrent goodbyes were lost outright in ${survivors.size} of $ITERATIONS " +
                        "iterations (#2655), leaving peers in the roster that had said goodbye. " +
                        "Unlike a lost announce nothing repairs this: the peer is gone from the " +
                        "network and will never announce again, so the roster names it until " +
                        "something outside `PeerRoster` evicts it",
                )
            },
        )
    }

    /**
     * Two replicas merging each other, concurrently and repeatedly. The claim is that this
     * **terminates**.
     *
     * [PeerRoster.merge] snapshots the other replica under *its* lock and applies under its own, as
     * two sequential acquisitions. Written the obvious way instead — reading `other`'s lattice from
     * inside our own critical section — the two threads below take the same pair of locks in
     * opposite orders and the pair deadlocks.
     *
     * **Plain daemon threads with a bounded `join`, not the coroutine harness.** That is not a
     * stylistic choice, it is the only shape that fails *legibly*, and it was measured: written
     * first inside `runConcurrencyStress` under `Dispatchers.Default`, the nested-lock mutation did
     * not produce the harness's named failure and dumps — it hung `:kuilt-mdns:jvmTest` outright
     * past 11 minutes and had to be killed. `withTimeout` cancels **coroutines**, and a thread
     * blocked on a native monitor has no suspension point at which cancellation could land, so the
     * cap never fires and no result XML is ever written — the #1135 shape this repo exists to keep
     * out of CI. `Thread.join(millis)` returns whether or not the thread is stuck, which turns the
     * deadlock into an assertion; `isDaemon` keeps a stuck pair from holding the JVM open.
     *
     * Convergence is counted alongside, so the arm is not purely a liveness one — a run that never
     * completed a merge round-trip proves nothing about merging.
     */
    @Test
    fun mutuallyMergingReplicasConvergeWithoutDeadlocking() {
        val fromA = (0 until PER_WRITER).map { PeerId("a-$it") }.toSet()
        val fromB = (0 until PER_WRITER).map { PeerId("b-$it") }.toSet()
        val union = fromA + fromB
        val wedged = mutableListOf<String>()
        val divergent = mutableListOf<String>()
        var attempted = 0
        var converged = 0
        for (iter in 0 until MERGE_ITERATIONS) {
            val a = PeerRoster(ReplicaId("A"))
            val b = PeerRoster(ReplicaId("B"))
            fromA.forEach { a.announce(it) }
            fromB.forEach { b.announce(it) }
            attempted++

            val start = CountDownLatch(1)
            val toA = mergeThread("merge-into-A-$iter", start) { repeat(MERGE_ROUNDS) { a.merge(b) } }
            val toB = mergeThread("merge-into-B-$iter", start) { repeat(MERGE_ROUNDS) { b.merge(a) } }
            start.countDown()
            toA.join(MERGE_BUDGET_MILLIS)
            toB.join(MERGE_BUDGET_MILLIS)

            if (toA.isAlive || toB.isAlive) {
                wedged += "iter=$iter stuck: A-direction=${toA.isAlive} B-direction=${toB.isAlive}"
                // Stop attempting. The stuck pair still holds both rosters' locks, so the settling
                // merges below would block this thread too — and every later iteration would cost
                // another full budget for a verdict already reached.
                break
            }
            // One more merge each way, unraced, so convergence is judged on a settled pair rather
            // than on whichever interleaving the race happened to end in.
            a.merge(b)
            b.merge(a)
            if (a.peers.value != union || b.peers.value != union) {
                divergent += "iter=$iter a=${a.peers.value.size} b=${b.peers.value.size} want=${union.size}"
            } else {
                converged++
            }
        }

        assertAll(
            {
                assertEquals(
                    emptyList(),
                    wedged.take(MAX_REPORTED_LOSSES),
                    "two replicas merging each other DEADLOCKED (#2655), after $attempted of " +
                        "$MERGE_ITERATIONS attempted iterations and $converged converged ones. " +
                        "`merge` must snapshot the other replica under its lock and apply under its " +
                        "own as two SEQUENTIAL acquisitions; nested, the two directions take the " +
                        "same pair of locks in opposite orders — and a mutual merge is the ordinary " +
                        "shape for two discovery nodes, not an exotic one",
                )
            },
            {
                assertEquals(
                    emptyList(),
                    divergent.take(MAX_REPORTED_LOSSES),
                    "replicas failed to converge on the union after merging both ways",
                )
            },
            {
                assertTrue(
                    converged >= 1,
                    "rig precondition: at least one iteration must have completed the whole mutual " +
                        "merge and agreed on the union; $converged did. A run that wedged " +
                        "immediately, or never merged anything, says nothing about merging",
                )
            },
        )
    }

    /** A started daemon thread that runs [body] once [start] is released. */
    private fun mergeThread(name: String, start: CountDownLatch, body: () -> Unit): Thread =
        Thread({ start.await(); body() }, name).apply { isDaemon = true; this.start() }

    private fun roster() = PeerRoster(ReplicaId("local"))

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

        /**
         * Iterations of the mutual-merge arm. Far fewer, because that arm fails by **wedging**, not
         * by counting: one interleaving that takes both locks in opposite orders is enough, and the
         * remaining iterations only buy chances at hitting it.
         */
        const val MERGE_ITERATIONS = 50

        /** Merges each direction attempts per iteration — the overlap this arm needs. */
        const val MERGE_ROUNDS = 200

        /**
         * How long a direction gets to finish its [MERGE_ROUNDS] before it counts as wedged.
         *
         * A **real-time** ceiling on a **real blocking** join, which is what makes it the right unit
         * here — unlike a `runTest` ceiling, it is not measuring a virtual-time trajectory through a
         * loaded box. Generous: healthy, both directions finish in single-digit milliseconds, so
         * this is three orders of magnitude of slack and still bounds the deadlock.
         */
        const val MERGE_BUDGET_MILLIS = 10_000L

        /** Cap on the failure message only; `losses.size` still reports the true total. */
        const val MAX_REPORTED_LOSSES = 10
    }
}
