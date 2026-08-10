package us.tractat.kuilt.crdt

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Sequence threading for [Rga] (#2193, Phase 3B): an append may hand its materialized
 * [Rga.sequence] to the state it produces, so an append-then-read loop stops paying a full
 * `computeSequence()` per turn.
 *
 * Two halves, and the **second is the one that is usually skipped**:
 *
 * 1. **The guard is sound.** Threading is valid only when `after` is the last element of the
 *    *full* sequence, tombstones included; every other position must fall through to a
 *    recompute. The oracle is a cache-free reconstruction of the same value —
 *    `Rga.fromOps(ops, lamport, compactedBelow)` — because [Rga.equals] compares `ops` and
 *    [Rga.compactedBelow] only, so a state carrying a *wrong* threaded order still compares
 *    **equal** to a correct one. No equality assertion can see this class of bug; only
 *    comparing the orders can.
 * 2. **The guard actually fires.** A guard that is correct but always falls through to `null`
 *    passes every case above while delivering exactly zero. The whole optimisation depends on
 *    a warmth-propagation chain — [Rga.removeFirst] / [Rga.removeAt] / [Rga.insertAt] force
 *    the lazy anyway and hand the result on — so a break anywhere in that chain is a silent
 *    no-op. [theExporterSteadyStateRecomputesTheOrderExactlyOnce] pins it by counting
 *    recomputations over a run shaped like the real consumer's, and by asserting the *identity*
 *    of the list [Rga.sequence] hands back. Identity is a stronger and cheaper probe than a
 *    counter or a wall-clock ratio: `assertSame(threadedSequence, sequence)` is deterministic,
 *    host-load-independent, and needs no instrumentation in production code at all.
 */
class RgaSequenceThreadingTest {

    private val a = ReplicaId("a")
    private val peer = ReplicaId("peer")

    /**
     * The same value rebuilt with no cache at all: the order `computeSequence` would produce
     * for a replica that received these ops over the wire. Anything a threaded state disagrees
     * with this about is a divergence between two replicas holding one state.
     */
    private fun <V> oracle(state: Rga<V>): Rga<V> =
        Rga.fromOps(state.ops, state.lamport, state.compactedBelow)

    /**
     * `x -> y -> z` from one author, with `x` locally evicted and `z` tombstoned by a remote
     * `Remove` — the shape `WarpLogRecordExporter` reaches after absorbing a peer's removal.
     *
     * The `removeAt` is what **warms** the chain (it forces the lazy and hands the list on), so
     * [state] is the dangerous one: it holds a materialized order *and* its last visible
     * element ([y]) is no longer its last full-sequence element ([z]).
     */
    private class TrailingTombstoneLog(val state: Rga<String>, val x: RgaId, val y: RgaId, val z: RgaId)

    private fun warmLogWithATrailingTombstone(): TrailingTombstoneLog {
        val (s1, x) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val (s2, y) = s1.insertAfter(a, x.id, "y")
        val (s3, z) = s2.insertAfter(a, y.id, "z")
        val (evicted, _) = requireNotNull(s3.removeAt(0)) // tombstones x; warms the chain
        return TrailingTombstoneLog(evicted.apply(RgaOp.Remove<String>(z.id)), x.id, y.id, z.id)
    }

    // ---- 1. The guard is sound ------------------------------------------------------------

    @Test
    fun appendingAfterTheFullSequenceTailThreadsAndAgreesWithTheCacheFreeOracle() {
        val (s1, x) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val (s2, y) = s1.insertAfter(a, x.id, "y")
        val (warm, _) = requireNotNull(s2.removeAt(0)) // warms the chain; y is still the tail
        val (appended, w) = warm.insertAfter(a, y.id, "w")

        assertAll(
            { assertNotNull(appended.threadedSequence, "an append after the full tail must thread") },
            { assertEquals(listOf(x.id, y.id, w.id), appended.sequence) },
            { assertEquals(oracle(appended).sequence, appended.sequence, "threaded order must match a cold rebuild") },
            { assertEquals(listOf("y", "w"), appended.toList()) },
        )
    }

    @Test
    fun bulkAppendingAfterTheFullSequenceTailThreadsAndAgreesWithTheOracle() {
        val (s1, x) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val (warm, _) = requireNotNull(s1.removeAt(0))
        val (appended, minted) = warm.insertAllAfter(a, x.id, listOf("p", "q", "r"))

        assertAll(
            { assertNotNull(appended.threadedSequence) },
            { assertEquals(listOf(x.id) + minted.map { it.id }, appended.sequence) },
            { assertEquals(oracle(appended).sequence, appended.sequence) },
            { assertEquals(listOf("p", "q", "r"), appended.toList()) },
        )
    }

    @Test
    fun insertingAfterHeadOnANonEmptyLogPrependsAndMustNotThread() {
        val log = warmLogWithATrailingTombstone()
        val (prepended, p) = log.state.insertAfter(a, RgaId.HEAD, "p")

        assertAll(
            { assertNull(prepended.threadedSequence, "HEAD on a non-empty log prepends — a suffix would be wrong") },
            // HEAD's children sort DESCENDING and a fresh local id carries the maximal Lamport,
            // so `p` leads. A naive suffix would have put it last.
            { assertEquals(listOf(p.id, log.x, log.y, log.z), prepended.sequence) },
            { assertEquals(oracle(prepended).sequence, prepended.sequence) },
            { assertEquals(listOf("p", "y"), prepended.toList()) },
        )
    }

    @Test
    fun insertingMidSequenceMustNotThread() {
        val log = warmLogWithATrailingTombstone()
        val (mid, m) = log.state.insertAfter(a, log.x, "m")

        assertAll(
            { assertNull(mid.threadedSequence) },
            { assertEquals(listOf(log.x, m.id, log.y, log.z), mid.sequence) },
            { assertEquals(oracle(mid).sequence, mid.sequence) },
            { assertEquals(listOf("m", "y"), mid.toList()) },
        )
    }

    /**
     * The production counterexample, and the reason the guard may not be inferred from caller
     * intent: `WarpLogRecordExporter.tail` is the last **visible** element, which after a
     * remotely-tombstoned trailing entry is *not* the last full-sequence element. The append
     * becomes a **sibling** of the tombstone and its higher Lamport sorts it **ahead**.
     *
     * Assert on [Rga.sequence], not on [Rga.toList]. The displaced element is tombstoned, so
     * both orders project to the *same* visible list — this divergence is invisible to
     * `toList()` and to `equals` alike, and shows up only in the full order. That order is
     * public API: a `WindowPolicy` in `:kuilt-quilter` reads [Rga.sequence] to decide which ids
     * a history window drops, so two replicas disagreeing about it drop different sets.
     */
    @Test
    fun appendingAfterTheLastVisibleElementWithATrailingTombstoneMustNotThread() {
        val log = warmLogWithATrailingTombstone()
        val (appended, w) = log.state.insertAfter(a, log.y, "w")
        val naiveSuffix = listOf(log.x, log.y, log.z, w.id)

        assertAll(
            { assertNotNull(log.state.threadedSequence, "the state under test must be WARM, or this proves nothing") },
            { assertNull(appended.threadedSequence, "last VISIBLE is not last in sequence — must fall through") },
            { assertEquals(listOf(log.x, log.y, w.id, log.z), appended.sequence, "the append sorts ahead") },
            { assertEquals(oracle(appended).sequence, appended.sequence) },
            { assertNotEquals(naiveSuffix, appended.sequence, "a suffix would have been the wrong order") },
            // Both orders project to the same visible list — which is exactly why an assertion
            // on toList() (or on equals) cannot see this bug.
            { assertEquals(listOf("y", "w"), appended.toList()) },
        )
    }

    @Test
    fun bulkAppendingAfterTheLastVisibleElementWithATrailingTombstoneMustNotThread() {
        val log = warmLogWithATrailingTombstone()
        val (appended, minted) = log.state.insertAllAfter(a, log.y, listOf("w", "v"))

        assertAll(
            { assertNull(appended.threadedSequence) },
            { assertEquals(listOf(log.x, log.y, minted[0].id, minted[1].id, log.z), appended.sequence) },
            { assertEquals(oracle(appended).sequence, appended.sequence) },
            { assertEquals(listOf("y", "w", "v"), appended.toList()) },
        )
    }

    @Test
    fun bulkInsertingAfterHeadOnANonEmptyLogMustNotThread() {
        val log = warmLogWithATrailingTombstone()
        val (prepended, minted) = log.state.insertAllAfter(a, RgaId.HEAD, listOf("p", "q"))

        assertAll(
            { assertNull(prepended.threadedSequence) },
            { assertEquals(minted.map { it.id } + listOf(log.x, log.y, log.z), prepended.sequence) },
            { assertEquals(oracle(prepended).sequence, prepended.sequence) },
            { assertEquals(listOf("p", "q", "y"), prepended.toList()) },
        )
    }

    // ---- 2. Rule 2: threading never forces the cold lazy ------------------------------------

    /**
     * The fill path — appends with nothing ever reading the order — must stay cold.
     *
     * A `sequence.last()` probe in the guard would satisfy every correctness case above and
     * re-introduce a full `computeSequence()` per append on the one path that today pays none,
     * turning a Θ(N)-per-turn saving into a Θ(N)-per-turn cost.
     */
    @Test
    fun theFillPathNeverMaterialisesTheOrder() {
        var single = Rga.empty<String>()
        var after = RgaId.HEAD
        val singleStates = buildList {
            repeat(CHAIN) {
                val (next, op) = single.insertAfter(a, after, "v$it")
                single = next
                after = op.id
                add(next)
            }
        }
        val (bulk, _) = Rga.empty<String>().insertAllAfter(a, RgaId.HEAD, List(CHAIN) { "v$it" })

        assertAll(
            { assertTrue(singleStates.all { it.threadedSequence == null }, "insertAfter must not warm a cold log") },
            { assertNull(bulk.threadedSequence, "insertAllAfter must not warm a cold log") },
            { assertNull(Rga.empty<String>().threadedSequence, "empty() starts cold, not seeded") },
        )
    }

    /**
     * A remote `Remove` cannot reorder anything, so threading it is *correct* — but this path
     * never reads the order today, so it may only propagate a list already in hand. Forcing one
     * would put a full `computeSequence()` on every remote `Remove`, a new Θ(N) per op on the
     * gossip path.
     */
    @Test
    fun applyRemovePropagatesAWarmOrderAndNeverForcesAColdOne() {
        val (s1, x) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val (cold, y) = s1.insertAfter(a, x.id, "y")
        val coldAfterRemove = cold.apply(RgaOp.Remove<String>(y.id))

        val (warm, _) = requireNotNull(cold.removeAt(0))
        val warmAfterRemove = warm.apply(RgaOp.Remove<String>(y.id))

        assertAll(
            { assertNull(coldAfterRemove.threadedSequence, "a cold log must stay cold") },
            { assertSame(warm.threadedSequence, warmAfterRemove.threadedSequence, "the same list, not a copy") },
            { assertEquals(oracle(warmAfterRemove).sequence, warmAfterRemove.sequence) },
            { assertEquals(emptyList(), warmAfterRemove.toList()) },
        )
    }

    // ---- 3. Dispositions of the paths that must never thread --------------------------------

    /**
     * A remote [RgaOp.Insert] must not thread **even when its `after` is the current tail**.
     * Unlike a freshly minted local id, a remote id can be the missing predecessor an
     * already-held orphan reroots through, which moves elements the guard never looks at.
     */
    @Test
    fun remoteInsertNeverThreadsEvenAtTheTail() {
        val (s1, x) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val (warm, _) = requireNotNull(s1.removeAt(0))
        val applied = warm.apply(RgaOp.Insert(id = RgaId(REMOTE_LAMPORT, peer, 1L), value = "remote", after = x.id))

        assertAll(
            { assertNotNull(warm.threadedSequence, "the state under test must be WARM") },
            { assertNull(applied.threadedSequence) },
            { assertEquals(oracle(applied).sequence, applied.sequence) },
            { assertEquals(listOf("remote"), applied.toList()) },
        )
    }

    @Test
    fun mergeCompactionAndFlooringAllDropTheThreadedOrder() {
        val (s1, x) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val (s2, y) = s1.insertAfter(a, x.id, "y")
        val (warm, _) = requireNotNull(s2.removeAt(1)) // tombstones the LEAF y; warms the chain

        val (peerState, _) = Rga.empty<String>().insertAfter(peer, RgaId.HEAD, "p")
        val merged = warm.piece(peerState)

        val stable = VersionVector.of(mapOf(a to y.id.seq))
        val (compacted, compactOp) = requireNotNull(warm.compact(stable, stable, stable))
        val remotelyCompacted = warm.apply(compactOp)
        val (floored, _) = requireNotNull(warm.dropWindow(a, setOf(x.id)))
        val decoded = Rga.fromOps(warm.ops, warm.lamport, warm.compactedBelow)

        assertAll(
            { assertNotNull(warm.threadedSequence, "the state under test must be WARM") },
            { assertNull(merged.threadedSequence, "a union reorders arbitrarily") },
            { assertNull(compacted.threadedSequence, "compaction reroots survivors") },
            { assertNull(remotelyCompacted.threadedSequence) },
            { assertNull(floored.threadedSequence, "a floor re-roots survivors to HEAD") },
            { assertNull(decoded.threadedSequence, "the wire decode has no cache at all") },
            { assertEquals(oracle(merged).sequence, merged.sequence) },
            { assertEquals(oracle(compacted).sequence, compacted.sequence) },
            { assertEquals(oracle(floored).sequence, floored.sequence) },
            { assertEquals(listOf("p", "x"), merged.toList().sorted()) },
            { assertEquals(listOf("x"), compacted.toList()) },
            { assertEquals(VersionVector.of(mapOf(a to x.id.seq)), floored.compactedBelow) },
        )
    }

    // ---- 4. The guard actually fires ---------------------------------------------------------

    /**
     * The load-bearing assertion. A run shaped exactly like `WarpLogRecordExporter`'s steady
     * state — `entries()` to read the head, `removeFirst(k)` to evict it, `insertAllAfter(tail)`
     * to append the turn — must compute the RGA order **once**, on the first turn, and never
     * again.
     *
     * Recomputations are counted structurally rather than with a production counter: a turn
     * recomputes exactly when the state it starts from carries no threaded order, because
     * [Rga.sequence] is `cache?.sequence ?: computeSequence()`. The `assertSame` closes the
     * remaining gap by proving the lazy really hands back the threaded list rather than a
     * fresh one.
     *
     * Without this test the whole task can land, pass every correctness case, and deliver
     * nothing — the warmth chain is what makes the guard reachable at all, and it is invisible
     * to any assertion on output.
     */
    @Test
    fun theExporterSteadyStateRecomputesTheOrderExactlyOnce() {
        val (filled, seeded) = Rga.empty<String>().insertAllAfter(a, RgaId.HEAD, List(CAP) { "seed$it" })
        var log = filled
        var tail = seeded.last().id
        val fillStayedCold = log.threadedSequence == null

        val recomputedOnTurn = mutableListOf<Int>()
        val threadedAfterAppend = mutableListOf<Boolean>()
        val handedBackTheThreadedList = mutableListOf<Boolean>()
        repeat(TURNS) { turn ->
            // evictLeading: read the head off the pre-removal instance, then tombstone it.
            if (log.threadedSequence == null) recomputedOnTurn += turn
            log.entries().take(BATCH)
            val (afterEvict, _) = log.removeFirst(BATCH)
            // applyTurn: append the turn after the last visible element.
            val (afterAppend, inserts) = afterEvict.insertAllAfter(a, tail, List(BATCH) { "t$turn.$it" })
            log = afterAppend
            tail = inserts.last().id
            threadedAfterAppend += afterAppend.threadedSequence != null
            handedBackTheThreadedList += afterAppend.sequence === afterAppend.threadedSequence
        }

        val survivors = log.toList()
        assertAll(
            { assertTrue(fillStayedCold, "the fill path must not have paid for threading") },
            {
                assertEquals(
                    listOf(0),
                    recomputedOnTurn,
                    "only the first turn may recompute; a later one means the warmth chain is broken",
                )
            },
            { assertTrue(threadedAfterAppend.all { it }, "every append after the first eviction must thread") },
            { assertTrue(handedBackTheThreadedList.all { it }, "sequence must hand back the threaded list itself") },
            { assertEquals(oracle(log).sequence, log.sequence, "and the threaded order must still be the real one") },
            { assertEquals(CAP, survivors.size) },
            { assertEquals("t${TURNS - 1}.${BATCH - 1}", survivors.last()) },
        )
    }

    private companion object {
        private const val CHAIN = 6
        private const val CAP = 16
        private const val BATCH = 4
        private const val TURNS = 6
        private const val REMOTE_LAMPORT = 9L
    }
}
