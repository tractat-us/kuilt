@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.scale

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.cbor.CborArray
import kotlinx.serialization.cbor.CborLabel
import us.tractat.kuilt.crdt.Causal
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.DotContext
import us.tractat.kuilt.crdt.DotMap
import us.tractat.kuilt.crdt.DotSet
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.LWWMap
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Quilted
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.gossip.GossipSeam
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Phase-0 measurement for **#2037** — "dot-based payloads carry a flat 47–78% per-entry causal tax
 * that scale never dilutes" — and the gate on whether any of the encodings that issue names should
 * be built. Third in the line started by [MerkleDigestCostModelTest] (#1955) and continued by
 * [DotCausalDigestCostModelTest] (#1986), and it keeps their method: **price every frame through
 * the real `Cbor` codec [Quilter] uses**, then ground the model on bytes a [MeteredSeam] actually
 * counted. Nothing here is built; the deliverable is the table and a go/no-go.
 *
 * #2037 names three directions. This suite prices all three plus one it did not anticipate, and
 * the ranking is not the one the issue expects:
 *
 * 1. **Replica-id interning** — a per-frame id table with entries carrying a small integer index.
 *    Real, and the *only* candidate whose saving grows with id width, so it is the one that matters
 *    for a UUID replica id. Part (D).
 * 2. **Dot run-length / delta encoding** — priced in part (F), where it is disqualified by the very
 *    constraint #2037 flags: canonical order is **key** order, dot density lives in **mint** order,
 *    and they are the same order only for a key scheme that happens to sort the way it was written.
 * 3. **Whether the joining-peer path is the real consumer** — part (H). It is not the joining peer.
 *    Every path that costs O(n) is the *same frame*, and the one that ships it most often on a live
 *    room is the **local mutation**, because `ORSet` has no delta mutator: the canonical
 *    `Patch(state.add(…))` puts the entire state on the wire once per write. Metered in part (I).
 * 4. **Not in #2037: the field names.** Every dot on the wire spells out `"replica"` and `"seq"` in
 *    full — 12 of the ~31-byte tax before a single byte of identity. Part (E) prices dropping them,
 *    which needs **no table, no protocol, and no new frame** — one annotation and one codec flag.
 *
 * **Method note — the pin comes first.** Every candidate is a *probe* class encoded by the real
 * codec, so the numbers are only measurements if the **baseline** probe reproduces today's bytes
 * exactly. Part (B) asserts byte-for-byte equality between the mirror and a real
 * `Causal<DotMap<String, DotSet>>` frame at four shapes, and every later part varies exactly one
 * thing away from that mirror. Without (B) this file would be a model wearing a measurement's
 * clothes.
 *
 * **Canonicality is a hard gate, not a caveat.** `Quilter.stateRoot()` hashes the state as it
 * appears on the wire, so equal states must encode to identical bytes on every peer or #1955's
 * digest gate silently stops engaging for that pair. Part (G) is a byte-parity test per surviving
 * candidate, including the negative control: the *same* interning candidate with a first-seen
 * table order — the `LinkedHashSet`-in-merge-order shape that has now gone wrong four times
 * (#1957 / #1978 / #2010 / #1979) — and it does diverge.
 *
 * **Build strategy** is [DotCausalDigestCostModelTest]'s: `ORSet.add` copies the whole entry map
 * per call, so a fold is O(n²) and 100k is out of reach. The sweeps build the underlying
 * `Causal<DotMap<…>>` structurally in O(n), and part (B) is what licenses that.
 *
 * Determinism mirrors the sibling suites: [UnconfinedTestDispatcher], per-peer seeded RNG,
 * heartbeats pushed past the measurement window, `jitter = ZERO`, bounded virtual-time advance —
 * never `advanceUntilIdle`, which would hang rather than fail on the re-arming anti-entropy timer.
 */
class DotWireEncodingCostModelTest {

    private companion object {
        /** The default `QuilterConfig.antiEntropyInterval`, in seconds — the egress denominator. */
        const val ANTI_ENTROPY_SECONDS = 60.0

        /** Seconds in an hour — the budget denominator in part (H). */
        const val SECONDS_PER_HOUR = 3_600.0

        /**
         * A full-width stand-in for a real root, matching both predecessor suites: FNV-1a 64 over
         * the encoded state is ~uniform over `Long` and pays CBOR's full 9-byte cost.
         */
        const val REPRESENTATIVE_ROOT = -0x5AA53CC31EE12DD2L
    }

    // ---- shapes under measurement ------------------------------------------------------------

    /** Entry counts swept, matching #1955/#1986 so all three tables are directly comparable. */
    private val sizes = listOf(1, 10, 100, 1_000, 10_000, 100_000)

    /**
     * Entry counts part (I) meters one write against, on a real mesh. Shorter than [sizes] and
     * 4x-spaced: each row stands a mesh up and floods a full state across it, and the property
     * under test — flatness — needs a wide span rather than many points. Quoted at exactly these
     * three in #2044's design, so the two tables are directly comparable.
     */
    private val writeSweepSizes = listOf(100, 400, WRITE_SWEEP_MAX)

    /** Replica counts swept. Interning's table cost is O(R); its saving is O(entries). */
    private val replicaCounts = listOf(1, 2, 4, 8, 16, 64, 256)

    /**
     * Replica-id widths swept, in characters. `11` is the sibling suites' shape; `36` is a raw
     * UUID, which #2037 identifies as the case where the tax is worst; `8` is a short device
     * handle; `2` is the practical floor — narrow enough that interning has almost nothing to
     * take, and still wide enough to name the 256 replicas the top of the replica sweep needs.
     */
    private val idWidths = listOf(MIN_ID_WIDTH, SHORT_ID_WIDTH, DEFAULT_ID_WIDTH, UUID_ID_WIDTH)

    private val replica = ReplicaId("replica-0")

    /** [buildInMemoryMesh] names peers `peer-N`, and [Quilter] defaults its replica to the peer id. */
    private val meshSender = ReplicaId("peer-0")

    /**
     * Anti-entropy interval for the metered rounds. Bracketed on both sides, and the bracket is
     * load-bearing for every number in part (I):
     *
     * - **Above** the virtual time a mesh spends before the round window opens (six [flush]es, so
     *   `6 * flushSteps` ms) — otherwise a round lands *inside* a metered write window and adds a
     *   digest-plus-ack of noise to it. At 50 ms it did: the pre-#2044 run metered one 400-entry
     *   full-state write at 228,297 b against a 25,330 b state, i.e. 9.01 link crossings rather
     *   than the 9 the flood actually makes, the excess being ~1.8 stray rounds. Harmless against
     *   a 228 kB signal; 12% against the ~1.6 kB delta this part now has to resolve.
     * - **Below** `QuilterConfig.resendRetryInterval` / `fullStateRetryInterval` (30 s each)
     *   divided by [ANTI_ENTROPY_ROUNDS], so twenty rounds of virtual time cannot trip a retry
     *   and charge the round window for a full state.
     */
    private val antiEntropyInterval = 1.seconds

    /** Virtual-time steps used to settle handshakes and first-contact traffic. */
    private val flushSteps = 32

    /** #1955's element shape, reused so ORSet-vs-GSet stays a like-for-like ratio. */
    private fun element(i: Int) = "element-with-a-realistic-id-$i"

    /**
     * A replica id of exactly [width] characters. Fixed width matters: a dot carries its replica id
     * in full on **every** entry, so a naming scheme that widens partway through a sweep puts an
     * artefact of the naming into the column that is supposed to isolate replica count.
     */
    private fun replicaIdOf(r: Int, width: Int): String {
        val digits = r.toString(RADIX_36)
        require(digits.length <= width) { "replica $r does not fit in $width characters" }
        // The pad character is deliberately outside the base-36 alphabet: padding with a real
        // digit would let a wide replica index collide with a narrow padded one.
        return "-".repeat(width - digits.length) + digits
    }

    private fun replicaId(r: Int) = ReplicaId(replicaIdOf(r, DEFAULT_ID_WIDTH))

    private val string = String.serializer()
    private val orSetStoreSerializer = DotMap.serializer(string, DotSet.serializer())
    private val orSetCausalSerializer = Causal.serializer(orSetStoreSerializer)
    private val orSetCausalFrame = QuiltMessage.serializer(orSetCausalSerializer)
    private val orSetFrame = QuiltMessage.serializer(ORSet.serializer(string))
    private val gsetFrame = QuiltMessage.serializer(GSet.serializer(string))

    private fun <T> bytes(serializer: KSerializer<T>, value: T): ByteArray =
        Cbor.encodeToByteArray(serializer, value)

    private fun <T> bytesOf(serializer: KSerializer<T>, value: T): Int = bytes(serializer, value).size

    private fun <S> frameBytes(serializer: KSerializer<QuiltMessage<S>>, state: S, sender: ReplicaId = replica): Int =
        bytesOf(serializer, QuiltMessage.FullState(sender, state))

    // ---- A. the probe families ---------------------------------------------------------------

    /**
     * Today's dot, restated as a probe: a two-field CBOR map that spells out both field names on
     * every occurrence. `ReplicaId` is a `value class` over `String`, so it inlines to a text
     * string and the probe's `String` is the same wire shape.
     */
    @Serializable
    private data class NamedDot(val replica: String, val seq: Long)

    @Serializable
    private data class NamedContext(val vv: Map<String, Long>, val cloud: List<NamedDot>)

    /** The baseline mirror. Part (B) asserts this encodes byte-for-byte as today's `Causal`. */
    @Serializable
    private data class NamedCausal(val store: Map<String, List<NamedDot>>, val context: NamedContext)

    /** **C1** — the replica id replaced by an index into a per-frame table. */
    @Serializable
    private data class InternedDot(val replica: Int, val seq: Long)

    @Serializable
    private data class InternedContext(val vv: Map<Int, Long>, val cloud: List<InternedDot>)

    /**
     * **C1** — [replicas] is the frame's id table. It is the **sorted distinct** set of ids
     * appearing anywhere in the value, so it is a pure function of the value and never of merge or
     * insertion order; see part (G), which pins that and shows the first-seen alternative failing.
     */
    @Serializable
    private data class InternedCausal(
        val replicas: List<String>,
        val store: Map<String, List<InternedDot>>,
        val context: InternedContext,
    )

    /** **C0-array** — the same two fields as [NamedDot], encoded as a CBOR array with no names. */
    @Serializable
    @CborArray
    private data class ArrayDot(val replica: String, val seq: Long)

    @Serializable
    private data class ArrayContext(val vv: Map<String, Long>, val cloud: List<ArrayDot>)

    @Serializable
    private data class ArrayCausal(val store: Map<String, List<ArrayDot>>, val context: ArrayContext)

    /** **C0-label** — names replaced by small integer CBOR labels; needs [labelledCbor]. */
    @Serializable
    private data class LabelDot(
        @CborLabel(1) val replica: String,
        @CborLabel(2) val seq: Long,
    )

    @Serializable
    private data class LabelContext(val vv: Map<String, Long>, val cloud: List<LabelDot>)

    @Serializable
    private data class LabelCausal(val store: Map<String, List<LabelDot>>, val context: LabelContext)

    /** **C0-array + C1** — the two levers stacked, to test whether they compose additively. */
    @Serializable
    @CborArray
    private data class ArrayInternedDot(val replica: Int, val seq: Long)

    @Serializable
    private data class ArrayInternedContext(val vv: Map<Int, Long>, val cloud: List<ArrayInternedDot>)

    @Serializable
    private data class ArrayInternedCausal(
        val replicas: List<String>,
        val store: Map<String, List<ArrayInternedDot>>,
        val context: ArrayInternedContext,
    )

    private val namedFrame = QuiltMessage.serializer(NamedCausal.serializer())
    private val internedFrame = QuiltMessage.serializer(InternedCausal.serializer())
    private val arrayFrame = QuiltMessage.serializer(ArrayCausal.serializer())
    private val labelFrame = QuiltMessage.serializer(LabelCausal.serializer())
    private val arrayInternedFrame = QuiltMessage.serializer(ArrayInternedCausal.serializer())

    /** `preferCborLabelsOverNames` only bites where `@CborLabel` is present, so it isolates [LabelDot]. */
    private val labelledCbor = Cbor { preferCborLabelsOverNames = true }

    /** Definite-length encoding: a codec flag, not a protocol change. Priced on the baseline mirror. */
    private val definiteCbor = Cbor { useDefiniteLengthEncoding = true }

    // ---- B. the pin: the baseline probe reproduces today's bytes exactly ----------------------

    /**
     * A `(key, dot)` view of the state under measurement — the one shape every probe family is
     * built from, so no two candidates can accidentally be built from different data.
     *
     * Round-robin replica assignment gives each replica a **contiguous** seq run, so every
     * [DotContext.add] compacts straight into the version vector and the cloud stays empty: the
     * steady state a converged replica is actually in, and O(n·R) to build rather than the O(n²) a
     * gap-holding cloud would cost.
     */
    private fun rows(n: Int, replicas: Int, width: Int, naming: (Int) -> String): List<Pair<String, Pair<String, Long>>> =
        (0 until n).map { i ->
            naming(i) to (replicaIdOf(i % replicas, width) to (i / replicas + 1).toLong())
        }

    private fun contextParts(n: Int, replicas: Int, width: Int): Map<String, Long> =
        (0 until replicas)
            .filter { r -> r < n }
            .associate { r -> replicaIdOf(r, width) to ((n - 1 - r) / replicas + 1).toLong() }

    private fun realCausal(n: Int, replicas: Int, width: Int, naming: (Int) -> String = ::element):
        Causal<DotMap<String, DotSet>> {
        val entries = LinkedHashMap<String, DotSet>(n.coerceAtLeast(1))
        rows(n, replicas, width, naming).forEach { (key, dot) ->
            entries[key] = DotSet(setOf(Dot(ReplicaId(dot.first), dot.second)))
        }
        var context = DotContext.EMPTY
        val seqs = LongArray(replicas)
        for (i in 0 until n) {
            val r = i % replicas
            context = context.add(Dot(ReplicaId(replicaIdOf(r, width)), ++seqs[r]))
        }
        return Causal(DotMap(entries), context)
    }

    private fun namedCausal(n: Int, replicas: Int, width: Int, naming: (Int) -> String = ::element): NamedCausal {
        val store = rows(n, replicas, width, naming)
            .sortedBy { it.first }
            .associate { (key, dot) -> key to listOf(NamedDot(dot.first, dot.second)) }
        return NamedCausal(store, NamedContext(contextParts(n, replicas, width).toSortedMap(), emptyList()))
    }

    /** The canonical id table: the sorted distinct ids present in the value. A function of the value. */
    private fun canonicalTable(n: Int, replicas: Int, width: Int): List<String> =
        (0 until replicas).filter { it < n }.map { replicaIdOf(it, width) }.sorted()

    private fun internedCausal(n: Int, replicas: Int, width: Int, naming: (Int) -> String = ::element): InternedCausal {
        val table = canonicalTable(n, replicas, width)
        val index = table.withIndex().associate { (i, id) -> id to i }
        val store = rows(n, replicas, width, naming)
            .sortedBy { it.first }
            .associate { (key, dot) -> key to listOf(InternedDot(index.getValue(dot.first), dot.second)) }
        val vv = contextParts(n, replicas, width).mapKeys { (id, _) -> index.getValue(id) }.toSortedMap()
        return InternedCausal(table, store, InternedContext(vv, emptyList()))
    }

    private fun arrayCausal(n: Int, replicas: Int, width: Int, naming: (Int) -> String = ::element): ArrayCausal {
        val store = rows(n, replicas, width, naming)
            .sortedBy { it.first }
            .associate { (key, dot) -> key to listOf(ArrayDot(dot.first, dot.second)) }
        return ArrayCausal(store, ArrayContext(contextParts(n, replicas, width).toSortedMap(), emptyList()))
    }

    private fun labelCausal(n: Int, replicas: Int, width: Int, naming: (Int) -> String = ::element): LabelCausal {
        val store = rows(n, replicas, width, naming)
            .sortedBy { it.first }
            .associate { (key, dot) -> key to listOf(LabelDot(dot.first, dot.second)) }
        return LabelCausal(store, LabelContext(contextParts(n, replicas, width).toSortedMap(), emptyList()))
    }

    private fun arrayInternedCausal(
        n: Int,
        replicas: Int,
        width: Int,
        naming: (Int) -> String = ::element,
    ): ArrayInternedCausal {
        val table = canonicalTable(n, replicas, width)
        val index = table.withIndex().associate { (i, id) -> id to i }
        val store = rows(n, replicas, width, naming)
            .sortedBy { it.first }
            .associate { (key, dot) -> key to listOf(ArrayInternedDot(index.getValue(dot.first), dot.second)) }
        val vv = contextParts(n, replicas, width).mapKeys { (id, _) -> index.getValue(id) }.toSortedMap()
        return ArrayInternedCausal(table, store, ArrayInternedContext(vv, emptyList()))
    }

    @Test
    fun theBaselineProbeEncodesByteForByteAsTodaysCausalFrame() {
        // Four shapes, chosen to move every axis a candidate later varies: entry count, replica
        // count, id width. If the mirror drifted from the real encoding on any of them, every
        // saving below would be a saving against a fiction.
        val shapes = listOf(
            Triple(1, 1, DEFAULT_ID_WIDTH),
            Triple(1_000, 4, DEFAULT_ID_WIDTH),
            Triple(5_000, 16, UUID_ID_WIDTH),
            Triple(500, 256, SHORT_ID_WIDTH),
        )

        println("\n=== #2037 Phase 0 (B): the pin — baseline probe vs the real Causal frame ===")
        shapes.forEach { (n, r, w) ->
            val real = bytes(orSetCausalFrame, QuiltMessage.FullState(replica, realCausal(n, r, w)))
            val mirror = bytes(namedFrame, QuiltMessage.FullState(replica, namedCausal(n, r, w)))
            println("  n=%6d R=%4d idWidth=%2d  real=%9d b  mirror=%9d b  %s".format(
                n, r, w, real.size, mirror.size, if (real.contentEquals(mirror)) "IDENTICAL" else "DIVERGED"))
        }

        shapes.forEach { (n, r, w) ->
            val real = bytes(orSetCausalFrame, QuiltMessage.FullState(replica, realCausal(n, r, w)))
            val mirror = bytes(namedFrame, QuiltMessage.FullState(replica, namedCausal(n, r, w)))
            assertContentEquals(
                real, mirror,
                "the baseline probe must encode byte-for-byte as the real Causal frame at " +
                    "n=$n R=$r idWidth=$w — otherwise every candidate below is priced " +
                    "against a model, not against today's wire",
            )
        }
    }

    // ---- C. anatomy: where the per-entry tax actually goes ------------------------------------

    /**
     * The measured marginal cost of one entry, taken as a difference of two frames rather than
     * divided out of one — so the fixed envelope, the context, and the CBOR length-header steps
     * cannot contaminate it.
     */
    private fun perEntryBytes(build: (Int) -> Int, at: Int = ANATOMY_N, step: Int = ANATOMY_STEP): Double =
        (build(at + step) - build(at)).toDouble() / step

    @Test
    fun theCausalTaxDecomposesIntoNamesIdentityAndSequence() {
        val r = 4
        val baselineAt = { w: Int -> { n: Int -> frameBytes(namedFrame, namedCausal(n, r, w)) } }
        val gsetAt = { n: Int -> frameBytes(gsetFrame, GSet.of(*Array(n) { element(it) })) }

        println("\n=== #2037 Phase 0 (C): anatomy of the per-entry causal tax, $r replicas ===")
        println("  Every column is a measured marginal cost: frame(n+$ANATOMY_STEP) - frame(n) over $ANATOMY_STEP, at n=$ANATOMY_N.")
        println("  %9s %12s %12s %12s %12s".format("id width", "ORSet b/e", "GSet b/e", "tax b/e", "tax as %"))
        val taxes = idWidths.associateWith { w ->
            val orSet = perEntryBytes(baselineAt(w))
            val gset = perEntryBytes(gsetAt)
            println("  %9d %12.2f %12.2f %12.2f %11.1f%%".format(w, orSet, gset, orSet - gset, (orSet - gset) * 100.0 / orSet))
            orSet - gset
        }

        // Isolate the identity term: the tax is linear in id width with slope 1 b/char (CBOR text
        // strings are length-prefixed, one byte per character past the header step), so the
        // intercept is everything that is NOT the replica id.
        val slope = (taxes.getValue(UUID_ID_WIDTH) - taxes.getValue(SHORT_ID_WIDTH)) /
            (UUID_ID_WIDTH - SHORT_ID_WIDTH)
        val intercept = taxes.getValue(SHORT_ID_WIDTH) - slope * SHORT_ID_WIDTH

        // Isolate the names term by measuring the same value with the names removed and nothing
        // else changed — the array-encoded dot of part (E).
        val namesTerm = perEntryBytes(baselineAt(DEFAULT_ID_WIDTH)) -
            perEntryBytes(build = { n -> frameBytes(arrayFrame, arrayCausal(n, r, DEFAULT_ID_WIDTH)) })

        println()
        println("  tax(width) = %.2f * width + %.2f  b/entry".format(slope, intercept))
        println("    identity  : %.2f b/entry at width=11, %.2f at width=36 (a raw UUID)"
            .format(slope * DEFAULT_ID_WIDTH, slope * UUID_ID_WIDTH))
        println("    the names : %.2f b/entry — \"replica\" and \"seq\", spelled out on every dot".format(namesTerm))
        println("    remainder : %.2f b/entry — the seq value and CBOR's structural headers"
            .format(intercept - namesTerm))
        println()
        println("  The two levers cross over at an id width of %.1f characters: below it the FIELD NAMES"
            .format(namesTerm / slope))
        println("  cost more than the identity they label, above it the identity wins. #2037 names only")
        println("  the identity lever, so it is the smaller one for every id narrower than that.")

        assertAll(
            // A CBOR text string costs one byte per character, so the identity term must track id
            // width at slope ~1. If this ever stopped holding, the interning saving in (D) would
            // not be readable off the width column.
            {
                assertTrue(
                    slope in 0.9..1.1,
                    "the identity term must cost ~1 b per id character (measured slope $slope) — " +
                        "that is what makes interning's saving proportional to id width",
                )
            },
            // The headline finding of this part: for any id narrower than the crossover the field
            // names cost more than the identity they label. Asserted at the 8-character handle
            // rather than at the crossover itself, which sits at ~12 characters and would make
            // this a knife-edge on a measurement whose point is the ranking, not the exact width.
            {
                assertTrue(
                    namesTerm > slope * SHORT_ID_WIDTH,
                    "at an 8-character replica id the field names ($namesTerm b/entry) must cost " +
                        "more than the id itself (${slope * SHORT_ID_WIDTH} b/entry) — the lever " +
                        "#2037 does not name is bigger than the one it does",
                )
            },
            // And the inversion at a UUID: interning overtakes name elision once ids are wide.
            {
                assertTrue(
                    slope * UUID_ID_WIDTH > namesTerm,
                    "at a 36-character UUID the identity term (${slope * UUID_ID_WIDTH} b/entry) " +
                        "must exceed the names ($namesTerm b/entry) — which is why the two " +
                        "candidates rank differently by deployment, not absolutely",
                )
            },
        )
    }

    // ---- D. candidate C1: replica-id interning ------------------------------------------------

    @Test
    fun replicaIdInterningPaysInProportionToIdWidthAndCostsATableAtLowEntryCounts() {
        println("\n=== #2037 Phase 0 (D): C1 — replica-id interning, measured ===")
        idWidths.forEach { w ->
            println("\n  id width = $w characters, 4 replicas:")
            println("  %9s %14s %14s %12s %12s".format("entries", "today b", "interned b", "saved b", "saved %"))
            sizes.forEach { n ->
                val base = frameBytes(namedFrame, namedCausal(n, 4, w))
                val interned = frameBytes(internedFrame, internedCausal(n, 4, w))
                println("  %9d %14d %14d %12d %11.1f%%".format(
                    n, base, interned, base - interned, (base - interned) * 100.0 / base))
            }
        }

        println("\n  Break-even: the smallest entry count at which the table pays for itself.")
        println("  %9s %s".format("id width", replicaCounts.joinToString("") { "R=%-4d%3s".format(it, "") }))
        idWidths.forEach { w ->
            val cells = replicaCounts.joinToString("") { r ->
                val breakEven = (1..MAX_BREAK_EVEN).firstOrNull { n ->
                    frameBytes(internedFrame, internedCausal(n, r, w)) <
                        frameBytes(namedFrame, namedCausal(n, r, w))
                }
                "%7s ".format(breakEven?.toString() ?: ">$MAX_BREAK_EVEN")
            }
            println("  %9d %s".format(w, cells))
        }

        println("\n  Saving at n = 10,000, swept over replica count (the table is O(R), the saving O(n)):")
        println("  %9s %s".format("id width", replicaCounts.joinToString("") { "R=%-4d%3s".format(it, "") }))
        idWidths.forEach { w ->
            val cells = replicaCounts.joinToString("") { r ->
                val base = frameBytes(namedFrame, namedCausal(GRID_N, r, w))
                val interned = frameBytes(internedFrame, internedCausal(GRID_N, r, w))
                "%6.1f%% ".format((base - interned) * 100.0 / base)
            }
            println("  %9d %s".format(w, cells))
        }

        val sample = 128
        val roundTripped = Cbor.decodeFromByteArray(
            internedFrame,
            bytes(internedFrame, QuiltMessage.FullState(replica, internedCausal(sample, 4, DEFAULT_ID_WIDTH))),
        )

        assertAll(
            // A smaller encoding nobody can read back is not a candidate.
            {
                assertEquals(
                    internedCausal(sample, 4, DEFAULT_ID_WIDTH),
                    (roundTripped as QuiltMessage.FullState).state,
                    "the interned frame must round-trip through Cbor",
                )
            },
            // The candidate's whole case: the saving is proportional to id width, so it is small
            // where ids are short and large where they are UUIDs. Anyone quoting a single
            // "interning saves X%" number is quoting their own id scheme.
            {
                val short = savedFraction(SHORT_ID_WIDTH)
                val uuid = savedFraction(UUID_ID_WIDTH)
                assertTrue(
                    uuid > short * 2,
                    "interning must save proportionally more with a UUID id (${pct(uuid)} vs " +
                        "${pct(short)} at 8 chars) — the saving is the id, so it scales with the id",
                )
            },
            // The cost side #2037 asks for. Below the break-even the table is dead weight: at one
            // entry with a narrow id it makes the frame BIGGER, because the table's own field name
            // costs more than the two occurrences of a 2-character id it replaces.
            {
                val base = frameBytes(namedFrame, namedCausal(1, 1, MIN_ID_WIDTH))
                val interned = frameBytes(internedFrame, internedCausal(1, 1, MIN_ID_WIDTH))
                assertTrue(
                    interned > base,
                    "at one entry with a $MIN_ID_WIDTH-character id the table must be a net LOSS " +
                        "($base b -> $interned b) — that is the low-entry-count cost the issue asks for",
                )
            },
            // And the other end of the same effect: an id is not written once per entry but once
            // per entry PLUS once per context row, so a UUID pays for its table from the very
            // first entry. The table overhead is bounded by the ids actually present, never by
            // the roster — at n=1 only one replica can have written, whatever the replica count.
            {
                val base = frameBytes(namedFrame, namedCausal(1, 256, UUID_ID_WIDTH))
                val interned = frameBytes(internedFrame, internedCausal(1, 256, UUID_ID_WIDTH))
                assertTrue(
                    interned < base,
                    "with a UUID id the table must already pay at one entry ($base b -> $interned b) " +
                        "— the id appears in the dot AND in the context, so one entry is two copies",
                )
            },
            // The floor: with the narrowest realistic id there is almost nothing left to intern,
            // and the index costs a byte of its own. Interning must be a wash, never a regression.
            {
                val base = frameBytes(namedFrame, namedCausal(GRID_N, 4, MIN_ID_WIDTH))
                val interned = frameBytes(internedFrame, internedCausal(GRID_N, 4, MIN_ID_WIDTH))
                assertTrue(
                    interned <= base + TABLE_SLACK,
                    "with $MIN_ID_WIDTH-character ids interning must be a wash, not a regression " +
                        "($base b -> $interned b)",
                )
            },
        )
    }

    private fun savedFraction(width: Int, n: Int = GRID_N, replicas: Int = 4): Double {
        val base = frameBytes(namedFrame, namedCausal(n, replicas, width))
        val interned = frameBytes(internedFrame, internedCausal(n, replicas, width))
        return (base - interned).toDouble() / base
    }

    private fun pct(fraction: Double) = "%.1f%%".format(fraction * 100)

    // ---- E. the lever #2037 does not name: the field names ------------------------------------

    @Test
    fun droppingTheDotFieldNamesSavesMoreThanInterningAtEveryIdWidthBelowAUuid() {
        println("\n=== #2037 Phase 0 (E): C0 — the dot's field names, which cost nothing to remove ===")
        println("  Three forms, all pure encoding, none touching lattice semantics:")
        println("    array   : @CborArray on Dot — [id, seq] instead of {\"replica\": id, \"seq\": seq}")
        println("    label   : @CborLabel(1)/(2) + Cbor { preferCborLabelsOverNames = true }")
        println("    definite: Cbor { useDefiniteLengthEncoding = true } — one codec flag, nothing else")
        println()
        println("  %9s %9s %12s %12s %12s %12s".format(
            "id width", "entries", "today b", "array b", "label b", "definite b"))
        idWidths.forEach { w ->
            listOf(1_000, GRID_N).forEach { n ->
                val base = frameBytes(namedFrame, namedCausal(n, 4, w))
                val array = frameBytes(arrayFrame, arrayCausal(n, 4, w))
                val label = labelledCbor
                    .encodeToByteArray(labelFrame, QuiltMessage.FullState(replica, labelCausal(n, 4, w))).size
                val definite = definiteCbor
                    .encodeToByteArray(namedFrame, QuiltMessage.FullState(replica, namedCausal(n, 4, w))).size
                println("  %9d %9d %12d %12d %12d %12d".format(w, n, base, array, label, definite))
            }
        }

        println("\n  Saving at n = 10,000, 4 replicas, as a fraction of the frame:")
        println("  %9s %12s %12s %12s %12s".format("id width", "array", "label", "definite", "interning"))
        idWidths.forEach { w ->
            val base = frameBytes(namedFrame, namedCausal(GRID_N, 4, w))
            val array = frameBytes(arrayFrame, arrayCausal(GRID_N, 4, w))
            val label = labelledCbor
                .encodeToByteArray(labelFrame, QuiltMessage.FullState(replica, labelCausal(GRID_N, 4, w))).size
            val definite = definiteCbor
                .encodeToByteArray(namedFrame, QuiltMessage.FullState(replica, namedCausal(GRID_N, 4, w))).size
            println("  %9d %11.1f%% %11.1f%% %11.1f%% %11.1f%%".format(
                w,
                (base - array) * 100.0 / base,
                (base - label) * 100.0 / base,
                (base - definite) * 100.0 / base,
                savedFraction(w) * 100,
            ))
        }

        // A smaller encoding nobody can read back is not a candidate. Round-trip every form
        // through the codec that would carry it, at a shape with more than one replica.
        val sample = 128
        val arrayRoundTrip = Cbor.decodeFromByteArray(
            arrayFrame,
            bytes(arrayFrame, QuiltMessage.FullState(replica, arrayCausal(sample, 4, DEFAULT_ID_WIDTH))),
        )
        val labelRoundTrip = labelledCbor.decodeFromByteArray(
            labelFrame,
            labelledCbor.encodeToByteArray(
                labelFrame,
                QuiltMessage.FullState(replica, labelCausal(sample, 4, DEFAULT_ID_WIDTH)),
            ),
        )
        val definiteRoundTrip = definiteCbor.decodeFromByteArray(
            namedFrame,
            definiteCbor.encodeToByteArray(
                namedFrame,
                QuiltMessage.FullState(replica, namedCausal(sample, 4, DEFAULT_ID_WIDTH)),
            ),
        )

        assertAll(
            // Decodability, first — the cheapest way this whole direction could be dead.
            {
                assertEquals(
                    arrayCausal(sample, 4, DEFAULT_ID_WIDTH),
                    (arrayRoundTrip as QuiltMessage.FullState).state,
                    "the array-encoded dot must round-trip through Cbor",
                )
            },
            {
                assertEquals(
                    labelCausal(sample, 4, DEFAULT_ID_WIDTH),
                    (labelRoundTrip as QuiltMessage.FullState).state,
                    "the label-encoded dot must round-trip through the labelled Cbor",
                )
            },
            {
                assertEquals(
                    namedCausal(sample, 4, DEFAULT_ID_WIDTH),
                    (definiteRoundTrip as QuiltMessage.FullState).state,
                    "definite-length encoding must round-trip",
                )
            },
            // The finding: at the sibling suites' 11-character id, removing the names beats
            // interning the ids — and it needs no table, so it has no break-even at all.
            {
                val base = frameBytes(namedFrame, namedCausal(GRID_N, 4, DEFAULT_ID_WIDTH))
                val array = frameBytes(arrayFrame, arrayCausal(GRID_N, 4, DEFAULT_ID_WIDTH))
                val interned = frameBytes(internedFrame, internedCausal(GRID_N, 4, DEFAULT_ID_WIDTH))
                assertTrue(
                    array < interned,
                    "at an 11-character id, dropping the field names ($array b) must beat interning " +
                        "them ($interned b) — the lever #2037 names is the smaller of the two",
                )
            },
            // And it is monotone: unlike interning, name elision saves the same absolute bytes at
            // every id width, so it never regresses and never needs a break-even analysis.
            {
                val savings = idWidths.map { w ->
                    frameBytes(namedFrame, namedCausal(GRID_N, 4, w)) -
                        frameBytes(arrayFrame, arrayCausal(GRID_N, 4, w))
                }
                assertTrue(
                    savings.min() > 0 && savings.max() - savings.min() < GRID_N / 10,
                    "name elision must save a near-constant number of bytes at every id width " +
                        "(measured $savings) — it is independent of the id, which is why it has no " +
                        "break-even and cannot regress",
                )
            },
        )
    }

    // ---- F. candidate C2: dot run-length / delta encoding -------------------------------------

    /**
     * The seq column alone, in canonical (key-sorted) order, priced three ways. Measuring the
     * column rather than a whole frame is deliberate: run-length encoding can only ever touch the
     * seq values, so folding it into a frame would bury a small effect under a large constant.
     *
     * **The distribution assumption is the whole result, so it is a parameter.** [naming] fixes how
     * the canonical key order relates to the order the dots were minted in:
     *
     * - [mintOrderedKey] — zero-padded and written in order, so key order **is** mint order. This
     *   is the best case a real key scheme can reach, and measuring only this would overstate the
     *   candidate badly.
     * - [element] — #1955's descriptive shape, whose lexicographic order is 0, 1, 10, 100, … : the
     *   partial correlation a natural key scheme actually gives.
     * - [scrambledKey] — a seeded permutation, standing for a UUID / hash / user-supplied key, where
     *   key order carries no information about mint order at all.
     */
    private fun seqColumnBytes(n: Int, replicas: Int, naming: (Int) -> String): Triple<Int, Int, Int> {
        val ordered = rows(n, replicas, DEFAULT_ID_WIDTH, naming).sortedBy { it.first }
        val seqs = ordered.map { it.second.second }
        val longs = ListSerializer(Long.serializer())

        val globalDeltas = seqs.mapIndexed { i, s -> if (i == 0) s else s - seqs[i - 1] }
        val lastPerReplica = HashMap<String, Long>()
        val perReplicaDeltas = ordered.map { (_, dot) ->
            val delta = dot.second - (lastPerReplica[dot.first] ?: 0L)
            lastPerReplica[dot.first] = dot.second
            delta
        }
        return Triple(
            bytesOf(longs, seqs),
            bytesOf(longs, globalDeltas),
            bytesOf(longs, perReplicaDeltas),
        )
    }

    private fun mintOrderedKey(i: Int) = "u%09d".format(i)

    private fun scrambledKey(i: Int) = "%08x-key".format(Random(SCRAMBLE_SEED + i).nextInt())

    @Test
    fun dotDeltaEncodingOnlyPaysWhenCanonicalKeyOrderHappensToBeMintOrder() {
        val n = GRID_N
        println("\n=== #2037 Phase 0 (F): C2 — dot run-length / delta encoding, seq column only ===")
        println("  n = $n entries. The seq column is the ONLY thing an RLE/delta scheme can touch.")
        println("  Canonical order is KEY order (DotMapSerializer sorts by key); dot density lives in")
        println("  MINT order. The three key schemes differ only in how those two orders relate.")
        println()
        println("  %26s %8s %12s %12s %14s".format("key scheme", "replicas", "raw b", "delta b", "per-repl b"))
        val schemes = listOf<Pair<String, (Int) -> String>>(
            "mint-ordered (best case)" to ::mintOrderedKey,
            "descriptive (#1955 shape)" to ::element,
            "scrambled (uuid/hash key)" to ::scrambledKey,
        )
        val results = schemes.associate { (name, naming) ->
            name to listOf(1, 4, 64).map { r ->
                val (raw, global, perReplica) = seqColumnBytes(n, r, naming)
                println("  %26s %8d %12d %12d %14d".format(name, r, raw, global, perReplica))
                Triple(raw, global, perReplica)
            }
        }

        println()
        println("  Best achievable saving as a fraction of the WHOLE frame (11-char ids, R=4):")
        val frame = frameBytes(namedFrame, namedCausal(n, 4, DEFAULT_ID_WIDTH))
        schemes.forEach { (name, naming) ->
            val (raw, global, perReplica) = seqColumnBytes(n, 4, naming)
            val best = minOf(global, perReplica)
            println("  %26s  seq column %6d b of %8d b frame -> best %6d b, frame saving %5.1f%%"
                .format(name, raw, frame, best, (raw - best) * 100.0 / frame))
        }
        println()
        println("  The best case is unreachable by construction: making key order equal mint order")
        println("  means letting the WRITER choose the canonical order, which is exactly the")
        println("  content-independent ordering #1957 / #1978 / #2010 / #1979 keep landing on.")

        val mintBest = results.getValue("mint-ordered (best case)")[1]
        val scrambledBest = results.getValue("scrambled (uuid/hash key)")[1]

        assertAll(
            // The favourable case really is favourable — so a measurement that assumed it would
            // publish a large, wrong number. Naming it is the point.
            {
                assertTrue(
                    minOf(mintBest.second, mintBest.third) < mintBest.first / 2,
                    "with mint-ordered keys the delta column must be far smaller than the raw one " +
                        "(raw ${mintBest.first} b vs best ${minOf(mintBest.second, mintBest.third)} b) — " +
                        "this is the number an artificially dense measurement would report",
                )
            },
            // And the realistic case is not: a scrambled key order makes deltas as wide as the
            // values they replace, or wider.
            {
                assertTrue(
                    minOf(scrambledBest.second, scrambledBest.third) > scrambledBest.first * 0.9,
                    "with key order uncorrelated to mint order, delta encoding must save essentially " +
                        "nothing (raw ${scrambledBest.first} b vs best " +
                        "${minOf(scrambledBest.second, scrambledBest.third)} b)",
                )
            },
            // Even the best case is small against the frame — the seq column is a few percent of it.
            {
                val (raw, global, perReplica) = seqColumnBytes(n, 4, ::mintOrderedKey)
                assertTrue(
                    (raw - minOf(global, perReplica)) < frame / 10,
                    "even the best-case seq saving must be under 10% of the frame " +
                        "(${raw - minOf(global, perReplica)} b of $frame b) — the seq column is not " +
                        "where the tax lives",
                )
            },
        )
    }

    // ---- G. canonicality: the hard gate every candidate must pass -----------------------------

    /**
     * The same logical value, reached two ways. [canonicalTable] derives the id table from the
     * value; [firstSeenTable] derives it from the order the ids were encountered — the
     * `LinkedHashSet`-in-merge-order shape that has now produced two encodings of one value four
     * times on this track (#1957 / #1978 / #2010 / #1979).
     */
    private fun firstSeenTable(rows: List<Pair<String, Pair<String, Long>>>): List<String> =
        rows.map { it.second.first }.distinct()

    private fun internedWith(
        table: List<String>,
        rows: List<Pair<String, Pair<String, Long>>>,
        vv: Map<String, Long>,
    ): InternedCausal {
        val index = table.withIndex().associate { (i, id) -> id to i }
        return InternedCausal(
            replicas = table,
            store = rows.sortedBy { it.first }
                .associate { (key, dot) -> key to listOf(InternedDot(index.getValue(dot.first), dot.second)) },
            context = InternedContext(vv.mapKeys { (id, _) -> index.getValue(id) }.toSortedMap(), emptyList()),
        )
    }

    @Test
    fun everySurvivingCandidateEncodesOneValueOneWay() {
        val n = 64
        val replicas = 4
        val w = DEFAULT_ID_WIDTH
        val forward = rows(n, replicas, w, ::element)
        // The same value, discovered in the opposite order — the merge-order permutation a real
        // peer reaches by absorbing the same set of deltas in a different sequence.
        val reversed = forward.reversed()
        val vv = contextParts(n, replicas, w)

        val canonicalForward = bytes(
            internedFrame,
            QuiltMessage.FullState(replica, internedWith(canonicalTable(n, replicas, w), forward, vv)),
        )
        val canonicalReversed = bytes(
            internedFrame,
            QuiltMessage.FullState(replica, internedWith(canonicalTable(n, replicas, w), reversed, vv)),
        )
        val firstSeenForward = bytes(
            internedFrame,
            QuiltMessage.FullState(replica, internedWith(firstSeenTable(forward), forward, vv)),
        )
        val firstSeenReversed = bytes(
            internedFrame,
            QuiltMessage.FullState(replica, internedWith(firstSeenTable(reversed), reversed, vv)),
        )

        val arrayForward = bytes(arrayFrame, QuiltMessage.FullState(replica, arrayCausal(n, replicas, w)))
        val arrayReversed = bytes(
            arrayFrame,
            QuiltMessage.FullState(
                replica,
                ArrayCausal(
                    reversed.sortedBy { it.first }
                        .associate { (key, dot) -> key to listOf(ArrayDot(dot.first, dot.second)) },
                    ArrayContext(vv.toSortedMap(), emptyList()),
                ),
            ),
        )

        println("\n=== #2037 Phase 0 (G): canonical encoding — the gate, not a caveat ===")
        println("  One logical value, two discovery orders. `Quilter.stateRoot()` hashes the wire bytes,")
        println("  so any pair of peers whose bytes differ never matches roots and #1955's gate dies.")
        println("  C1 with a CONTENT-SORTED table : ${canonicalForward.size} b vs ${canonicalReversed.size} b -> " +
            if (canonicalForward.contentEquals(canonicalReversed)) "IDENTICAL" else "DIVERGED")
        println("  C1 with a FIRST-SEEN table     : ${firstSeenForward.size} b vs ${firstSeenReversed.size} b -> " +
            if (firstSeenForward.contentEquals(firstSeenReversed)) "IDENTICAL" else "DIVERGED")
        println("  C0 array-encoded dot           : ${arrayForward.size} b vs ${arrayReversed.size} b -> " +
            if (arrayForward.contentEquals(arrayReversed)) "IDENTICAL" else "DIVERGED")
        println("  Same size, different bytes is the failure mode that hides: compare CONTENT.")

        assertAll(
            // C1 survives — but only in the content-sorted form.
            {
                assertContentEquals(
                    canonicalForward, canonicalReversed,
                    "interning with a content-sorted table must encode one value one way",
                )
            },
            // The negative control. This is not hypothetical: it is the fourth recurrence of the
            // same defect class on this track, and it is invisible to a round-trip test.
            {
                assertNotEquals(
                    firstSeenForward.toList(), firstSeenReversed.toList(),
                    "a first-seen table MUST diverge under reordering — if this ever stops failing " +
                        "the negative control has gone inert and (G) proves nothing",
                )
            },
            // C0 survives trivially: it removes bytes, it does not reorder anything.
            {
                assertContentEquals(
                    arrayForward, arrayReversed,
                    "array-encoding the dot must not disturb canonical order — it drops names, " +
                        "it does not choose an order",
                )
            },
        )
    }

    // ---- H. which path actually consumes these bytes ------------------------------------------

    private fun rootDigestBytes(sender: ReplicaId = replica): Int = bytesOf(
        orSetFrame,
        QuiltMessage.RootDigest<ORSet<String>>(sender = sender, root = REPRESENTATIVE_ROOT, upThrough = 1L),
    )

    private fun ackBytes(sender: ReplicaId = replica): Int =
        bytesOf(orSetFrame, QuiltMessage.Ack<ORSet<String>>(acker = sender, sender = sender, seq = 1L))

    private fun digestRoundBytes(sender: ReplicaId = replica): Int = rootDigestBytes(sender) + ackBytes(sender)

    /**
     * What a single `ORSet` add costs on the delta path: the one entry plus the one dot that names
     * it. Priced on the raw `Causal` mirror part (B) pins, like every other column in this part, so
     * the ratio against the full-state column is like-for-like.
     *
     * When this part was written the row was a **counterfactual** — `ORSet` had no delta mutator,
     * so `Patch(state.add(…))` put the entire state on the wire once per write. It grew one in
     * #2044, and part (I) now meters both paths on a real mesh. The model is no longer free to
     * drift: [theOnPerMutationFullStateDominatesBootstrapAndSteadyStateAlike] pins it against what
     * [ORSet.add] really produces.
     */
    private fun minimalDeltaBytes(width: Int = DEFAULT_ID_WIDTH): Int = bytesOf(
        QuiltMessage.serializer(orSetCausalSerializer),
        QuiltMessage.Delta(
            sender = replica,
            seq = 1L,
            delta = Causal(
                DotMap(mapOf(element(0) to DotSet(setOf(Dot(ReplicaId(replicaIdOf(0, width)), 1L))))),
                DotContext.of(Dot(ReplicaId(replicaIdOf(0, width)), 1L)),
            ),
        ),
    )

    /**
     * The same add, through the real [ORSet.add] and [Quilter]'s own frame. Differs from
     * [minimalDeltaBytes] only by `ORSet`'s one-field serialization wrapper — `Causal` reached
     * through a `{"causal": …}` map rather than directly — which is [ORSET_WRAPPER_SLACK] bytes.
     */
    private fun realDeltaBytes(width: Int = DEFAULT_ID_WIDTH): Int = bytesOf(
        orSetFrame,
        QuiltMessage.Delta(
            sender = replica,
            seq = 1L,
            delta = ORSet.empty<String>().add(ReplicaId(replicaIdOf(0, width)), element(0)).delta,
        ),
    )

    @Test
    fun theOnPerMutationFullStateDominatesBootstrapAndSteadyStateAlike() {
        val replicas = 4
        val digestRound = digestRoundBytes()

        println("\n=== #2037 Phase 0 (H): which path pays the tax — the budget ===")
        println("  Every O(n) path ships the SAME frame. What differs is how often.")
        println("  %9s %14s %14s %14s %14s".format("entries", "full state b", "digest rnd b", "minimal delta", "state/delta"))
        sizes.forEach { n ->
            val full = frameBytes(namedFrame, namedCausal(n, replicas, DEFAULT_ID_WIDTH))
            println("  %9d %14d %14d %14d %13.0fx".format(
                n, full, digestRound, minimalDeltaBytes(), full.toDouble() / minimalDeltaBytes()))
        }

        println("\n  Cluster-wide bytes/hour at n = 100,000 over $replicas peers. J = joins/hr, M = writes/hr.")
        println("  A join costs ${replicas - 1} full states (each existing peer unicasts one to the joiner).")
        println("  A write costs a full state too ON THE `Patch(state.add(...))` PATH — and that row is a")
        println("  LOWER bound: it charges ${replicas - 1} links, while part (I) meters the gossip flood at")
        println("  three times that. The last column is the same workload written through `ORSet.add`,")
        println("  which #2044 made a delta mutator: no longer a counterfactual, and metered in part (I).")
        val full = frameBytes(namedFrame, namedCausal(100_000, replicas, DEFAULT_ID_WIDTH)).toDouble()
        val antiEntropyPerHour = replicas * SECONDS_PER_HOUR / ANTI_ENTROPY_SECONDS * digestRound
        println("  %6s %6s %18s %18s %18s %14s".format(
            "J/hr", "M/hr", "anti-entropy b", "joins b", "writes b", "if delta'd"))
        listOf(0 to 0, 0 to 1, 0 to 60, 1 to 0, 1 to 60, 10 to 600).forEach { (joins, writes) ->
            println("  %6d %6d %18.0f %18.0f %18.0f %14.0f".format(
                joins, writes, antiEntropyPerHour,
                joins * full * (replicas - 1),
                writes * full * (replicas - 1),
                writes * minimalDeltaBytes().toDouble() * (replicas - 1)))
        }

        println("\n  Read the table, not the issue's framing. Bootstrap and the FULL-STATE write path ship")
        println("  the IDENTICAL frame; what separates them is frequency, and a join is the rare event.")
        println("  At one write per minute a 100k-entry ORSet moved " +
            "${"%.0f".format(60 * full * (replicas - 1) / 1e6)} MB/hr cluster-wide, which")
        println("  60 joins per hour would have to match to compete — and no room joins once a minute.")
        println("  Through `add`'s delta the same workload is " +
            "${"%.0f".format(60 * minimalDeltaBytes().toDouble() * (replicas - 1) / 1e3)} kB/hr, and the")
        println("  join column is what is left to optimise. That reordering is what #2044 bought.")

        assertAll(
            // The steady-state anti-entropy path is already negligible — #1955 took it, and #1986
            // confirmed the dot-based types inherited it for free. It is not where the tax lands.
            {
                assertTrue(
                    antiEntropyPerHour < full,
                    "a whole hour of anti-entropy (${antiEntropyPerHour.toLong()} b) must cost less " +
                        "than ONE full state (${full.toLong()} b) — the quiescent path is done",
                )
            },
            // The finding that made this actionable: a minimal delta is four orders of magnitude
            // below the full state that used to ship in its place.
            {
                assertTrue(
                    full / minimalDeltaBytes() > 1_000,
                    "a 100k-entry full state (${full.toLong()} b) must dwarf the minimal delta a " +
                        "single add represents (${minimalDeltaBytes()} b) — that ratio, not the " +
                        "encoding, is the headline",
                )
            },
            // And the model is now pinned to production rather than to a sketch of it. If
            // `ORSet.add` ever regressed to shipping state, the delta column of this budget
            // would silently keep quoting the sketch; this is what stops that.
            {
                assertTrue(
                    realDeltaBytes() - minimalDeltaBytes() in 0..ORSET_WRAPPER_SLACK,
                    "the modelled minimal delta (${minimalDeltaBytes()} b) must still be what " +
                        "ORSet.add really produces (${realDeltaBytes()} b, one `causal` " +
                        "wrapper wider) — otherwise this whole column is a sketch of a method " +
                        "that exists and can be measured",
                )
            },
        )
    }

    // ---- I. grounding: what a MeteredSeam actually counts on each path ------------------------

    /**
     * One `(type, state size)` mesh run, metered on **both** write paths.
     *
     * Every field is a byte count a [MeteredSeam] actually counted, never a wall-clock reading, so
     * the whole of part (I) reproduces unchanged on a saturated box. That is deliberate: a timing
     * number here would have to be re-measured against `uptime` to mean anything, and the property
     * under test — how many bytes one write puts on the wire — has no time in it.
     *
     * @property fullAdd cluster egress for one `Patch(state.add(…))`: the whole state, flooded.
     * @property deltaAdd cluster egress for the same write through the type's delta mutator.
     * @property fullRemove cluster egress for one `Patch(state.remove(…))`.
     * @property deltaRemove cluster egress for the same removal through the delta mutator.
     * @property fullState the frame one converged replica's whole state occupies at the end.
     * @property seededState the same, at the moment the mesh bootstrapped.
     * @property bootstrap cluster egress for the whole first-contact burst.
     * @property perRoundPerNode converged anti-entropy, per node per round — the #1955 figure.
     */
    private data class MeteredPaths(
        val fullAdd: Long,
        val deltaAdd: Long,
        val fullRemove: Long,
        val deltaRemove: Long,
        val fullState: Int,
        val seededState: Int,
        val bootstrap: Long,
        val perRoundPerNode: Double,
    ) {
        /** What admitting one new peer costs: each existing peer unicasts it one full state. */
        fun oneJoin(nodes: Int): Double = fullState.toDouble() * (nodes - 1)
    }

    /**
     * The four writes a type must expose to be metered here, plus how to build a state of a given
     * size. One shape for all three types so they go through the **identical** mesh script — a
     * per-type harness is how one type quietly ends up measured on a different path, and the point
     * of this part is a table whose rows are comparable.
     *
     * [addFull]/[removeFull] are the `Patch(state.mutator(…))` spelling every consumer used before
     * #2044 — reconstructed as `state.piece(state.mutator(…))`, which the delta-mutator law makes
     * byte-identical to what that spelling produced. [addDelta]/[removeDelta] are the delta
     * mutators. The `timestamp` argument is ignored by the two causal types and is [LWWMap]'s
     * write tag.
     */
    @Suppress("LongParameterList")
    private class WriteProbe<S : Quilted<S>>(
        val label: String,
        val serializer: KSerializer<S>,
        val seed: (Int) -> S,
        val addFull: (S, ReplicaId, String, Long) -> S,
        val addDelta: (S, ReplicaId, String, Long) -> Patch<S>,
        val removeFull: (S, ReplicaId, String, Long) -> S,
        val removeDelta: (S, ReplicaId, String, Long) -> Patch<S>,
        val holds: (S, String) -> Boolean,
    )

    /** Seed values for the two map probes. Fixed width, so entry count is the only axis moving. */
    private fun seedValue(i: Int) = "seed-value-$i"

    private val orSetProbe = WriteProbe(
        label = "ORSet",
        serializer = ORSet.serializer(string),
        seed = { n ->
            (0 until n).fold(ORSet.empty<String>()) { set, i ->
                set.piece { it.add(replicaId(i % SEED_REPLICAS), element(i)) }
            }
        },
        addFull = { set, r, key, _ -> set.piece(set.add(r, key)) },
        addDelta = { set, r, key, _ -> set.add(r, key) },
        removeFull = { set, _, key, _ -> set.piece(set.remove(key)) },
        removeDelta = { set, _, key, _ -> set.remove(key) },
        holds = { set, key -> set.contains(key) },
    )

    private val orMapProbe = WriteProbe(
        label = "ORMap",
        serializer = ORMap.serializer(string, GSet.serializer(string)),
        seed = { n ->
            (0 until n).fold(ORMap.empty<String, GSet<String>>()) { map, i ->
                map.piece { it.put(replicaId(i % SEED_REPLICAS), element(i), GSet.of(seedValue(i))) }
            }
        },
        addFull = { map, r, key, _ -> map.piece(map.put(r, key, GSet.of(PROBE_VALUE))) },
        addDelta = { map, r, key, _ -> map.put(r, key, GSet.of(PROBE_VALUE)) },
        removeFull = { map, _, key, _ -> map.piece(map.remove(key)) },
        removeDelta = { map, _, key, _ -> map.remove(key) },
        holds = { map, key -> map[key] != null },
    )

    private val lwwMapProbe = WriteProbe(
        label = "LWWMap",
        serializer = LWWMap.serializer(string, string),
        seed = { n ->
            (0 until n).fold(LWWMap.empty<String, String>()) { map, i ->
                map.piece { it.set(replicaId(i % SEED_REPLICAS), i + 1L, element(i), seedValue(i)) }
            }
        },
        addFull = { map, r, key, at -> map.piece(map.set(r, at, key, PROBE_VALUE)) },
        addDelta = { map, r, key, at -> map.set(r, at, key, PROBE_VALUE) },
        removeFull = { map, r, key, at -> map.piece(map.remove(r, at, key)) },
        removeDelta = { map, r, key, at -> map.remove(r, at, key) },
        holds = { map, key -> map[key] != null },
    )

    /**
     * Stand a [MESH_NODES]-node gossip mesh up on a [stateSize]-entry state of [probe]'s type and
     * meter, in order: the bootstrap burst, one add on each path, one remove on each path, and
     * [ANTI_ENTROPY_ROUNDS] converged anti-entropy rounds.
     *
     * **Everything metered here is asserted to have happened.** A byte count is only a measurement
     * of the path you think it is if that path ran, and the failure mode is not a red test — it is
     * a *smaller, better-looking* number. Two guards, both of which have caught something in this
     * tree before:
     *
     * - Each write is followed by a check that every peer's state actually moved. A frame nobody
     *   received still costs its sender bytes and would be priced as a successful write.
     * - Every node writes once before the meter opens, so `nextSeq > 0` everywhere. A replica that
     *   has never applied a local mutation sends `upThrough = 0`, and the recipient's
     *   `resyncReceiveCursor` returns at its `<= 0L` early guard **before** acking — which prices a
     *   matched anti-entropy round at half and reads as a better result. That guard has gone
     *   unnoticed three times, once inside a measurement harness.
     *
     * The warm-up uses the delta path because it is outside every measured window and the full one
     * would ship four whole states for nothing.
     */
    @Suppress("LongMethod")
    private suspend fun <S : Quilted<S>> TestScope.meterBothPaths(
        probe: WriteProbe<S>,
        stateSize: Int,
    ): MeteredPaths {
        val clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        fun flush() = repeat(flushSteps) { testScheduler.advanceTimeBy(1); testScheduler.runCurrent() }

        val messageSerializer = QuiltMessage.serializer(probe.serializer)
        val seeded = probe.seed(stateSize)

        val mesh = buildInMemoryMesh(MESH_NODES)
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

        // ---- (i) the bootstrap burst: every peer's first-contact FullState to every other ----
        // `Quilter.onPeersChanged` calls `sendFullStateTo` once per newly-seen peer, so standing
        // the replicators up on an already-connected mesh IS the joining-peer path, n(n-1) times.
        val beforeBootstrap = mesh.clusterMetrics().totalBytesOut
        val quilters = gossips.map { gossip ->
            Quilter(
                seam = gossip,
                initial = seeded,
                valueSerializer = probe.serializer,
                scope = backgroundScope,
                config = QuilterConfig(expectVirtualTime = true, antiEntropyInterval = antiEntropyInterval),
                random = Random(100),
            )
        }
        flush()
        val bootstrap = mesh.clusterMetrics().totalBytesOut - beforeBootstrap

        quilters.forEachIndexed { i, quilter ->
            quilter.mutate { probe.addDelta(it, ReplicaId("peer-$i"), "warm-up-$i", PROBE_TIMESTAMP_BASE + i) }
        }
        flush()
        assertEquals(
            1,
            quilters.map { it.state.value }.toSet().size,
            "${probe.label}/$stateSize: the mesh must be converged before the meter opens",
        )

        // ---- (ii) one write, each of four ways ----
        fun window(write: (Quilter<S>) -> Unit): Long {
            val before = mesh.clusterMetrics().totalBytesOut
            write(quilters[0])
            flush()
            return mesh.clusterMetrics().totalBytesOut - before
        }

        fun assertEveryPeer(key: String, present: Boolean, what: String) {
            assertTrue(
                quilters.all { probe.holds(it.state.value, key) == present },
                "${probe.label}/$stateSize: $what must have reached every peer — a frame nobody " +
                    "received still costs its sender bytes, and would be metered as a write. " +
                    "Holds `$key`: ${quilters.map { probe.holds(it.state.value, key) }}, wanted " +
                    "$present everywhere",
            )
        }

        val fullAdd = window { q ->
            q.mutate { Patch(probe.addFull(it, meshSender, FULL_PATH_KEY, PROBE_TIMESTAMP_BASE + FULL_ADD_TICK)) }
        }
        assertEveryPeer(FULL_PATH_KEY, present = true, what = "the full-state add")

        val deltaAdd = window { q ->
            q.mutate { probe.addDelta(it, meshSender, DELTA_PATH_KEY, PROBE_TIMESTAMP_BASE + DELTA_ADD_TICK) }
        }
        assertEveryPeer(DELTA_PATH_KEY, present = true, what = "the delta add")

        val fullRemove = window { q ->
            q.mutate { Patch(probe.removeFull(it, meshSender, FULL_PATH_KEY, PROBE_TIMESTAMP_BASE + FULL_REMOVE_TICK)) }
        }
        assertEveryPeer(FULL_PATH_KEY, present = false, what = "the full-state remove")

        val deltaRemove = window { q ->
            q.mutate { probe.removeDelta(it, meshSender, DELTA_PATH_KEY, PROBE_TIMESTAMP_BASE + DELTA_REMOVE_TICK) }
        }
        assertEveryPeer(DELTA_PATH_KEY, present = false, what = "the delta remove")

        // The #1955 gate, stated where it can fail: `Quilter.stateRoot()` hashes the state as it
        // appears on the wire, so two peers that agree logically but encode differently never match
        // roots and anti-entropy silently degrades to shipping full states forever. Comparing the
        // encodings directly is the same check without the hash in the way.
        val encodings = quilters.map { bytes(probe.serializer, it.state.value) }
        encodings.forEach {
            assertContentEquals(
                encodings.first(), it,
                "${probe.label}/$stateSize: every peer must ENCODE the converged state identically " +
                    "after a mixed full-state/delta workload — equal-but-differently-encoded is " +
                    "exactly what #1955's root-hash gate cannot see past",
            )
        }

        // ---- (iii) converged anti-entropy rounds, for scale ----
        val beforeRounds = mesh.clusterMetrics().totalBytesOut
        repeat(ANTI_ENTROPY_ROUNDS) {
            testScheduler.advanceTimeBy(antiEntropyInterval.inWholeMilliseconds)
            testScheduler.runCurrent()
        }
        val roundBytes = mesh.clusterMetrics().totalBytesOut - beforeRounds

        val fullState = frameBytes(messageSerializer, quilters.first().state.value, meshSender)
        val seededState = frameBytes(messageSerializer, seeded, meshSender)

        quilters.forEach { it.close() }
        gossips.forEach { it.close() }
        mesh.close()

        return MeteredPaths(
            fullAdd = fullAdd,
            deltaAdd = deltaAdd,
            fullRemove = fullRemove,
            deltaRemove = deltaRemove,
            fullState = fullState,
            seededState = seededState,
            bootstrap = bootstrap,
            perRoundPerNode = roundBytes.toDouble() / (ANTI_ENTROPY_ROUNDS * MESH_NODES),
        )
    }

    /**
     * The standing assertion of this file, **inverted on purpose**.
     *
     * It used to read *"one add must cost more than admitting a new peer — if this ever inverts,
     * `ORSet` grew a delta mutator and part (H)'s budget is stale"*. #2044 grew it one. The
     * inversion is therefore the finding, not a regression, and deleting the assertion would have
     * thrown away the only thing in this suite that was watching for it.
     *
     * Both directions are asserted on the **same mesh, in the same run**, which is what keeps the
     * new one honest: the old property still holds on the `Patch(state.add(…))` path, so a harness
     * that had stopped measuring anything would fail the control rather than pass the inversion.
     */
    @Test
    fun oneWriteNoLongerCostsMoreThanAdmittingANewPeer() =
        runTest(UnconfinedTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val stateSize = 400
            val paths = meterBothPaths(orSetProbe, stateSize)
            val oneJoin = paths.oneJoin(MESH_NODES)

            println("\n=== #2044 grounding (I): the paths, metered, $stateSize entries, $MESH_NODES nodes ===")
            println("  full state at that moment      : ${paths.fullState} b (at bootstrap: ${paths.seededState} b)")
            println("  (i)   bootstrap burst, whole mesh: ${paths.bootstrap} b " +
                "= ${"%.2f".format(paths.bootstrap / paths.seededState.toDouble())} full states " +
                "(modelled ${MESH_NODES * (MESH_NODES - 1)} first-contact frames; the excess is handshake)")
            println("  (i')  ONE join into a live mesh  : ${oneJoin.toLong()} b " +
                "(${MESH_NODES - 1} existing peers each ship one full state)")
            println("  (ii)  ONE add,   Patch(add(...)) : ${paths.fullAdd} b " +
                "= ${"%.1f".format(paths.fullAdd / paths.fullState.toDouble())} full states, " +
                "${"%.1f".format(paths.fullAdd / oneJoin)}x a join")
            println("  (ii') ONE add,   add(...) delta  : ${paths.deltaAdd} b " +
                "= ${"%.3f".format(paths.deltaAdd / oneJoin)}x a join, " +
                "${"%.0f".format(paths.fullAdd.toDouble() / paths.deltaAdd)}x smaller than the full-state form")
            println("  (iii) converged round, per node  : ${"%.1f".format(paths.perRoundPerNode)} b " +
                "(modelled ${digestRoundBytes(meshSender)} b) — one delta write = " +
                "${"%.0f".format(paths.deltaAdd / paths.perRoundPerNode)} of these")
            println()
            println("  #2037 asked whether the JOINING PEER is the real consumer. On the path this file")
            println("  was written against it was not — the LOCAL WRITE was, and by a multiple, for two")
            println("  compounding reasons: `ORSet.add` returned the whole new set, so `Patch(state.add(...))`")
            println("  broadcast the ENTIRE state once per write; and a broadcast is flooded, so it crossed")
            println("  ${"%.1f".format(paths.fullAdd / paths.fullState.toDouble())} links where a join's unicast crosses ${MESH_NODES - 1}.")
            println("  Through `add`'s delta the flood factor is unchanged and the frame is not, so the write")
            println("  path drops BELOW the join and the ranking #2037 assumed is restored — by removing the")
            println("  O(state) term, not by shrinking it. Every candidate in #2037 was a constant factor on")
            println("  a term that no longer has to be paid at all.")

            assertAll(
                // The inversion. Named for what it means rather than for the number, because the
                // number is the whole set and the property is that the write no longer carries it.
                {
                    assertTrue(
                        paths.deltaAdd < oneJoin,
                        "one add through ORSet.add's delta (${paths.deltaAdd} b) must now cost LESS than " +
                            "admitting a new peer (${oneJoin.toLong()} b). This assertion used to run the " +
                            "other way; #2044 gave ORSet a delta mutator and it inverted on purpose",
                    )
                },
                // The control that keeps the inversion honest: the old direction, same mesh, same
                // run. A meter that had gone silent would fail here rather than pass above.
                {
                    assertTrue(
                        paths.fullAdd > oneJoin,
                        "the OLD path must still cost more than a join (${paths.fullAdd} b vs " +
                            "${oneJoin.toLong()} b) — if this stopped holding the meter has gone " +
                            "silent and the inversion above proves nothing",
                    )
                },
                // The scale of the change on one wire, stated as a ratio so it survives any future
                // re-encoding of the frame.
                {
                    assertTrue(
                        paths.fullAdd > paths.deltaAdd * DELTA_HEADLINE_RATIO,
                        "at $stateSize entries the delta path (${paths.deltaAdd} b) must be at least " +
                            "${DELTA_HEADLINE_RATIO.toInt()}x below the full-state path (${paths.fullAdd} b)",
                    )
                },
                // The `upThrough = 0` guard, as an assertion rather than a comment: a matched round
                // is digest-out PLUS ack-back, and a silent no-op resync halves it.
                {
                    assertTrue(
                        paths.perRoundPerNode > rootDigestBytes(meshSender) * 1.2,
                        "a matched round must carry the ack back, not the digest alone (metered " +
                            "${paths.perRoundPerNode} b vs one digest ${rootDigestBytes(meshSender)} b) — a " +
                            "harness whose nodes never wrote prices this at half",
                    )
                },
                // The bootstrap path, grounded against its model: n(n-1) first-contact full states.
                {
                    val modelled = paths.seededState.toDouble() * MESH_NODES * (MESH_NODES - 1)
                    assertTrue(
                        paths.bootstrap in modelled.toLong()..(modelled * 1.1).toLong(),
                        "the bootstrap burst (${paths.bootstrap} b) must carry " +
                            "${MESH_NODES * (MESH_NODES - 1)} full states (${modelled.toLong()} b) plus only " +
                            "handshake noise — one per ordered pair, which is what makes a join " +
                            "O(peers) full states",
                    )
                },
            )
        }

    /**
     * The property #2044 actually bought, on all three types that got a delta mutator: **the
     * metered cost of one write is flat in state size.**
     *
     * *Cheaper than before* would have been the easy assertion and the wrong one — it passes a
     * future change that reintroduces an O(entries) term with a better constant, which is precisely
     * the regression worth catching. Flatness does not.
     *
     * It also cannot be satisfied vacuously by a delta that ships the whole state, which is what
     * makes this test the load-bearing one for `ORSet`. The delta-mutator law
     * `X.piece(mᵟ(X)) == m(X)` is satisfied **perfectly** by shipping the entire state — `m(X)`
     * joined onto `X` is exactly `m(X)` — so a no-op delta leaves every law test in `:kuilt-crdt`
     * green. It was verified so while Task 3 of #2044's plan was being written. Only a frame-size
     * assertion sees it, and for `ORSet` this is the only one there is.
     *
     * Three guards make the flatness claim mean something:
     *
     * 1. **A negative control on the same rows.** The full-state path must grow at least
     *    [O_N_CONTROL]× between the smallest and largest state. A harness that had stopped
     *    measuring would report *both* paths flat, and pass a one-sided test.
     * 2. **Both mutators.** An add and a remove are different shapes — one mints a dot and
     *    supersedes the dots it observed, the other retires them and (for `LWWMap`) writes a
     *    tombstone.
     * 3. **The anti-entropy round, unchanged.** Step 3 of the plan and the reason this test is a
     *    #1955 safety check rather than a nicety: a delta path that desynchronised the root hash
     *    would show up here as full states in the round window, not as digests.
     */
    @Test
    fun oneWriteCostsTheSameOnAOneHundredAndAOneThousandSixHundredEntryState() =
        runTest(UnconfinedTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rows = mutableListOf<Triple<String, Int, MeteredPaths>>()
            for (n in writeSweepSizes) rows += Triple(orSetProbe.label, n, meterBothPaths(orSetProbe, n))
            for (n in writeSweepSizes) rows += Triple(orMapProbe.label, n, meterBothPaths(orMapProbe, n))
            for (n in writeSweepSizes) rows += Triple(lwwMapProbe.label, n, meterBothPaths(lwwMapProbe, n))

            println("\n=== #2044 (I): one write, metered on both paths, $MESH_NODES nodes ===")
            println("  Cluster-wide bytes a MeteredSeam counted for ONE write, flood included. `today` is")
            println("  `Patch(state.mutator(...))`; `delta'd` is the mutator #2044 added. No wall clock is")
            println("  read anywhere in this table.")
            println("  %8s %9s %14s %14s %14s %14s %12s".format(
                "type", "entries", "add today", "add delta'd", "remove today", "remove delta'd", "round b/node"))
            rows.forEach { (type, n, m) ->
                println("  %8s %9d %14d %14d %14d %14d %12.1f".format(
                    type, n, m.fullAdd, m.deltaAdd, m.fullRemove, m.deltaRemove, m.perRoundPerNode))
            }
            println()
            println("  The `today` columns grow with the state; the `delta'd` columns do not move at all.")
            println("  `LWWMap`'s removal is a TOMBSTONE write, so its `remove today` matches its `add")
            println("  today` — the map does not shrink — and its delta is a one-cell map, never an empty one.")

            assertAll(
                *rows.groupBy { it.first }.map { (type, byType) ->
                    {
                        val small = byType.first { it.second == writeSweepSizes.first() }.third
                        val large = byType.first { it.second == writeSweepSizes.last() }.third
                        assertAll(
                            {
                                assertTrue(
                                    large.deltaAdd <= small.deltaAdd * FLAT_TOLERANCE,
                                    "$type: an add through the delta mutator must be FLAT in state size — " +
                                        "${large.deltaAdd} b at ${writeSweepSizes.last()} entries against " +
                                        "${small.deltaAdd} b at ${writeSweepSizes.first()}, tolerance " +
                                        "${FLAT_TOLERANCE}x. Not 'smaller than before': that would pass a " +
                                        "change that reintroduced O(entries) with a better constant",
                                )
                            },
                            {
                                assertTrue(
                                    large.deltaRemove <= small.deltaRemove * FLAT_TOLERANCE,
                                    "$type: a remove through the delta mutator must be FLAT in state size — " +
                                        "${large.deltaRemove} b at ${writeSweepSizes.last()} entries against " +
                                        "${small.deltaRemove} b at ${writeSweepSizes.first()}",
                                )
                            },
                            // The negative control. Without it a harness that had gone silent would
                            // report every column flat and pass the two assertions above.
                            {
                                assertTrue(
                                    large.fullAdd >= small.fullAdd * O_N_CONTROL,
                                    "$type: the FULL-STATE add must still grow with the state " +
                                        "(${small.fullAdd} b -> ${large.fullAdd} b over a " +
                                        "${writeSweepSizes.last() / writeSweepSizes.first()}x state) — if it " +
                                        "does not, this harness cannot see an O(entries) term and the " +
                                        "flatness above is vacuous",
                                )
                            },
                        )
                    }
                }.toTypedArray(),
                // Step 3 of #2044's plan: the #1955 gate is still engaged. Anti-entropy on the delta
                // path must still be a digest and an ack, at every state size and for every type —
                // a root-hash desynchronisation would show up here as full states instead.
                {
                    val rounds = rows.map { it.third.perRoundPerNode }
                    assertTrue(
                        rounds.all { it in CONVERGED_ROUND_BYTES - CONVERGED_ROUND_SLACK..CONVERGED_ROUND_BYTES + CONVERGED_ROUND_SLACK },
                        "converged anti-entropy must still cost ~$CONVERGED_ROUND_BYTES b/node/round on the " +
                            "delta path (measured $rounds) — that is #1955's digest-plus-ack, and a delta " +
                            "path that desynchronised the root hash would show up here as FULL STATES",
                    )
                },
                // Stronger than the band above, and the form that actually names the property: the
                // round is a digest, so entry count must not reach it AT ALL.
                {
                    val rounds = rows.map { it.third.perRoundPerNode }
                    assertEquals(
                        rounds.min(), rounds.max(),
                        "the converged round must be identical at every state size and for every type " +
                            "(measured $rounds) — it carries a root hash and a cursor, and nothing that " +
                            "grows",
                    )
                },
                // The `upThrough = 0` guard, restated per row. A replica that never applied a local
                // mutation sends `upThrough = 0`, and the recipient's `resyncReceiveCursor` returns
                // at its `<= 0L` early guard BEFORE acking — which prices a matched round at half
                // and reads as a better result. The band above would catch that too; this says why.
                {
                    rows.forEach { (type, n, metered) ->
                        assertTrue(
                            metered.perRoundPerNode > rootDigestBytes(meshSender) * 1.2,
                            "$type/$n: a matched round must carry the ack back, not the digest alone " +
                                "(metered ${metered.perRoundPerNode} b vs one digest " +
                                "${rootDigestBytes(meshSender)} b) — a harness whose nodes never wrote " +
                                "prices this at half",
                        )
                    }
                },
            )
        }
    // ---- J. the combination -------------------------------------------------------------------

    @Test
    fun theTwoEncodingCandidatesComposeButDoNotAdd() {
        println("\n=== #2037 Phase 0 (J): C0 and C1 stacked, n = 10,000, 4 replicas ===")
        println("  %9s %12s %12s %12s %14s %12s %10s %12s".format(
            "id width", "today b", "array b", "interned b", "array+intern b", "sum of two", "saved", "+definite b"))
        idWidths.forEach { w ->
            val base = frameBytes(namedFrame, namedCausal(GRID_N, 4, w))
            val array = frameBytes(arrayFrame, arrayCausal(GRID_N, 4, w))
            val interned = frameBytes(internedFrame, internedCausal(GRID_N, 4, w))
            val both = frameBytes(arrayInternedFrame, arrayInternedCausal(GRID_N, 4, w))
            val allThree = definiteCbor.encodeToByteArray(
                arrayInternedFrame,
                QuiltMessage.FullState(replica, arrayInternedCausal(GRID_N, 4, w)),
            ).size
            val sumOfSavings = (base - array) + (base - interned)
            println("  %9d %12d %12d %12d %14d %12d %9.1f%% %12d".format(
                w, base, array, interned, both, sumOfSavings, (base - both) * 100.0 / base, allThree))
        }
        println("  The combined frame is FLAT in id width — the id now appears once, in the table —")
        println("  so a UUID replica id becomes free rather than tripling the tax.")

        println("\n  And what the best combination leaves, against a GSet baseline of the same payload:")
        println("  %9s %12s %12s %14s %14s".format("id width", "today b", "both b", "GSet b", "residual tax"))
        idWidths.forEach { w ->
            val base = frameBytes(namedFrame, namedCausal(GRID_N, 4, w))
            val both = frameBytes(arrayInternedFrame, arrayInternedCausal(GRID_N, 4, w))
            val gset = frameBytes(gsetFrame, GSet.of(*Array(GRID_N) { element(it) }))
            println("  %9d %12d %12d %14d %13.1f%%".format(w, base, both, gset, (both - gset) * 100.0 / both))
        }

        assertAll(
            // They compose in the same direction but sub-additively: interning shrinks the id that
            // the array form was already not naming, so the second lever has less left to take.
            {
                idWidths.forEach { w ->
                    val base = frameBytes(namedFrame, namedCausal(GRID_N, 4, w))
                    val array = frameBytes(arrayFrame, arrayCausal(GRID_N, 4, w))
                    val interned = frameBytes(internedFrame, internedCausal(GRID_N, 4, w))
                    val both = frameBytes(arrayInternedFrame, arrayInternedCausal(GRID_N, 4, w))
                    assertTrue(
                        both <= minOf(array, interned),
                        "at width $w the combination ($both b) must be at least as small as either " +
                            "lever alone (array $array b, interned $interned b)",
                    )
                    assertTrue(
                        base - both <= (base - array) + (base - interned),
                        "the savings must not super-add at width $w",
                    )
                }
            },
            // The ceiling on this whole direction: even both levers together leave a real residual
            // tax, because one dot per entry is a lattice requirement, not an encoding accident.
            {
                val both = frameBytes(arrayInternedFrame, arrayInternedCausal(GRID_N, 4, DEFAULT_ID_WIDTH))
                val gset = frameBytes(gsetFrame, GSet.of(*Array(GRID_N) { element(it) }))
                assertTrue(
                    both > gset,
                    "even fully compressed, an ORSet frame ($both b) must exceed the GSet carrying " +
                        "the same payload ($gset b) — the residual is the dot itself",
                )
            },
        )
    }
}

/** The sibling suites' replica-id width; 11 characters, wide enough to hold `replica-255`. */
private const val DEFAULT_ID_WIDTH = 11

/** A short device/session handle. */
private const val SHORT_ID_WIDTH = 8

/** The practical floor: two base-36 characters still name 1,296 replicas. */
private const val MIN_ID_WIDTH = 2

/** Replica ids are base-36 so the narrowest widths still address the whole replica sweep. */
private const val RADIX_36 = 36

/** A raw UUID, which #2037 identifies as roughly tripling the per-entry tax. */
private const val UUID_ID_WIDTH = 36

/** Entry count for the (R x id width) grids — big enough to be asymptotic, small enough to be quick. */
private const val GRID_N = 10_000

/** Where the marginal per-entry costs in part (C) are measured. */
private const val ANATOMY_N = 4_000

/** The span the marginal cost is averaged over, chosen to straddle no CBOR length-header step. */
private const val ANATOMY_STEP = 1_000

/** Upper bound on the break-even search in part (D). */
private const val MAX_BREAK_EVEN = 4_000

/** Slack allowed when asserting that interning is a wash at a 1-character id. */
private const val TABLE_SLACK = 8

/** Seed for the scrambled key scheme in part (F). */
private const val SCRAMBLE_SEED = 20_370

/** Nodes in every metered mesh in part (I) — the sibling suites' shape. */
private const val MESH_NODES = 4

/** Replicas the seeded states in part (I) are written by, round-robin, as elsewhere in this file. */
private const val SEED_REPLICAS = 4

/** Converged anti-entropy rounds metered in part (I). */
private const val ANTI_ENTROPY_ROUNDS = 20

/**
 * `LWWMap` write tags in part (I) start here: above every seeded tag (which run `1..entries`, so
 * at most [WRITE_SWEEP_MAX]) and — the reason for the round number — **the same width at every
 * state size**, so entry count cannot reach the delta frame through the varint that encodes it.
 */
private const val PROBE_TIMESTAMP_BASE = 1_000_000L

/** Offsets from [PROBE_TIMESTAMP_BASE], one per metered write, so no two share a tag. */
private const val FULL_ADD_TICK = 10L
private const val DELTA_ADD_TICK = 11L
private const val FULL_REMOVE_TICK = 12L
private const val DELTA_REMOVE_TICK = 13L

/** The largest state part (I) sweeps; [PROBE_TIMESTAMP_BASE] must clear it. */
private const val WRITE_SWEEP_MAX = 1_600

/** The one key part (I) writes through `Patch(state.mutator(…))`. */
private const val FULL_PATH_KEY = "probe-key-written-through-the-full-state-path"

/** The one key part (I) writes through the delta mutator. Same width, so neither is favoured. */
private const val DELTA_PATH_KEY = "probe-key-written-through-the-delta-mutator--"

/** The value the two map probes write. Fixed, so entry count is the only axis that moves. */
private const val PROBE_VALUE = "probe-value"

/**
 * How much a delta write may grow between the smallest and largest state before it stops being
 * flat. Generous on purpose: the frames are *identical* in practice, and the point of the bound is
 * to fail an O(entries) term, not to pin a byte count.
 */
private const val FLAT_TOLERANCE = 1.2

/**
 * How much the full-state write must grow over the same span. The negative control on flatness:
 * a harness that had stopped measuring would report every column flat and pass a one-sided test.
 * Well under the 16x the state itself grows, so it fails on silence rather than on encoding drift.
 */
private const val O_N_CONTROL = 3.0

/** The margin by which the delta path must beat the full-state path at 400 entries. */
private const val DELTA_HEADLINE_RATIO = 50.0

/**
 * Converged anti-entropy, per node per round: a `RootDigest` out and an `Ack` back. #1955 took this
 * path to a digest exchange and #1986 confirmed the dot-based types inherited it; part (I) asserts
 * the delta path did not disturb it. Re-record from part (I)'s `round b/node` column if the frame
 * itself changes — but check first that it is the frame and not a full state leaking in.
 */
private const val CONVERGED_ROUND_BYTES = 94.0

/** Slack on [CONVERGED_ROUND_BYTES]. A full state leaking into the round window is ~250x this. */
private const val CONVERGED_ROUND_SLACK = 4.0

/**
 * The bytes `ORSet`'s generated serializer adds over the raw `Causal` it wraps: a one-entry CBOR
 * map keyed `"causal"`. Part (H) prices its columns on the `Causal` mirror and pins them against
 * the real `ORSet` frame across this constant.
 */
private const val ORSET_WRAPPER_SLACK = 16
