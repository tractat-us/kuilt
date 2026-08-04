@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.scale

import java.lang.management.ManagementFactory
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.LWWMap
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.gossip.GossipSeam
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Phase-0 measurement for **#1955** (digest-gated anti-entropy for the non-causal CRDTs), and the
 * gate on whether that issue should be built at all.
 *
 * **The class name outlives what shipped, deliberately.** #1955 shipped a *bare root hash*, not a
 * Merkle tree. This class keeps the name because it still prices the sharded/tree variant that was
 * measured and rejected — part (D) is the whole reason that decision has a number behind it.
 * Nothing here should be read as a claim that a tree exists in the codebase.
 *
 * #1955 and its predecessor #663 both say *measure first*, and #663's stated trigger was
 * "when average CRDT state exceeds a threshold". Nobody had produced the threshold. This
 * suite produces it, for the two types that are unblocked today — `LWWMap` and `GSet`
 * (`Rga`/`Fugue` are gated on #1978's canonical `Compact.positions`).
 *
 * **What is measured, not modelled.** Every byte count below comes out of the real codec:
 * [Quilter] frames its wire messages as `Cbor.encodeToByteArray(QuiltMessage.serializer(v), …)`,
 * and so does this suite. Part (B) closes the loop on a real [MeteredSeam], checking what the wire
 * actually carries against what the codec says the frame costs.
 *
 * **Superseded in part.** `QuiltMessage.RootDigest` shipped in #1955, so the converged-round cost
 * is no longer modelled at all: [quiescentRoundBytes] prices the *shipped* frame, and part (B) —
 * [convergedRoundShipsADigestNotTheState] and [convergedRoundCostIsFlatInStateSize] — measures it
 * end to end on a [MeteredSeam]. Part (B) used to assert the opposite (an anti-entropy round *is*
 * one encoded `FullState`, exactly); that equality held right up until #1955 falsified it by
 * design, and it is what licenses (A)'s sweep and (D)'s `today b/round` column to price a
 * full-state round from the codec alone. The codec has not changed since, only which frame the
 * tick reaches for.
 *
 * **A converged round is two frames, not one.** The digest goes out and the matched peer acks its
 * `upThrough` back, so [matchedRoundBytes] — not [quiescentRoundBytes] — is the figure to quote.
 * [meterConvergedRounds] makes every node apply a local mutation before the meter opens for exactly
 * that reason; see its KDoc for why a never-written mesh silently halves the number.
 *
 * **What is still modelled.** The *sharded* variant was measured and deliberately not built — its
 * advantage collapses as divergence grows (see the (D) table) — so its frames stay declared here
 * as [ShardDigestsProbe] / [ShardPushProbe] and encoded with the same `Cbor`: a real price for a
 * hypothetical frame, rather than a guessed constant. The one genuinely derived quantity is the
 * shard-mismatch count, which is closed-form (see [expectedMismatchedShards]).
 *
 * The verdict table is printed by [crossoverTableForDigestGatedAntiEntropy].
 *
 * Determinism mirrors the sibling scaling suites: [UnconfinedTestDispatcher], per-peer seeded RNG,
 * heartbeats pushed past the measurement window, `jitter = ZERO` for synchronous view convergence,
 * bounded virtual-time advance — never `advanceUntilIdle`, which would hang rather than fail on
 * the forever-re-arming anti-entropy timer.
 */
class MerkleDigestCostModelTest {

    private companion object {
        /** Knuth's 32-bit golden-ratio constant — only to make the probe shard vector incompressible. */
        const val GOLDEN_RATIO_32 = -0x61c88647

        /**
         * A full-width stand-in for a real root. `stateRoot()` is FNV-1a 64 over the encoded state,
         * so a root is ~uniformly distributed over `Long` and pays CBOR's full 9-byte cost; a tidy
         * placeholder like `-1L` encodes in one byte and would understate the frame by eight.
         */
        const val REPRESENTATIVE_ROOT = -0x5AA53CC31EE12DD2L

        /**
         * The largest mesh one [meshSender] can price for, and the bound [meterConvergedRounds]
         * enforces. [buildInMemoryMesh] names peers `peer-0` … `peer-(n-1)`, so ten peers are
         * `peer-0` … `peer-9` — every id the same six characters as [meshSender] — and the
         * eleventh brings in `peer-10`, a seventh character and one more CBOR byte per frame.
         *
         * Note the bound is `<= 10`, **not** the `< 10` that "sub-10-node" suggests: the mesh of
         * size ten is the last one that is uniformly priced, not the first one that is not.
         */
        const val MAX_UNIFORMLY_PRICED_MESH = 10
    }

    /**
     * Anti-entropy interval used by the metered rounds in (B). Short enough that twenty rounds fit
     * in a one-second virtual window, and far below the 30 s `fullStateRetryInterval`, so no
     * first-contact retry can ride the tick and pollute the measurement.
     */
    private val antiEntropyInterval = 50.milliseconds

    /** Virtual-time steps used to settle handshakes and first-contact traffic before metering. */
    private val flushSteps = 32

    /**
     * The replica id a metered peer actually uses: [buildInMemoryMesh] names peers `peer-N` and
     * [Quilter] defaults its replica to the seam's own peer id. Every id in a sub-10-node mesh is
     * six characters, so one name prices every peer's frames.
     */
    private val meshSender = ReplicaId("peer-0")

    // ---- shapes under measurement ----------------------------------------------------------

    /**
     * Sizes swept. Capped at 100k entries: both CRDTs are immutable-collection-backed, so a
     * naive fold is O(n²); [lwwMapOf] builds by balanced [LWWMap.piece] to stay O(n log n).
     */
    private val sizes = listOf(1, 10, 100, 1_000, 10_000, 100_000)

    private val replica = ReplicaId("replica-0")
    private val gsetSerializer = QuiltMessage.serializer(GSet.serializer(String.serializer()))
    private val lwwSerializer =
        QuiltMessage.serializer(LWWMap.serializer(String.serializer(), String.serializer()))

    private fun gsetOf(size: Int): GSet<String> =
        GSet.of(*Array(size) { "element-with-a-realistic-id-$it" })

    /** Balanced-join build: O(n log n) rather than the O(n²) of a left fold over [LWWMap.set]. */
    private fun lwwMapOf(size: Int): LWWMap<String, String> {
        if (size == 0) return LWWMap.empty()
        fun single(i: Int): LWWMap<String, String> =
            LWWMap.empty<String, String>().set(replica, i.toLong(), "entity-$i.attribute", "value-$i")
        fun build(from: Int, to: Int): LWWMap<String, String> {
            if (to - from == 1) return single(from)
            val mid = (from + to) / 2
            return build(from, mid).piece(build(mid, to))
        }
        return build(0, size)
    }

    /**
     * The sender id is part of the frame, so it is a parameter rather than a constant: [Quilter]
     * defaults `replica` to `ReplicaId(seam.selfId.value)`, and [buildInMemoryMesh] names peers
     * `peer-N` — three CBOR bytes shorter than this suite's own [replica]. Pricing the grounding
     * check in (B) against the wrong sender id is a 3-byte error that looks like negative framing
     * overhead.
     */
    private fun fullStateBytes(state: GSet<String>, sender: ReplicaId = replica): Int =
        Cbor.encodeToByteArray(gsetSerializer, QuiltMessage.FullState(sender, state)).size

    private fun fullStateBytes(state: LWWMap<String, String>, sender: ReplicaId = replica): Int =
        Cbor.encodeToByteArray(lwwSerializer, QuiltMessage.FullState(sender, state)).size

    // ---- A. the real cost of today's anti-entropy round ------------------------------------

    @Test
    fun fullStateWireBytesScaleLinearlyWithEntryCount() {
        val gset = sizes.map { it to fullStateBytes(gsetOf(it)) }
        val lww = sizes.map { it to fullStateBytes(lwwMapOf(it)) }

        println("\n=== #1955 Phase 0 (A): measured QuiltMessage.FullState wire bytes (Cbor) ===")
        println("  %9s  %14s %10s   %14s %10s".format("entries", "GSet bytes", "b/entry", "LWWMap bytes", "b/entry"))
        sizes.forEachIndexed { i, n ->
            println(
                "  %9d  %14d %10.1f   %14d %10.1f".format(
                    n, gset[i].second, gset[i].second.toDouble() / n, lww[i].second, lww[i].second.toDouble() / n,
                ),
            )
        }
        println("  slope (b/entry, from the 10k→100k segment): GSet=${slope(gset)}, LWWMap=${slope(lww)}")

        // Linearity is what licenses extrapolating past 100k in the verdict table: the per-entry
        // cost must be flat, not growing. Compare the two largest segments' slopes.
        listOf("GSet" to gset, "LWWMap" to lww).forEach { (name, rows) ->
            val mid = segmentSlope(rows, 3, 4)
            val top = segmentSlope(rows, 4, 5)
            assertTrue(
                top in (mid * 0.9)..(mid * 1.1),
                "$name per-entry wire cost must be flat to extrapolate (1k→10k=$mid vs 10k→100k=$top b/entry)",
            )
        }
    }

    private fun segmentSlope(rows: List<Pair<Int, Int>>, lo: Int, hi: Int): Double =
        (rows[hi].second - rows[lo].second).toDouble() / (rows[hi].first - rows[lo].first)

    private fun slope(rows: List<Pair<Int, Int>>): Double = segmentSlope(rows, 4, 5)

    // ---- B. acceptance: what a converged anti-entropy round actually costs ------------------

    /**
     * Stands up an [n]-node metered mesh in which **every** node replicates the same [state], so
     * every anti-entropy round is a converged round — the case the #1955 digest gate exists for —
     * and returns the bytes that [rounds] such rounds put on the wire, cluster-wide.
     *
     * **Every node applies one local mutation before the meter opens, and the mesh is then allowed
     * to re-agree.** That is load-bearing, not decoration. A replica that has never called
     * `Quilter.apply` sits at `nextSeq == 0`, so its digest carries `upThrough = 0`, and the
     * recipient's `resyncReceiveCursor` returns at its `upThrough <= 0` guard *before* acking.
     * Metering that mesh prices digest-out-with-nothing-back — a state no replica that has ever
     * written is in — and halves the published round cost. One mutation each puts every node at
     * `nextSeq >= 1`; the deltas re-agree the states, so the window still holds genuinely
     * *converged* rounds, now carrying the matched round's `Ack` exactly as production does.
     * Convergence is asserted rather than assumed — a diverged window would be measuring the
     * mismatch branch, which is a different quantity entirely.
     *
     * Handshakes, the first-contact `FullState` exchange, and those reconvergence deltas are all
     * flushed *before* the meter is read, so the window holds anti-entropy traffic and nothing else.
     */
    private suspend fun TestScope.meterConvergedRounds(n: Int, state: GSet<String>, rounds: Int): Long {
        val clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        fun flush() = repeat(flushSteps) { testScheduler.advanceTimeBy(1); testScheduler.runCurrent() }

        val mesh = buildInMemoryMesh(n)
        val gossips = mesh.seams.mapIndexed { i, base ->
            GossipSeam(
                base = base,
                random = Random(1 + i),
                clock = clock,
                config = HeartbeatConfig(interval = 1.hours, timeout = 1.hours, reconnectWindow = 1.hours),
                jitter = ZERO..ZERO,
            )
        }
        gossips.forEach { it.start(backgroundScope) }
        flush()

        val quilters = gossips.map { gossip ->
            Quilter(
                seam = gossip,
                initial = state,
                valueSerializer = GSet.serializer(String.serializer()),
                scope = backgroundScope,
                config = QuilterConfig(expectVirtualTime = true, antiEntropyInterval = antiEntropyInterval),
                random = Random(100),
            )
        }
        flush()

        quilters.forEachIndexed { i, quilter -> quilter.mutate { it.add("converged-writer-$i") } }
        flush()

        val settled = quilters.map { it.state.value }.toSet()
        assertEquals(
            1,
            settled.size,
            "the metered window must hold CONVERGED rounds: $n writing nodes settled on " +
                "${settled.size} distinct states, so the rounds below would be mismatch rounds",
        )

        val before = mesh.clusterMetrics().totalBytesOut
        repeat(rounds) {
            testScheduler.advanceTimeBy(antiEntropyInterval.inWholeMilliseconds)
            testScheduler.runCurrent()
        }
        val measured = mesh.clusterMetrics().totalBytesOut - before
        mesh.close()
        return measured
    }

    /**
     * [meshSender]'s "every id in a sub-10-node mesh is six characters" is true and, until now,
     * unenforced — [meterConvergedRounds] takes the node count as a parameter, so a larger mesh
     * would keep printing figures that are silently a byte per frame light. The neighbouring KDoc
     * already records that this error class "looks like negative framing overhead", i.e. it has
     * bitten once and was caught by luck.
     *
     * The two length assertions are what pin the *bound* rather than restate it: raising
     * [MAX_UNIFORMLY_PRICED_MESH] admits a `peer-10` the sender cannot price, and lowering it
     * makes the guard needlessly tight.
     */
    @Test
    fun meteringRefusesAMeshItCannotPrice() = runTest(UnconfinedTestDispatcher()) {
        assertTrue(
            (0 until MAX_UNIFORMLY_PRICED_MESH).all { "peer-$it".length == meshSender.value.length },
            "every peer id in a $MAX_UNIFORMLY_PRICED_MESH-node mesh must be as long as $meshSender, " +
                "or one sender id cannot price them all",
        )
        assertNotEquals(
            meshSender.value.length,
            "peer-$MAX_UNIFORMLY_PRICED_MESH".length,
            "and the next id must not be, or the bound is tighter than it needs to be",
        )
        assertFailsWith<IllegalArgumentException>(
            "a mesh one peer past the uniformly-priced bound must be refused, not measured",
        ) {
            meterConvergedRounds(n = MAX_UNIFORMLY_PRICED_MESH + 1, state = gsetOf(1), rounds = 1)
        }
    }

    @Test
    fun convergedRoundShipsADigestNotTheState() = runTest(UnconfinedTestDispatcher()) {
        val n = 4
        val rounds = 20
        val stateSize = 200

        val shared = gsetOf(stateSize)
        val measured = meterConvergedRounds(n = n, state = shared, rounds = rounds)

        val fullState = fullStateBytes(shared, meshSender)
        val digestFrame = quiescentRoundBytes(meshSender)
        val matchedRound = matchedRoundBytes(meshSender)
        val perNodeRound = measured.toDouble() / (rounds * n)
        println("\n=== #1955 acceptance (B): converged anti-entropy round, GSet($stateSize), $n nodes ===")
        println("  bytes/node/round now       : ${"%.1f".format(perNodeRound)}")
        println("  one RootDigest frame       : $digestFrame (encoded from the shipped class)")
        println("  its matched Ack back       : ${matchedAckBytes(meshSender)}")
        println("  modelled matched round     : $matchedRound (digest out + ack back)")
        println("  full state would have been : $fullState")
        println("  reduction                  : ${"%.1f".format(fullState / perNodeRound)}x")

        assertAll(
            // The Phase-0 prediction was that a converged round becomes a small constant frame,
            // independent of state size. 200 entries is ~6.5 KB of state; a digest round is tens of
            // bytes. Assert the order of magnitude, not an exact frame size, so incidental framing
            // changes do not red-light this.
            {
                assertTrue(
                    perNodeRound < fullState / 20.0,
                    "a converged round must cost far less than the state ($perNodeRound b vs $fullState b)",
                )
            },
            // The floor, and the reason [meterConvergedRounds] makes every node write. A matched
            // round is digest-out PLUS ack-back; a harness whose nodes have never applied a local
            // mutation sits at `upThrough = 0`, `resyncReceiveCursor` returns before acking, and
            // the round silently prices at half. This assertion is what makes that regression red.
            {
                assertTrue(
                    perNodeRound > digestFrame * 1.2,
                    "a matched round must carry the ack back, not the digest alone " +
                        "(metered $perNodeRound b vs one digest $digestFrame b) — nodes that have " +
                        "never applied a local mutation emit `upThrough = 0` and get no ack",
                )
            },
            // Grounding, in the role the old part (B) played: the wire must carry the matched round
            // and essentially nothing else. A round costing multiples of it would mean something
            // else is riding the anti-entropy tick, and every figure (D) derives from
            // [matchedRoundBytes] would be understated.
            {
                assertTrue(
                    perNodeRound < matchedRound * 1.5,
                    "a converged round must be one digest plus its ack, not several frames " +
                        "(metered $perNodeRound b vs modelled $matchedRound b)",
                )
            },
        )
    }

    @Test
    fun convergedRoundCostIsFlatInStateSize() = runTest(UnconfinedTestDispatcher()) {
        // The #1955 claim is not "cheaper" but "constant": the converged round must cost the same
        // at 200 entries as at 20,000, while full state grows 100x. Measured, not modelled.
        val rounds = 10
        data class Row(val size: Int, val bytes: Long, val fullState: Int)

        val measured = listOf(200, 20_000).map { size ->
            val shared = gsetOf(size)
            Row(
                size = size,
                bytes = meterConvergedRounds(n = 2, state = shared, rounds = rounds),
                fullState = fullStateBytes(shared, meshSender),
            )
        }

        println("\n=== #1955 acceptance (B): converged-round cost vs state size, $rounds rounds, 2 nodes ===")
        measured.forEach {
            println(
                "  GSet(%6d) -> %7d bytes total   (a full-state round would be %9d b)"
                    .format(it.size, it.bytes, it.fullState),
            )
        }

        val (small, large) = measured
        assertTrue(
            large.bytes < small.bytes * 2,
            "converged-round cost must be ~flat in state size " +
                "(${small.size} entries: ${small.bytes} b, ${large.size} entries: ${large.bytes} b)",
        )
    }

    // ---- C. frame sizes: the shipped root digest, and the shard vector that was not built ----

    /** The shard vector, sent only when roots disagree. */
    @Serializable
    @SerialName("shardDigests")
    private class ShardDigestsProbe(val sender: ReplicaId, val shards: List<Int>)

    /** The entries of the shards that disagreed — the diff ship. */
    @Serializable
    @SerialName("shardPush")
    private class ShardPushProbe(val sender: ReplicaId, val shardIds: List<Int>, val entries: GSet<String>)

    private fun <T> bytesOf(serializer: KSerializer<T>, value: T): Int =
        Cbor.encodeToByteArray(serializer, value).size

    /**
     * The **sender's half** of a converged round: one root-hash frame out. Independent of state
     * size — that constancy *is* the optimization, so part (B) measures it rather than trusting it.
     * For what the whole round costs, see [matchedRoundBytes]: the recipient answers a matched
     * digest with an `Ack`, and quoting this figure as the round is the mistake that made the
     * published cost ~2x optimistic.
     *
     * Priced from the **shipped** `QuiltMessage.RootDigest`, not a probe. A probe of it drifted
     * once already: it modelled `(sender, root)` while the shipped frame carries a third field,
     * `upThrough` — the #1266 receive-cursor resync a matched round would otherwise skip — so every
     * figure derived from it underpriced the real converged round. Encoding the shipped class makes
     * that class of drift impossible, and it already pays the sealed-variant tag, so no
     * [sealedTagOverhead] is added here.
     *
     * `upThrough` is the sender's own-delta high-water. Its floor is **`0`**, not `1`: `nextSeq`
     * starts at `0` and pre-increments on the first `apply`, so a replica that has never applied a
     * local mutation ships `upThrough = 0`. That distinction is not cosmetic — at `0` the recipient's
     * `resyncReceiveCursor` returns before acking, so such a round has no reply at all. `1L` is
     * priced here because it is the floor for a replica that has *written*, which is the production
     * case and the one [meterConvergedRounds] reproduces; a busy replica's sequence number costs a
     * few bytes more.
     */
    private fun quiescentRoundBytes(sender: ReplicaId = replica): Int = bytesOf(
        gsetSerializer,
        QuiltMessage.RootDigest<GSet<String>>(sender = sender, root = REPRESENTATIVE_ROOT, upThrough = 1L),
    )

    /**
     * The `Ack` that comes **back** on a matched round. `onRootDigest`'s match branch calls
     * `resyncReceiveCursor`, which acks the digest's `upThrough` unconditionally (idempotent at the
     * sender, and it heals a previously-lost ack). `seq = 1L` mirrors [quiescentRoundBytes]'s
     * written-replica floor.
     */
    private fun matchedAckBytes(sender: ReplicaId = replica): Int =
        bytesOf(gsetSerializer, QuiltMessage.Ack<GSet<String>>(acker = sender, sender = sender, seq = 1L))

    /**
     * A whole converged round between two replicas that have each written at least once: the digest
     * out and the ack back. This — not [quiescentRoundBytes] alone — is the figure to quote as the
     * steady-state cost of anti-entropy, and it is what part (B) meters end to end.
     *
     * It is still flat in state size (both frames are), so the #1955 claim is unaffected; only the
     * constant moves.
     */
    private fun matchedRoundBytes(sender: ReplicaId = replica): Int =
        quiescentRoundBytes(sender) + matchedAckBytes(sender)

    /**
     * The tag a variant pays for living in the `QuiltMessage` sealed hierarchy. Measured against
     * the smallest existing variant so the probes above are priced as real `QuiltMessage` members.
     */
    private fun sealedTagOverhead(): Int {
        val tagged = bytesOf(gsetSerializer, QuiltMessage.Ack(replica, replica, seq = 1L))
        val bare = bytesOf(BareAckProbe.serializer(), BareAckProbe(replica, replica, 1L))
        return tagged - bare
    }

    @Serializable
    private class BareAckProbe(val acker: ReplicaId, val sender: ReplicaId, val seq: Long)

    @Test
    fun digestExchangeCostIsConstantInStateSize() {
        val quiescent = quiescentRoundBytes()
        val shardVectors = listOf(16, 64, 256, 1024).map { s ->
            val vector = List(s) { it * GOLDEN_RATIO_32 }
            s to bytesOf(ShardDigestsProbe.serializer(), ShardDigestsProbe(replica, vector)) + sealedTagOverhead()
        }

        println("\n=== #1955 Phase 0 (C): measured digest-protocol frame sizes (Cbor) ===")
        println("  sealed-variant tag overhead        : ${sealedTagOverhead()} bytes")
        println("  RootDigest out (shipped class)     : $quiescent bytes — constant in state size")
        println("  Ack back on a match                : ${matchedAckBytes()} bytes")
        println("  whole matched round                : ${matchedRoundBytes()} bytes — also constant")
        shardVectors.forEach { (s, bytes) -> println("  shard vector, S=%4d               : %6d bytes".format(s, bytes)) }

        // The whole premise: a converged round costs the same whether the CRDT holds 1 entry
        // or 100k. If this frame ever grew with state size the optimization would be void.
        assertTrue(quiescent < 64, "a converged round must be a small constant frame (was $quiescent bytes)")
    }

    // ---- D. the crossover, and the verdict -------------------------------------------------

    /**
     * Expected number of mismatched shards when [divergent] keys are spread uniformly over
     * [shards] buckets: `S·(1 − (1 − 1/S)^d)`. Saturates at `S`, which is the point — past
     * `d ≈ S` a shard vector stops discriminating and the diff ship approaches full state.
     */
    private fun expectedMismatchedShards(divergent: Int, shards: Int): Double =
        shards * (1.0 - (1.0 - 1.0 / shards).pow(divergent))

    @Test
    fun crossoverTableForDigestGatedAntiEntropy() {
        val shards = 256
        val quiescent = quiescentRoundBytes()
        val matchedRound = matchedRoundBytes()
        val vectorBytes = bytesOf(
            ShardDigestsProbe.serializer(),
            ShardDigestsProbe(replica, List(shards) { it }),
        ) + sealedTagOverhead()

        val gsetRows = sizes.map { it to fullStateBytes(gsetOf(it)) }
        val bytesPerEntry = slope(gsetRows)

        println("\n=== #1955 Phase 0 (D): crossover, GSet, S=$shards shards ===")
        println("  converged round: $matchedRound b (digest $quiescent + ack ${matchedAckBytes()}) vs O(state) before #1955")
        println(
            "  crossover at ${(matchedRound / bytesPerEntry).toInt() + 1} entries — " +
                "above that a converged round is cheaper as a digest",
        )
        // The "before #1955" columns price the full state alone. That round also carried an ack
        // (`onFullState` resyncs the cursor exactly as the digest's match branch does), so the
        // comparison is a few tens of bytes conservative against a multi-KB-to-multi-MB frame —
        // it understates the win, which is the safe direction.
        println("\n  Steady-state egress per node at the default 60s antiEntropyInterval:")
        println("  %9s %19s %17s %12s".format("entries", "b/round (before)", "b/s (before)", "b/s (now)"))
        gsetRows.forEach { (n, bytes) ->
            println("  %9d %19d %17.1f %12.2f".format(n, bytes, bytes / 60.0, matchedRound / 60.0))
        }

        println("\n  Diverged round — d keys actually differ (n = 100k entries):")
        println("  %8s %12s %16s %12s".format("d", "shards hit", "diff-ship bytes", "vs full"))
        val n = 100_000
        val fullBytes = gsetRows.last().second
        listOf(1, 10, 100, 1_000, 10_000).forEach { d ->
            val hit = expectedMismatchedShards(d, shards)
            val shipped = (hit / shards * n * bytesPerEntry).roundToLong() + quiescent + vectorBytes
            println("  %8d %12.1f %16d %11.1fx".format(d, hit, shipped, fullBytes.toDouble() / shipped))
        }

        // The diff ship above is priced from the measured per-entry slope. Check that against a
        // real encoding of one shard's worth of entries, so the table is not resting on a fit.
        val oneShard = n / shards
        val encodedShardPush = bytesOf(
            ShardPushProbe.serializer(),
            ShardPushProbe(replica, listOf(7), gsetOf(oneShard)),
        ) + sealedTagOverhead()
        val modelledShardPush = (oneShard * bytesPerEntry).roundToLong()
        println(
            "\n  one-shard diff ship: modelled ${modelledShardPush}b vs encoded ${encodedShardPush}b " +
                "(${"%.1f".format((encodedShardPush - modelledShardPush) * 100.0 / modelledShardPush)}% apart)",
        )
        assertTrue(
            encodedShardPush.toDouble() in (modelledShardPush * 0.9)..(modelledShardPush * 1.2),
            "per-entry slope must price a real shard push (modelled $modelledShardPush b, encoded $encodedShardPush b)",
        )

        // The finding the issue turns on: a digest gate is a *sparse-divergence* optimization.
        // At d ≈ S the shard vector stops discriminating and the diff ship converges on full state.
        val sparse = expectedMismatchedShards(1, shards) / shards * n * bytesPerEntry
        val dense = expectedMismatchedShards(10_000, shards) / shards * n * bytesPerEntry
        assertTrue(sparse * 100 < dense, "digest pays off only while divergence is sparse (d << S)")
        assertTrue(dense > fullBytes * 0.5, "at d >> S the diff ship must approach full state")
    }

    @Test
    fun rehashPerRoundIsNegligibleAgainstTheInterval() {
        // #1955's one remaining open design question is "incremental maintenance vs O(n) rehash
        // per round". Price the naive option before designing the clever one: canonical-encode
        // and 32-bit-mix every entry of a 100k-entry GSet, as a full rehash round would.
        val elements = gsetOf(100_000).elements.toList()
        repeat(3) { hashAll(elements) } // warm the JIT; this is an order-of-magnitude datum
        val start = System.nanoTime()
        val rounds = 10
        repeat(rounds) { hashAll(elements) }
        val perRound = (System.nanoTime() - start) / rounds

        // A CPU number quoted without the load it was taken under is not a measurement — a
        // contended box distorts wall-clock by orders of magnitude and the distortion is
        // invisible in the number. Print the load so the datum can never be quoted context-free.
        val load = ManagementFactory.getOperatingSystemMXBean().systemLoadAverage
        val cores = Runtime.getRuntime().availableProcessors()
        println("\n=== #1955 Phase 0 (E): full rehash cost, 100k entries ===")
        println("  ${perRound / 1_000_000.0} ms per round, against a ${60.seconds} interval")
        println("  duty cycle: ${"%.5f".format(perRound / 60e9 * 100)}% of one core")
        println("  taken at load ${"%.2f".format(load)} on $cores cores — ${
            if (load > cores * 0.7) "SATURATED, treat as an upper bound" else "quiet enough to quote"
        }")

        assertTrue(
            perRound < 1_000_000_000L,
            "a full rehash must stay far under the anti-entropy interval (was ${perRound / 1e6} ms)",
        )
    }

    private fun hashAll(elements: List<String>): Int {
        var acc = 0
        elements.forEach { e ->
            var h = 0
            e.encodeToByteArray().forEach { b -> h = h * 31 + b }
            acc = acc xor (h * -0x61c88647)
        }
        return acc
    }

    @Test
    fun mismatchModelSanity() {
        // Guards the closed form in expectedMismatchedShards against an off-by-one reading:
        // one divergent key hits exactly one shard; d = S hits ~63% of them (1 − 1/e).
        assertEquals(1.0, expectedMismatchedShards(1, 256), 1e-9)
        assertTrue(expectedMismatchedShards(256, 256) / 256 in 0.62..0.64)
        assertTrue(expectedMismatchedShards(100_000, 256) > 255.9)
    }
}
