package us.tractat.kuilt.bolt

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.serializer
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.crdt.VersionVector
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * [BoltDecorator] on its own, with no telemetry anywhere near it — the suppression protocol, the
 * failure surface, and the bound on both.
 *
 * The end-to-end scenario the class exists for (a gossiping exporter's records reaching a
 * server-side archive) is pinned from the consumer's side, in `:kuilt-otel`'s
 * `GossipedRecordsReachTheArchiveTest`. It has to live there: `:kuilt-bolt` staying ignorant of
 * telemetry is what lets one decorator serve every op-log owner, and a test dependency the other
 * way would quietly spend that.
 */
class BoltDecoratorTest {

    private val alice = ReplicaId("alice")
    private val bob = ReplicaId("bob")
    private val epoch = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun format(): BoltArchiveFormat<RgaId, String, RgaOp<String>> =
        BoltArchiveFormat.rga(serializer<String>())

    private fun newBolt(capacityBytes: Long = Long.MAX_VALUE): InMemoryBolt<RgaId, String, RgaOp<String>> =
        InMemoryBolt(format(), FixedClock(epoch), capacityBytes = capacityBytes)

    private fun decorate(
        bolt: Bolt<RgaOp<String>>,
        dedupWindow: Int = BoltDecorator.DEFAULT_DEDUP_WINDOW,
    ) = BoltDecorator(bolt, format(), dedupWindow)

    private suspend fun Bolt<RgaOp<String>>.archivedOps(): List<RgaOp<String>> =
        replay(ReplayScope.All).frames().toList().flatMap { it.ops }

    // ── Suppression: the merge path's whole reason for needing it ──────────────

    /**
     * **The anti-entropy property.** A peer re-offers the log it holds on every round, so an
     * archive that took it at face value would write one full copy of that log per round — growth
     * proportional to time spent gossiping rather than to history.
     *
     * The second publish must therefore write **no frame at all**, not merely converge to the same
     * replayed ops: a replay that happens to be de-duplicated says nothing about what was written.
     *
     * **Mutation receipts.**
     * - Replacing the reservation filter with `identified` (suppress nothing) reds
     *   `assertIs<AppendResult.Skipped>(second, …)`, `framesWritten`, and the archived-op count —
     *   the archive doubles, which is the failure this exists to prevent.
     * - Making the identity `id` alone rather than the whole [us.tractat.kuilt.crdt.LogOp] — i.e.
     *   dropping the insert-versus-remove discriminator — reds `assertEquals(ops, archived, …)`:
     *   the `Remove` is swallowed as a duplicate of its own target `Insert`, and the archive loses
     *   the removal entirely. That is the case the frame's dots provably cannot cover, because a
     *   `Remove` mints none.
     */
    @Test
    fun rePublishingTheSameOperationsWritesNothingAndDoesNotDoubleTheArchive() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val bolt = newBolt()
            val decorator = decorate(bolt)
            val ops = insertsWithARemoval()

            val first = decorator.publish(ops)
            val second = decorator.publish(ops)
            val archived = bolt.archivedOps()

            assertAll(
                { assertIs<AppendResult.Written>(first, "the first round archives") },
                { assertIs<AppendResult.Skipped>(second, "the second writes no frame at all") },
                { assertEquals(ops, archived, "every operation once — including the Remove, which mints no dot") },
                { assertEquals(1L, decorator.health.value.framesWritten, "one round, one frame") },
                { assertEquals(ops.size.toLong(), decorator.health.value.opsArchived) },
                {
                    assertEquals(
                        ops.size.toLong(),
                        decorator.health.value.opsDeduplicated,
                        "the saving is counted — an efficiency number, not a loss one",
                    )
                },
            )
        }

    /**
     * A compaction record is a record of *forgetting*, so it never reaches the archive — and it is
     * never an identity either, because it names a **set** of ids rather than one.
     *
     * The second half is the part worth a test. If a `Compact` were treated as an identity it would
     * be suppressed on the second round like anything else, and nothing would notice; the failure
     * only shows up in what it *drags with it*, which is why this publishes content beside it.
     *
     * **Mutation receipt:** letting a `Compact` through the classification filter in `publish` reds
     * the archived-ops assertion, because `BoltArchiveFormat.contentOnly` then reports a smaller
     * frame than the reservation claimed and the counts stop lining up.
     */
    @Test
    fun aCompactionRecordIsNeitherArchivedNorAnIdentity() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = newBolt()
        val decorator = decorate(bolt)
        val fixture = rgaWithACompaction()

        val withCompaction = decorator.publish(fixture.contentOps + fixture.compactOp)
        val onlyCompaction = decorator.publish(listOf(fixture.compactOp))
        val archived = bolt.archivedOps()

        assertAll(
            { assertIs<AppendResult.Written>(withCompaction, "the content beside it is archived") },
            { assertEquals(fixture.contentOps, archived, "and the compaction record is not") },
            {
                assertIs<AppendResult.Skipped>(
                    onlyCompaction,
                    "a publish of nothing but compaction records offers the archive nothing",
                )
            },
            {
                assertEquals(
                    0L,
                    decorator.health.value.opsDeduplicated,
                    "and it is not counted as a duplicate — it was never an identity to begin with",
                )
            },
        )
    }

    // ── Failure: identities released, identities reported ─────────────────────

    /**
     * A refused append **releases** the identities it claimed, so the next anti-entropy round
     * archives the operations instead of skipping them as already-kept.
     *
     * This is the difference between a transient full disk costing a burst of records and costing
     * them permanently. The claim happens before the append — it has to, or two concurrent
     * publishes of the same operation both write it — so the release is what keeps the optimism
     * honest.
     *
     * **Mutation receipt:** moving the claim after a successful append (`archived.add` only in the
     * `Written` branch) leaves this test green, which is the point of the pairing — it reds
     * [rePublishingTheSameOperationsWritesNothingAndDoesNotDoubleTheArchive] instead, under
     * concurrency. Deleting the release (`else -> { }`) reds this one on both surviving assertions:
     * the retry is skipped and the archive stays empty.
     */
    @Test
    fun aRefusedAppendReleasesItsIdentitiesSoTheNextRoundArchivesThem() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val backing = newBolt()
            val bolt = RefusingOnce(backing)
            val decorator = decorate(bolt)
            val ops = insertsWithARemoval()

            val refused = decorator.publish(ops)
            val retried = decorator.publish(ops)
            val archived = backing.archivedOps()

            assertAll(
                { assertIs<AppendResult.Failed>(refused, "the first round is refused") },
                { assertIs<AppendResult.Written>(retried, "the retry is not skipped as already-archived") },
                { assertEquals(ops, archived, "and the operations really are in the archive after it") },
            )
        }

    /**
     * The failure surface carries **identities**, not a tally: the dots and the offset range of the
     * frame that did not land.
     *
     * A record the archive refused is one the live replica windows away next, so it is lost from
     * both sides. `failed++` makes every recovery — defer windowing for those records, re-feed
     * them, correlate the gap against a backend — unimplementable, which is the whole reason this
     * type does not just count.
     *
     * **Mutation receipt:** replacing `recentFailures` with a bare counter reds the dots assertion.
     * Note what this canNOT reach: `AppendResult.Failed` is constructed by the *backend*, so the
     * dots being the **right** ones is `InMemoryBolt`'s property (pinned by
     * `BoltConformanceSuite.anExhaustedBoltReportsUnavailableAndRefusesTheAppend`), and all this
     * pins is that the decorator carries them through rather than flattening them.
     */
    @Test
    fun aRefusedAppendReportsTheDotsAndTheOffsetRangeItLost() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        // Smaller than one segment header, so the archive is full before the first frame.
        val decorator = decorate(newBolt(capacityBytes = 8L))
        val ops = insertsWithARemoval()

        val refused = assertIs<AppendResult.Failed>(decorator.publish(ops))
        val health = decorator.health.value
        val expectedDots = ops.filterIsInstance<RgaOp.Insert<String>>().map { it.id.dot }.toSet()

        assertAll(
            { assertEquals(1L, health.appendsFailed) },
            { assertEquals(listOf(refused), health.recentFailures, "the failure itself, identities and all") },
            { assertEquals(expectedDots, health.recentFailures.single().insertDots, "WHICH records were lost") },
            { assertEquals(0L, health.opsArchived, "and nothing is claimed as archived") },
            { assertNotNull(refused.offset, "the offset the frame would have occupied") },
        )
    }

    /**
     * The suppression set is **bounded**, and an identity pushed out of it is archived a second
     * time rather than silently dropped.
     *
     * That is the trade this class makes deliberately, so it is asserted rather than left as a
     * comment: an unbounded set would be sized by an archive that is unbounded by construction —
     * a server holding a year of history would hold a year of identities in memory beside it. A
     * miss costs bytes, never correctness, because folding an op-log CRDT's operation twice is
     * idempotent.
     *
     * The dropped identity is the **oldest**, which is also the least likely to be re-offered: a
     * peer re-offers the log it currently holds, and its own retention bounds that.
     *
     * **Mutation receipt:** deleting `trimToWindow`'s body reds the last two assertions — the old
     * identity stays remembered, so the re-publish is skipped and the archive holds one frame.
     */
    @Test
    fun anIdentityPushedOutOfTheWindowIsArchivedAgainRatherThanLost() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val bolt = newBolt()
            val decorator = decorate(bolt, dedupWindow = 1)
            val (afterOld, old) = Rga.empty<String>().insertAt(alice, 0, "old")
            val (_, new) = afterOld.insertAt(alice, 1, "new")

            decorator.publish(listOf(old))
            decorator.publish(listOf(new))
            val again = decorator.publish(listOf(old))
            val archived = bolt.archivedOps()

            assertAll(
                { assertIs<AppendResult.Written>(again, "the evicted identity is no longer remembered") },
                { assertEquals(listOf(old, new, old), archived, "so it is archived a second time") },
                { assertEquals(3L, decorator.health.value.framesWritten) },
                {
                    assertEquals(
                        0L,
                        decorator.health.value.opsDeduplicated,
                        "and nothing was suppressed — the window held one identity, and it was not this one",
                    )
                },
            )
        }

    // ── fixtures ──────────────────────────────────────────────────────────────

    /**
     * Two inserts and the removal of one of them — the shape a `Remove` has to survive.
     *
     * The removal is what makes the dedup identity non-trivial: it mints no dot of its own, so a
     * frame's insert-only dots cannot tell it apart from the insert it tombstones.
     */
    private fun insertsWithARemoval(): List<RgaOp<String>> {
        val (r1, first) = Rga.empty<String>().insertAt(alice, 0, "first")
        val (r2, second) = r1.insertAt(bob, 1, "second")
        val (_, removal) = assertNotNull(r2.removeAt(1), "removeAt(1) must find the trailing element")
        return listOf(first, second, removal)
    }

    /**
     * An `Rga` that has compacted its **trailing** element, plus the ops that produced it.
     *
     * Trailing, not leading: `Rga.compact` refuses to collect an id a live insert still anchors via
     * `after`, and signals that refusal by returning `null`. Compacting the first of two elements
     * would yield no compaction at all — silently — and every assertion about compaction records
     * would then pass for the wrong reason.
     */
    private fun rgaWithACompaction(): CompactionFixture {
        val (r1, first) = Rga.empty<String>().insertAt(alice, 0, "kept")
        val (r2, second) = r1.insertAt(bob, 1, "suppressed")
        val (r3, removal) = assertNotNull(r2.removeAt(1), "removeAt(1) must find the trailing element")

        val cut = VersionVector.of(mapOf(alice to first.id.seq, bob to second.id.seq))
        val (_, compactOp) = assertNotNull(
            r3.compact(stableCut = cut, frontierMax = cut, delivered = cut),
            "the tombstoned, causally-stable, unanchored trailing insert must be collectable",
        )
        return CompactionFixture(listOf(first, second, removal), compactOp)
    }

    private class CompactionFixture(
        val contentOps: List<RgaOp<String>>,
        val compactOp: RgaOp.Compact,
    )

    /** A [Bolt] that refuses its **first** append and then behaves, so a retry can be observed. */
    private class RefusingOnce(private val backing: Bolt<RgaOp<String>>) : Bolt<RgaOp<String>> {
        private var refused = false

        override suspend fun append(ops: List<RgaOp<String>>): AppendResult {
            if (refused) return backing.append(ops)
            refused = true
            return AppendResult.Failed(reason = "refused once, on purpose", insertDots = emptySet(), offset = 0L)
        }

        override fun replay(scope: ReplayScope): Flow<ReplayEvent<RgaOp<String>>> = backing.replay(scope)

        override fun availability(): BoltAvailability = backing.availability()
    }

    private class FixedClock(private val at: Instant) : Clock {
        override fun now(): Instant = at
    }
}
