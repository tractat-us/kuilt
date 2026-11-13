package us.tractat.kuilt.heddle

import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
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
 * Amendments 7–10). The golden snapshot of format 1 is [GOLDEN_FORMAT_1].
 */
class LocalHeddlePersistenceTest {

    private val root = GroupId("root")
    private val a = GroupId("a")
    private val b = GroupId("b")
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
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, a), edgeOf(root, b))), root, supply = 0L, policy = policy, epoch = epoch)
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
        memory.wakeOffsets[AttachmentId("root>${leaf.value}")] ?: Rational.ZERO

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
            LocalHeddleSnapshot(persisted.root, persisted.epoch, persisted.ledger, AllocatorMemory.EMPTY, persisted.totalSlots, persisted.open),
            policy,
            epoch = 1L,
        )
        val expected = continuation(reference, open)
        val actual = continuation(restored, open)
        val control = continuation(forgetful, open)
        val bPicks = { picks: List<Any> -> picks.count { it == b } }
        assertAll(
            { assertTrue(offset > Rational.ZERO, "the rig: b's wake offset is non-zero at persist time, $offset") },
            { assertEquals(true, persisted.allocatorMemory.demandingObserved[AttachmentId("root>b")], "and b was last seen waiting") },
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
     * Format 1 is frozen: the scenario still encodes to the golden, byte for byte. A change to any
     * serializer inside a snapshot, the ledger's and the allocator memory's included, reds here, and
     * then the choice is deliberate: a new format with its own surrogate, or, before the format has
     * shipped, a regenerated golden. The message carries the current encoding for that case.
     */
    @Test
    fun formatOneStillEncodesToTheGolden() {
        val text = encode(wakerScenario().first.persist())
        assertEquals(GOLDEN_FORMAT_1, text, "format 1 changed; the encoding is now:\n$text\n")
    }

    @Test
    fun unsupportedVersionIsNamed() {
        val later = GOLDEN_FORMAT_1.replaceFirst("{\"formatVersion\":1,", "{\"formatVersion\":2,")
        assertAll(
            { assertTrue(later != GOLDEN_FORMAT_1, "the rig: the version was rewritten") },
            { assertEquals(RestoreResult.UnsupportedVersion(2, listOf(1)), LocalHeddle.decode(later, Json)) },
            { assertEquals(RestoreResult.UnsupportedVersion(0, listOf(1)), LocalHeddle.decode("""{"formatVersion":0,"state":{}}""", Json)) },
            {
                assertEquals(
                    RestoreResult.UnsupportedVersion(9, listOf(1)),
                    LocalHeddle.decode("""{"formatVersion":9,"state":{"a shape":"this release has never seen"}}""", Json),
                    "nothing past the version is read",
                )
            },
            { assertEquals(listOf(1), LocalHeddleSnapshot.SUPPORTED_FORMAT_VERSIONS) },
            { assertEquals(1, LocalHeddleSnapshot.FORMAT_VERSION) },
        )
    }

    @Test
    fun corruptBytesAreNamedNotThrown() {
        val golden = GOLDEN_FORMAT_1
        val cases = listOf(
            "not json" to "x",
            "truncated" to golden.substring(0, golden.length / 2),
            "no version" to """{"state":{}}""",
            "state before version" to golden.replaceFirst("{\"formatVersion\":1,", "{").dropLast(1) + ",\"formatVersion\":1}",
            "a lease on an edge the ledger lacks" to golden.replace("\"path\":[\"root>a\"]", "\"path\":[\"root>z\"]"),
            "a lease whose path ends elsewhere" to golden.replace("\"path\":[\"root>a\"]", "\"path\":[\"root>b\"]"),
            "a lease from a later epoch" to golden.replaceFirst("\"leases\":[{\"epoch\":0,", "\"leases\":[{\"epoch\":5,"),
            "negative capacity" to golden.replace("\"totalSlots\":2,", "\"totalSlots\":-2,"),
        )
        val checks: List<() -> Unit> =
            cases.map { (name, text) -> { assertTrue(text != golden, "the rig: $name changed the text") } } +
                cases.map { (name, text) -> { assertIs<RestoreResult.Corrupt>(LocalHeddle.decode(text, Json), name) } }
        assertAll(*checks.toTypedArray())
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
        val reset = LocalHeddle.over(h.snapshot().ledger, root, policy, epoch = 1L, Leasable.EveryGroup)
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
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, a))), root, supply = 0L, policy = policy)
        h.reconcileCapacity(totalSlots = 1L)
        // Lease out all the root holds, so the next acquire has to mint.
        val held = mutableListOf<Lease>()
        while (h.snapshot().ledger.holdings(root, LOCAL) > 0L) held += h.lease(setOf(a))
        val restored = throughJson(h)
        val uncapacitated = LocalHeddle.restore(
            h.persist().let { LocalHeddleSnapshot(it.root, it.epoch, it.ledger, it.allocatorMemory, totalSlots = 0L, it.open) },
            policy,
            epoch = 1L,
        )
        assertAll(
            { assertTrue(held.isNotEmpty(), "the rig: the scenario leased the root dry") },
            { assertEquals(0L, restored.snapshot().ledger.holdings(root, LOCAL), "the rig: nothing is left at the root") },
            { assertIs<AcquireOutcome.Acquired>(restored.acquire(setOf(a), setOf(a), startedAt = 0L)) },
            { assertEquals(AcquireOutcome.SupplyExhausted, uncapacitated.acquire(setOf(a), setOf(a), startedAt = 0L), "the control: with no capacity it is refused") },
        )
    }

    /**
     * A persist and restore at a random point of a random lease sequence is invisible: from then on
     * every outcome and the books after every step match an engine that never persisted. Leases are
     * addressed by their place in the open list, which is id order in both: the restored engine's
     * own ids come after every restored one, as later sequence numbers do in the reference.
     */
    @Test
    fun aRestoreAnywhereIsInvisible() {
        val p = GroupId("p")
        val q = GroupId("q")
        val leaves = listOf(GroupId("a/b"), GroupId("#3"), GroupId("c-7f3a"), GroupId("é#%@"))
        val ledger = shapedLedger(
            root,
            listOf(
                edgeOf(root, p, 2), edgeOf(root, q, 1),
                edgeOf(p, leaves[0], 3), edgeOf(p, leaves[1], 1),
                edgeOf(q, leaves[2], 1), edgeOf(q, leaves[3], 2),
            ),
        )
        val wide = LocalPolicy(defaultReservation = 3L)
        var restores = 0
        var withLeasesOpen = 0
        var withAWakeOffset = 0
        var compared = 0
        for (seed in 0 until SEEDS) {
            val rng = Random(seed)
            val reference = engineOver(ledger, root, supply = 0L, policy = wide).also { it.reconcileCapacity(2L) }
            var subject = engineOver(ledger, root, supply = 0L, policy = wide).also { it.reconcileCapacity(2L) }
            val cut = rng.nextInt(STEPS)
            repeat(STEPS) { step ->
                if (step == cut) {
                    val persisted = subject.persist()
                    if (persisted.leases.isNotEmpty()) withLeasesOpen++
                    if (persisted.allocatorMemory.wakeOffsets.values.any { it != Rational.ZERO }) withAWakeOffset++
                    subject = throughJson(subject, wide)
                    restores++
                }
                val open = reference.snapshot().leases
                val kind = rng.nextInt(5)
                val (expected, actual) = when {
                    kind == 0 || open.isEmpty() -> {
                        val waiting = leaves.filter { rng.nextInt(3) != 0 }.toSet()
                        val eligible = waiting.filterTo(HashSet()) { rng.nextBoolean() }
                        val r = 1L + rng.nextInt(5)
                        val s = step.toLong()
                        shape(reference.acquire(waiting, eligible, s, r)) to shape(subject.acquire(waiting, eligible, s, r))
                    }
                    else -> {
                        val i = rng.nextInt(open.size)
                        val mine = subject.snapshot().leases[i].id
                        val theirs = open[i].id
                        val amount = rng.nextInt(9).toLong()
                        when (kind) {
                            1 -> reference.extend(theirs, amount + 1) to subject.extend(mine, amount + 1)
                            2 -> reference.complete(theirs, amount) to subject.complete(mine, amount)
                            3 -> reference.cancel(theirs) to subject.cancel(mine)
                            else -> {
                                val leaf = leaves[rng.nextInt(leaves.size)]
                                shape(reference.admit(leaf, step.toLong())) to shape(subject.admit(leaf, step.toLong()))
                            }
                        }
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
        )
    }

    /** An outcome with the lease id left out, since a restored engine names its new leases under its own epoch. */
    private fun shape(out: Any): Any = when (out) {
        is AcquireOutcome.Acquired -> Triple(out.lease.leaf, out.lease.reserved, out.lease.startedAt)
        else -> out
    }

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
