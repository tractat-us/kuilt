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
        val store = RecordingStore()
        val exporter = exporterFor(store)
        repeat(300) { i -> exporter.export(record(i)) }

        val ops = store.operations()
        val deletes = ops.withIndex().filter {
            it.value.kind == StoreOpKind.DELETE && it.value.key.name.startsWith("otel.logs.seg.")
        }
        assertTrue(deletes.isNotEmpty(), "precondition: something must have been retired")

        assertAll(
            *deletes.map { (at, delete) ->
                {
                    val number = delete.key.name.substringAfterLast('.').toInt()
                    val commitAt = ops.take(at).indexOfLast {
                        it.kind == StoreOpKind.WRITE && it.key == INDEX_KEY_FOR_TEST
                    }
                    val index = decodeIndexForTest(requireNotNull(ops[commitAt].bytes))
                    val covering = ops[commitAt - 1]
                    assertAll(
                        {
                            assertTrue(
                                number in index.retired && number !in index.sealedSegments,
                                "segment $number was deleted before an index write moved it onto the " +
                                    "ledger; a crash here loses the key forever: $index",
                            )
                        },
                        {
                            assertEquals(
                                StoreKey(segmentKeyForTest(index.active)),
                                covering.key,
                                "the write immediately before the ledger commit must be the ACTIVE " +
                                    "segment carrying the state that supersedes segment $number",
                            )
                        },
                        { assertEquals(StoreOpKind.WRITE, covering.kind, "the covering action must be a write") },
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
