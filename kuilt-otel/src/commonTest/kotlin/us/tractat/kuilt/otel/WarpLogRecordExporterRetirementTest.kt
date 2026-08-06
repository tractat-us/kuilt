package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the **retirement of superseded segments** in [WarpLogRecordExporter] (#2127).
 *
 * Windowing takes a superseded record's ops out of the in-memory log but leaves them in whichever
 * *sealed* segment they landed in, so the store kept gaining roughly one key per `segmentOps`
 * operations for as long as the device ran. Retirement deletes a sealed segment once the
 * suppression state covers every op it holds.
 *
 * **This is the code path that deletes a user's telemetry**, so most of what is pinned here is
 * refusal rather than reclamation: a segment whose content could not be read is never retired
 * ([aSegmentWhoseContentCouldNotBeReadIsNeverRetired]), a segment carrying an `RgaOp.Compact` is
 * never retired ([aMergedSegmentIsRetiredOnlyWhenItCarriesNoCompact]), and the delete is ordered
 * behind both the covering write and the ledger write that names it
 * ([theLedgerIsCommittedAfterTheCoveringWriteAndBeforeTheKeyIsDeleted]).
 *
 * **These two are the guards for "a segment carrying a `Compact` is never retired", and the
 * legacy-blob test next door is not.** `WarpLogRecordExporterSegmentTest`'s
 * `aCompactionInheritedFromTheLegacyBlobIsNeverDropped` compacts a record **this** replica
 * authored, so once windowing walks past it the per-author floor suppresses it whether or not the
 * `Compact` survives — it pins *"the record does not come back"*, never *"the `Compact` survived"*.
 * [aMergedSegmentIsRetiredOnlyWhenItCarriesNoCompact] uses a foreign author with a retired-anyway
 * control arm, and [droppingTheSegmentCarryingACompactReAdmitsAForeignAuthorsRecord] supplies the
 * counterfactual. Do not cite the legacy test for this clause.
 *
 * The reclamation side is measured in the two units a device runs out of: keys a recovery has to
 * open ([recoverOpensABoundedNumberOfKeysNoMatterHowManyRecordsWereEverExported]) and bytes left
 * resident ([bytesOnDiskAreBoundedByTheWindowNotByRecordsEverExported]).
 */
class WarpLogRecordExporterRetirementTest {

    private val replicaA = ReplicaId("A")
    private val replicaB = ReplicaId("B")

    private fun recordId(id: Int): ByteString =
        ByteString(ByteArray(8) { i -> (id shr (8 * i)).toByte() })

    private fun record(id: Int, body: String = "body-$id") = LogRecord(
        recordId = recordId(id),
        body = body,
        observedEpochNanos = 1_700_000_000_000_000_000L,
    )

    private fun exporterFor(
        store: DurableStore,
        replica: ReplicaId = replicaA,
        maxRecords: Int = 10,
        bufferPolicy: BufferPolicy = BufferPolicy.DROP_OLDEST,
        segmentOps: Int = 8,
    ) = WarpLogRecordExporter(
        replica = replica,
        store = store,
        maxRecords = maxRecords,
        bufferPolicy = bufferPolicy,
        segmentOps = segmentOps,
    )

    /**
     * A **fixed-width** record. The byte-accounting tests below compare a store's resident total
     * early and late in one run, and a body that gains a character when the id gains a digit
     * would read as growth-with-N that has nothing to do with what is under test.
     */
    private fun sizedRecord(id: Int) =
        record(id, body = "log record body number ${id.toString().padStart(6, '0')}")

    private fun segmentKeys(store: RecordingStore): Set<String> =
        store.keys().filter { it.startsWith("otel.logs.seg.") }.toSet()

    // ---- The headline claim: recovery opens a bounded number of keys ----

    @Test
    fun recoverOpensABoundedNumberOfKeysNoMatterHowManyRecordsWereEverExported() = runTest {
        val store = RecordingStore()
        val exporter = exporterFor(store)
        repeat(1_000) { i -> exporter.export(record(i)) }
        val live = exporter.snapshot().toList()

        store.resetReadCount()
        val recovered = exporterFor(store)
        recovered.recover()

        assertAll(
            {
                // 1000 records at 8 ops a segment is >250 segments if nothing is ever retired,
                // and every one of them is a key the next start has to open. The ceiling is
                // deliberately loose — the SHAPE (flat, not Θ(records)) is what is pinned.
                assertTrue(
                    store.reads() <= 12,
                    "recover() must not scale with records ever exported; opened ${store.reads()} keys",
                )
            },
            // Without this the assertion above passes trivially if retirement deleted the log.
            { assertEquals(live, recovered.snapshot().toList(), "retirement cost live records") },
            { assertEquals(10, recovered.snapshot().size, "the window itself must be intact") },
        )
    }

    @Test
    fun theStoreStopsGainingKeysOnceTheWindowIsFull() = runTest {
        // The complement of the test above, measured on the WRITE side rather than at recovery:
        // the resident key count has to stop climbing, not merely climb more slowly.
        val store = RecordingStore()
        val exporter = exporterFor(store)
        repeat(100) { i -> exporter.export(record(i)) }
        val early = segmentKeys(store).size
        repeat(900) { i -> exporter.export(record(100 + i)) }
        val late = segmentKeys(store).size

        assertTrue(
            late <= early,
            "the store gained keys across 900 further records through a 10-record window: $early -> $late",
        )
    }

    @Test
    fun bytesOnDiskAreBoundedByTheWindowNotByRecordsEverExported() = runTest {
        // The key count and the byte total are different claims, and only one of them is what a
        // phone runs out of. A layout that kept ONE key and let it grow forever satisfies every
        // key-counting test in this file, so the total is measured directly here.
        //
        // The control arm is the same 1000 records through a cap none of them reach: no eviction,
        // so no window pass, so no retirement — which is precisely the Θ(records ever) layout that
        // shipped before this change. Without it "the total stopped growing" could be read off a
        // store that never held much in the first place.
        // The total is **not** flat to the byte, and asserting that it is would be a false
        // claim that reddens on an unrelated change. It creeps by a few dozen bytes as the
        // Lamport counters and seqs inside the retained ops gain a CBOR integer width — and
        // that creep SATURATES. So what is asserted is the shape: the growth over the second,
        // ten-times-longer stretch must not exceed the growth over the first. Θ(records ever)
        // would make it ten times larger; measured, it is an order of magnitude smaller.
        val unbounded = RecordingStore()
        exporterFor(unbounded, maxRecords = 10_000)
            .also { e -> repeat(1_000) { i -> e.export(sizedRecord(i)) } }

        val bounded = RecordingStore()
        val exporter = exporterFor(bounded, maxRecords = 10)
        repeat(100) { i -> exporter.export(sizedRecord(i)) }
        val early = bounded.residentBytes()
        repeat(900) { i -> exporter.export(sizedRecord(100 + i)) }
        val late = bounded.residentBytes()
        repeat(9_000) { i -> exporter.export(sizedRecord(1_000 + i)) }
        val later = bounded.residentBytes()

        assertAll(
            {
                assertTrue(
                    later - late <= late - early,
                    "resident bytes are still growing with records ever exported: $early -> $late " +
                        "over 900 records, then $late -> $later over 9,000",
                )
            },
            {
                assertTrue(
                    later * 5 < unbounded.residentBytes(),
                    "the windowed run is not materially smaller than the Θ(records ever) control: " +
                        "$later vs ${unbounded.residentBytes()} bytes",
                )
            },
            // Without this the two assertions above pass trivially on a store that lost the log.
            { assertEquals(10, exporter.snapshot().size, "and the window itself must be intact") },
        )
    }

    @Test
    fun bothBufferPoliciesBoundTheTotalNotJustThePerExportWrite() = runTest {
        // `WarpLogRecordExporterSegmentTest.bothBufferPoliciesBoundThePerExportWrite` bounds what
        // ONE export writes; this bounds what the store *holds*. Both policies owe it, and they
        // discharge it for opposite reasons — which is why the per-policy arm below is asserted
        // rather than left to read as an incidentally flat number.
        //
        // DROP_NEWEST refuses the arrival, so a saturated buffer appends no op and writes nothing
        // at all: its total is flat because the exporter fell silent. DROP_OLDEST keeps evicting,
        // keeps writing, and its total is flat because each window pass drops the superseded ops
        // and retires the segments holding them. A test that only measured the total could not
        // tell those apart, and would pass on a DROP_OLDEST that had silently stopped exporting.
        //
        // Scoped to local exports, as the design is. The merge path — where DROP_NEWEST's
        // eviction count is permanently zero, so only the size arm of the trigger can fire — is
        // pinned by `aGossipFedReplicaRetiresThroughTheMergePath` and, in memory, by
        // `WarpLogRecordExporterWindowingTest.aFullDropNewestBufferDoesNotGrowUnboundedWhenAPeerMergesIn`.
        for (policy in BufferPolicy.entries) {
            val store = RecordingStore()
            val exporter = exporterFor(store, maxRecords = 10, bufferPolicy = policy)
            repeat(100) { i -> exporter.export(sizedRecord(i)) }
            val early = store.residentBytes()
            repeat(900) { i -> exporter.export(sizedRecord(100 + i)) }
            val late = store.residentBytes()
            store.resetWriteLog()
            repeat(9_000) { i -> exporter.export(sizedRecord(1_000 + i)) }
            val later = store.residentBytes()

            assertAll(
                { assertEquals(10, exporter.snapshot().size, "$policy: the cap must hold") },
                {
                    // Same shape, and for the same reason, as
                    // bytesOnDiskAreBoundedByTheWindowNotByRecordsEverExported: the residual creep
                    // is CBOR integer width, and it saturates.
                    assertTrue(
                        later - late <= late - early,
                        "$policy: resident bytes are still growing with records ever exported: " +
                            "$early -> $late over 900 records, then $late -> $later over 9,000",
                    )
                },
                {
                    when (policy) {
                        BufferPolicy.DROP_NEWEST -> assertEquals(
                            emptyList<Int>(),
                            store.writes(),
                            "$policy: a saturated buffer must write nothing at all",
                        )
                        BufferPolicy.DROP_OLDEST -> assertTrue(
                            store.writes().isNotEmpty(),
                            "$policy: precondition — this policy keeps writing, so the flat total " +
                                "above is reclamation rather than silence",
                        )
                    }
                },
            )
        }
    }

    @Test
    fun aGossipFedReplicaRetiresThroughTheMergePath() = runTest {
        // `merge` has to call retirement itself, and a test set that only drives `export` cannot
        // see that: a retirement wired solely into `pendingWrites` leaves every test above green.
        //
        // DROP_NEWEST is the configuration where the difference is total rather than incidental.
        // It refuses arrivals instead of evicting, so once the buffer is saturated `export`
        // returns before queueing a single action — the export path writes NOTHING, forever. A
        // replica in that state fed only by gossip would accumulate one key per merge for as long
        // as the process lives.
        val store = RecordingStore()
        val a = exporterFor(store, maxRecords = 5, bufferPolicy = BufferPolicy.DROP_NEWEST, segmentOps = 4)
        val peer = exporterFor(RecordingStore(), replica = replicaB, maxRecords = 10_000, segmentOps = 64)
        repeat(20) { i -> a.export(record(i, body = "a$i")) }

        var early = 0
        repeat(40) { round ->
            repeat(5) { i -> peer.export(record(1_000 + round * 5 + i)) }
            a.merge(peer.snapshot())
            if (round == 10) early = segmentKeys(store).size
        }
        val late = segmentKeys(store).size

        assertAll(
            {
                assertTrue(
                    store.operations().any {
                        it.kind == StoreOpKind.DELETE && it.key.name.startsWith("otel.logs.seg.")
                    },
                    "40 merges into a saturated DROP_NEWEST buffer retired nothing; the export path " +
                        "writes nothing at all in that state, so merge has to retire or nobody does",
                )
            },
            { assertTrue(late <= early, "the store gained keys across 30 further merges: $early -> $late") },
            { assertEquals(5, a.snapshot().size, "the window must still be intact") },
        )
    }

    // ---- Unknown content must mean KEEP ----

    @Test
    fun aSegmentWhoseContentCouldNotBeReadIsNeverRetired() = runTest {
        // THE hazard this whole design is shaped around. A segment that could not be read has no
        // recorded content, and a retirability test of the shape `contents[n].orEmpty().all { }`
        // is vacuously TRUE for it — so a transient I/O error would delete the segment outright,
        // turning "one bad read costs one segment's records until the next clean start" into
        // permanent destruction. Absence of evidence must never read as evidence.
        //
        // Two arms over identical inputs, so the assertion cannot pass by the segment simply
        // never having been retirable: the control proves the healthy run DOES retire it.
        val control = runToRetirement(poison = false)
        val poisoned = runToRetirement(poison = true)
        val atStake = poisoned.atStake
        val poisonedIndex = decodeIndexForTest(requireNotNull(poisoned.store.read(INDEX_KEY_FOR_TEST)))

        assertAll(
            { assertEquals(control.atStake, atStake, "the two arms must put the same segment at stake") },
            {
                assertTrue(
                    segmentKeyForTest(atStake) !in segmentKeys(control.store),
                    "precondition: the healthy run must retire segment $atStake",
                )
            },
            {
                assertTrue(
                    segmentKeyForTest(atStake) in segmentKeys(poisoned.store),
                    "a segment whose content could not be read was DELETED; unknown content must mean keep",
                )
            },
            {
                // Keeping the key is only half of it — the index has to keep naming the segment,
                // or it is unreachable and unsweepable in a format with no key enumeration.
                assertTrue(
                    atStake in poisonedIndex.sealedSegments,
                    "the unreadable segment lost its place in the index: $poisonedIndex",
                )
            },
        )
    }

    /** The store of a two-phase run, and the segment whose fate the run decides. */
    private class RetirementRun(val store: RecordingStore, val atStake: Int)

    /**
     * Export, restart, export again — and report the segment at stake: the oldest one still
     * sealed at the restart, which the second phase supersedes and so would retire.
     *
     * With [poison] the *second* phase cannot read that segment, which is exactly the transient
     * I/O error `readSegment` already degrades on. The first phase is identical either way, so
     * both arms put the same number at stake.
     */
    private suspend fun runToRetirement(poison: Boolean): RetirementRun {
        val store = RecordingStore()
        val first = exporterFor(store)
        repeat(60) { i -> first.export(record(i)) }

        val sealed = decodeIndexForTest(requireNotNull(store.read(INDEX_KEY_FOR_TEST))).sealedSegments
        val atStake = requireNotNull(sealed.minOrNull()) { "the first phase left no sealed segment to poison" }
        val reading = if (poison) FailReadOfStore(store, StoreKey(segmentKeyForTest(atStake))) else store
        val second = exporterFor(reading)
        second.recover()
        repeat(300) { i -> second.export(record(1_000 + i)) }
        return RetirementRun(store, atStake)
    }

    // ---- A retained Compact pins its segment ----

    @Test
    fun aMergedSegmentIsRetiredOnlyWhenItCarriesNoCompact() = runTest {
        // `Rga` guarantees "once compacted, always compacted", and a retained `RgaOp.Compact` is
        // the only thing carrying it for the ids it names — nothing prunes one. Delete the
        // segment holding it and a peer that never received the compaction re-admits the purged
        // record on the next merge. `merge` persists the remote log verbatim, so a remote that
        // carries a Compact pins its segment from the moment it lands.
        //
        // Both arms run, because "never retired" is only meaningful next to a segment that IS.
        for (carriesCompact in listOf(false, true)) {
            val store = RecordingStore()
            val a = exporterFor(store, maxRecords = 5, segmentOps = 4)
            a.merge(remoteLog(carriesCompact))
            val adopted = decodeIndexForTest(requireNotNull(store.read(INDEX_KEY_FOR_TEST))).sealedSegments.max()
            repeat(300) { i -> a.export(record(1_000 + i)) }

            assertEquals(
                carriesCompact,
                segmentKeyForTest(adopted) in segmentKeys(store),
                if (carriesCompact) {
                    "a segment carrying an RgaOp.Compact was retired; its suppression is unrecoverable"
                } else {
                    "precondition: a Compact-free merged segment must be retirable once superseded"
                },
            )
        }
    }

    @Test
    fun droppingTheSegmentCarryingACompactReAdmitsAForeignAuthorsRecord() = runTest {
        // [aMergedSegmentIsRetiredOnlyWhenItCarriesNoCompact] pins the DECISION — a segment
        // carrying an `RgaOp.Compact` keeps its key — and says why in a comment. This pins the
        // "why" itself, end to end, and on the path the pin exists for: a **foreign** author's
        // dots. `Rga.dropWindow` cannot fold those into this replica's floor, so the retained
        // `Compact` is the only thing suppressing them. Nothing else in this file reaches that:
        // [aPeerHoldingRetiredRecordsStillCannotPushThemBackIn] resurrects on the own-dot floor
        // path, and the legacy-blob test's Compact names an id THIS replica authored.
        //
        // Two arms over one scenario. The counterfactual deletes the keys that actually carry a
        // Compact — decoded from the store, not guessed — and merges the same foreign log back.
        // Without it "never retired" is a claim about a segment nothing would have missed.
        val kept = foreignResurrectionRun(dropTheCompactCarryingSegments = false)
        val dropped = foreignResurrectionRun(dropTheCompactCarryingSegments = true)

        assertAll(
            {
                assertTrue(
                    kept.compactCarryingSegments > 0,
                    "precondition: windowing a foreign author's dots must have minted a Compact somewhere",
                )
            },
            {
                assertTrue(
                    dropped.foreignBodies.isNotEmpty(),
                    "counterfactual: dropping the Compact must let the peer re-admit its records, or " +
                        "pinning the segment that carries one is guarding nothing",
                )
            },
            {
                assertTrue(
                    kept.foreignBodies.isEmpty(),
                    "a foreign author's windowed-away records came back; got ${kept.foreignBodies}",
                )
            },
        )
    }

    /** What one arm of [droppingTheSegmentCarryingACompactReAdmitsAForeignAuthorsRecord] observed. */
    private class ForeignResurrection(val compactCarryingSegments: Int, val foreignBodies: List<String>)

    /**
     * Merge a foreign log, window it away, restart, and merge the same log back — reporting which
     * of the foreign records came back.
     *
     * With [dropTheCompactCarryingSegments] every key whose decoded segment holds an
     * `RgaOp.Compact` is deleted before the restart, which is precisely what retiring one would
     * have done.
     *
     * The restarted exporter's window is wide enough for both logs on purpose. At the run's
     * 5-record cap the merge would immediately window the foreign records away again in *both*
     * arms, and the assertion would read windowing rather than suppression — green either way.
     */
    private suspend fun foreignResurrectionRun(dropTheCompactCarryingSegments: Boolean): ForeignResurrection {
        val store = RecordingStore()
        val foreign = foreignLog()
        val a = exporterFor(store, maxRecords = 5, segmentOps = 4)
        a.merge(foreign)
        repeat(200) { i -> a.export(record(1_000 + i, body = "mine$i")) }

        var carriers = 0
        for (key in segmentKeys(store)) {
            val bytes = store.read(StoreKey(key)) ?: continue
            if (decodeSegmentForTest(bytes).compactOpCount == 0) continue
            carriers++
            if (dropTheCompactCarryingSegments) store.delete(StoreKey(key))
        }

        val restarted = exporterFor(store, maxRecords = 100, segmentOps = 4)
        restarted.recover()
        restarted.merge(foreign)
        val bodies = restarted.snapshot().toList().mapNotNull { it.body }
        return ForeignResurrection(carriers, bodies.filter { it.startsWith("foreign") })
    }

    /** A foreign replica's op-log of [count] records, carrying no compaction of its own. */
    private fun foreignLog(count: Int = 6): Rga<LogRecord> {
        var rga = Rga.empty<LogRecord>()
        var tail = RgaId.HEAD
        repeat(count) { i ->
            val (next, op) = rga.insertAfter(replica = replicaB, after = tail, value = record(500 + i, "foreign$i"))
            rga = next
            tail = op.id
        }
        return rga
    }

    /** A foreign replica's op-log, optionally carrying an [RgaOp.Compact] of its own. */
    private fun remoteLog(carriesCompact: Boolean): Rga<LogRecord> {
        var rga = Rga.empty<LogRecord>()
        var tail = RgaId.HEAD
        val ids = mutableListOf<RgaId>()
        repeat(6) { i ->
            val (next, op) = rga.insertAfter(replica = replicaB, after = tail, value = record(500 + i))
            rga = next
            tail = op.id
            ids += op.id
        }
        if (!carriesCompact) return rga
        return rga.apply(RgaOp.Compact(rga.positionsFor(setOf(ids[0]))))
    }

    // ---- Crash safety: the write order, and both windows ----

    @Test
    fun theLedgerIsCommittedAfterTheCoveringWriteAndBeforeTheKeyIsDeleted() = runTest {
        // The two state-based tests below pin what a crash can LEAVE BEHIND; this pins the write
        // ORDER that makes those the only reachable states — the same reason
        // aMigrationThatDiesPartWayLeavesTheLegacyBlobIntact exists next door.
        //
        // Inverting the order does not lose records (they are already invisible) — it loses
        // SUPPRESSION. Delete a segment whose covering floor never reached disk and the
        // restarted replica holds neither the Insert, nor its Remove, nor anything that refuses
        // them, so a peer that still holds the raw ops re-admits the records as live.
        // Delimited PER EXPORT, not over the whole stream. `export()` is one batch, so the ops it
        // adds are exactly that batch — and the batch is the unit the ordering claim is about.
        // Scanning the flat stream instead lets the *previous* batch's active-segment write stand
        // in for this one's, which is precisely the stale covering state the order exists to
        // prevent: an inverted order still reads as "an active write, then the ledger".
        val store = RecordingStore()
        val exporter = exporterFor(store)
        val batches = mutableListOf<List<StoreOperation>>()
        var seen = 0
        repeat(300) { i ->
            exporter.export(record(i))
            val ops = store.operations()
            batches += ops.subList(seen, ops.size)
            seen = ops.size
        }

        val retiring = batches.filter { batch ->
            batch.any { it.kind == StoreOpKind.DELETE && it.key.name.startsWith("otel.logs.seg.") }
        }
        assertTrue(retiring.isNotEmpty(), "precondition: something must have been retired")

        assertAll(
            *retiring.map { batch ->
                {
                    val swept = batch.withIndex().filter {
                        it.value.kind == StoreOpKind.DELETE && it.value.key.name.startsWith("otel.logs.seg.")
                    }
                    val numbers = swept.map { it.value.key.name.substringAfterLast('.').toInt() }
                    val commitAt = batch.indexOfFirst {
                        it.kind == StoreOpKind.WRITE && it.key == INDEX_KEY_FOR_TEST &&
                            decodeIndexForTest(requireNotNull(it.bytes)).retired.isNotEmpty()
                    }
                    assertTrue(commitAt >= 0, "no ledger write in the batch that swept $numbers")
                    val index = decodeIndexForTest(requireNotNull(batch[commitAt].bytes))
                    assertAll(
                        {
                            assertTrue(
                                swept.all { it.index > commitAt },
                                "a key was deleted before the ledger write that names it; a crash there " +
                                    "loses the key forever, in a format with no key enumeration",
                            )
                        },
                        {
                            assertTrue(
                                numbers.all { it in index.retired && it !in index.sealedSegments },
                                "the ledger write does not name every key this batch deleted: $index vs $numbers",
                            )
                        },
                        {
                            // The covering state — the raised floor — rides in this write. Read
                            // from the same batch, so an inverted order has nothing to fall back on.
                            val covering = batch.getOrNull(commitAt - 1)
                            assertEquals(
                                StoreKey(segmentKeyForTest(index.active)),
                                covering?.key,
                                "the write immediately before the ledger commit must be THIS batch's " +
                                    "active-segment write; retiring $numbers on a stale floor lets a peer " +
                                    "resurrect their records",
                            )
                        },
                    )
                }
            }.toTypedArray(),
        )
    }

    @Test
    fun aCrashBetweenTheLedgerWriteAndTheDeleteStillSweepsOnTheNextStart() = runTest {
        // The window the ledger exists for. There is no key-enumeration API, so a segment the
        // index simply stopped naming would be unreachable and unsweepable forever; naming it
        // under `retired` is what lets the next start finish the job.
        val store = RecordingStore()
        val exporter = exporterFor(FailDeleteStore(store))
        repeat(300) { i -> exporter.export(record(i)) }
        val live = exporter.snapshot().toList()
        val ledger = decodeIndexForTest(requireNotNull(store.read(INDEX_KEY_FOR_TEST))).retired

        val restarted = exporterFor(store)
        restarted.recover()

        assertAll(
            { assertTrue(ledger.isNotEmpty(), "precondition: the ledger must name what the crash left behind") },
            {
                // A refused delete of superseded garbage must not be reported as a failed export:
                // the record WAS durably written, and health.failed means "the store is rejecting
                // writes".
                assertEquals(0, exporter.health.value.failed, "a failed sweep was reported as a failed export")
            },
            {
                assertTrue(
                    ledger.none { segmentKeyForTest(it) in segmentKeys(store) },
                    "the ledger's keys survived the next start: ${segmentKeys(store)}",
                )
            },
            { assertEquals(live, restarted.snapshot().toList(), "the interrupted retirement cost records") },
        )
    }

    @Test
    fun aRetirementIsNeverPublishedByABatchWhoseCoveringWriteWasRefused() = runTest {
        // The window the two crash tests below cannot reach, because a crash STOPS the process.
        // Here it does not: the store keeps refusing the segment write while accepting every index
        // write, and the exporter carries on and builds the NEXT batch on whatever the failed one
        // left behind. That next batch opens with `if (!indexPersisted) Put(INDEX_KEY, ...)` —
        // BEFORE its own active-segment write — so anything a failed batch moved onto the ledger
        // in memory is published there with nothing covering it. `loadPersistedState` then sweeps
        // `retired` unconditionally, before it reads a thing.
        //
        // A quota-bound store is exactly this shape, not an exotic one: `IndexedDbDurableStore`
        // refuses LARGE writes and accepts small ones, and the segment blob is ~123 KB at
        // DEFAULT_LOG_SEGMENT_OPS against an index of a few ints.
        //
        // The property, stated over the write stream rather than over one batch's shape: a number
        // reaches an on-disk `retired` list only after a write carrying its covering state landed.
        // Restating a number the store already holds under `retired` is free — it is the number
        // appearing for the FIRST time that has to be covered.
        val store = RecordingStore()
        val quota = RefuseSegmentWritesStore(store)
        val exporter = exporterFor(quota)
        val batches = mutableListOf<List<StoreOperation>>()
        var seen = 0

        repeat(200) { i ->
            exporter.export(record(i))
            val ops = store.operations()
            batches += ops.subList(seen, ops.size)
            seen = ops.size
        }
        quota.refuseSegmentWrites()
        repeat(200) { i ->
            exporter.export(record(1_000 + i))
            val ops = store.operations()
            batches += ops.subList(seen, ops.size)
            seen = ops.size
        }

        val published = mutableSetOf<Int>()
        val offences = mutableListOf<String>()
        var commits = 0
        batches.forEach { batch ->
            batch.forEachIndexed { at, operation ->
                if (operation.kind != StoreOpKind.WRITE || operation.key != INDEX_KEY_FOR_TEST) return@forEachIndexed
                val index = decodeIndexForTest(requireNotNull(operation.bytes))
                val fresh = index.retired.toSet() - published
                published += index.retired
                if (fresh.isEmpty()) return@forEachIndexed
                commits++
                val covering = batch.getOrNull(at - 1)
                if (covering?.key != StoreKey(segmentKeyForTest(index.active))) {
                    offences += "retired $fresh with ${covering?.key ?: "nothing"} before it, not " +
                        "${segmentKeyForTest(index.active)}"
                }
            }
        }

        assertAll(
            {
                assertTrue(
                    quota.refusedWrites() > 0,
                    "precondition: the store must actually have refused the covering write",
                )
            },
            { assertTrue(commits > 0, "precondition: something must have been retired") },
            {
                assertTrue(
                    offences.isEmpty(),
                    "a retirement reached disk without its covering write: $offences",
                )
            },
        )
    }

    @Test
    fun noIndexWriteEverNamesARetiredSegmentAsSealedAgain() = runTest {
        // One batch can carry TWO index writes: the ledger commit, and then a roll's, when the
        // pass pushed the active segment past `segmentOps`. The roll's is encoded while the batch
        // is still being built — before the ledger's move has been applied to any field — so it
        // has to *project* that move. Without the projection it lands on disk AFTER the ledger
        // write and undoes it: the segment is named as sealed again and dropped from `retired`,
        // while the sweep that follows in the same batch deletes its key anyway. The result is a
        // key named as live forever, in a format with no key enumeration to ever find it again.
        //
        // Stated over the whole write stream rather than one batch, because that is the property
        // — segment numbers are never reused, so a number that has been retired must never appear
        // under `sealedSegments` in any later index write, whichever call site emitted it.
        //
        // The configuration is not arbitrary and a pure-export run does NOT reach this. A pass on
        // the export path can only shrink the active segment — it purges this replica's own ops
        // under the raised floor and adds no op — so `activeOpCount` cannot cross `segmentOps` on
        // the way out of one, and the roll never fires in a retiring batch. It takes a *foreign*
        // author's dots: those cannot fold into the floor, so the pass mints an `RgaOp.Compact`
        // that ADDS an op to the active segment. Hence the merge, and a `segmentOps` small enough
        // for that one extra op to tip the roll.
        val store = RecordingStore()
        val exporter = exporterFor(store, maxRecords = 5, segmentOps = 2)
        exporter.merge(foreignLog(2))
        repeat(300) { i -> exporter.export(record(i)) }

        val indexes = store.operations()
            .filter { it.kind == StoreOpKind.WRITE && it.key == INDEX_KEY_FOR_TEST }
            .map { decodeIndexForTest(requireNotNull(it.bytes)) }
        val retired = mutableSetOf<Int>()
        val resurrected = mutableListOf<Int>()
        indexes.forEach { index ->
            resurrected += index.sealedSegments.filter { it in retired }
            retired += index.retired
        }

        assertAll(
            { assertTrue(retired.isNotEmpty(), "precondition: something must have been retired") },
            {
                assertTrue(
                    resurrected.isEmpty(),
                    "an index write named already-retired segments $resurrected as sealed again",
                )
            },
        )
    }

    @Test
    fun aRefusedCoveringWriteCannotCostRecordsARecoveryWasStillReading() = runTest {
        // The consequence of the ordering above, priced in records. While the store refuses every
        // segment write it accepts every index write, so NOTHING a recovery reads can change — the
        // record-bearing keys are frozen at the last write that landed. The only thing that can
        // move is the ledger, and a ledger entry is a DELETE the next start performs before it
        // reads anything. So a difference here is not "the last few exports were not durable"
        // (they were refused, and reported as failures); it is records that were durable, that a
        // restart WOULD have reconstructed, destroyed by a retirement nothing covered.
        val store = RecordingStore()
        val quota = RefuseSegmentWritesStore(store)
        val exporter = exporterFor(quota)
        repeat(300) { i -> exporter.export(record(i)) }
        val beforePressure = exporterFor(store).also { it.recover() }.snapshot().toList()

        quota.refuseSegmentWrites()
        repeat(300) { i -> exporter.export(record(1_000 + i)) }
        val after = exporterFor(store).also { it.recover() }.snapshot().toList()

        assertAll(
            { assertTrue(beforePressure.isNotEmpty(), "precondition: the healthy phase must leave records") },
            {
                assertTrue(
                    quota.refusedWrites() > 0,
                    "precondition: the store must actually have refused the covering write",
                )
            },
            {
                assertEquals(
                    beforePressure,
                    after,
                    "a store that refused every covering write still lost records a recovery was reading",
                )
            },
        )
    }

    @Test
    fun aCrashBeforeTheLedgerWriteLeavesTheOldLayoutIntact() = runTest {
        // The other window. The exporter "dies" at the instant the commit-point index write is
        // refused, so everything queued before it landed and nothing after it did. What is on
        // disk must still be the pre-retirement layout, and must lose nothing.
        val store = RecordingStore()
        val blocked = FailRetirementLedgerStore(store)
        val exporter = exporterFor(blocked)
        var i = 0
        while (!blocked.tripped && i < 1_000) {
            exporter.export(record(i))
            i++
        }
        assertTrue(blocked.tripped, "precondition: a retirement must have been attempted")
        val live = exporter.snapshot().toList()

        val restarted = exporterFor(store)
        restarted.recover()
        val onDisk = decodeIndexForTest(requireNotNull(store.read(INDEX_KEY_FOR_TEST)))

        assertAll(
            {
                assertTrue(
                    store.operations().none {
                        it.kind == StoreOpKind.DELETE && it.key.name.startsWith("otel.logs.seg.")
                    },
                    "a segment was deleted although the ledger write never landed",
                )
            },
            { assertEquals(emptyList(), onDisk.retired, "the refused write must not have reached the store") },
            { assertEquals(live, restarted.snapshot().toList(), "the pre-crash layout lost records") },
        )
    }

    // ---- Retirement must not weaken what windowing already promised ----

    @Test
    fun aPeerHoldingRetiredRecordsStillCannotPushThemBackIn() = runTest {
        // Retirement deletes the ops; the floor that suppresses them lives in the active segment,
        // written before the delete. If that ordering were wrong the records would come back
        // here — a restarted replica holding neither the Insert, the Remove, nor the floor has
        // nothing left to refuse a peer that still holds the raw ops.
        val store = RecordingStore()
        val exporter = exporterFor(store)
        repeat(8) { i -> exporter.export(record(i, body = "early$i")) }
        val peer = exporter.snapshot()
        repeat(300) { i -> exporter.export(record(100 + i, body = "later$i")) }

        val restarted = exporterFor(store)
        restarted.recover()
        restarted.merge(peer)

        assertAll(
            {
                assertTrue(
                    store.operations().any {
                        it.kind == StoreOpKind.DELETE && it.key.name.startsWith("otel.logs.seg.")
                    },
                    "precondition: a segment must actually have been retired",
                )
            },
            {
                val bodies = restarted.snapshot().toList().mapNotNull { it.body }
                assertTrue(bodies.none { it.startsWith("early") }, "retired records came back on merge; got $bodies")
            },
        )
    }
}
