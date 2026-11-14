package us.tractat.kuilt.heddle

import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.modules.SerializersModule
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [LocalHeddle.persist], [LocalHeddle.restore] and [LocalHeddle.decode] (#3173, plan Task P with
 * Amendments 7–10). This release writes format 2, pinned by [GOLDEN_FORMAT_2], and still reads
 * format 1, which v0.7.4 wrote: [GOLDEN_FORMAT_1] and its siblings are decode-only.
 */
class LocalHeddlePersistenceTest {

    /** `root → {a, b}` at equal weight. */
    private val pair = Topology("root", listOf(Topology.Lane("a"), Topology.Lane("b")))
    private val root = pair.groupId()
    private val a = pair.groupId("a")
    private val b = pair.groupId("b")
    private val policy = LocalPolicy(defaultReservation = 1L)

    private fun encode(snapshot: LocalHeddleSnapshot): String = Json.encodeToString(LocalHeddleSnapshot.serializer(), snapshot)

    private fun decoded(text: String): LocalHeddleSnapshot {
        val out = LocalHeddle.decode(text, Json)
        return assertIs<RestoreResult.Restored>(out, "decode: $out").snapshot
    }

    /** [h] persisted, encoded as JSON, decoded and restored under the next epoch. */
    private fun throughJson(h: LocalHeddle, with: LocalPolicy = policy): LocalHeddle {
        val snapshot = decoded(encode(h.persist()))
        return LocalHeddle.restore(snapshot, with, epoch = snapshot.epoch + 1)
    }

    /**
     * What two engines must agree on to have the same books: each edge's summary, each group's
     * holdings, the totals minted and spent, the open leases and the wake memory. Not the ledger
     * value itself, since mint records are named by epoch and a restored engine mints under its own.
     */
    private fun books(h: LocalHeddle): List<Any> {
        val s = h.snapshot()
        val l = s.ledger
        val edges = l.allEdges().sorted()
        return listOf(
            edges.map { l.edge(it) },
            groupsOf(l).map { it to l.holdings(it, LOCAL) },
            l.mintedTotal(),
            l.leafSpentTotal(),
            s.leases,
            s.allocatorMemory,
        )
    }

    /**
     * The waker scenario. `root → {a, b}` at equal weight with two slots reconciled and no supply
     * laid down: `a` runs alone for 50 cycles while `b` is observed idle, then both are waiting, so
     * the first pick clamps `b` as a waker. Two leases are left open, one under each, so the
     * persisted wake offset is the one the clamp just wrote.
     */
    private fun wakerScenario(epoch: Long = 0L): Pair<LocalHeddle, List<Lease>> {
        val h = LocalHeddle.fromTopology(pair, policy, epoch)
        h.reconcileCapacity(totalSlots = 2L)
        repeat(50) {
            val lease = h.lease(setOf(a), startedAt = it.toLong())
            h.complete(lease.id, 1L)
        }
        val open = listOf(h.lease(setOf(a, b), startedAt = 100L), h.lease(setOf(a, b), startedAt = 101L))
        return h to open
    }

    /** The same calls after the scenario, on whichever engine: settle both open leases, then 20 slots with both waiting. */
    private fun continuation(h: LocalHeddle, open: List<Lease>): List<Any> {
        val outcomes = ArrayList<Any>()
        outcomes += h.complete(open[0].id, 1L)
        outcomes += h.complete(open[1].id, 3L)
        repeat(20) {
            val lease = h.lease(setOf(a, b), startedAt = 200L + it)
            outcomes += lease.leaf
            outcomes += h.complete(lease.id, 1L)
        }
        return outcomes
    }

    private fun wakeOffsetOf(memory: AllocatorMemory, leaf: GroupId): Rational =
        memory.wakeOffsets[edgeTo(leaf)] ?: Rational.ZERO

    /** The edge `root → leaf` of [pair] as [LocalHeddle.fromTopology] boots it, at generation 0. */
    private fun edgeTo(leaf: GroupId): AttachmentId =
        checkNotNull(LocalHeddle.fromTopology(pair, policy, 0L).snapshot().ledger.lineageOf(leaf)).single()

    @Test
    fun snapshotRoundTripsThroughJson() {
        val (h, _) = wakerScenario()
        val snapshot = h.persist()
        val text = encode(snapshot)
        val back = decoded(text)
        assertAll(
            { assertEquals(2, snapshot.leases.size, "the rig: two leases are open") },
            { assertEquals(snapshot, back, "the decoded snapshot is the persisted one") },
            { assertEquals(text, encode(back), "and encodes to the same text") },
            { assertEquals(h.snapshot().leases, back.leases) },
            { assertEquals(h.snapshot().allocatorMemory, back.allocatorMemory) },
            { assertEquals(2L, back.totalSlots) },
        )
    }

    /** The ByteArray overload, over a binary format that carries JSON as UTF-8. */
    @Test
    fun snapshotRoundTripsThroughABinaryFormat() {
        val (h, _) = wakerScenario()
        val snapshot = h.persist()
        val bytes = JsonBytes.encodeToByteArray(LocalHeddleSnapshot.serializer(), snapshot)
        assertAll(
            { assertEquals(RestoreResult.Restored(snapshot), LocalHeddle.decode(bytes, JsonBytes)) },
            { assertIs<RestoreResult.Corrupt>(LocalHeddle.decode(bytes.copyOf(bytes.size / 2), JsonBytes)) },
        )
    }

    /** The envelope is a sequence in a real binary format too: CBOR writes it as an array and reads it back. */
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun snapshotRoundTripsThroughCbor() {
        val snapshot = wakerScenario().first.persist()
        val bytes = Cbor.encodeToByteArray(LocalHeddleSnapshot.serializer(), snapshot)
        assertAll(
            { assertEquals(4, (bytes[0].toInt() and 0xFF) shr 5, "the rig: CBOR wrote an array (major type 4), not a map (5)") },
            { assertEquals(RestoreResult.Restored(snapshot), LocalHeddle.decode(bytes, Cbor)) },
        )
    }

    /**
     * A CBOR array with an element past the state is corrupt, and named so rather than thrown. CBOR
     * looks up each element's annotations in the envelope's descriptor before it hands back the
     * element's index, so this is the one input that asks the descriptor about an index it does not
     * describe.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun aCborElementPastTheStateIsCorrupt() {
        val bytes = Cbor.encodeToByteArray(LocalHeddleSnapshot.serializer(), wakerScenario().first.persist())
        // `9F 02 <state> FF`: an indefinite-length array, the version, the state, the break.
        val state = bytes.copyOfRange(2, bytes.size - 1)
        val extra = bytes.copyOfRange(0, bytes.size - 1) + state + bytes.copyOfRange(bytes.size - 1, bytes.size)
        assertAll(
            { assertEquals(listOf(0x9F, 0x02), bytes.take(2).map { it.toInt() and 0xFF }, "the rig: an indefinite-length array, version 2 first") },
            { assertEquals(0xFF, bytes.last().toInt() and 0xFF, "the rig: closed by a break") },
            { assertIs<RestoreResult.Restored>(LocalHeddle.decode(bytes, Cbor), "the rig: the untouched bytes decode") },
            {
                val out = LocalHeddle.decode(extra, Cbor)
                assertTrue(out is RestoreResult.Corrupt && "past its state" in out.reason, "expected Corrupt naming the element past the state, got $out")
            },
        )
    }

    /**
     * The envelope's descriptor says what is written: a LIST of exactly two elements, an `Int` named
     * `formatVersion` and then the state, under the snapshot's own name. Schema tooling and
     * descriptor-driven formats read this, not the bytes.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun theDescriptorDescribesTheEnvelope() {
        val d = LocalHeddleSnapshot.serializer().descriptor
        assertAll(
            { assertEquals("us.tractat.kuilt.heddle.LocalHeddleSnapshot", d.serialName) },
            { assertEquals(StructureKind.LIST, d.kind) },
            { assertEquals(2, d.elementsCount) },
            { assertEquals(listOf("formatVersion", "state"), List(d.elementsCount) { d.getElementName(it) }) },
            { assertEquals(PrimitiveKind.INT, d.getElementDescriptor(0).kind) },
            { assertEquals(StructureKind.CLASS, d.getElementDescriptor(1).kind) },
        )
    }

    /**
     * A format that writes each collection's size before its elements and reads sequentially reads
     * the envelope too. Read without its size, the `2` in front is taken for the version and the real
     * version for the start of the state, so no snapshot would come back `Restored`.
     */
    @Test
    fun snapshotRoundTripsThroughASizePrefixedFormat() {
        val snapshot = richScenario().first.persist()
        val bytes = SizePrefixedFormat.encodeToByteArray(LocalHeddleSnapshot.serializer(), snapshot)
        assertAll(
            { assertEquals(listOf<Byte>(0, 0, 0, 2), bytes.take(4), "the rig: the format wrote the envelope's size first") },
            { assertEquals(listOf<Byte>(0, 0, 0, 2), bytes.slice(4..7), "the rig: and the version after it") },
            { assertEquals(RestoreResult.Restored(snapshot), LocalHeddle.decode(bytes, SizePrefixedFormat)) },
        )
    }

    /** A size-prefixed envelope that says it holds other than two elements is corrupt, whatever follows. */
    @Test
    fun aSizePrefixedEnvelopeOfTheWrongSizeIsCorrupt() {
        val bytes = SizePrefixedFormat.encodeToByteArray(LocalHeddleSnapshot.serializer(), wakerScenario().first.persist())
        val checks = listOf(0, 1, 3).map { size ->
            val resized = byteArrayOf(0, 0, 0, size.toByte()) + bytes.copyOfRange(4, bytes.size)
            val check: () -> Unit = {
                val out = LocalHeddle.decode(resized, SizePrefixedFormat)
                assertTrue(out is RestoreResult.Corrupt && "has $size" in out.reason, "size $size: expected Corrupt naming it, got $out")
            }
            check
        }
        assertAll(
            { assertIs<RestoreResult.Restored>(LocalHeddle.decode(bytes, SizePrefixedFormat), "the rig: the untouched bytes decode") },
            *checks.toTypedArray(),
        )
    }

    /**
     * A snapshot taken mid-drain restores, and the drain finishes after it. A removal with a lease
     * open leaves its edge CLOSING; the lease's path crosses that edge. Restored, the lease completes,
     * and applying the same topology again retires the edge.
     */
    @Test
    fun aDrainingRemovalRetiresAfterARestore() {
        val before = Topology("root", listOf(Topology.Lane("a"), Topology.Lane("spare")))
        val after = Topology("root", listOf(Topology.Lane("a")))
        val spare = before.groupId("spare")
        val h = LocalHeddle.fromTopology(before, policy, epoch = 0L).also { it.reconcileCapacity(2L) }
        val leftover = h.lease(setOf(spare))
        val edge = leftover.let { checkNotNull(h.capturedPath(it.id)).single() }
        val first = h.apply(after, generation = 1L)
        val persisted = h.persist()
        val restored = throughJson(h)
        val done = restored.complete(leftover.id, 1L)
        val second = restored.apply(after, generation = 1L)
        assertAll(
            { assertIs<StepRefusal.Draining>(first.refusals.single(), "the rig: the removal is draining") },
            { assertEquals(Lifecycle.CLOSING, persisted.ledger.lifecycle(edge), "the rig: the snapshot holds a CLOSING edge on an open lease's path") },
            { assertEquals(listOf(leftover), persisted.leases) },
            { assertEquals(LeaseOutcome.Done(1L, 0L), done, "the lease completes after the restore") },
            { assertEquals(emptyList(), second.refusals, "and the same topology applied again lands") },
            { assertEquals(Lifecycle.RETIRED, restored.snapshot().ledger.lifecycle(edge), "the edge retired") },
        )
    }

    /**
     * A lease whose leaf has no single live lineage in the ledger, here because a second live
     * inbound edge reaches it, is a snapshot no engine could have written: after every landed run,
     * every open lease's path is its leaf's lineage. It decodes as [RestoreResult.Corrupt] rather
     * than restoring an engine whose first reload would trip on it.
     */
    @Test
    fun aQuarantinedLineageIsCorrupt() {
        val (h, _) = wakerScenario()
        val second = AttachmentRecord(AttachmentId("root/a@2:1#9"), root, a, Weight.of(2))
        var ledger = h.snapshot().ledger
        ledger = ledger.piece(checkNotNull(ledger.prepare(second)).delta)
        ledger = ledger.piece(checkNotNull(ledger.activate(second.id)).delta)
        val envelope = Json.parseToJsonElement(encode(h.persist())).jsonArray
        val state = envelope[1].jsonObject
        val tampered = JsonArray(
            listOf(envelope[0], JsonObject(state + ("ledger" to Json.encodeToJsonElement(EntitlementLedger.serializer(), ledger)))),
        )
        assertAll(
            { assertEquals(null, ledger.lineageOf(a), "the rig: a's lineage is quarantined") },
            { assertIs<RestoreResult.Corrupt>(LocalHeddle.decode(tampered.toString(), Json)) },
        )
    }

    @Test
    fun restoreKeepsLeases() {
        val (reference, open) = wakerScenario()
        val restored = throughJson(wakerScenario().first)
        val before = restored.snapshot().leases
        val viaReference = open.map { reference.complete(it.id, 2L) }
        val viaRestored = open.map { restored.complete(it.id, 2L) }
        assertAll(
            { assertEquals(open, before, "every lease is open again under its own id") },
            { assertEquals(setOf(a, b), open.map { it.leaf }.toSet(), "the rig: one lease under each leaf") },
            { assertEquals(listOf(LeaseOutcome.Done(2L, 1L), LeaseOutcome.Done(2L, 1L)), viaRestored) },
            { assertEquals(viaReference, viaRestored, "completing them is what completing them would have been") },
            { assertEquals(books(reference), books(restored), "charged to the same leaves, down the same paths") },
            { assertTrue(restored.snapshot().ledger.validate().isEmpty()) },
        )
    }

    /**
     * A snapshot built in this module with a lease at the root and no path is refused. Decoding never
     * gets this far with an empty path, so this is the one route that reaches the check, and the
     * root's live lineage is empty too, so the lineage check alone would let it through.
     */
    @Test
    fun aRootLeaseWithNoPathIsRefused() {
        val s = LocalHeddle.fromTopology(pair, policy, epoch = 0L).persist()
        val atRoot = PersistedLease(Lease(LeaseId(0L, 0L), root, reserved = 1L, startedAt = 0L), path = emptyList())
        assertAll(
            { assertEquals(emptyList(), s.ledger.lineageOf(root), "the rig: the root's live lineage is the empty path") },
            {
                val e = assertFailsWith<IllegalArgumentException> {
                    LocalHeddleSnapshot(s.epoch, s.ledger, s.allocatorMemory, s.totalSlots, listOf(atRoot), s.lastApplied)
                }
                assertTrue("empty path" in e.message.orEmpty(), "refused for its empty path: ${e.message}")
            },
        )
    }

    @Test
    fun startedAtSurvivesRestore() {
        val (h, open) = wakerScenario()
        val restored = throughJson(h)
        val orphans = restored.reconcileLeases(live = emptySet(), now = 110L).orphaned
        assertAll(
            { assertEquals(listOf(100L, 101L), restored.snapshot().leases.map { it.startedAt }) },
            { assertEquals(open, orphans.map { it.lease }) },
            { assertEquals(listOf(10L, 9L), orphans.map { it.elapsed }, "elapsed is read off the persisted start times") },
        )
    }

    /**
     * The topology comes back with the engine, so the restored one leases exactly what the persisted
     * one did. The draft marks `pool` for runtime children and attaches none, so `pool` reads as a
     * leaf in the ledger and only the topology says it is not leasable. An engine that leased every
     * leaf after a restore would admit work there. The lane is the control: it is still leasable, so
     * the refusal is about `pool`, not a restored engine that leases nothing.
     */
    @Test
    fun lastAppliedSurvivesRestore() {
        val draft = Topology(
            "root",
            listOf(
                Topology.Lane("lane"),
                Topology.Group("pool", Weight.ONE, emptyList(), runtimeChildren = Topology.RuntimeChildren(Weight.ONE)),
            ),
        )
        val pool = draft.groupId("pool")
        val original = LocalHeddle.fromTopology(draft, policy, epoch = 0L)
        val before = original.admit(pool, startedAt = 0L)
        val restored = throughJson(original)
        assertAll(
            { assertEquals(draft, original.persist().lastApplied, "the rig: the snapshot holds the draft") },
            { assertEquals(draft, restored.persist().lastApplied, "and so does the restored engine's") },
            {
                assertEquals(
                    listOf<Any>(AcquireOutcome.NoLiveLineage(pool), AcquireOutcome.NoLiveLineage(pool)),
                    listOf(before, restored.admit(pool, startedAt = 1L)),
                    "a marked group with nothing attached is refused before the restore and after it",
                )
            },
            { assertIs<AcquireOutcome.Acquired>(restored.admit(draft.groupId("lane"), startedAt = 2L), "the control: the lane still leases") },
        )
    }

    /**
     * Restore under the next epoch, with the wake offset `b`'s clamp just wrote, then the same calls
     * as an engine that never persisted. Picks, outcomes and books all match. The control arm
     * restores the same snapshot with the wake memory forgotten, and picks differently, so the
     * memory is what carries the match.
     */
    @Test
    fun restoreReplaysIdenticallyWithAWaker() {
        val (reference, open) = wakerScenario()
        val persisted = wakerScenario().first.persist()
        val offset = wakeOffsetOf(persisted.allocatorMemory, b)

        val restored = LocalHeddle.restore(decoded(encode(persisted)), policy, epoch = 1L)
        val forgetful = LocalHeddle.restore(
            LocalHeddleSnapshot(
                persisted.epoch, persisted.ledger, AllocatorMemory.EMPTY, persisted.totalSlots, persisted.open, persisted.lastApplied,
            ),
            policy,
            epoch = 1L,
        )
        val expected = continuation(reference, open)
        val actual = continuation(restored, open)
        val control = continuation(forgetful, open)
        val bPicks = { picks: List<Any> -> picks.count { it == b } }
        assertAll(
            { assertTrue(offset > Rational.ZERO, "the rig: b's wake offset is non-zero at persist time, $offset") },
            { assertEquals(true, persisted.allocatorMemory.demandingObserved[edgeTo(b)], "and b was last seen waiting") },
            { assertEquals(20, expected.count { it is GroupId }, "the rig: 20 picks ran after the restore") },
            { assertEquals(expected, actual, "the same picks and outcomes as the engine that never persisted") },
            { assertEquals(books(reference), books(restored), "and the same books") },
            { assertNotEquals(expected, control, "the control: forgetting the memory changes the picks") },
            { assertTrue(bPicks(control) > bPicks(expected), "it hands b back the idle credit: ${bPicks(control)} against ${bPicks(expected)}") },
        )
    }

    /**
     * Format 1, as checked in. A later release must still decode it ([SUPPORTED_FORMAT_VERSIONS]),
     * and an engine restored from it carries on as the engine that wrote it would have.
     */
    @Test
    fun previousFormatRestores() {
        val snapshot = decoded(GOLDEN_FORMAT_1)
        val restored = LocalHeddle.restore(snapshot, policy, epoch = snapshot.epoch + 1)
        val (reference, open) = wakerScenario()
        assertAll(
            { assertEquals(open, snapshot.leases, "the golden holds the scenario's open leases") },
            { assertTrue(wakeOffsetOf(snapshot.allocatorMemory, b) > Rational.ZERO, "and b's wake offset") },
            { assertEquals(continuation(reference, open), continuation(restored, open)) },
            { assertEquals(books(reference), books(restored)) },
        )
    }

    /**
     * Format 2 adds nothing the waker scenario writes, so its golden and format 1's hold one
     * snapshot: the bump changed what a snapshot may carry, not what this one means.
     */
    @Test
    fun formatOneAndFormatTwoGoldensHoldTheSameSnapshot() {
        assertAll(
            { assertTrue(GOLDEN_FORMAT_2.startsWith("[2,") && GOLDEN_FORMAT_1.startsWith("[1,"), "the rig: one golden per format") },
            { assertEquals(decoded(GOLDEN_FORMAT_1), decoded(GOLDEN_FORMAT_2)) },
        )
    }

    /**
     * The format this release writes is pinned: the scenario encodes to the golden, byte for byte.
     * A change to any serializer inside a snapshot, the ledger's, the allocator memory's and the
     * topology's included, reds here, and then the choice is deliberate. Before format 2 has
     * shipped, regenerate the golden from the message, which carries the current encoding. After,
     * a field added to a nested type takes a default and leaves the bytes alone; any other nested
     * change needs a frozen surrogate of that type under the format's surrogate, because a new
     * top-level format alone does not keep an earlier format's nested values readable.
     */
    @Test
    fun theCurrentFormatEncodesToItsGolden() {
        val text = encode(wakerScenario().first.persist())
        assertEquals(GOLDEN_FORMAT_2, text, "format 2 changed; the encoding is now:\n$text\n")
    }

    /**
     * The current format is pinned in CBOR too, both ways: the JSON golden's snapshot encodes to the
     * CBOR golden byte for byte, and the CBOR golden decodes to that snapshot. Format 1's CBOR golden
     * still decodes to the same snapshot. The same choice as [theCurrentFormatEncodesToItsGolden]
     * applies when it reds.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun theCurrentFormatEncodesToItsCborGolden() {
        val snapshot = decoded(GOLDEN_FORMAT_2)
        val hex = hexOf(Cbor.encodeToByteArray(LocalHeddleSnapshot.serializer(), snapshot))
        assertAll(
            { assertEquals(GOLDEN_FORMAT_2_CBOR, hex, "format 2 changed in CBOR; the encoding is now:\n$hex\n") },
            { assertEquals(RestoreResult.Restored(snapshot), LocalHeddle.decode(bytesOf(GOLDEN_FORMAT_2_CBOR), Cbor)) },
            { assertEquals(RestoreResult.Restored(snapshot), LocalHeddle.decode(bytesOf(GOLDEN_FORMAT_1_CBOR), Cbor), "format 1's CBOR still reads") },
        )
    }

    /**
     * A CBOR writer that states each array's length up front (`82`, not `9f … ff`) is read too, and
     * a definite-length array of three, `83 02 S S`, is refused by its stated size before anything
     * past the version is read.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun definiteLengthCborRoundTripsAndAThreeElementArrayIsCorrupt() {
        val definite = Cbor { useDefiniteLengthEncoding = true }
        val snapshot = decoded(GOLDEN_FORMAT_2)
        val bytes = definite.encodeToByteArray(LocalHeddleSnapshot.serializer(), snapshot)
        val state = bytes.copyOfRange(2, bytes.size)
        val three = byteArrayOf(0x83.toByte(), 0x02) + state + state
        assertAll(
            { assertEquals(listOf(0x82, 0x02), bytes.take(2).map { it.toInt() and 0xFF }, "the rig: a definite-length array of two, version 2 first") },
            { assertEquals(RestoreResult.Restored(snapshot), LocalHeddle.decode(bytes, definite)) },
            { assertEquals(RestoreResult.Restored(snapshot), LocalHeddle.decode(bytes, Cbor), "a default reader reads it as well") },
            {
                val out = LocalHeddle.decode(three, definite)
                assertTrue(out is RestoreResult.Corrupt && "has 3" in out.reason, "expected Corrupt naming the size, got $out")
            },
        )
    }

    private fun hexOf(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun bytesOf(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /**
     * The tree the rich scenario runs: `acme` (a nested group at a fractional weight, holding a group
     * and a lane), `tenants` (marked for runtime children), and, while [withSpare], a lane `spare`.
     * While [capped], `acme`, `interactive` and the runtime marker each carry a ceiling.
     */
    private fun richTree(acmeWeight: Weight, withSpare: Boolean, capped: Boolean = false): Topology = Topology(
        "root",
        listOfNotNull(
            Topology.Group(
                "acme",
                acmeWeight,
                listOf(
                    Topology.Group("batch", Weight.ONE, listOf(Topology.Lane("x"), Topology.Lane("y", Weight.of(2)))),
                    Topology.Lane("interactive", maxConcurrent = 2L.takeIf { capped }),
                ),
                maxConcurrent = 3L.takeIf { capped },
            ),
            Topology.Group("tenants", Weight.ONE, emptyList(), runtimeChildren = Topology.RuntimeChildren(Weight.ONE, maxConcurrent = 2L.takeIf { capped })),
            Topology.Lane("spare").takeIf { withSpare },
        ),
    )

    private val richBefore = richTree(Weight.of(3, 2), withSpare = true)
    private val richAfter = richTree(Weight.of(5, 2), withSpare = false)

    /** [richAfter] with ceilings, high enough that no call of the scenarios meets them: what format 2's rich golden last applied. */
    private val richCapped = richTree(Weight.of(5, 2), withSpare = false, capped = true)
    private val richPolicy = LocalPolicy(defaultReservation = 2L)
    private val tenant = richBefore.runtimeChildId(listOf("tenants"), "t1")
    private val richLeaves = setOf(richBefore.groupId("acme", "interactive"), richBefore.groupId("acme", "batch", "x"), richBefore.groupId("acme", "batch", "y"), tenant)

    /**
     * The shapes format 1's envelope and topology have to carry; the ledger's own maps are pinned
     * in `:kuilt-heddle`'s `EntitlementLedgerCanonicalEncodingTest`. `t1` is attached under `tenants`; leases open at
     * `acme/interactive`, `spare`, `t1` and `acme/batch/x`; a reload reweights `acme`, moving the two
     * leases under it onto its new edge, and removes `spare`, which drains with its lease open; the
     * `batch/x` lease is cancelled; the engine is restored under epoch 1 and leases once more, so the
     * open leases span two epochs. Returns that engine and its open leases.
     */
    private fun richScenario(): Pair<LocalHeddle, List<Lease>> {
        val h = LocalHeddle.fromTopology(richBefore, richPolicy, epoch = 0L).also { it.reconcileCapacity(4L) }
        check(h.attachRuntimeChild(listOf("tenants"), "t1", generation = 1L).refusals.isEmpty())
        val interactive = h.lease(setOf(richBefore.groupId("acme", "interactive")), reservation = 2L, startedAt = 10L)
        val spare = h.lease(setOf(richBefore.groupId("spare")), reservation = 3L, startedAt = 11L)
        val inTenant = h.lease(setOf(tenant), reservation = 1L, startedAt = 12L)
        val x = h.lease(setOf(richBefore.groupId("acme", "batch", "x")), reservation = 2L, startedAt = 13L)
        val pathBefore = h.capturedPath(interactive.id)
        val reload = h.apply(richAfter, generation = 2L)
        check(reload.refusals.single() is StepRefusal.Draining) { "spare drains: $reload" }
        check(h.capturedPath(interactive.id) != pathBefore) { "the reweight moved the interactive lease" }
        check(h.cancel(x.id) == LeaseOutcome.Done(0L, 0L)) { "cancelled" }
        val restored = throughJson(h, richPolicy)
        val later = restored.lease(setOf(richBefore.groupId("acme", "batch", "y")), reservation = 2L, startedAt = 20L)
        return restored to listOf(interactive, spare, inTenant, later)
    }

    /** The same calls after the rich scenario, on whichever engine: settle every lease, reload again so `spare` retires, then 12 slots. */
    private fun richContinuation(h: LocalHeddle, open: List<Lease>): List<Any> {
        val outcomes = ArrayList<Any>()
        open.forEachIndexed { i, lease -> outcomes += h.complete(lease.id, 1L + i) }
        outcomes += shape(h.apply(richAfter, generation = 3L))
        repeat(12) {
            val out = h.acquire(richLeaves, richLeaves, startedAt = 100L + it)
            outcomes += shape(out)
            if (out is AcquireOutcome.Acquired) outcomes += h.complete(out.lease.id, 2L)
        }
        outcomes.add(books(h).dropLast(2))
        return outcomes
    }

    private val departed = richBefore.runtimeChildId(listOf("tenants"), "t2")

    /**
     * The rich scenario, then a second runtime child `t2` attached under `tenants`, leased, settled
     * and detached, so its edge retires with its books closed, then a reload that changes only
     * ceilings ([richCapped]), which moves no edge. Returns that engine, its open leases, and its
     * snapshot with `t2`'s edge forgotten: what format 2 adds over format 1, the fold (#3192) and
     * the ceilings (#3324).
     */
    private fun richFoldedScenario(): Triple<LocalHeddle, List<Lease>, LocalHeddleSnapshot> {
        val (h, open) = richScenario()
        check(h.attachRuntimeChild(listOf("tenants"), "t2", generation = 4L).refusals.isEmpty())
        val edge = checkNotNull(h.snapshot().ledger.lineageOf(departed)).last()
        val lease = h.lease(setOf(departed), reservation = 2L, startedAt = 30L)
        check(h.complete(lease.id, 3L) is LeaseOutcome.Done)
        check(h.detachRuntimeChild(listOf("tenants"), "t2").refusals.isEmpty())
        val ceilingsOnly = h.apply(richCapped, generation = 5L)
        check(ceilingsOnly.applied.isEmpty() && ceilingsOnly.refusals.all { it is StepRefusal.Draining }) { "a change of ceiling alone is no step: $ceilingsOnly" }
        val whole = h.persist()
        val folded = checkNotNull(whole.ledger.forget(listOf(edge))) { "t2's edge is forgettable: ${whole.ledger.forgetRefusal(edge)}" }
        return Triple(h, open, LocalHeddleSnapshot(whole.epoch, folded, whole.allocatorMemory, whole.totalSlots, whole.open, whole.lastApplied))
    }

    /** [continuation] with the books' rows for [departed] and its edge left out, the only rows a fold removes. */
    private fun List<Any>.withoutDeparted(edge: AttachmentId): List<Any> {
        val books = last() as List<*>
        val edges = (books[0] as List<*>).filterNot { (it as EdgeSummary?)?.attachment == edge }
        val holdings = (books[1] as List<*>).filterNot { (it as Pair<*, *>).first == departed }
        return dropLast(1) + listOf(listOf(edges, holdings) + books.drop(2))
    }

    /**
     * The rich scenario's encoding is pinned, with a fold in it, and it restores: an engine started
     * from it carries on exactly as the engine that wrote it, which never forgot, would have, through
     * a drain finishing and a reload. The books agree on every row but the forgotten edge's and its
     * child's, which the fold no longer holds.
     */
    @Test
    fun richCurrentFormatEncodesToItsGoldenAndRestores() {
        val (reference, open, snapshot) = richFoldedScenario()
        val edge = checkNotNull(reference.snapshot().ledger.inboundEdges(departed).singleOrNull())
        val text = encode(snapshot)
        // Decoded inside each arm, so a golden that no longer decodes still prints the new encoding.
        val golden = { decoded(GOLDEN_FORMAT_2_RICH) }
        assertAll(
            { assertEquals(GOLDEN_FORMAT_2_RICH, text, "format 2 changed; the rich encoding is now:\n$text\n") },
            { assertEquals(setOf(0L, 1L), golden().leases.map { it.id.epoch }.toSet(), "the rig: open leases from two epochs") },
            { assertTrue(golden().ledger.let { l -> l.allEdges().any { l.lifecycle(it) == Lifecycle.CLOSING } }, "the rig: an edge is draining") },
            { assertTrue(golden().leases.any { it.leaf == tenant }, "the rig: a lease at a runtime child") },
            { assertEquals(richCapped, golden().lastApplied, "the rig: the topology last applied is the reload's, with its ceilings") },
            { assertTrue(golden().ledger.hasForgotten() && edge !in golden().ledger.allEdges(), "the rig: t2's edge is forgotten") },
            { assertTrue("\"forgotten\":" in GOLDEN_FORMAT_2_RICH, "the rig: the fold is in the bytes") },
            { assertTrue((reference.snapshot().ledger.edge(edge)?.spent ?: 0L) > 0L, "the rig: the forgotten edge had spent something") },
            {
                val restored = LocalHeddle.restore(golden(), richPolicy, epoch = snapshot.epoch + 1)
                assertEquals(richContinuation(reference, open).withoutDeparted(edge), richContinuation(restored, open))
            },
        )
    }

    /**
     * Format 2's rich golden carries a ceiling on a group, a lane and a runtime marker, and an engine
     * restored from it enforces them: `interactive` (ceiling 2) has one lease open in the golden,
     * so it takes one more and then nothing (#3324).
     */
    @Test
    fun formatTwosGoldenCarriesACeiling() {
        val snapshot = decoded(GOLDEN_FORMAT_2_RICH)
        val acme = assertIs<Topology.Group>(snapshot.lastApplied.children.first { it.name == "acme" })
        val tenants = assertIs<Topology.Group>(snapshot.lastApplied.children.first { it.name == "tenants" })
        val interactive = richBefore.groupId("acme", "interactive")
        val restored = LocalHeddle.restore(snapshot, richPolicy, epoch = snapshot.epoch + 1)
        val second = restored.acquire(setOf(interactive), setOf(interactive), startedAt = 0L)
        val third = restored.acquire(setOf(interactive), setOf(interactive), startedAt = 0L)
        assertAll(
            { assertTrue("\"maxConcurrent\":3" in GOLDEN_FORMAT_2_RICH, "the ceiling is in the bytes") },
            { assertEquals(listOf(3L, 2L, 2L), listOf(acme.maxConcurrent, acme.children.first { it.name == "interactive" }.maxConcurrent, tenants.runtimeChildren?.maxConcurrent)) },
            { assertEquals(1, snapshot.leases.count { it.leaf == interactive }, "the rig: one lease open at interactive") },
            { assertIs<AcquireOutcome.Acquired>(second) },
            { assertEquals(AcquireOutcome.NothingEligible, third, "interactive is at its ceiling") },
        )
    }

    /** Format 1 predates the fold, so a format-1 snapshot that carries one is corrupt. */
    @Test
    fun aFormatOneSnapshotCannotCarryAFold() {
        val text = GOLDEN_FORMAT_2_RICH.replaceFirst("[2,", "[1,")
        val out = LocalHeddle.decode(text, Json)
        assertAll(
            { assertTrue(text.startsWith("[1,") && "\"forgotten\":" in text, "the rig: format 1 with a fold") },
            { assertTrue(out is RestoreResult.Corrupt && "format 1 predates" in out.reason, "expected Corrupt naming format 1, got $out") },
        )
    }

    /** An honest fold is never negative, so a snapshot whose fold is, in either of its two maps, is corrupt. */
    @Test
    fun aNegativeFoldIsCorrupt() {
        val outflow = "\"outflow\":{\"root/tenants\":{\"local\":3}}"
        val leafSpend = "\"leafSpend\":{\"local\":3}"
        val tampered = listOf(
            "outflow" to GOLDEN_FORMAT_2_RICH.replace(outflow, outflow.replace(":3", ":-3")),
            "leaf spend" to GOLDEN_FORMAT_2_RICH.replace(leafSpend, leafSpend.replace(":3", ":-3")),
        )
        assertAll(
            { assertIs<RestoreResult.Restored>(LocalHeddle.decode(GOLDEN_FORMAT_2_RICH, Json), "the control: the untampered fold restores") },
            *tampered.map { (name, text) ->
                val check: () -> Unit = {
                    assertTrue(text != GOLDEN_FORMAT_2_RICH && ":-3" in text, "the rig: $name tampered")
                    val out = LocalHeddle.decode(text, Json)
                    assertTrue(out is RestoreResult.Corrupt && "never negative" in out.reason, "$name: expected Corrupt naming the negative fold, got $out")
                }
                check
            }.toTypedArray(),
        )
    }

    /**
     * The rich scenario as format 1, on the 1/16-unit grid (#3313): never written by a release, but
     * the same snapshot the current format writes, and an engine restored from it carries on exactly
     * as the engine that wrote it would have.
     */
    @Test
    fun richFormatOneOnTheGridStillRestoresAndCarriesOn() {
        val (reference, open) = richScenario()
        val snapshot = decoded(GOLDEN_FORMAT_1_RICH_GRID)
        val restored = LocalHeddle.restore(snapshot, richPolicy, epoch = snapshot.epoch + 1)
        assertAll(
            { assertTrue(GOLDEN_FORMAT_1_RICH_GRID.startsWith("[1,"), "the rig: format 1") },
            { assertEquals(reference.persist(), snapshot, "the same snapshot the current format writes") },
            { assertEquals(richContinuation(reference, open), richContinuation(restored, open)) },
        )
    }

    /**
     * The rich snapshot **v0.7.4 wrote**, byte for byte, still decodes and restores, and the engine
     * carries on from it through the same drain and reload. Its seat and one wake offset sit off the
     * 1/16-unit grid (#3313); this engine reads each up to the grid once, so the continuation is not
     * byte-identical to one from [GOLDEN_FORMAT_1_RICH_GRID], but every call in it completes.
     */
    @Test
    fun richFormatOneThatV074WroteStillRestoresAndCarriesOn() {
        val (reference, open) = richScenario()
        val shipped = decoded(GOLDEN_FORMAT_1_RICH)
        val offGrid = shipped.ledger.gauge(AttachmentId("root/acme@5:2#2"))?.floor
        val restored = LocalHeddle.restore(shipped, richPolicy, epoch = shipped.epoch + 1)
        val carriedOn = richContinuation(restored, open)
        assertAll(
            // The rig: the shipped bytes really are the pre-grid values.
            { assertEquals(Rational.of(116L, 15L), offGrid) },
            { assertEquals(richContinuation(reference, open).size, carriedOn.size, "every call of the continuation ran") },
        )
    }

    /**
     * This release writes format 2 and reads formats 1 and 2 (#3192). Format 3 is a later
     * release's, refused by name with nothing past the version read.
     */
    @Test
    fun unsupportedVersionIsNamed() {
        val later = GOLDEN_FORMAT_1.replaceFirst("[1,", "[3,")
        val supported = listOf(1, 2)
        assertAll(
            { assertTrue(later != GOLDEN_FORMAT_1, "the rig: the version was rewritten") },
            { assertEquals(RestoreResult.UnsupportedVersion(3, supported), LocalHeddle.decode(later, Json)) },
            { assertEquals(RestoreResult.UnsupportedVersion(0, supported), LocalHeddle.decode("""[0,{}]""", Json)) },
            {
                assertEquals(
                    RestoreResult.UnsupportedVersion(9, supported),
                    LocalHeddle.decode("""[9,{"a shape":"this release has never seen"}]""", Json),
                    "nothing past the version is read",
                )
            },
            { assertEquals(RestoreResult.UnsupportedVersion(3, supported), LocalHeddle.decode("""[3]""", Json), "with no state at all") },
            { assertEquals(supported, LocalHeddleSnapshot.SUPPORTED_FORMAT_VERSIONS) },
            { assertEquals(2, LocalHeddleSnapshot.FORMAT_VERSION) },
        )
    }

    @Test
    fun corruptBytesAreNamedNotThrown() {
        assertAll(*(corruptionChecks(GOLDEN_FORMAT_1, version = 1) + corruptionChecks(GOLDEN_FORMAT_2, version = 2)).toTypedArray())
    }

    /** One check per way [golden], a snapshot of format [version], can be corrupted. */
    private fun corruptionChecks(golden: String, version: Int): List<() -> Unit> {
        val prefix = "[$version,"
        val pathToA = "\"path\":[\"${edgeTo(a).value}\"]"
        val cases = listOf(
            "not json" to "x",
            "truncated" to golden.substring(0, golden.length / 2),
            "no version" to "[]",
            "no state" to "[$version]",
            "the old object envelope" to "{\"formatVersion\":$version,\"state\":" + golden.removePrefix(prefix).dropLast(1) + "}",
            "state before version" to "[" + golden.removePrefix(prefix).dropLast(1) + ",$version]",
            // A well-formed second state, so only the "past its state" refusal can catch it.
            "an element past the state" to golden.dropLast(1) + "," + golden.removePrefix(prefix).dropLast(1) + "]",
            "a lease on an edge the ledger lacks" to golden.replace(pathToA, "\"path\":[\"root/z@1:1#0\"]"),
            "a lease from a later epoch" to golden.replaceFirst("\"leases\":[{\"epoch\":0,", "\"leases\":[{\"epoch\":5,"),
            "a topology rooted elsewhere" to golden.replace("\"lastApplied\":{\"root\":\"root\"", "\"lastApplied\":{\"root\":\"elsewhere\""),
            "an invalid topology" to golden.replace("\"lastApplied\":{\"root\":\"root\"", "\"lastApplied\":{\"root\":\"\""),
        )
        // One per `require` a decoded snapshot runs, each pinned to its own reason.
        val requires = listOf(
            Triple("a negative epoch", golden.replaceFirst("$prefix{\"epoch\":0,", "$prefix{\"epoch\":-1,"), "epoch must be non-negative"),
            Triple("a negative sequence number", golden.replaceFirst("\"seq\":50,", "\"seq\":-50,"), "negative sequence number"),
            Triple("ids not strictly increasing", golden.replaceFirst("\"seq\":51,", "\"seq\":50,"), "strictly increasing id order"),
            Triple("a reservation of 0", golden.replaceFirst("\"reserved\":1,", "\"reserved\":0,"), "not a positive amount"),
            Triple("an empty path", golden.replace(pathToA, "\"path\":[]"), "empty path"),
            Triple("a broken path", golden.replace(pathToA, "\"path\":[\"${edgeTo(b).value}\",\"${edgeTo(a).value}\"]"), "path is broken"),
            Triple("a path across a retired edge", golden.replaceFirst("\"lifecycle\":{\"${edgeTo(a).value}\":\"ACTIVE\"", "\"lifecycle\":{\"${edgeTo(a).value}\":\"RETIRED\""), "which is retired"),
            Triple("negative capacity", golden.replace("\"totalSlots\":2,", "\"totalSlots\":-2,"), "totalSlots must be non-negative"),
        )
        return buildList {
            add { assertIs<RestoreResult.Restored>(LocalHeddle.decode(golden, Json), "the rig: format $version's golden decodes") }
            for ((name, text) in cases) {
                add { assertTrue(text != golden, "the rig: format $version, $name changed the text") }
                add { assertIs<RestoreResult.Corrupt>(LocalHeddle.decode(text, Json), "format $version, $name") }
            }
            for ((name, text, reason) in requires) {
                add { assertTrue(text != golden, "the rig: format $version, $name changed the text") }
                add {
                    val out = LocalHeddle.decode(text, Json)
                    assertTrue(out is RestoreResult.Corrupt && reason in out.reason, "format $version, $name: expected Corrupt naming \"$reason\", got $out")
                }
            }
        }
    }

    @Test
    fun restoreRejectsANonIncreasingEpoch() {
        val snapshot = wakerScenario(epoch = 4L).first.persist()
        assertAll(
            { assertFailsWith<IllegalArgumentException> { LocalHeddle.restore(snapshot, policy, epoch = 4L) } },
            { assertFailsWith<IllegalArgumentException> { LocalHeddle.restore(snapshot, policy, epoch = 3L) } },
            { assertEquals(5L, LocalHeddle.restore(snapshot, policy, epoch = 5L).epoch) },
        )
    }

    /**
     * One snapshot restored twice, under two later epochs, while the run that wrote it carries on:
     * no id is issued twice across the three, and none repeats an id the snapshot holds.
     */
    @Test
    fun doubleRestoreCannotReissueAnId() {
        val (original, open) = wakerScenario()
        val snapshot = original.persist()
        val first = LocalHeddle.restore(snapshot, policy, epoch = 1L)
        val second = LocalHeddle.restore(snapshot, policy, epoch = 2L)
        val issued = open.map { it.id } + listOf(original, first, second).flatMap { h -> (0 until 5).map { h.lease(setOf(a, b)).id } }
        assertAll(
            { assertEquals(17, issued.size, "the rig: 2 persisted and 15 new ids") },
            { assertEquals(issued.size, issued.toSet().size, "every id is distinct: $issued") },
        )
    }

    /**
     * Review Focus 3. After a reset (a fresh engine under a new epoch) an old id is refused by name
     * and charges nothing. After a restore, an old id the snapshot still holds is valid, and one it
     * no longer holds is [LeaseOutcome.Stale], never charged to anyone.
     */
    @Test
    fun staleLeaseAfterResetIsNamed() {
        val (h, open) = wakerScenario()
        val settled = h.lease(setOf(a, b))
        h.complete(settled.id, 1L)
        val reset = LocalHeddle.fromTopology(pair, policy, epoch = 1L)
        val resetBefore = reset.snapshot().ledger
        val restored = throughJson(h)
        val restoredBefore = restored.snapshot().ledger
        assertAll(
            { assertEquals(LeaseOutcome.Stale(1L), reset.complete(open[0].id, 1L)) },
            { assertEquals(LeaseOutcome.Stale(1L), reset.extend(open[1].id, 1L)) },
            { assertEquals(LeaseOutcome.Stale(1L), reset.cancel(open[1].id)) },
            { assertSame(resetBefore, reset.snapshot().ledger, "the reset engine charged nothing") },
            { assertEquals(LeaseOutcome.Stale(1L), restored.complete(settled.id, 1L), "a lease settled before the persist") },
            { assertSame(restoredBefore, restored.snapshot().ledger, "and that charged nothing either") },
            { assertEquals(LeaseOutcome.Done(1L, 0L), restored.complete(open[0].id, 1L), "a lease the snapshot holds is still valid") },
        )
    }

    /**
     * A restored engine is sized as the persisted one was: with no supply left at the root, its first
     * acquire tops up on its own rather than being refused, with no [LocalHeddle.reconcileCapacity]
     * call after the restore.
     */
    @Test
    fun capacitySurvivesRestore() {
        // Sized for one slot, and nothing minted yet: the next acquire has to top up.
        val h = LocalHeddle.fromTopology(Topology("root", listOf(Topology.Lane("a"))), policy, epoch = 0L)
        h.reconcileCapacity(totalSlots = 1L)
        val restored = throughJson(h)
        val uncapacitated = LocalHeddle.restore(
            h.persist().let { LocalHeddleSnapshot(it.epoch, it.ledger, it.allocatorMemory, totalSlots = 0L, it.open, it.lastApplied) },
            policy,
            epoch = 1L,
        )
        assertAll(
            { assertEquals(1L, h.persist().totalSlots, "the rig: the capacity is in the snapshot") },
            { assertEquals(0L, restored.snapshot().ledger.holdings(root, LOCAL), "the rig: nothing is held at the root") },
            { assertIs<AcquireOutcome.Acquired>(restored.acquire(setOf(a), setOf(a), startedAt = 0L)) },
            { assertEquals(AcquireOutcome.SupplyExhausted, uncapacitated.acquire(setOf(a), setOf(a), startedAt = 0L), "the control: with no capacity it is refused") },
        )
    }

    /**
     * A persist and restore at a random point of a random sequence is invisible: from then on every
     * outcome and the books after every step match an engine that never persisted. The sequence
     * mixes lease operations with reloads, a reweight (which moves open leases), a removal (which
     * drains), a new weight on a runtime marker, and attaching and detaching runtime children, so a
     * restore lands mid-drain and after a reload as well as on a quiet tree. Leases are addressed by
     * their place in the open list, which is id order in both: the restored engine's own ids come
     * after every restored one, as later sequence numbers do in the reference.
     */
    @Test
    fun aRestoreAnywhereIsInvisible() {
        fun tree(pWeight: Long = 2L, markerWeight: Long = 1L, withHash3: Boolean = true) = Topology(
            "root",
            listOf(
                Topology.Group("p", Weight.of(pWeight), listOfNotNull(Topology.Lane("a/b", Weight.of(3)), Topology.Lane("#3").takeIf { withHash3 })),
                Topology.Group("q", Weight.ONE, listOf(Topology.Lane("c-7f3a"), Topology.Lane("é#%@", Weight.of(2)))),
                Topology.Group("r", Weight.ONE, emptyList(), runtimeChildren = Topology.RuntimeChildren(Weight.of(markerWeight))),
            ),
        )
        val base = tree()
        val drafts = listOf(base, tree(pWeight = 3L), tree(withHash3 = false), tree(markerWeight = 2L))
        val runtime = listOf("x", "y")
        val leaves = listOf(base.groupId("p", "a/b"), base.groupId("p", "#3"), base.groupId("q", "c-7f3a"), base.groupId("q", "é#%@")) +
            runtime.map { base.runtimeChildId(listOf("r"), it) }
        val wide = LocalPolicy(defaultReservation = 3L)
        var restores = 0
        var withLeasesOpen = 0
        var withAWakeOffset = 0
        var midDrain = 0
        var afterAReload = 0
        var withARuntimeLease = 0
        var drains = 0
        var compared = 0
        for (seed in 0 until SEEDS) {
            val rng = Random(seed)
            val reference = LocalHeddle.fromTopology(base, wide, epoch = 0L).also { it.reconcileCapacity(2L) }
            var subject = LocalHeddle.fromTopology(base, wide, epoch = 0L).also { it.reconcileCapacity(2L) }
            val cut = rng.nextInt(STEPS)
            repeat(STEPS) { step ->
                if (step == cut) {
                    val persisted = subject.persist()
                    if (persisted.leases.isNotEmpty()) withLeasesOpen++
                    if (persisted.allocatorMemory.wakeOffsets.values.any { it != Rational.ZERO }) withAWakeOffset++
                    if (persisted.ledger.allEdges().any { persisted.ledger.lifecycle(it) == Lifecycle.CLOSING }) midDrain++
                    if (persisted.lastApplied != base) afterAReload++
                    if (persisted.leases.any { it.leaf.value.startsWith("root/r/") }) withARuntimeLease++
                    subject = throughJson(subject, wide)
                    restores++
                }
                val open = reference.snapshot().leases
                val kind = rng.nextInt(8)
                val (expected, actual) = when {
                    kind == 0 || (kind in 1..3 && open.isEmpty()) -> {
                        val waiting = leaves.filter { rng.nextInt(3) != 0 }.toSet()
                        val eligible = waiting.filterTo(HashSet()) { rng.nextBoolean() }
                        val r = 1L + rng.nextInt(5)
                        val s = step.toLong()
                        shape(reference.acquire(waiting, eligible, s, r)) to shape(subject.acquire(waiting, eligible, s, r))
                    }
                    kind in 1..3 -> {
                        val i = rng.nextInt(open.size)
                        val mine = subject.snapshot().leases[i].id
                        val theirs = open[i].id
                        val amount = rng.nextInt(9).toLong()
                        when (kind) {
                            1 -> reference.extend(theirs, amount + 1) to subject.extend(mine, amount + 1)
                            2 -> reference.complete(theirs, amount) to subject.complete(mine, amount)
                            else -> reference.cancel(theirs) to subject.cancel(mine)
                        }
                    }
                    kind == 4 -> {
                        val leaf = leaves[rng.nextInt(leaves.size)]
                        shape(reference.admit(leaf, step.toLong())) to shape(subject.admit(leaf, step.toLong()))
                    }
                    kind == 5 -> {
                        val draft = drafts[rng.nextInt(drafts.size)]
                        val out = shape(reference.apply(draft, step.toLong()))
                        if (reference.snapshot().ledger.allEdges().any { reference.snapshot().ledger.lifecycle(it) == Lifecycle.CLOSING }) drains++
                        out to shape(subject.apply(draft, step.toLong()))
                    }
                    kind == 6 -> {
                        val name = runtime[rng.nextInt(runtime.size)]
                        shape(reference.attachRuntimeChild(listOf("r"), name, step.toLong())) to
                            shape(subject.attachRuntimeChild(listOf("r"), name, step.toLong()))
                    }
                    else -> {
                        val name = runtime[rng.nextInt(runtime.size)]
                        shape(reference.detachRuntimeChild(listOf("r"), name)) to shape(subject.detachRuntimeChild(listOf("r"), name))
                    }
                }
                if (expected != actual) fail("seed $seed step $step (restored at $cut): $expected, but $actual")
                if (books(reference).dropLast(2) != books(subject).dropLast(2)) fail("seed $seed step $step: the books diverged")
                if (reference.snapshot().allocatorMemory != subject.snapshot().allocatorMemory) fail("seed $seed step $step: the wake memory diverged")
                compared++
            }
        }
        assertAll(
            { assertEquals(SEEDS, restores, "every seed restored once") },
            { assertEquals(SEEDS * STEPS, compared, "every step was compared") },
            { assertTrue(withLeasesOpen > SEEDS / 2, "the rig: most restores carried open leases, $withLeasesOpen") },
            { assertTrue(withAWakeOffset > 0, "the rig: some restore carried a wake offset, $withAWakeOffset") },
            { assertTrue(drains > 0, "the rig: some reload left an edge draining, $drains") },
            { assertTrue(midDrain > 0, "the rig: some restore landed mid-drain, $midDrain") },
            { assertTrue(afterAReload > 0, "the rig: some restore came after a reload, $afterAReload") },
            { assertTrue(withARuntimeLease > 0, "the rig: some restore carried a lease at a runtime child, $withARuntimeLease") },
        )
    }

    /**
     * An outcome with every lease id left out, since a restored engine names its new leases under its
     * own epoch: a lease is its leaf, reservation and start time.
     */
    private fun shape(out: Any): Any = when (out) {
        is AcquireOutcome.Acquired -> shapeOf(out.lease)
        is ApplyResult -> out.applied to out.refusals.map { refusal ->
            when (refusal) {
                is StepRefusal.Draining -> listOf(refusal.child, refusal.edge, refusal.outstanding, refusal.blockingLeases.map(::shapeOf))
                is StepRefusal.Refused -> listOf(refusal.child, refusal.step)
            }
        }
        else -> out
    }

    private fun shapeOf(lease: Lease): Any = Triple(lease.leaf, lease.reserved, lease.startedAt)

    /** A binary format carrying JSON as UTF-8: enough to drive the `ByteArray` overload with what `commonTest` has. */
    private object JsonBytes : BinaryFormat {
        override val serializersModule: SerializersModule = Json.serializersModule

        override fun <T> encodeToByteArray(serializer: SerializationStrategy<T>, value: T): ByteArray =
            Json.encodeToString(serializer, value).encodeToByteArray()

        override fun <T> decodeFromByteArray(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T =
            Json.decodeFromString(deserializer, bytes.decodeToString())
    }

    private companion object {
        const val SEEDS = 60
        const val STEPS = 40
    }
}
