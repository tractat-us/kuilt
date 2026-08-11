package us.tractat.kuilt.otel

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.bolt.AppendResult
import us.tractat.kuilt.bolt.Bolt
import us.tractat.kuilt.bolt.BoltArchiveFormat
import us.tractat.kuilt.bolt.BoltDecorator
import us.tractat.kuilt.bolt.InMemoryBolt
import us.tractat.kuilt.bolt.ReplayScope
import us.tractat.kuilt.bolt.frames
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * **The scenario the whole `:kuilt-bolt` module exists to make possible**, driven through the
 * shipped wiring rather than a hand-built CRDT: a phone's log records reach a server by gossip, and
 * the server's archive keeps them after the server's own live replica has forgotten them.
 *
 * ### Why this cannot be a conformance-suite property
 *
 * `BoltConformanceSuite` already pins asymmetric retention — but it drives a bare `Rga`, feeding
 * the bolt operations by hand. That test is **green against a decorator that tees only the local
 * path**, because a hand-driven fixture never exercises a merge. A merge is a state join with no
 * operation stream at all, so the failure it cannot see is precisely the one that matters: a
 * server-side archive holding the server's own telemetry and zero phone records, which is the
 * capability the module opens by calling impossible.
 *
 * So the property is exercised **through a gossiping exporter**, on this side of the module edge
 * (`:kuilt-bolt` never learns what a log record is), and the mutation receipt below is against the
 * merge-path publication specifically.
 */
class GossipedRecordsReachTheArchiveTest {

    private val phone = ReplicaId("phone")
    private val server = ReplicaId("server")
    private val epoch = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun recordId(id: Int): ByteString =
        ByteString(ByteArray(8) { i -> (id.toLong() shr (8 * i)).toByte() })

    private fun record(id: Int) = LogRecord(recordId = recordId(id), body = "record-$id")

    private fun newBolt(): InMemoryBolt<RgaId, LogRecord, RgaOp<LogRecord>> =
        InMemoryBolt(BoltArchiveFormat.rga(LogRecord.serializer()), FixedClock(epoch))

    /** An exporter whose applied operations are archived — the shipped wiring, in one place. */
    private fun archivingExporter(
        replica: ReplicaId,
        store: DurableStore,
        bolt: Bolt<RgaOp<LogRecord>>,
    ): Pair<WarpLogRecordExporter, BoltDecorator<RgaId, LogRecord, RgaOp<LogRecord>>> {
        val decorator = BoltDecorator(bolt, BoltArchiveFormat.rga(LogRecord.serializer()))
        val exporter = WarpLogRecordExporter(
            replica = replica,
            store = store,
            appliedOps = { ops -> decorator.publish(ops) },
        )
        return exporter to decorator
    }

    private suspend fun Bolt<RgaOp<LogRecord>>.archivedBodies(): List<String?> =
        replay(ReplayScope.All).frames().toList()
            .flatMap { it.ops }
            .filterIsInstance<RgaOp.Insert<LogRecord>>()
            .map { it.value.body }

    /**
     * **The headline.** Four records are written on the phone, gossiped to the server, and the
     * server then forgets every one of them. Its archive still replays all four.
     *
     * [WarpLogRecordExporter.clear] is the forgetting, chosen because it is the *total*,
     * deterministic one: after it the live replica holds nothing and a fresh exporter recovers
     * nothing, so "the archive kept them" cannot be confused with "the buffer had not got round to
     * dropping them". It also answers the question a bolt-decorated exporter forces — a clear
     * empties the replica and **does not** reach through to the archive, because an archive whose
     * retention matched its source's would have no reason to exist.
     *
     * **Mutation receipt.** Deleting `publishApplied(remote.operations().toList())` from
     * `WarpLogRecordExporter.mergeTurn` reds the third and fourth assertions: the archive replays
     * nothing, because a state join produces no operations to tee and nothing else on the server
     * ever saw the phone's records as operations. Deleting the *export*-path publication instead
     * leaves this test green — which is the asymmetry worth naming, and why
     * [WarpLogRecordExporterAppliedOpsTest] carries that arm.
     */
    @Test
    fun aServersArchiveKeepsThePhonesRecordsAfterTheServerHasForgottenThem() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val phoneSide = WarpLogRecordExporter(replica = phone, store = InMemoryDurableStore())
            val serverStore = InMemoryDurableStore()
            val bolt = newBolt()
            val (serverSide, _) = archivingExporter(server, serverStore, bolt)
            repeat(RECORDS) { i -> phoneSide.export(record(i)) }

            serverSide.merge(phoneSide.snapshot())
            val heldBeforeForgetting = serverSide.snapshot().size
            val cleared = serverSide.clear()

            val recovered = WarpLogRecordExporter(replica = server, store = serverStore)
            recovered.recover()
            val archived = bolt.archivedBodies()

            assertAll(
                { assertEquals(RECORDS, heldBeforeForgetting, "the gossip really did land on the server") },
                { assertEquals(ExportResult.Success, cleared) },
                { assertEquals(emptyList(), serverSide.snapshot().toList(), "and the server forgot every one") },
                { assertEquals(emptyList(), recovered.snapshot().toList(), "durably — a fresh exporter finds none") },
                {
                    assertEquals(
                        (0 until RECORDS).map { "record-$it" },
                        archived,
                        "while the archive beside it still replays all four — the capability the module exists for",
                    )
                },
            )
        }

    /**
     * Anti-entropy re-offers the same peer log every round. The archive must not grow by one copy
     * of that log per round.
     *
     * This is the failure the decorator's suppression window exists to design against, checked
     * where it actually bites — the merge path, through the real exporter, with the real remote log
     * being re-offered rather than a synthetic repeat.
     *
     * **Mutation receipt:** replacing the reservation filter in `BoltDecorator.publish` with
     * `identified` (suppress nothing) reds every assertion here — three frames, three copies.
     *
     * **What this cannot reach:** it says nothing about a peer whose log has *changed* between
     * rounds. That case is the ordinary one and is covered by the first test; the pathological one
     * is a peer offering the same bytes forever, which is what this drives.
     */
    @Test
    fun reMergingTheSamePeerEveryRoundDoesNotDoubleTheArchive() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val phoneSide = WarpLogRecordExporter(replica = phone, store = InMemoryDurableStore())
        val bolt = newBolt()
        val (serverSide, decorator) = archivingExporter(server, InMemoryDurableStore(), bolt)
        repeat(RECORDS) { i -> phoneSide.export(record(i)) }

        repeat(ROUNDS) { serverSide.merge(phoneSide.snapshot()) }

        val health = decorator.health.value
        val archived = bolt.archivedBodies()
        assertAll(
            { assertEquals(1L, health.framesWritten, "one frame for the first round, none for the repeats") },
            { assertEquals(RECORDS.toLong(), health.opsArchived, "one copy of the peer's log, not $ROUNDS") },
            {
                assertEquals(
                    (RECORDS * (ROUNDS - 1)).toLong(),
                    health.opsDeduplicated,
                    "and the repeats are accounted for as suppression, not silently dropped",
                )
            },
            { assertEquals((0 until RECORDS).map { "record-$it" }, archived, "each record exactly once") },
        )
    }

    /**
     * The archive is a **superset** of the live replica, including when the exporter's own durable
     * write fails: the record is published before that write is attempted, so it lands in the
     * archive and nowhere else.
     *
     * Stated as a property rather than left as an accident. For an archive that direction is the
     * product — a subset would be a silent hole in exactly the history somebody went looking for.
     *
     * **Mutation receipt:** deleting `publishApplied(applied)` from `exportTurn` reds the archive
     * assertion.
     *
     * **And a GREEN row that matters more than the red one.** *Moving* that call to after
     * `commit(actions)` leaves this test **green** — measured. `commit` reports a refused write by
     * returning `ExportResult.Failure` rather than throwing, so a publication placed after it still
     * runs and the archive still ends up holding the record. This test therefore pins the
     * *outcome*, not the *ordering*; the ordering is pinned only by the explicit
     * `listOf("publish", "write")` assertion in
     * `WarpLogRecordExporterAppliedOpsTest.publicationPrecedesTheDurableWriteSoARefusedWriteStillReachesTheSink`.
     * The two are not redundant — this one drives the real decorator end to end, that one drives
     * the order — but neither alone covers the pair.
     */
    @Test
    fun aRefusedDurableWriteStillReachesTheArchive() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = newBolt()
        val (exporter, decorator) = archivingExporter(phone, RefusingStore(), bolt)

        val result = exporter.export(record(0))
        val archived = bolt.archivedBodies()

        assertAll(
            { assertTrue(result is ExportResult.Failure, "the store refused the write") },
            { assertEquals(listOf("record-0"), archived, "the archive holds what the store does not") },
            { assertEquals(1L, decorator.health.value.framesWritten) },
            { assertEquals(emptyList(), decorator.health.value.recentFailures, "the ARCHIVE did not fail") },
        )
    }

    /**
     * **The precondition on the headline, driven rather than described.** A merge can only hand
     * over what the peer *still holds*, so records the phone windowed away before the server's next
     * round never reach the archive at all — and nothing anywhere reports the gap.
     *
     * This is the sharp edge of "a year on the server beside an hour on the phone": the archive's
     * completeness is bounded by **gossip frequency**, not by how much the archive can hold. Merge
     * more slowly than the peer's buffer turns over and the archive is exactly as complete as the
     * schedule allowed, silently. `Bolt.replay`'s truncation verdict cannot help — it reports damage
     * to the *archive*, not a gap at the *source*.
     *
     * **The second assertion is mandatory, not decoration.** "The archive lacks the early records"
     * is trivially true of an archive that was never fed anything, so the test first proves the
     * *phone* really did lose them from the log it offers. Without it the whole test goes vacuous
     * the moment the fixture stops windowing — a `maxRecords` bump, a `windowPassDue` change — and
     * it would go on passing while asserting nothing.
     *
     * The fourth assertion is the other half of the same guard: the records the phone *kept* do
     * reach the archive, so this is a *gap*, not a merge that failed outright.
     */
    @Test
    fun recordsThePhoneWindowedAwayBeforeTheNextRoundNeverReachTheArchive() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            // A tiny cap so a window pass fires deterministically within the test's record count:
            // `windowPassDue` triggers at `evictionsSincePass >= maxRecords`.
            val phoneSide = WarpLogRecordExporter(
                replica = phone,
                store = InMemoryDurableStore(),
                maxRecords = WINDOWED_CAP,
            )
            val bolt = newBolt()
            val (serverSide, _) = archivingExporter(server, InMemoryDurableStore(), bolt)
            repeat(WINDOWED_RECORDS) { i -> phoneSide.export(record(i)) }

            // The server's FIRST merge happens only now — after the phone has already forgotten.
            serverSide.merge(phoneSide.snapshot())

            val stillOffered = phoneSide.snapshot().operations()
                .filterIsInstance<RgaOp.Insert<LogRecord>>()
                .map { it.value.body }
                .toSet()
            val archived = bolt.archivedBodies().toSet()
            val earliest = "record-0"
            val newest = "record-${WINDOWED_RECORDS - 1}"

            assertAll(
                {
                    assertFalse(
                        earliest in stillOffered,
                        "the phone must really have windowed the earliest record out of the log it OFFERS — " +
                            "without this the assertion below is true of any archive, including an empty one",
                    )
                },
                { assertFalse(earliest in archived, "so the archive never hears about it, and never can") },
                {
                    assertEquals(
                        stillOffered,
                        archived,
                        "the archive holds exactly what the phone still had to give — no more, no less",
                    )
                },
                { assertTrue(newest in archived, "and the merge did work; this is a gap, not a failure") },
            )
        }

    /**
     * A full archive must not take down the exporter, and must say **which** records it lost.
     *
     * The identities are the point: the live replica windows those records away next, so a refused
     * append loses them from both sides. A consumer holding the dots can defer windowing for them,
     * re-feed them, or correlate the gap against a backend. `failed++` makes all three
     * unimplementable.
     */
    @Test
    fun aFullArchiveDoesNotFailTheExportAndReportsWhatItLost() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        // Smaller than a single segment header, so the archive is full before the first frame.
        val full = InMemoryBolt(BoltArchiveFormat.rga(LogRecord.serializer()), FixedClock(epoch), capacityBytes = 8L)
        val (exporter, decorator) = archivingExporter(phone, InMemoryDurableStore(), full)

        val result = exporter.export(record(0))

        val failures = decorator.health.value.recentFailures
        assertAll(
            { assertEquals(ExportResult.Success, result, "a full archive disk does not take down logging") },
            { assertEquals(1, exporter.snapshot().size, "and the record is exported normally") },
            { assertEquals(1, failures.size, "but the archive says it could not keep it") },
            {
                assertEquals(
                    exporter.snapshot().operations().filterIsInstance<RgaOp.Insert<LogRecord>>()
                        .map { it.id.dot }.toSet(),
                    failures.single().insertDots,
                    "naming the dot it lost — identities, never a tally",
                )
            },
            { assertTrue(failures.single() is AppendResult.Failed, "carried as the refusal itself") },
        )
    }

    private class FixedClock(private val at: Instant) : Clock {
        override fun now(): Instant = at
    }

    /** A store that refuses every write. Reads and deletes are no-ops over nothing. */
    private class RefusingStore : DurableStore {
        override suspend fun read(key: StoreKey): ByteArray? = null

        override suspend fun write(key: StoreKey, bytes: ByteArray): Unit =
            throw IllegalStateException("this store is refusing writes")

        override suspend fun delete(key: StoreKey) = Unit
    }

    private companion object {
        const val RECORDS = 4
        const val ROUNDS = 3

        /** Small enough that a window pass fires within [WINDOWED_RECORDS] exports. */
        const val WINDOWED_CAP = 2
        const val WINDOWED_RECORDS = 8
    }
}
