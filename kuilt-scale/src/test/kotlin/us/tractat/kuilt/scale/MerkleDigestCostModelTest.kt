@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.scale

import java.lang.management.ManagementFactory
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
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Phase-0 measurement for **#1955** (Merkle/digest anti-entropy for the non-causal CRDTs),
 * and the gate on whether that issue should be built at all.
 *
 * #1955 and its predecessor #663 both say *measure first*, and #663's stated trigger was
 * "when average CRDT state exceeds a threshold". Nobody had produced the threshold. This
 * suite produces it, for the two types that are unblocked today — `LWWMap` and `GSet`
 * (`Rga`/`Fugue` are gated on #1978's canonical `Compact.positions`).
 *
 * **What is measured, not modelled.** Every byte count below comes out of the real codec:
 * [Quilter] frames its wire messages as `Cbor.encodeToByteArray(QuiltMessage.serializer(v), …)`,
 * and so does this suite. [meteredReconcileMatchesEncodedFullStateSize] closes the loop by
 * checking the encoded size against bytes actually counted on a [MeteredSeam], so the size
 * sweep can then run without standing up a mesh per data point.
 *
 * **What is modelled.** The digest protocol does not exist yet, so its messages are declared
 * here as [RootDigestProbe] / [ShardDigestsProbe] / [ShardPushProbe] and encoded with the same
 * `Cbor` — a real price for a hypothetical frame, rather than a guessed constant. The one
 * genuinely derived quantity is the shard-mismatch count, which is closed-form
 * (see [expectedMismatchedShards]).
 *
 * The verdict table is printed by [crossoverTableForDigestGatedAntiEntropy].
 */
class MerkleDigestCostModelTest {

    private companion object {
        /** Knuth's 32-bit golden-ratio constant — only to make the probe shard vector incompressible. */
        const val GOLDEN_RATIO_32 = -0x61c88647
    }

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

    // ---- B. grounding: does the encoded size equal what the wire actually carries? ----------

    @Test
    fun meteredReconcileMatchesEncodedFullStateSize() = runTest(UnconfinedTestDispatcher()) {
        val n = 4
        val rounds = 20
        val stateSize = 200
        val antiEntropy = 50.milliseconds

        val clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        fun flush() = repeat(32) { testScheduler.advanceTimeBy(1); testScheduler.runCurrent() }

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

        // Quilter defaults its replica to the seam's own peer id, and that id is *in* the frame.
        // Derive it rather than assuming this suite's `replica`, or the comparison below is off by
        // the difference in id length.
        val sender = ReplicaId(gossips[0].selfId.value)
        Quilter(
            seam = gossips[0],
            initial = gsetOf(stateSize),
            valueSerializer = GSet.serializer(String.serializer()),
            scope = backgroundScope,
            config = QuilterConfig(expectVirtualTime = true, antiEntropyInterval = antiEntropy),
            random = Random(100),
        )
        flush()

        val before = mesh.clusterMetrics().totalBytesOut
        repeat(rounds) { testScheduler.advanceTimeBy(antiEntropy.inWholeMilliseconds); testScheduler.runCurrent() }
        val totalOnWire = mesh.clusterMetrics().totalBytesOut - before
        mesh.close()

        val encoded = fullStateBytes(gsetOf(stateSize), sender)
        println("\n=== #1955 Phase 0 (B): model grounding at GSet($stateSize), sender=${sender.value} ===")
        println("  metered bytes on the wire, $rounds rounds : $totalOnWire")
        println("  Cbor-encoded FullState frame × $rounds     : ${encoded * rounds}")
        println("  framing/relay overhead                   : ${totalOnWire - encoded * rounds} bytes")

        // Exact, not a band: an anti-entropy round *is* one encoded FullState frame and nothing
        // else — heartbeats are pinned to a 1h interval so none fire inside the window, and a
        // unicast push draws no reply. That equality is what licenses (A)'s size sweep to stand in
        // for wire cost without standing up a mesh per data point; if it ever stops holding, the
        // sweep is measuring the wrong quantity and this suite's conclusions do not follow.
        assertEquals(
            encoded.toLong() * rounds,
            totalOnWire,
            "$rounds anti-entropy rounds must put exactly $rounds encoded FullState frames " +
                "($encoded b each) on the wire",
        )
    }

    // ---- C. the digest protocol's own cost -------------------------------------------------

    /** Round hash only: what a converged round would carry if the roots match. */
    @Serializable
    @SerialName("rootDigest")
    private class RootDigestProbe(val sender: ReplicaId, val root: Long)

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
     * Cost of a converged round: one root-hash frame, nothing in reply. Independent of state
     * size — that constancy *is* the optimization, so it is asserted rather than printed.
     */
    private fun quiescentRoundBytes(): Int =
        bytesOf(RootDigestProbe.serializer(), RootDigestProbe(replica, root = -1L)) + sealedTagOverhead()

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
        println("  converged round (root hash only)   : $quiescent bytes — constant in state size")
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
        val vectorBytes = bytesOf(
            ShardDigestsProbe.serializer(),
            ShardDigestsProbe(replica, List(shards) { it }),
        ) + sealedTagOverhead()

        val gsetRows = sizes.map { it to fullStateBytes(gsetOf(it)) }
        val bytesPerEntry = slope(gsetRows)

        println("\n=== #1955 Phase 0 (D): crossover, GSet, S=$shards shards ===")
        println("  converged round: $quiescent b (digest) vs O(state) (today)")
        println(
            "  crossover at ${(quiescent / bytesPerEntry).toInt() + 1} entries — " +
                "above that a converged round is cheaper as a digest",
        )
        println("\n  Steady-state egress per node at the default 60s antiEntropyInterval:")
        println("  %9s %14s %14s %10s".format("entries", "today b/round", "b/s (today)", "b/s (digest)"))
        gsetRows.forEach { (n, bytes) ->
            println("  %9d %14d %14.1f %10.2f".format(n, bytes, bytes / 60.0, quiescent / 60.0))
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
