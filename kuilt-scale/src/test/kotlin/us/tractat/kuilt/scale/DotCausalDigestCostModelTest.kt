@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.scale

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.Causal
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.DotContext
import us.tractat.kuilt.crdt.DotFun
import us.tractat.kuilt.crdt.DotMap
import us.tractat.kuilt.crdt.DotSet
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.LWWRegister
import us.tractat.kuilt.crdt.MVRegister
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.ORMapEntry
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.VersionVector
import us.tractat.kuilt.gossip.GossipSeam
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Phase-0 measurement for **#1986** — "gate anti-entropy on a version-vector diff for the
 * dot-based CRDTs (`ORSet`/`ORMap`/`MVRegister`)" — and the gate on whether that issue should be
 * built at all. Sibling of [MerkleDigestCostModelTest], which did the same job for #1955's
 * non-causal half (`GSet`/`LWWMap`) and established the method: price every frame through the
 * **real** `Cbor` codec [Quilter] uses, then ground the model on bytes a [MeteredSeam] actually
 * counted.
 *
 * #1986 rests on three premises. This suite prices all three, and **two of them are false**:
 *
 * 1. *"A converged `ORSet` node pays the same pointless full-state round today."* — **Stale.**
 *    #1955 shipped `QuiltMessage.RootDigest`, and `Quilter.stateRoot()` hashes `_state.value`
 *    through the message serializer with no knowledge of `S`. The quiescent win is therefore
 *    **already delivered** for the dot-based types, at zero additional work. Part (E) measures it
 *    on a real `ORSet` mesh.
 * 2. *"`DotContext` already answers what are you missing, exactly, in O(#replicas)."* — **False
 *    for the observed-remove types.** `ORSet.remove` / `ORMap.remove` drop dots from the store and
 *    leave the context untouched, by construction (that is what makes a remove propagate). So two
 *    replicas can hold **byte-identical contexts and divergent states**, and a context/vector
 *    comparison cannot see it. Part (G) exhibits a three-operation counterexample; part (H) shows
 *    the same gap defeats the *diff* as well as the *gate*.
 * 3. *"`Delivered.vector` is already on the wire, so this may be closer to using existing state
 *    than adding any."* — **False for these types.** `Quilted.causalDots()` defaults to the empty
 *    set and only `Rga`/`Fugue`/`MovableTree`/`JsonCrdt` override it; `ORSet`, `ORMap` and
 *    `MVRegister` do not. Their `Delivered.vector` is `VersionVector.EMPTY`, so the vector #1986
 *    proposes to reuse does not exist yet.
 *
 * What *is* real is the cost table: parts (A)–(D) price the full-state round, the causal metadata
 * inside it, and the version-vector frame that would gate it — against the flat root digest that
 * already ships. Those numbers are the reason to say no on size grounds for `MVRegister` even
 * setting correctness aside.
 *
 * **Note in passing, not fixed here (#2010):** `VersionVector.entries` encodes non-canonically —
 * two `equals` vectors reached by different merge orders produce different bytes. It does not
 * affect any figure below, because every vector here is built once and priced by *size*, and CBOR
 * map size is order-independent. It would matter to an implementation, which is why #1986 lists it
 * as a prerequisite.
 *
 * **Build strategy.** `ORSet.add`/`ORMap.put` copy the whole entry map per call, so a fold is
 * O(n²) and 100k entries is out of reach. The sweep therefore builds the underlying
 * `Causal<DotMap<…>>` — the representation `ORSet`/`ORMap` are documented thin wrappers over —
 * structurally, in O(n). Part (B) pins that: at three sizes the real CRDT's frame and the
 * structural frame differ by the *same* constant (the one-field wrapper), so the sweep is the real
 * type's cost plus a measured offset, not a model.
 *
 * Determinism mirrors the sibling scaling suites: [UnconfinedTestDispatcher], per-peer seeded RNG,
 * heartbeats pushed past the measurement window, `jitter = ZERO`, bounded virtual-time advance —
 * never `advanceUntilIdle`, which would hang rather than fail on the re-arming anti-entropy timer.
 */
class DotCausalDigestCostModelTest {

    private companion object {
        /**
         * A full-width stand-in for a real root, matching [MerkleDigestCostModelTest]: FNV-1a 64
         * over the encoded state is ~uniform over `Long` and pays CBOR's full 9-byte cost, so a
         * tidy placeholder like `-1L` would understate the frame by eight bytes.
         */
        const val REPRESENTATIVE_ROOT = -0x5AA53CC31EE12DD2L

        /** The default `QuilterConfig.antiEntropyInterval`, in seconds — the egress denominator. */
        const val ANTI_ENTROPY_SECONDS = 60.0
    }

    /** Anti-entropy interval for the metered rounds in (E); far below `fullStateRetryInterval`. */
    private val antiEntropyInterval = 50.milliseconds

    /** Virtual-time steps used to settle handshakes and first-contact traffic before metering. */
    private val flushSteps = 32

    /** [buildInMemoryMesh] names peers `peer-N`, and [Quilter] defaults its replica to the peer id. */
    private val meshSender = ReplicaId("peer-0")

    private val replica = ReplicaId("replica-0")

    // ---- shapes under measurement ----------------------------------------------------------

    /** Entry counts swept, matching #1955's Phase 0 so the two tables are directly comparable. */
    private val sizes = listOf(1, 10, 100, 1_000, 10_000, 100_000)

    /** Replica counts swept. A dot-based type's metadata is O(#replicas) *and* O(#entries). */
    private val replicaCounts = listOf(1, 2, 4, 8, 16, 64, 256)

    /** Same element shape as #1955's `GSet` sweep, so ORSet-vs-GSet is a like-for-like ratio. */
    private fun element(i: Int) = "element-with-a-realistic-id-$i"

    /**
     * A short element id — a device/session handle rather than a descriptive key. The causal tax
     * of a dot-based type is a fixed number of bytes **per entry**, so the fraction of the frame it
     * consumes is set by how big the payload beside it is. This shape is the other end of that
     * range and is where #1986's "metadata can dominate the payload" concern is actually true.
     */
    private fun shortElement(i: Int) = "u%07d".format(i)

    /**
     * Fixed width on purpose. A dot carries its replica id in full, on **every entry**, so id
     * length is a direct multiplier on the per-entry causal tax — and `"replica-$r"` would widen
     * from 9 to 11 characters partway through the replica sweep, putting an artefact of the naming
     * scheme into a column that is supposed to isolate replica count. 11 characters is also
     * conservative against production: a UUID replica id is 36, which roughly triples the tax.
     */
    private fun replicaId(r: Int) = ReplicaId("replica-%03d".format(r))

    private val string = String.serializer()
    private val orSetStoreSerializer = DotMap.serializer(string, DotSet.serializer())
    private val orSetCausalSerializer = Causal.serializer(orSetStoreSerializer)
    private val orSetCausalFrame = QuiltMessage.serializer(orSetCausalSerializer)
    private val orSetFrame = QuiltMessage.serializer(ORSet.serializer(string))

    private val lwwSerializer = LWWRegister.serializer(string)
    private val orMapEntrySerializer = ORMapEntry.serializer(lwwSerializer)
    private val orMapStoreSerializer = DotMap.serializer(string, orMapEntrySerializer)
    private val orMapCausalSerializer = Causal.serializer(orMapStoreSerializer)
    private val orMapCausalFrame = QuiltMessage.serializer(orMapCausalSerializer)
    private val orMapFrame = QuiltMessage.serializer(ORMap.serializer(string, lwwSerializer))

    private val mvSerializer = MVRegister.serializer(string)
    private val mvFrame = QuiltMessage.serializer(mvSerializer)
    private val gsetFrame = QuiltMessage.serializer(GSet.serializer(string))

    private fun <T> bytesOf(serializer: KSerializer<T>, value: T): Int =
        Cbor.encodeToByteArray(serializer, value).size

    private fun <S> frameBytes(serializer: KSerializer<QuiltMessage<S>>, state: S, sender: ReplicaId = replica): Int =
        bytesOf(serializer, QuiltMessage.FullState(sender, state))

    /**
     * The causal history of [n] entries spread round-robin over [replicas] replicas.
     *
     * Round-robin assignment gives every replica a **contiguous** seq run, so each
     * [DotContext.add] compacts straight into the version vector and the cloud stays empty —
     * the steady state a converged replica is actually in, and O(n·replicas) to build rather
     * than the O(n²) a gap-holding cloud would cost.
     */
    private fun contextOf(n: Int, replicas: Int): DotContext {
        var context = DotContext.EMPTY
        val seqs = LongArray(replicas)
        for (i in 0 until n) {
            val r = i % replicas
            context = context.add(Dot(replicaId(r), ++seqs[r]))
        }
        return context
    }

    /** The dot the round-robin assignment gives entry [i]. */
    private fun dotFor(i: Int, replicas: Int): Dot =
        Dot(replicaId(i % replicas), (i / replicas + 1).toLong())

    /** The `Causal` an [ORSet] of [n] elements added by [replicas] replicas wraps. */
    private fun orSetCausal(
        n: Int,
        replicas: Int,
        naming: (Int) -> String = ::element,
    ): Causal<DotMap<String, DotSet>> {
        val entries = LinkedHashMap<String, DotSet>(n.coerceAtLeast(1))
        for (i in 0 until n) entries[naming(i)] = DotSet(setOf(dotFor(i, replicas)))
        return Causal(DotMap(entries), contextOf(n, replicas))
    }

    /** The `Causal` an [ORMap] of [n] `LWWRegister` values put by [replicas] replicas wraps. */
    private fun orMapCausal(n: Int, replicas: Int): Causal<DotMap<String, ORMapEntry<LWWRegister<String>>>> {
        val entries = LinkedHashMap<String, ORMapEntry<LWWRegister<String>>>(n.coerceAtLeast(1))
        for (i in 0 until n) {
            val dot = dotFor(i, replicas)
            val value = LWWRegister.empty<String>().set(dot.replica, dot.seq, "value-$i")
            entries[element(i)] = ORMapEntry(DotSet(setOf(dot)), value)
        }
        return Causal(DotMap(entries), contextOf(n, replicas))
    }

    /** An [MVRegister] with [writers] mutually-concurrent writes — its whole state is O(writers). */
    private fun mvRegisterOf(writers: Int): MVRegister<String> =
        (0 until writers)
            .map { MVRegister.empty<String>().set(replicaId(it), "value-$it") }
            .reduce { a, b -> a.piece(b) }

    private fun gsetOf(n: Int, naming: (Int) -> String = ::element): GSet<String> =
        GSet.of(*Array(n) { naming(it) })

    // ---- A. what a full-state anti-entropy round costs for the dot-based types --------------

    @Test
    fun fullStateWireBytesForDotBasedTypesScaleLinearlyWithEntryCount() {
        val orSet = sizes.map { it to frameBytes(orSetCausalFrame, orSetCausal(it, replicas = 4)) + orSetWrapper() }
        val orMap = sizes.map { it to frameBytes(orMapCausalFrame, orMapCausal(it, replicas = 4)) + orMapWrapper() }
        val gset = sizes.map { it to frameBytes(gsetFrame, gsetOf(it)) }

        println("\n=== #1986 Phase 0 (A): measured QuiltMessage.FullState wire bytes, 4 replicas (Cbor) ===")
        println("  %9s %12s %9s %12s %9s %12s %9s".format(
            "entries", "ORSet b", "b/entry", "ORMap b", "b/entry", "GSet b", "ORSet/GSet"))
        sizes.forEachIndexed { i, n ->
            println(
                "  %9d %12d %9.1f %12d %9.1f %12d %9.2fx".format(
                    n, orSet[i].second, orSet[i].second.toDouble() / n,
                    orMap[i].second, orMap[i].second.toDouble() / n,
                    gset[i].second, orSet[i].second.toDouble() / gset[i].second,
                ),
            )
        }
        println("  slope (b/entry, 10k->100k): ORSet=${slope(orSet)}, ORMap=${slope(orMap)}, GSet=${slope(gset)}")

        // The other axis. The store carries one dot per entry whatever the replica count, so the
        // only genuinely R-dependent part of a full state is the context — and (C) shows that is
        // under 0.1% of a 10k-entry frame even at 256 replicas.
        println("\n  Same frame at n = 10,000, swept over replica count:")
        println("  %9s %14s %16s %14s".format("replicas", "ORSet b", "vs R=1", "context b"))
        val atOneReplica = frameBytes(orSetCausalFrame, orSetCausal(10_000, 1)) + orSetWrapper()
        replicaCounts.forEach { r ->
            val bytes = frameBytes(orSetCausalFrame, orSetCausal(10_000, r)) + orSetWrapper()
            println("  %9d %14d %15.4fx %14d".format(
                r, bytes, bytes.toDouble() / atOneReplica, contextBytes(10_000, r)))
        }

        // Linearity is what licenses extrapolating past 100k, exactly as in #1955's part (A).
        listOf("ORSet" to orSet, "ORMap" to orMap).forEach { (name, rows) ->
            val mid = segmentSlope(rows, 3, 4)
            val top = segmentSlope(rows, 4, 5)
            assertTrue(
                top in (mid * 0.9)..(mid * 1.1),
                "$name per-entry wire cost must be flat to extrapolate (1k->10k=$mid vs 10k->100k=$top b/entry)",
            )
        }
    }

    private fun segmentSlope(rows: List<Pair<Int, Int>>, lo: Int, hi: Int): Double =
        (rows[hi].second - rows[lo].second).toDouble() / (rows[hi].first - rows[lo].first)

    private fun slope(rows: List<Pair<Int, Int>>): Double = segmentSlope(rows, 4, 5)

    // ---- B. the pin: the structural builders encode what the real CRDTs encode ---------------

    /**
     * The constant a real [ORSet] frame costs over the [Causal] frame it wraps: CBOR's one-field
     * map header plus the field name. Measured, never assumed — part (B) asserts it is the same at
     * three sizes, which is what makes the O(n) structural sweep a measurement of `ORSet`.
     */
    private fun orSetWrapper(): Int =
        frameBytes(orSetFrame, realOrSet(1, 1)) - frameBytes(orSetCausalFrame, orSetCausal(1, 1))

    private fun orMapWrapper(): Int =
        frameBytes(orMapFrame, realOrMap(1, 1)) - frameBytes(orMapCausalFrame, orMapCausal(1, 1))

    /** An [ORSet] built the way a consumer builds one — through the public API, one `add` per element. */
    private fun realOrSet(n: Int, replicas: Int): ORSet<String> {
        var set = ORSet.empty<String>()
        for (i in 0 until n) set = set.add(replicaId(i % replicas), element(i))
        return set
    }

    private fun realOrMap(n: Int, replicas: Int): ORMap<String, LWWRegister<String>> {
        var map = ORMap.empty<String, LWWRegister<String>>()
        for (i in 0 until n) {
            val id = replicaId(i % replicas)
            map = map.put(id, element(i), LWWRegister.empty<String>().set(id, (i / replicas + 1).toLong(), "value-$i"))
        }
        return map
    }

    @Test
    fun structuralBuildersPriceTheRealCrdtsPlusOneConstantWrapper() {
        // Sized so the O(n^2) real-API fold stays under a second: the point is that the offset is
        // constant across three decades, not that it holds at 100k.
        val pinSizes = listOf(1, 100, 2_000)
        val orSetDeltas = pinSizes.map { n ->
            frameBytes(orSetFrame, realOrSet(n, 1)) - frameBytes(orSetCausalFrame, orSetCausal(n, 1))
        }
        val orMapDeltas = pinSizes.map { n ->
            frameBytes(orMapFrame, realOrMap(n, 1)) - frameBytes(orMapCausalFrame, orMapCausal(n, 1))
        }

        println("\n=== #1986 Phase 0 (B): structural builder vs the real CRDT, single replica ===")
        pinSizes.forEachIndexed { i, n ->
            println("  n=%6d  ORSet frame - Causal frame = %3d b   ORMap frame - Causal frame = %3d b"
                .format(n, orSetDeltas[i], orMapDeltas[i]))
        }

        assertAll(
            // A constant offset means the structural builder reproduces the real CRDT's encoding
            // exactly and only the one-field wrapper differs — so (A)'s sweep is a measurement of
            // ORSet/ORMap, not of a model that happens to resemble them.
            { assertEquals(1, orSetDeltas.toSet().size, "ORSet wrapper overhead must be constant: $orSetDeltas") },
            { assertEquals(1, orMapDeltas.toSet().size, "ORMap wrapper overhead must be constant: $orMapDeltas") },
            { assertEquals(orSetDeltas.first(), orSetWrapper(), "orSetWrapper() must be the measured constant") },
            { assertEquals(orMapDeltas.first(), orMapWrapper(), "orMapWrapper() must be the measured constant") },
        )
    }

    // ---- C. how much of the frame is causal metadata, and where it stops dominating ----------

    /**
     * The context alone, priced by its own serializer. This is the quantity #1986 calls "O(#replicas)"
     * — and it *is* O(#replicas) once compacted. It is also, for the dot-based types, the *smaller*
     * half of the causal metadata: the per-entry dots inside the store are O(#entries) and repeat the
     * replica id on every entry.
     */
    private fun contextBytes(n: Int, replicas: Int): Int =
        bytesOf(DotContext.serializer(), contextOf(n, replicas))

    @Test
    fun causalMetadataIsAPerEntryTaxWhileTheContextItselfVanishes() {
        val replicas = 4
        println("\n=== #1986 Phase 0 (C): causal metadata inside an ORSet FullState frame, $replicas replicas ===")
        println("  Two element shapes: a 28-char descriptive id (#1955's shape) and an 8-char handle.")
        println("  %9s %11s %10s %10s %11s %10s %12s %10s".format(
            "entries", "ORSet b", "GSet b", "meta %", "short OR b", "short GS b", "short meta %", "context b"))
        sizes.forEach { n ->
            val orSet = frameBytes(orSetCausalFrame, orSetCausal(n, replicas)) + orSetWrapper()
            val gset = frameBytes(gsetFrame, gsetOf(n))
            val shortOrSet = frameBytes(orSetCausalFrame, orSetCausal(n, replicas, ::shortElement)) + orSetWrapper()
            val shortGset = frameBytes(gsetFrame, gsetOf(n, ::shortElement))
            println("  %9d %11d %10d %9.1f%% %11d %10d %11.1f%% %10d".format(
                n, orSet, gset, (orSet - gset) * 100.0 / orSet,
                shortOrSet, shortGset, (shortOrSet - shortGset) * 100.0 / shortOrSet,
                contextBytes(n, replicas)))
        }
        println("  Causal metadata is a flat PER-ENTRY tax, not a fixed overhead — its share of the")
        println("  frame is set by the payload beside it, and it never amortises away with scale.")

        println("\n  DotContext bytes as a fraction of the ORSet frame, by (entries x replicas):")
        println("  %9s %s".format("entries", replicaCounts.joinToString("") { "R=%-4d%5s".format(it, "") }))
        sizes.forEach { n ->
            val cells = replicaCounts.joinToString("") { r ->
                val orSet = frameBytes(orSetCausalFrame, orSetCausal(n, r)) + orSetWrapper()
                "%8.2f%% ".format(contextBytes(n, r) * 100.0 / orSet)
            }
            println("  %9d %s".format(n, cells))
        }
        replicaCounts.forEach { r ->
            val crossover = sizes.firstOrNull { n ->
                contextBytes(n, r) * 100.0 / (frameBytes(orSetCausalFrame, orSetCausal(n, r)) + orSetWrapper()) < 1.0
            }
            println("  R=%-4d context falls below 1%% of the frame at n = %s".format(r, crossover ?: "> 100000"))
        }

        println("\n  context at n=1,      R=256: ${contextBytes(1, 256)} b (only one replica can have written)")
        println("  context at n=100000, R=4  : ${contextBytes(100_000, 4)} b — flat in entries, as #1986 says")

        assertAll(
            // The context genuinely is O(replicas), not O(entries): four decades of entry growth
            // move it by a handful of bytes, and only because the seq values themselves widen.
            {
                assertTrue(
                    contextBytes(100_000, 4) < contextBytes(4, 4) + 16,
                    "DotContext must be ~flat in entry count once compacted " +
                        "(n=4: ${contextBytes(4, 4)} b, n=100000: ${contextBytes(100_000, 4)} b) — it grows " +
                        "only as log(seq), which is why #1986 calls it O(#replicas)",
                )
            },
            // But the *frame* is not: the per-entry dots dominate, so a context-sized gate frame is
            // not a proxy for a state-sized one. This is the number that decides (D).
            {
                val orSet = frameBytes(orSetCausalFrame, orSetCausal(100_000, 4)) + orSetWrapper()
                assertTrue(
                    contextBytes(100_000, 4) < orSet / 1_000,
                    "the context must be a negligible slice of a 100k-entry frame " +
                        "(context ${contextBytes(100_000, 4)} b vs frame $orSet b)",
                )
            },
            // #1986's stated risk — "the metadata can dominate the payload at low entry counts" —
            // inverted: the *context* only dominates when there is nearly nothing else in the
            // frame, while the per-entry dot tax dominates a short-id payload at EVERY size.
            {
                val shortOrSet = frameBytes(orSetCausalFrame, orSetCausal(100_000, 4, ::shortElement)) + orSetWrapper()
                val shortGset = frameBytes(gsetFrame, gsetOf(100_000, ::shortElement))
                assertTrue(
                    shortOrSet - shortGset > shortOrSet / 2,
                    "with short element ids the per-entry causal tax must exceed the payload even at " +
                        "100k entries (metadata ${shortOrSet - shortGset} b of $shortOrSet b) — it is a " +
                        "per-entry cost, so scale never dilutes it",
                )
            },
        )
    }

    // ---- D. the gate frame: a version vector vs the root digest that already ships ------------

    /**
     * The **shipped** converged-round frame, priced from `QuiltMessage.RootDigest` itself rather
     * than a probe, exactly as [MerkleDigestCostModelTest] does — and flat in *both* state size and
     * replica count, which is the comparison #1986 turns on.
     */
    private fun rootDigestBytes(sender: ReplicaId = replica): Int = bytesOf(
        orSetFrame,
        QuiltMessage.RootDigest<ORSet<String>>(sender = sender, root = REPRESENTATIVE_ROOT, upThrough = 1L),
    )

    private fun ackBytes(sender: ReplicaId = replica): Int =
        bytesOf(orSetFrame, QuiltMessage.Ack<ORSet<String>>(acker = sender, sender = sender, seq = 1L))

    /** A whole converged round on the shipped path: the digest out and the matched peer's ack back. */
    private fun rootDigestRoundBytes(sender: ReplicaId = replica): Int = rootDigestBytes(sender) + ackBytes(sender)

    /**
     * The frame #1986's gate would send: the sender's version vector. Priced from the **existing**
     * `QuiltMessage.Delivered`, which is the frame the issue points at — no probe needed.
     *
     * Note the vector is `VersionVector`, the dense prefix only. A sound "what have you not seen"
     * needs the whole [DotContext], cloud included, whenever frames can arrive out of order — which
     * on a kuilt fabric they can, by contract. [dotContextExchangeBytes] prices that stricter form.
     */
    private fun versionVectorBytes(replicas: Int, sender: ReplicaId = replica): Int = bytesOf(
        orSetFrame,
        QuiltMessage.Delivered<ORSet<String>>(
            sender = sender,
            vector = VersionVector((0 until replicas).associate { replicaId(it) to (it + 1L) * 1_000L }),
        ),
    )

    /** The same exchange carrying a full compacted [DotContext] instead of the dense prefix. */
    private fun dotContextExchangeBytes(replicas: Int): Int =
        bytesOf(DotContext.serializer(), contextOf(replicas * 4, replicas)) + versionVectorEnvelope()

    /** The `Delivered` envelope cost: sender id plus the sealed-variant tag, with an empty vector. */
    private fun versionVectorEnvelope(): Int = versionVectorBytes(replicas = 0)

    @Test
    fun versionVectorExchangeGrowsWithReplicasWhileTheRootDigestDoesNot() {
        println("\n=== #1986 Phase 0 (D): the gate frame — vector vs the digest that already ships ===")
        println("  RootDigest out (shipped)      : ${rootDigestBytes()} b — flat in entries AND replicas")
        println("  Ack back on a match           : ${ackBytes()} b")
        println("  whole matched round (shipped) : ${rootDigestRoundBytes()} b")
        println("\n  %9s %16s %14s %20s %14s".format(
            "replicas", "Delivered(vv) b", "vs digest", "DotContext exch. b", "vs digest"))
        replicaCounts.forEach { r ->
            val vv = versionVectorBytes(r)
            val ctx = dotContextExchangeBytes(r)
            println("  %9d %16d %13.2fx %20d %13.2fx".format(
                r, vv, vv.toDouble() / rootDigestBytes(), ctx, ctx.toDouble() / rootDigestBytes()))
        }

        // MVRegister has no entry dimension at all: its whole state is O(#concurrent writers), the
        // same order as the vector that would gate it. Price the state against its own gate.
        println("\n  MVRegister — the state is already O(#writers), so a vector gate is near-vacuous:")
        println("  %9s %16s %16s %12s".format("writers", "FullState b", "Delivered(vv) b", "state/vv"))
        replicaCounts.forEach { r ->
            val state = frameBytes(mvFrame, mvRegisterOf(r))
            val vv = versionVectorBytes(r)
            println("  %9d %16d %16d %11.2fx".format(r, state, vv, state.toDouble() / vv))
        }

        assertAll(
            // The whole point: #1955's digest is flat, #1986's vector is not. Past a handful of
            // replicas the proposed gate frame is strictly larger than the one already shipping.
            {
                assertTrue(
                    versionVectorBytes(16) > rootDigestBytes(),
                    "a 16-replica vector (${versionVectorBytes(16)} b) must exceed the flat digest " +
                        "(${rootDigestBytes()} b) — that is the cost side of #1986's gate",
                )
            },
            // And MVRegister's own state is the same order as the vector, so gating it saves
            // a small multiple at best — before any correctness question is asked.
            {
                val state = frameBytes(mvFrame, mvRegisterOf(16))
                assertTrue(
                    state < versionVectorBytes(16) * 4,
                    "an MVRegister's full state ($state b) must be within a small factor of the " +
                        "vector that would gate it (${versionVectorBytes(16)} b)",
                )
            },
        )
    }

    // ---- E. grounding: what a converged ORSet round actually costs on a MeteredSeam ------------

    /**
     * Stands up an [n]-node metered mesh replicating the same [ORSet] and returns the bytes
     * [rounds] converged anti-entropy rounds put on the wire cluster-wide.
     *
     * **Every node applies one local mutation before the meter opens**, for the reason
     * [MerkleDigestCostModelTest.meterConvergedRounds] documents and this suite's brief flags as
     * the trap that has bitten three times: a replica that has never called `apply` sits at
     * `nextSeq == 0`, so its digest carries `upThrough = 0` and the recipient's
     * `resyncReceiveCursor` returns at its `upThrough <= 0` guard *before* acking. Metering that
     * mesh prices digest-out-with-nothing-back and silently halves the published round.
     * `roundsCarryTheMatchedAck` below is the assertion that keeps that regression red.
     */
    private suspend fun TestScope.meterConvergedOrSetRounds(n: Int, state: ORSet<String>, rounds: Int): Long {
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
                valueSerializer = ORSet.serializer(string),
                scope = backgroundScope,
                config = QuilterConfig(expectVirtualTime = true, antiEntropyInterval = antiEntropyInterval),
                random = Random(100),
            )
        }
        flush()

        quilters.forEachIndexed { i, quilter ->
            quilter.mutate { Patch(it.add(ReplicaId("peer-$i"), "converged-writer-$i")) }
        }
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

    @Test
    fun convergedOrSetRoundAlreadyShipsADigestNotTheState() = runTest(UnconfinedTestDispatcher()) {
        val n = 4
        val rounds = 20
        val stateSize = 200

        val shared = realOrSet(stateSize, replicas = 4)
        val measured = meterConvergedOrSetRounds(n = n, state = shared, rounds = rounds)

        val fullState = frameBytes(orSetFrame, shared, meshSender)
        val digest = rootDigestBytes(meshSender)
        val modelledRound = rootDigestRoundBytes(meshSender)
        val perNodeRound = measured.toDouble() / (rounds * n)

        println("\n=== #1986 grounding (E): converged ORSet round, $stateSize entries, $n nodes ===")
        println("  bytes/node/round measured  : ${"%.1f".format(perNodeRound)}")
        println("  modelled matched round     : $modelledRound (digest $digest + ack ${ackBytes(meshSender)})")
        println("  framing overhead           : ${"%.1f".format(perNodeRound - modelledRound)} b " +
            "(MeteredSeam bytes minus encoded frames)")
        println("  full state would have been : $fullState")
        println("  reduction already in place : ${"%.0f".format(fullState / perNodeRound)}x")

        assertAll(
            // Premise 1 of #1986, falsified: the dot-based types are ALREADY digest-gated, because
            // `Quilter.stateRoot()` is generic over S. A converged ORSet node does not ship state.
            {
                assertTrue(
                    perNodeRound < fullState / 20.0,
                    "a converged ORSet round already costs far less than the state " +
                        "($perNodeRound b vs $fullState b) — #1955's RootDigest is type-generic",
                )
            },
            // The `upThrough = 0` trap: a matched round is digest-out PLUS ack-back. A harness
            // whose nodes never wrote prices at half, and looks like a better result.
            {
                assertTrue(
                    perNodeRound > digest * 1.2,
                    "a matched round must carry the ack back, not the digest alone " +
                        "(metered $perNodeRound b vs one digest $digest b)",
                )
            },
            // Grounding proper: the wire must carry the modelled round and essentially nothing
            // else, so every figure in (D) priced from the codec is a wire figure.
            {
                assertTrue(
                    perNodeRound < modelledRound * 1.5,
                    "a converged round must be one digest plus its ack, not several frames " +
                        "(metered $perNodeRound b vs modelled $modelledRound b)",
                )
            },
        )
    }

    // ---- F. the steady-state saving, and what a vector gate would add to it -------------------

    @Test
    fun quiescentRoundSavingRatioTable() {
        val replicas = 4
        val digestRound = rootDigestRoundBytes()
        println("\n=== #1986 Phase 0 (F): steady-state egress per node, ${ANTI_ENTROPY_SECONDS.toInt()}s interval ===")
        println("  ORSet, $replicas replicas. 'vector round' = one Delivered(vv) out + one Ack back.")
        println("  %9s %14s %14s %14s %12s %14s".format(
            "entries", "full state b", "digest rnd b", "vector rnd b", "digest sav", "vector sav"))
        sizes.forEach { n ->
            val full = frameBytes(orSetCausalFrame, orSetCausal(n, replicas)) + orSetWrapper()
            val vectorRound = versionVectorBytes(replicas) + ackBytes()
            println("  %9d %14d %14d %14d %11.0fx %13.0fx".format(
                n, full, digestRound, vectorRound, full.toDouble() / digestRound, full.toDouble() / vectorRound))
        }

        println("\n  Egress at the default interval (B/s per node), and what #1986 would add:")
        println("  %9s %16s %16s %16s".format("entries", "pre-#1955 B/s", "today B/s", "with a vector B/s"))
        sizes.forEach { n ->
            val full = frameBytes(orSetCausalFrame, orSetCausal(n, replicas)) + orSetWrapper()
            val vectorRound = versionVectorBytes(replicas) + ackBytes()
            println("  %9d %16.1f %16.2f %16.2f".format(
                n, full / ANTI_ENTROPY_SECONDS, digestRound / ANTI_ENTROPY_SECONDS,
                vectorRound / ANTI_ENTROPY_SECONDS))
        }

        // The verdict on the quiescent half: whatever #1986's vector round costs, it is measured
        // against a round that already costs a flat ~100 bytes — so the *incremental* saving is at
        // best a few tens of bytes per round and at worst negative. The 34,000x is already banked.
        val vectorRound = versionVectorBytes(replicas) + ackBytes()
        assertTrue(
            vectorRound >= digestRound,
            "a $replicas-replica vector round ($vectorRound b) is not cheaper than the shipped " +
                "digest round ($digestRound b) — there is no quiescent saving left for #1986 to take",
        )
    }

    // ---- G. the decisive finding: equal contexts do not imply equal states -------------------

    /**
     * The `Causal` mirror of an [ORSet] operation sequence, built from the public constructors
     * `ORSet` itself uses. `add` mints a dot and records it; `remove` drops the dots and **leaves
     * the context alone** — quoting `ORSet.remove`: *"drop the dots currently on it. The context is
     * unchanged (those dots stay witnessed, so the removal propagates on merge)."*
     */
    private fun mirrorAdd(state: Causal<DotMap<String, DotSet>>, replica: ReplicaId, element: String) =
        state.context.nextDot(replica).let { dot ->
            Causal(DotMap(state.store.entries + (element to DotSet(setOf(dot)))), state.context.add(dot))
        }

    private fun mirrorRemove(state: Causal<DotMap<String, DotSet>>, element: String) =
        Causal(DotMap(state.store.entries - element), state.context)

    @Test
    fun equalCausalContextsDoNotImplyConvergedOrSetStates() {
        // Three operations, all reachable, none exotic:
        //   1. A adds "x"     2. B absorbs A's add     3. A removes "x", and the delta is LOST.
        // Anti-entropy is the *only* backstop left: the delta path detects a gap only if A mints
        // again, and A has gone quiet.
        val a0 = ORSet.empty<String>().add(ReplicaId("A"), "x")
        val b = a0
        val a = a0.remove("x")

        val mirrorA0 = mirrorAdd(Causal(DotMap(), DotContext.EMPTY), ReplicaId("A"), "x")
        val mirrorB = mirrorA0
        val mirrorA = mirrorRemove(mirrorA0, "x")

        val contextA = bytesOf(DotContext.serializer(), mirrorA.context)
        val contextB = bytesOf(DotContext.serializer(), mirrorB.context)
        val stateA = bytesOf(orSetFrame, QuiltMessage.FullState(ReplicaId("A"), a))
        val stateB = bytesOf(orSetFrame, QuiltMessage.FullState(ReplicaId("A"), b))

        println("\n=== #1986 Phase 0 (G): a version-vector gate cannot see an observed remove ===")
        println("  A.elements = ${a.elements}   B.elements = ${b.elements}   ->  DIVERGENT")
        println("  A.context  = ${mirrorA.context}")
        println("  B.context  = ${mirrorB.context}   ->  IDENTICAL ($contextA b vs $contextB b)")
        println("  encoded states: A=$stateA b, B=$stateB b -> the shipped root hash DIFFERS and heals")
        println("  a vector gate would report 'converged' and skip the heal, forever")

        assertAll(
            // The states really do differ, and the merge really does heal them — so this is a
            // detection failure, not a lattice bug.
            { assertNotEquals(a.elements, b.elements, "the two replicas must actually be divergent") },
            { assertEquals(b.piece(a).elements, a.elements, "the causal merge still heals the divergence") },
            // And the context — the thing #1986 proposes to compare — is identical, structurally
            // and byte-for-byte. A vector derived from it is identical too, a fortiori: a
            // VersionVector is the context's dense prefix.
            { assertEquals(mirrorA.context, mirrorB.context, "contexts must be equal despite the divergence") },
            {
                assertEquals(
                    contextA, contextB,
                    "contexts must encode identically, so no digest of the vector can distinguish them",
                )
            },
            // While the state encodings differ, which is exactly what the shipped RootDigest hashes.
            {
                assertNotEquals(
                    stateA, stateB,
                    "the shipped root hash is over the whole state, so it does catch this",
                )
            },
            // The stores differ while the contexts do not — the structural statement of the finding.
            { assertNotEquals(mirrorA.store.entries.keys, mirrorB.store.entries.keys, "stores must differ") },
        )
    }

    // ---- H. the same gap defeats the diff, not just the gate ---------------------------------

    @Test
    fun aDotPreciseDiffNeedsTheLiveDotSetNotJustTheContext() {
        // #1986's diff would be "ship the entries whose dots are outside your context". Whichever
        // context the sender attaches to that partial store, the result is not a delta.
        //
        // (1) ORSet with the FULL context: the receiver drops every key the diff omitted. A
        //     context-covered dot that is missing from the accompanying store *is* the remove
        //     signal — that is exactly what makes `ORSet.remove` propagate — so "omitted because
        //     you already have it" and "omitted because I deleted it" are the same wire bytes.
        val senderA = mirrorAdd(Causal(DotMap(), DotContext.EMPTY), ReplicaId("A"), "already-has")
        val senderAB = mirrorAdd(senderA, ReplicaId("A"), "newly-added")
        val receiver = senderA
        val diffWithFullContext =
            Causal(DotMap(senderAB.store.entries.filterKeys { it == "newly-added" }), senderAB.context)
        val afterFullContextDiff = receiver.piece(diffWithFullContext)

        // (2) MVRegister with a PARTIAL context: the receiver keeps the superseded write and
        //     reports a phantom concurrent conflict. So neither context choice works.
        val latest = MVRegister.empty<String>().set(ReplicaId("A"), "v1").set(ReplicaId("A"), "v2")
        val secondDot = Dot(ReplicaId("A"), 2L)
        val diffWithPartialContext = Causal(DotFun(mapOf(secondDot to "v2")), DotContext.of(secondDot))
        val mvReceiver = Causal(DotFun(mapOf(Dot(ReplicaId("A"), 1L) to "v1")), DotContext.of(Dot(ReplicaId("A"), 1L)))
        val afterPartialContextDiff = mvReceiver.piece(diffWithPartialContext)

        println("\n=== #1986 Phase 0 (H): a context diff is not a delta for these types ===")
        println("  ORSet — sender holds ${senderAB.store.entries.keys}, " +
            "receiver holds ${receiver.store.entries.keys}")
        println("    diff = the one entry outside the receiver's context, shipped with the full context")
        println("    receiver ends at ${afterFullContextDiff.store.entries.keys} — \"already-has\" was DELETED")
        println("  MVRegister — correct answer ${latest.values}, partial-context diff yields " +
            "${afterPartialContextDiff.store.values.values.toSet()} (phantom conflict)")
        println("  Only a full store comparison is sound — which is what the shipped root hash does.")

        assertAll(
            // Full context + partial store silently deletes whatever the diff omitted.
            {
                assertEquals(
                    setOf("newly-added"), afterFullContextDiff.store.entries.keys,
                    "a full context with a partial store deletes every omitted key — not a delta",
                )
            },
            // The partial-context form resurrects superseded writes instead.
            {
                assertEquals(
                    setOf("v1", "v2"), afterPartialContextDiff.store.values.values.toSet(),
                    "a partial context leaves the superseded write live — a phantom conflict",
                )
            },
            { assertEquals(setOf("v2"), latest.values, "the correct MVRegister answer is the single latest write") },
        )
    }
}
