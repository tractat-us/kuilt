package us.tractat.kuilt.bolt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
import kotlin.test.assertFailsWith
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

    /**
     * A doubt with distinct, non-default field values, so an assertion that it reached [health]
     * cannot pass against a decorator that forwarded a freshly constructed [DurabilityState].
     */
    private val degraded = DurabilityState.Degraded(
        fromOffset = 17L,
        toOffset = 512L,
        reason = "the volume refused to flush: errno=5 (Input/output error)",
    )

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
     * - Replacing the reservation filter with `identified.onEach { … }` (claim everything, suppress
     *   nothing) reds three assertions: the archive doubles to two frames and six ops, and nothing
     *   is counted as suppressed. That is the failure this exists to prevent.
     * - Making the identity `id` alone rather than the whole [us.tractat.kuilt.crdt.LogOp] — i.e.
     *   dropping the insert-versus-remove discriminator — reds `assertEquals(ops, archived, …)`
     *   with the `Remove` missing: it is swallowed as a duplicate of its own target `Insert`, and
     *   the archive loses the removal entirely. That is the case the frame's dots provably cannot
     *   cover, because a `Remove` mints none. (It reds two of this file's other tests too, for the
     *   same reason; this is the assertion that *names* the cause.)
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
     * **Mutation receipt, and a GREEN row worth naming.** Letting a `Compact` through the
     * classification filter in `publish` reds exactly **one** assertion — the suppression count,
     * because the compaction record becomes an identity, occupies a window slot, and is then
     * counted as a duplicate on the second round.
     *
     * The assertion above it — "and the compaction record is not [archived]" — stays **green**
     * under that mutation, and pretending otherwise would be worse than saying so. The property is
     * held by *two* barriers: this filter, and `BoltArchiveFormat.contentOnly` inside
     * [Bolt.append]. The bolt's own barrier is the load-bearing one and is mutation-checked where
     * it lives, in `BoltConformanceSuite.aBoltDiscardsCompactionRecordsAndKeepsWhatTheySuppress`.
     * So what this test actually pins about the decorator is the *accounting*: a record of
     * forgetting must not consume a suppression slot or be reported as a duplicate.
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
     * **Mutation receipt:** replacing the release with `trimToWindow()` — i.e. keeping the claim
     * after a refusal — reds both of this test's remaining assertions: the retry comes back
     * `Skipped` rather than `Written`, and the archive is still empty afterwards.
     *
     * The *other* half of the protocol — claiming **before** the append rather than after it — is
     * indistinguishable from this test's single publisher, and is pinned separately and
     * deterministically by [twoOverlappingPublishesOfTheSameOperationsArchiveItOnce].
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
     * **Mutation receipt:** never appending to `recentFailures` (`recentFailures =
     * current.recentFailures`) reds this test — the failure, and with it every identity, is gone
     * while `appendsFailed` still climbs, which is exactly the tally-instead-of-identities shape.
     *
     * **What this canNOT reach:** `AppendResult.Failed` is constructed by the *backend*, so the
     * dots being the **right** ones is `InMemoryBolt`'s property, pinned by
     * `BoltConformanceSuite.anExhaustedBoltReportsUnavailableAndRefusesTheAppend`. All this pins is
     * that the decorator carries them through rather than flattening them.
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
     * The dropped identity is the **least recently offered** — see
     * [aReOfferedIdentitySurvivesEvictionWhileAnIdleOneDoesNot] for why that qualifier is the whole
     * property and not a synonym for "oldest".
     *
     * **Mutation receipt:** short-circuiting `trimToWindow` (`if (archived.size >= 0) return`) reds
     * this test — the old identity stays remembered, so the re-publish is skipped.
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

    /**
     * **The window is LRU, not FIFO** — a re-offered identity is refreshed and survives; an idle
     * one is evicted in its place.
     *
     * This is the property the whole suppression design rests on, and it is invisible at any window
     * size big enough to hold the fixture: FIFO and LRU agree until something is actually evicted,
     * which is why this runs at `dedupWindow = 2` and steps one identity over the edge on purpose.
     *
     * Under FIFO the archive grows by a full copy of every peer's log every `dedupWindow / rate`
     * rounds — `LinkedHashSet.add` on a present element returns `false` and does **not** reorder
     * it, so a peer's identities march toward the head on a clock set by *everybody else's* traffic
     * and are evicted while that peer is still re-offering every one of them on every round. That
     * is "growth proportional to time spent gossiping", the exact failure #2216 names.
     *
     * The sequence: claim A, claim B, **re-offer A** (a hit, which must refresh it), claim C — the
     * eviction. LRU evicts B; FIFO evicts A.
     *
     * **Mutation receipt:** replacing `BoltDecorator.claim`'s body with a bare
     * `return archived.add(identity)` — i.e. FIFO — reds the **A** assertion and nothing else in
     * this file. Claiming after the append rather than before it reds it too.
     *
     * **The B assertion does not discriminate the two orders, and is not there to.** Under FIFO the
     * eviction takes A, so B is `Written` either way. It is the *vacuity guard*: without it, a
     * `trimToWindow` that evicted nothing at all would leave A `Skipped` and pass the A assertion
     * for entirely the wrong reason. B being `Written` is what proves an eviction happened, which
     * is the precondition the A assertion's meaning rests on.
     */
    @Test
    fun aReOfferedIdentitySurvivesEvictionWhileAnIdleOneDoesNot() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val bolt = newBolt()
            val decorator = decorate(bolt, dedupWindow = 2)
            val (r1, a) = Rga.empty<String>().insertAt(alice, 0, "a")
            val (r2, b) = r1.insertAt(alice, 1, "b")
            val (_, c) = r2.insertAt(alice, 2, "c")

            decorator.publish(listOf(a))
            decorator.publish(listOf(b))
            // The hit that must refresh A. Under FIFO this changes nothing at all.
            val reOfferA = decorator.publish(listOf(a))
            // The eviction: three identities claimed, room for two.
            decorator.publish(listOf(c))
            val aAgain = decorator.publish(listOf(a))
            val bAgain = decorator.publish(listOf(b))

            assertAll(
                { assertIs<AppendResult.Skipped>(reOfferA, "the re-offer is suppressed, and refreshes A") },
                { assertIs<AppendResult.Skipped>(aAgain, "A was re-offered, so it survived the eviction") },
                { assertIs<AppendResult.Written>(bAgain, "B was idle, so it is the one that went") },
            )
        }

    // ── A backend that throws is a backend that wrote nothing ─────────────────

    /**
     * A [Bolt] that **throws** rather than returning [AppendResult.Failed] must not take the claim
     * with it, and must not vanish from the failure surface.
     *
     * `Bolt.append`'s contract is "never throws *for an I/O failure*", which is narrower than never
     * throws — and `Bolt` is public and pluggable, so a backend over a network or a database can
     * raise anything. Before the conversion, such a throw exited `publish` with the identities
     * still claimed and without reaching `record`: on the export path each operation is published
     * exactly once and never re-offered, so those records were permanently absent from the archive
     * **and** absent from `health`. Lost from both sides, silently — the one outcome this module's
     * failure surface exists to make impossible.
     *
     * **Mutation receipt:** deleting the `catch (failure: Throwable)` arm in
     * `BoltDecorator.appendOrConvert` (letting the throw propagate) reds this test at the `publish`
     * call itself, before any assertion runs.
     */
    @Test
    fun aBackendThatThrowsIsConvertedToAFailureCarryingItsIdentities() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val backing = newBolt()
            val decorator = decorate(ThrowingOnce(backing) { IllegalStateException("the disk is on fire") })
            val ops = insertsWithARemoval()
            val expectedDots = ops.filterIsInstance<RgaOp.Insert<String>>().map { it.id.dot }.toSet()

            val converted = decorator.publish(ops)
            val retried = decorator.publish(ops)
            val archived = backing.archivedOps()

            assertAll(
                { assertIs<AppendResult.Failed>(converted, "a throw becomes a refusal, not an escape") },
                { assertEquals(expectedDots, assertIs<AppendResult.Failed>(converted).insertDots, "with its dots") },
                { assertEquals(1L, decorator.health.value.appendsFailed, "and it reaches the failure surface") },
                { assertEquals(1, decorator.health.value.recentFailures.size, "identities and all") },
                { assertIs<AppendResult.Written>(retried, "the claim was released, so a retry is not skipped") },
                { assertEquals(ops, archived, "and the operations really do land on the retry") },
            )
        }

    /**
     * Cancellation still propagates — and releases the claim on its way out.
     *
     * The conversion above must not swallow a structured-concurrency cancel: that would turn a
     * cancelled scope into a silent no-op, which is the failure `runCatchingCancellable` exists to
     * prevent everywhere else in this repo. But a cancelled append wrote nothing, so leaving the
     * identities claimed would silently drop them from a later round.
     *
     * **Mutation receipt:** removing the `catch (cancellation: CancellationException)` arm — so the
     * generic arm converts it to `Failed` — reds the first assertion (nothing is thrown). Removing
     * only its `lock.withLock { release(reserved) }` line reds the last one (the retry is skipped).
     */
    @Test
    fun cancellationPropagatesAndStillReleasesTheClaim() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val backing = newBolt()
        val decorator = decorate(ThrowingOnce(backing) { CancellationException("the scope went away") })
        val ops = insertsWithARemoval()

        // Thrown by the fake backend, not by a cancelled scope — so catching it here cancels
        // nothing, and the assertion is about propagation rather than about the test's own job.
        assertFailsWith<CancellationException> { decorator.publish(ops) }
        val retried = decorator.publish(ops)

        assertAll(
            { assertEquals(0L, decorator.health.value.appendsFailed, "a cancel is not reported as a refusal") },
            { assertEquals(emptyList(), decorator.health.value.recentFailures, "nor as a lost identity") },
            { assertIs<AppendResult.Written>(retried, "and the claim it never honoured was given back") },
        )
    }

    /**
     * Two publishes of the same operations, genuinely overlapping, archive it **once**.
     *
     * This is the arm that was reported as unproven in the first round of this file: claiming an
     * identity *before* the append rather than after is indistinguishable under a single publisher,
     * so nothing reded on it. It is not dead code — `BoltDecorator` is public and documented for any
     * op-log owner, and two owners sharing one decorator overlap immediately. (Through
     * `WarpLogRecordExporter` alone they cannot: every path holds its `writeMutex`.)
     *
     * The overlap is deterministic rather than raced: the fake bolt parks its first append on a
     * `CompletableDeferred`, both publishes are launched, and the gate is opened only once both
     * have reached it. So the second publish provably runs while the first is mid-append — the
     * exact window a claim-after-append protocol leaves open.
     *
     * **Mutation receipt:** moving the claim into the success branch (claim after the append) reds
     * the frame count and the archived-ops assertion — both publishes see the identities as free
     * and both write.
     */
    @Test
    fun twoOverlappingPublishesOfTheSameOperationsArchiveItOnce() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val backing = newBolt()
            val gate = GatedBolt(backing)
            val decorator = decorate(gate)
            val ops = insertsWithARemoval()

            backgroundScope.launch(StandardTestDispatcher(testScheduler)) { decorator.publish(ops) }
            backgroundScope.launch(StandardTestDispatcher(testScheduler)) { decorator.publish(ops) }
            // Bounded, never `advanceUntilIdle()`: nothing here re-arms, and `runCurrent` is enough
            // to let both publishes reach the gate. The first parks inside `append`; the second
            // runs while it is parked, which is exactly the window under test.
            testScheduler.runCurrent()
            val bothReachedTheGate = gate.appends
            gate.open()
            testScheduler.runCurrent()
            val archived = backing.archivedOps()

            assertAll(
                {
                    assertEquals(
                        1,
                        bothReachedTheGate,
                        "only one publish reached the archive — the other found the identities claimed " +
                            "WHILE the first was parked mid-append, which is the window under test",
                    )
                },
                { assertEquals(1L, decorator.health.value.framesWritten, "so only one frame was written") },
                { assertEquals(ops, archived, "and each operation is archived exactly once") },
            )
        }

    // ── Durability: the one signal that comes from BELOW the decorator ─────────

    /**
     * A backing archive that stops meeting the durability it promised shows up on [health], and
     * clears again when it recovers.
     *
     * **Why this is forwarded at all**, rather than left for a consumer to read off the bolt: the
     * wiring this module recommends is `{ ops -> publish(ops) }` into a `Unit`-returning sink, and a
     * consumer wired that way holds the decorator and **no reference to the bolt**. A degraded
     * archive only the bolt could report would be invisible to precisely the consumer [ArchiveHealth]
     * exists to inform.
     *
     * Four assertions:
     *
     * 1. a healthy backend reports [DurabilityState.AsPromised] — the arm that would go green on a
     *    hardcoded constant, and the reason 2 and 3 are here;
     * 2. a degraded backend's state reaches [health] **whole**, offsets and reason included, rather
     *    than being flattened to a boolean;
     * 3. it **clears** when the backend recovers — the only property on [ArchiveHealth] that can go
     *    back, and the one a latched signal would get wrong;
     * 4. a publish with nothing new to archive still refreshes it. A doubt raised by an earlier
     *    append does not stop being true because this publish carried only duplicates, and the
     *    counters confirm nothing was written.
     *
     * **Mutation receipts**, measured:
     *
     * | Mutation | Reds |
     * |---|---|
     * | Drop `durability = durability` from `record`'s `copy` | **2 and 4** |
     * | Refresh it only on [AppendResult.Written] | **4 only** |
     *
     * **Assertions 1 and 3 are green under both, and the reason is the same one.** Both expect
     * [DurabilityState.AsPromised], which is exactly what a decorator that forwards *nothing* also
     * answers — so neither can distinguish "forwarded a healthy state" from "never forwarded
     * anything". They earn their place differently: 1 is what a hardcoded constant would satisfy and
     * 2 would not, and 3 is the only assertion that would catch a signal that latches once degraded.
     * Neither is load-bearing against the mutations above, and saying so is better than a table that
     * reads as though every row were.
     *
     * **What this cannot reach:** the staleness the KDoc admits — a backend degrading while nothing
     * is published leaves [health] behind, and no assertion here observes that, because there is no
     * publish at which to observe it. [Bolt.durability] stays the authoritative answer.
     */
    @Test
    fun aBackingArchiveThatStopsMeetingItsPromiseShowsUpOnHealthAndClearsAgain() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val backing = SettableDurability(newBolt())
            val decorator = decorate(backing)
            val (afterFirst, first) = Rga.empty<String>().insertAt(alice, 0, "first")
            val (_, second) = afterFirst.insertAt(alice, 1, "second")

            decorator.publish(listOf(first))
            val healthy = decorator.health.value.durability
            backing.state = degraded
            decorator.publish(listOf(second))
            val afterDegrading = decorator.health.value
            backing.state = DurabilityState.AsPromised
            decorator.publish(listOf(second))
            val recovered = decorator.health.value

            backing.state = degraded
            // Nothing new: `second` is already claimed, so this publish is Skipped and writes no frame.
            decorator.publish(listOf(second))
            val afterASkippedPublish = decorator.health.value

            assertAll(
                { assertEquals(DurabilityState.AsPromised, healthy, "a backend meeting its promise is not a doubt") },
                {
                    assertEquals(
                        degraded,
                        afterDegrading.durability,
                        "and one that is not reaches health WHOLE — offsets and reason, not a boolean",
                    )
                },
                {
                    assertEquals(
                        DurabilityState.AsPromised,
                        recovered.durability,
                        "and it clears when the backend recovers — the one property here that can go back",
                    )
                },
                {
                    assertEquals(
                        degraded,
                        afterASkippedPublish.durability,
                        "a publish carrying only duplicates still refreshes it — an earlier append's doubt " +
                            "does not stop being true because this one had nothing to add",
                    )
                },
                {
                    assertEquals(
                        recovered.framesWritten,
                        afterASkippedPublish.framesWritten,
                        "and that publish really did write nothing, or the assertion above proves nothing",
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

        override fun durability(): DurabilityState = backing.durability()
    }

    /**
     * A [Bolt] that **throws** [failure] from its first append and then behaves.
     *
     * A throw rather than an [AppendResult.Failed], because that is the case `Bolt.append`'s
     * contract leaves open — it promises not to throw *for an I/O failure*, and a pluggable backend
     * over a network or a database has failures that are neither.
     */
    private class ThrowingOnce(
        private val backing: Bolt<RgaOp<String>>,
        private val failure: () -> Throwable,
    ) : Bolt<RgaOp<String>> {
        private var thrown = false

        override suspend fun append(ops: List<RgaOp<String>>): AppendResult {
            if (thrown) return backing.append(ops)
            thrown = true
            throw failure()
        }

        override fun replay(scope: ReplayScope): Flow<ReplayEvent<RgaOp<String>>> = backing.replay(scope)

        override fun availability(): BoltAvailability = backing.availability()

        override fun durability(): DurabilityState = backing.durability()
    }

    /**
     * A [Bolt] whose appends **park** until [open] is called, and which counts how many reached it.
     *
     * The count is what makes the overlap deterministic rather than raced: a second publish that
     * ran while the first was parked either reached this append or was suppressed before it, and
     * those are different numbers. `appends++` is unguarded on purpose — this runs on a single test
     * dispatcher, where there is no parallelism to guard against, and a lock here would hide the
     * very interleaving the test exists to create.
     */
    private class GatedBolt(private val backing: Bolt<RgaOp<String>>) : Bolt<RgaOp<String>> {
        private val gate = CompletableDeferred<Unit>()

        var appends: Int = 0
            private set

        override suspend fun append(ops: List<RgaOp<String>>): AppendResult {
            appends++
            gate.await()
            return backing.append(ops)
        }

        fun open() {
            gate.complete(Unit)
        }

        override fun replay(scope: ReplayScope): Flow<ReplayEvent<RgaOp<String>>> = backing.replay(scope)

        override fun availability(): BoltAvailability = backing.availability()

        override fun durability(): DurabilityState = backing.durability()
    }

    /**
     * A [Bolt] whose [durability] answer the test sets, and which archives normally otherwise.
     *
     * A fake that could only ever say [DurabilityState.AsPromised] would make "does the decorator
     * forward it?" unanswerable — the assertion would hold against a decorator that forwarded
     * nothing. Every real backend in the tree is healthy in a test, so the only way to drive the
     * other state through the decorator is a fake that can take it.
     */
    private class SettableDurability(private val backing: Bolt<RgaOp<String>>) : Bolt<RgaOp<String>> {
        var state: DurabilityState = DurabilityState.AsPromised

        override suspend fun append(ops: List<RgaOp<String>>): AppendResult = backing.append(ops)

        override fun replay(scope: ReplayScope): Flow<ReplayEvent<RgaOp<String>>> = backing.replay(scope)

        override fun availability(): BoltAvailability = backing.availability()

        override fun durability(): DurabilityState = state
    }

    private class FixedClock(private val at: Instant) : Clock {
        override fun now(): Instant = at
    }
}
