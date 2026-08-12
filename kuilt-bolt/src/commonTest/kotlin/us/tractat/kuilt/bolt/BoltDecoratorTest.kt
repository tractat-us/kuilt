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
import kotlin.test.assertTrue
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
        removalWindow: Int = BoltDecorator.DEFAULT_REMOVAL_WINDOW,
        frontierWindow: Int = BoltDecorator.DEFAULT_FRONTIER_WINDOW,
    ) = BoltDecorator(bolt, format(), removalWindow, frontierWindow)

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

    // ── Inserts: the frontier, which has no working set to exceed ─────────────

    /**
     * **The acceptance property.** An aggregate offered working set *far larger* than the bounded
     * identity window is suppressed completely, round after round, and the archive does not grow.
     *
     * This is the case the bounded window provably cannot reach. Under a window of
     * [ISOLATING_REMOVAL_WINDOW] identities, an offered set of [PEERS] × [OPS_PER_PEER] thrashes it
     * completely: every round would archive another near-full copy of every peer's log, which is
     * "growth proportional to time spent gossiping" at full strength. The frontier has no working
     * set at all — an insert it has archived is suppressed for the life of the process.
     *
     * **The rig asserts itself.** "The archive did not grow" is satisfied for free by a fixture that
     * never offered anything twice, and by one too small to strain any bound, so three assertions
     * exist to make those impossible:
     *
     * - the offered set is more than a hundred times the identity window, so nothing that window
     *   does can explain the result;
     * - `opsDeduplicated` equals the full offered set once per repeat round, so the operations
     *   really were re-offered and really were suppressed;
     * - `framesWritten` equals [PEERS], so rounds two and three wrote **nothing**, rather than
     *   writing something that replayed as a duplicate.
     *
     * **And the memory bound is in the fixture, not just the prose.** `frontierWindow` is set to
     * exactly [PEERS] — one run per author and not one entry to spare — so the decorator's entire
     * insert memory while suppressing the whole working set is **[PEERS] runs holding
     * `PEERS × OPS_PER_PEER` archived inserts**. Widening `frontierWindow` would weaken the test;
     * the tightness is the claim. The **precondition** that tightness rests on is *one `publish`
     * per peer per round* — splitting a peer's log across two publishes fragments its run and would
     * redden this legitimately. Repair such a red by restoring the single publish, never by
     * widening the window.
     *
     * **No replay, on purpose.** An earlier draft ended by replaying the archive and counting the
     * decoded operations, which cost more than the property it was checking. It also added nothing:
     * `AppendResult.Written` is the *only* thing that puts bytes in an archive, so `framesWritten`
     * staying at [PEERS] across [ROUNDS] rounds already says no byte was added after the first. The
     * op count comes from the **bolt's** own `AppendResult.Written.opCount`, not from anything the
     * decorator invented. Byte-level round-tripping is pinned where it is cheap — at three
     * operations, in [rePublishingTheSameOperationsWritesNothingAndDoesNotDoubleTheArchive], and in
     * `BoltConformanceSuite`.
     *
     * Inserts only, deliberately: removes are the stated residual and are bounded by
     * `removalWindow`, so including more than [ISOLATING_REMOVAL_WINDOW] of them would make the
     * archive grow for a reason this test is not about. [insertsAreSuppressedWithNoRemovalWindowAtAll]
     * pins that boundary from the other side.
     *
     * **Cost, because this is the class's most expensive test and a CPU-bound one — so unlike the
     * virtual-time tests `TEST_WEDGE_BACKSTOP` was written for, its ceiling really is wall-clock.**
     * Measured with `uptime` sampled alongside (load 9–13, so these are upper bounds): jvm 0.24 s,
     * macosArm64 1.52 s, iosSimulatorArm64 1.51 s — against a 30 s backstop, so roughly 20×
     * headroom on the slowest target. The three things that keep it there are [gossipingPeers]
     * minting one log rather than [PEERS], the absent replay above, and nothing else in the fixture
     * scaling with [ROUNDS]. Re-measure if any of them changes.
     *
     * **Mutation receipt, measured:** routing inserts back through the identity window (give
     * `archiveKeyOf`'s `LogOp.Insert` arm `ArchiveKey.Identity(classified)`) reds the op-count,
     * frame-count and dedup-count assertions together — 80,000 archived operations become 240,000,
     * 80 frames become 240, and `opsDeduplicated` falls from 160,000 to **zero**. Not "grows by
     * `offered − window`" but by the *whole* working set: each peer's [OPS_PER_PEER] identities are
     * evicted by the next peer's before the round comes round again, so a thrashing window
     * suppresses nothing at all. That is the failure this exists to prevent, at full strength.
     */
    @Test
    fun anOfferedWorkingSetFarLargerThanTheIdentityWindowStopsGrowingTheArchive() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val bolt = newBolt()
            val decorator = decorate(
                bolt,
                removalWindow = ISOLATING_REMOVAL_WINDOW,
                // Exactly one run per author, and not one to spare.
                frontierWindow = PEERS,
            )
            val peers = gossipingPeers()
            val offeredPerRound = peers.sumOf { it.size }

            // One publish per peer, per round. That is the fixture's precondition, not an
            // incidental shape — see this test's KDoc.
            repeat(ROUNDS) { peers.forEach { decorator.publish(it) } }
            val health = decorator.health.value

            assertAll(
                {
                    assertTrue(
                        offeredPerRound > BoltDecorator.DEFAULT_REMOVAL_WINDOW,
                        "the rig: $offeredPerRound operations offered per round exceeds even the SHIPPED default " +
                            "window of ${BoltDecorator.DEFAULT_REMOVAL_WINDOW}, let alone the " +
                            "$ISOLATING_REMOVAL_WINDOW this runs at — shrink the fixture and the test below " +
                            "proves nothing",
                    )
                },
                {
                    assertEquals(
                        offeredPerRound.toLong(),
                        health.opsArchived,
                        "each operation archived exactly once, across $ROUNDS rounds — the count the " +
                            "BOLT reported accepting, not one the decorator kept for itself",
                    )
                },
                {
                    assertEquals(
                        PEERS.toLong(),
                        health.framesWritten,
                        "one frame per peer, in the FIRST round only — later rounds wrote nothing at all, " +
                            "and a round that writes no frame cannot have added a byte",
                    )
                },
                {
                    assertEquals(
                        (offeredPerRound * (ROUNDS - 1)).toLong(),
                        health.opsDeduplicated,
                        "and the whole working set really was re-offered and suppressed on every later round",
                    )
                },
            )
        }

    /**
     * **The acceptance fixture's peers really are what those peers would have minted.**
     *
     * [gossipingPeers] re-keys one real log rather than minting [PEERS] of them, which is a fixture
     * optimisation standing on a claim about `Rga`: that an insert's `lamport` and `seq` are
     * derived from the minting replica's own counters and never from its [ReplicaId]. If that ever
     * stops being true the acceptance fixture silently stops being a fleet of real peers, and every
     * number it produces stops meaning what its KDoc says. So the claim is asserted here rather
     * than argued in a comment.
     *
     * Eight operations, not [OPS_PER_PEER]: the claim is per-operation, so length adds cost and no
     * coverage.
     */
    @Test
    fun everyPeersLogIsWhatThatPeerWouldReallyHaveMinted() {
        val author = ReplicaId("peer-7")

        val minted = liveLogOf(author, count = 8)
        val derived = reKeyed(liveLogOf(ReplicaId("peer-0"), count = 8), author)

        assertEquals(minted, derived, "re-keying peer 0's log must reproduce peer 7's exactly")
    }

    /**
     * Inserts offered **out of order** are archived once and suppressed thereafter — the frontier
     * closes the hole between them rather than treating it as a boundary.
     *
     * A merge hands the owner a peer's operations in no guaranteed order (`OpLogCrdt.operations()`
     * says so), so this is the ordinary path and not an edge case.
     *
     * **Mutation receipt:** deleting the `joinsBelow && joinsAbove` arm of `DotFrontier.add` leaves
     * this test green — the runs stay separate but still *contain* the dots — which is why
     * [DotFrontierTest.dotsArrivingOutOfOrderMergeIntoOneRunAsTheHoleCloses] asserts the run count
     * there rather than membership here. What this test pins is the decorator's side: that
     * out-of-order arrival is suppressed at all.
     */
    @Test
    fun insertsOfferedOutOfOrderAreArchivedOnceAndSuppressedAfterwards() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val bolt = newBolt()
            val decorator = decorate(bolt)
            val log = liveLogOf(alice, count = 3)

            decorator.publish(listOf(log[2]))
            decorator.publish(listOf(log[0]))
            decorator.publish(listOf(log[1]))
            val reOffered = decorator.publish(log)
            val archived = bolt.archivedOps()

            assertAll(
                { assertIs<AppendResult.Skipped>(reOffered, "every one of them is already held") },
                { assertEquals(log.toSet(), archived.toSet(), "each archived once") },
                { assertEquals(3L, decorator.health.value.framesWritten, "three frames, one per out-of-order offer") },
            )
        }

    /**
     * The frontier is **bounded** too, and a run pushed out of it is archived a second time rather
     * than silently dropped.
     *
     * The bound is in *runs*, not operations — `alice`'s whole log is one entry — so this is a far
     * larger fleet per unit memory than an identity window buys. But it is a bound, so the trade is
     * asserted rather than left as a comment.
     *
     * The victim is the **shortest** run: `bob` contributed one insert, `alice` two. Under gossip
     * every run is re-offered every round, so recency (the rule the removal window uses)
     * discriminates nothing, while length is exactly how many suppressed re-offers the entry buys.
     *
     * **Mutation receipt:** short-circuiting `DotFrontier.trim` (`if (runCount >= 0) return`) reds
     * the first two assertions — nothing is evicted, so `bob`'s insert is skipped as already-held
     * and never archived again.
     */
    @Test
    fun anInsertRunPushedOutOfTheFrontierIsArchivedAgainRatherThanLost() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val bolt = newBolt()
            val decorator = decorate(bolt, frontierWindow = 1)
            val aliceLog = liveLogOf(alice, count = 2)
            val bobLog = liveLogOf(bob, count = 1)

            decorator.publish(aliceLog)
            decorator.publish(bobLog)
            val bobAgain = decorator.publish(bobLog)
            val aliceAgain = decorator.publish(aliceLog)
            val archived = bolt.archivedOps()

            assertAll(
                { assertIs<AppendResult.Written>(bobAgain, "the shortest run was evicted, so it is archived again") },
                { assertEquals(4, archived.size, "three operations, one of them twice") },
                { assertIs<AppendResult.Skipped>(aliceAgain, "and the longer run is the one that survived") },
            )
        }

    /**
     * **Inserts do not touch the removal window at all** — they are suppressed with it turned off
     * entirely, and a remove is not.
     *
     * This is the residual, stated from both sides in one test. `removalWindow = 0` is the sharpest
     * possible statement that the two mechanisms are separate: if a single insert consumed a window
     * slot, a zero-sized window could suppress nothing at all.
     *
     * **Mutation receipt:** routing inserts through `ArchiveKey.Identity` reds the first assertion —
     * with no window to hold them, every insert is archived again on every round.
     */
    @Test
    fun insertsAreSuppressedWithNoRemovalWindowAtAll() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = newBolt()
        val decorator = decorate(bolt, removalWindow = 0)
        val inserts = liveLogOf(alice, count = 3)
        val removal = removalsOf(alice, count = 1)

        decorator.publish(inserts)
        val insertsAgain = decorator.publish(inserts)
        decorator.publish(removal)
        val removalAgain = decorator.publish(removal)
        val archived = bolt.archivedOps()

        assertAll(
            { assertIs<AppendResult.Skipped>(insertsAgain, "the frontier holds them with no window in sight") },
            {
                assertIs<AppendResult.Written>(
                    removalAgain,
                    "and a remove mints no dot, so with no window it is the residual — archived twice",
                )
            },
            { assertEquals(5, archived.size, "three inserts once, one removal twice") },
        )
    }

    // ── Removes: the residual, and the bounded window that holds it ───────────

    /**
     * The removal window is **bounded**, and an identity pushed out of it is archived a second time
     * rather than silently dropped.
     *
     * That is the trade this class makes deliberately, so it is asserted rather than left as a
     * comment: an unbounded set would be sized by an archive that is unbounded by construction —
     * a server holding a year of history would hold a year of identities in memory beside it. A
     * miss costs bytes, never correctness, because folding an op-log CRDT's operation twice is
     * idempotent.
     *
     * **Removes, not inserts.** An insert is recognised by the dot it mints and never reaches this
     * window; a remove mints none, which is why the window still exists. Testing it with inserts —
     * as this test did before #2254 — would now pin nothing, because the frontier would suppress
     * them whatever the window did.
     *
     * The dropped identity is the **least recently offered** — see
     * [aReOfferedRemovalSurvivesEvictionWhileAnIdleOneDoesNot] for why that qualifier is the whole
     * property and not a synonym for "oldest".
     *
     * **Mutation receipt:** short-circuiting `trimToWindow` (`if (archived.size >= 0) return`) reds
     * this test — the old identity stays remembered, so the re-publish is skipped.
     */
    @Test
    fun aRemovalPushedOutOfTheWindowIsArchivedAgainRatherThanLost() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val bolt = newBolt()
            val decorator = decorate(bolt, removalWindow = 1)
            val (old, new) = removalsOf(alice, count = 2)

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
     * **The removal window is LRU, not FIFO** — a re-offered identity is refreshed and survives; an
     * idle one is evicted in its place.
     *
     * This is the property the removal half of the suppression design rests on, and it is invisible
     * at any window size big enough to hold the fixture: FIFO and LRU agree until something is
     * actually evicted, which is why this runs at `removalWindow = 2` and steps one identity over
     * the edge on purpose.
     *
     * Under FIFO the archive grows by a full copy of every peer's tombstones every
     * `removalWindow / rate` rounds — `LinkedHashSet.add` on a present element returns `false` and
     * does **not** reorder it, so a peer's identities march toward the head on a clock set by
     * *everybody else's* traffic and are evicted while that peer is still re-offering every one of
     * them on every round. That is "growth proportional to time spent gossiping", the exact failure
     * #2216 names.
     *
     * The sequence: claim A, claim B, **re-offer A** (a hit, which must refresh it), claim C — the
     * eviction. LRU evicts B; FIFO evicts A.
     *
     * **Mutation receipt:** replacing `BoltDecorator.claimIdentity`'s body with a bare
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
    fun aReOfferedRemovalSurvivesEvictionWhileAnIdleOneDoesNot() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val bolt = newBolt()
            val decorator = decorate(bolt, removalWindow = 2)
            val (a, b, c) = removalsOf(alice, count = 3).let { Triple(it[0], it[1], it[2]) }

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
     * One author's live log: [count] inserts, minted by appending to a real [Rga].
     *
     * Real operations rather than hand-built `RgaOp.Insert` values, because the whole suppression
     * story rests on how `Rga` mints a dot — dense, contiguous, starting at 1 — and a fixture that
     * asserted that shape itself would be checking its own arithmetic. `insertAfter` is the O(1)
     * append path, which is what keeps [OPS_PER_PEER] affordable.
     */
    private fun liveLogOf(author: ReplicaId, count: Int): List<RgaOp<String>> {
        var replica = Rga.empty<String>()
        var last = RgaId.HEAD
        return List(count) { i ->
            val (next, op) = replica.insertAfter(author, last, "record-$i")
            replica = next
            last = op.id
            op
        }
    }

    /**
     * The acceptance fixture's fleet: [PEERS] peers, each holding [OPS_PER_PEER] inserts of its own.
     *
     * Peer 0's log is minted for real; every other peer's is that log **re-keyed** to its own
     * [ReplicaId]. That is not a shortcut around real operations — it is *exactly* what each peer
     * would mint. `Rga.mintInsert` derives an insert's `lamport` from the replica's own local
     * counter (`lamport + 1`) and its `seq` from its own per-author counter, neither of which reads
     * the `ReplicaId`. So [PEERS] replicas that each appended [OPS_PER_PEER] records without ever
     * merging — the state a mesh is in immediately before its first anti-entropy round — produce
     * byte-identical operations up to the id. [everyPeersLogIsWhatThatPeerWouldReallyHaveMinted]
     * asserts that rather than asking anyone to believe it.
     *
     * What it buys is time: minting is O(n²) in the *source* `Rga` (each mint copies its op-set), so
     * doing it [PEERS] times over rather than once is the fixture's dominant cost for no added
     * fidelity.
     */
    private fun gossipingPeers(): List<List<RgaOp<String>>> {
        val base = liveLogOf(ReplicaId("peer-0"), OPS_PER_PEER)
        return List(PEERS) { peer -> if (peer == 0) base else reKeyed(base, ReplicaId("peer-$peer")) }
    }

    /** [log] as [author] would have minted it — every id, and every `after` link, re-pointed. */
    private fun reKeyed(log: List<RgaOp<String>>, author: ReplicaId): List<RgaOp<String>> =
        log.map { op ->
            val insert = assertIs<RgaOp.Insert<String>>(op, "the acceptance fixture is inserts only")
            insert.copy(
                id = insert.id.copy(replicaId = author),
                // HEAD is the shared sentinel, not anybody's dot, so it must NOT be re-keyed.
                after = if (insert.after == RgaId.HEAD) RgaId.HEAD else insert.after.copy(replicaId = author),
            )
        }

    /**
     * [count] distinct removals by one author — the residual's fixture.
     *
     * Each tombstones a different element, so the [us.tractat.kuilt.crdt.LogOp.Remove] identities
     * are distinct while none of them mints a dot. That is exactly the population the bounded window
     * still has to hold.
     */
    private fun removalsOf(author: ReplicaId, count: Int): List<RgaOp<String>> {
        var replica = Rga.empty<String>()
        var last = RgaId.HEAD
        repeat(count) { i ->
            val (next, op) = replica.insertAfter(author, last, "doomed-$i")
            replica = next
            last = op.id
        }
        return List(count) {
            val (next, removal) = assertNotNull(replica.removeAt(0), "an element must remain to tombstone")
            replica = next
            removal
        }
    }

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

    private companion object {
        /**
         * How many gossiping peers the acceptance fixture stands up.
         *
         * Also the `frontierWindow` it runs at — one run per author, not one entry spare. Raising
         * one without the other loosens the rig rather than strengthening it.
         *
         * The decorator only ever sees the **aggregate** — precisely the quantity a shared window
         * bounds, and the quantity #2254 is about — so `PEERS × OPS_PER_PEER` is the number that has
         * to be big, and how it factorises is the fixture's business. That a single entry can cover
         * a *long* run is pinned where it is cheap:
         * [DotFrontierTest.aDenseRunOfSeqsCostsOneEntryHoweverManyDotsItCovers], at ten thousand dots.
         */
        const val PEERS = 80

        /**
         * How many inserts each peer re-offers every round.
         *
         * Its job, with [PEERS], is to put the aggregate above `DEFAULT_REMOVAL_WINDOW` — the
         * *shipped* window, not just the small one this test runs at. Shrinking either is what would
         * make the acceptance test vacuous, which is why the test asserts that threshold out loud
         * before asserting anything else.
         */
        const val OPS_PER_PEER = 1_000

        /**
         * How many anti-entropy rounds re-offer the whole working set.
         *
         * **Three, not two, and the third one is not margin.** Two rounds prove an operation is
         * suppressed *once*; they cannot distinguish that from a frontier that suppresses a round
         * and then forgets — round two would be `Skipped` either way and the loss would only show
         * in round three. The rounds after the first are also structurally the cheapest part of
         * this test: every publish is `Skipped`, so they classify and look up and encode nothing.
         * There is no runtime to buy by dropping one.
         */
        const val ROUNDS = 3

        /**
         * The identity window the acceptance fixture runs at — deliberately far too small to hold
         * the offered set.
         *
         * Not zero: a zero-sized window could be dismissed as a special case in the code, while a
         * small one is the ordinary path, thrashing exactly as `DEFAULT_REMOVAL_WINDOW` would at
         * fleet scale. What it switches off is any possibility that the window, rather than the
         * frontier, is what suppressed anything.
         */
        const val ISOLATING_REMOVAL_WINDOW = 16
    }
}
