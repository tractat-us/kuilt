package us.tractat.kuilt.bolt

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.crdt.VersionVector
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The contract every [Bolt] backend must satisfy. Subclass and implement [newBolt].
 *
 * Seven properties, and the second one is the reason the module exists:
 *
 * 1. **Round-trip** — appended ops replay identically, in order, at contiguous offsets.
 * 2. **The firewall** — a bolt fed a `Compact` keeps the ops that `Compact` suppresses and never
 *    replays the `Compact` itself. Mutation-checked; see
 *    [aBoltDiscardsCompactionRecordsAndKeepsWhatTheySuppress].
 * 3. **Asymmetric retention** — a windowed live replica forgets; the bolt beside it does not.
 * 4. **Scoped replay** — by offset, by arrival time, and by insert dots (inserts *only*).
 * 5. **An append with no content writes no frame.**
 * 6. **[Bolt.availability] is honest** — `Available` means an append will be accepted.
 * 7. **[Bolt.durability] is honest** — and *relative*: a backend that promised nothing is never
 *    degraded, and one that promised to flush every record says so when it could not.
 *
 * Fixtures are built from [Rga] because it is the op-log CRDT with the harder shape: it carries a
 * `compactedBelow` floor that `Fugue` has no equivalent of, so it can forget an op with no
 * `Compact` naming it. Backend-independent behaviour over `Fugue` ops is pinned separately,
 * outside this suite.
 */
abstract class BoltConformanceSuite {

    /**
     * A fresh, empty bolt reading its arrival timestamps from [clock].
     *
     * The clock is a parameter rather than a default because property 4 scopes a replay by arrival
     * time, which a backend reaching for the wall clock could not be asked about deterministically.
     */
    protected abstract fun newBolt(clock: Clock): Bolt<RgaOp<String>>

    /**
     * A bolt of this backend that is **already out of room** — a full archive, a read-only volume,
     * a runtime with no filesystem. Whatever exhaustion means for this backend, produced
     * deterministically.
     *
     * Deliberately **not nullable.** An "I cannot be exhausted" opt-out would put the vacuity this
     * obligation exists to remove one level up, where it is harder to see: the suite would go green
     * for a backend that never exercised the exhausted path at all. Every backend can produce one —
     * a byte budget, a tiny file cap, or (for a runtime with no storage) its ordinary state.
     *
     * The obligation asserts the *precondition* too, so a backend that returns a healthy bolt here
     * fails loudly rather than passing quietly.
     */
    protected abstract fun newExhaustedBolt(clock: Clock): Bolt<RgaOp<String>>

    /**
     * A bolt of this backend whose archive holds exactly [intactFrames] readable frames and is then
     * **damaged** — a crash mid-append, a pre-allocated region never written, bit-rot. Whatever a
     * torn archive looks like for this backend, produced deterministically.
     *
     * Non-nullable for the same reason [newExhaustedBolt] is, and the vacuity it removes is larger:
     * every path a *consumer* can reach on any backend writes whole frames after a whole header, so
     * without this hook [Truncated] is never constructed by any test in the tree — the verdict, its
     * offset, and the decision to stop the entire replay at it are all unasserted.
     *
     * **The damage must be followed by a HEALTHY segment** (or equivalent readable region) wherever
     * the backend has more than one. That detail carries the property's discriminating power against
     * the *plausible* mutation rather than the clumsy one — see
     * [aTruncatedArchiveStopsAtTheDamageAndSaysSo]. A fixture whose damage is last leaves "stop the
     * replay" and "skip to the next region, keeping the verdict" emitting identical events.
     */
    protected abstract suspend fun newTruncatedBolt(clock: Clock, intactFrames: Int): Bolt<RgaOp<String>>

    /**
     * A bolt of this backend whose archive is missing a whole region out of the **middle** of its
     * offset space, with exactly [intactFrames] readable frames ahead of the hole — a deleted segment
     * file, a segment that never reached disk, a region pre-allocated and never written. Whatever
     * losing a middle looks like for this backend, produced deterministically.
     *
     * Non-nullable for the same reason [newExhaustedBolt] and [newTruncatedBolt] are, and the vacuity
     * it removes is the one that shipped this defect twice. [newTruncatedBolt]'s damage is always
     * *within* a frame, so nothing in this suite ever produced an archive whose **segment sequence
     * itself** is discontinuous — and the reference backend's segments are an in-process list that
     * cannot lose an element, so it satisfied that silence for free. Two disk-backed backends were
     * then written independently against this suite and **both** replayed a hole as a [CleanTail]
     * (#2240). The general form is worth stating: *a conformance property is only as strong as the
     * weakest failure the reference implementation can reach.*
     *
     * **Frames must survive BEHIND the hole**, and here that is structural rather than merely
     * discriminating. A hole is seen by comparing a segment header's absolute base offset against
     * where the previous segment stopped, so an archive that simply ends early has no header left to
     * disagree with anything and is not discontinuous at all — it is just shorter. Frames behind the
     * hole are also what keeps "stop at the hole" and "step over the hole" from emitting identical
     * events, exactly as [newTruncatedBolt]'s KDoc argues one level down.
     *
     * **The segment before the hole must end the way this backend's ORDINARY segments end** —
     * pre-allocated tail included, if this backend pre-allocates. A fixture is free to choose a
     * segment budget, and the cheap choice (one frame per segment, sized to fit it) produces a
     * segment with **no zero tail**, whose parse therefore runs out of bytes and exits the frame loop
     * normally. That configuration walks straight past a backend's zero-tail/recorded-extent
     * machinery, so a backend that answers the wrong *reason* there goes green — which is #2240's own
     * thesis recurring one level down, the fixture picking the configuration in which the failure
     * cannot occur. A budget of one frame plus a small pad gives a real tail; drive both if the
     * backend distinguishes them.
     *
     * The obligation asserts its own precondition, so a backend that returns a healthy archive — or
     * one damaged some other way — fails loudly rather than passing quietly.
     *
     * **It hands back [DiscontinuousFixture.beyondTheHole] as well as the bolt, and that is the one
     * thing the suite cannot work out for itself.** Every scope this contract offers scans forward from
     * the archive's oldest surviving segment, so every one of them *stops at the hole* — there is no
     * call a conformance test can make that names an offset on the far side of it. Only the fixture,
     * which wrote those frames, knows. See
     * [resumingFromBeyondTheHoleReachesTheSameVerdictRatherThanACleanTail] for what it buys and what
     * the suite can and cannot check about it.
     *
     * **[lostSegments] is a parameter rather than a literal in each fixture, and that is the whole of
     * #2331's second gap.** A hole is observable through [Bolt] only as two offsets, so no assertion
     * here can tell one destroyed segment from four — which means a fixture left to choose for itself
     * chooses one, and a backend whose continuity check happens to recover across a *wider* gap than a
     * single segment passes. Threading the count through the hook drives the same fixture code at both
     * widths instead of adding a second fixture a subclass could get differently wrong, exactly as
     * `zeroTailBytes` is threaded through the mmap fixtures rather than hardcoded per subclass. The
     * fixture answers with [DiscontinuousFixture.lostFrameCount] appends as its evidence, and the
     * suite checks that against what it asked for.
     */
    protected abstract suspend fun newDiscontinuousBolt(
        clock: Clock,
        intactFrames: Int,
        lostSegments: Int,
    ): DiscontinuousFixture

    /**
     * A bolt of this backend whose segment sequence runs **backwards**: after exactly [intactFrames]
     * readable frames, a segment whose header claims an absolute `baseOffset` *below* where its
     * predecessor's frames ended — a stale segment file restored over a newer one, a directory
     * holding two generations of the same archive, a snapshot rolled back under a live writer.
     * Whatever doubling back looks like for this backend, produced deterministically.
     *
     * Non-nullable for the reason the three hooks above are, and here the vacuity it removes is
     * narrow and sharp rather than broad. [newDiscontinuousBolt] can make the gap as wide as you like
     * and can never make it *negative*, so every archive in this tree agrees that a successor's base
     * offset is too **high** — and a backend spelling its continuity check `header.baseOffset >
     * resumeOffset`, which reads perfectly and is how most people would write "this segment starts too
     * far along", is green across the entire suite. The archive it then hands back is not merely
     * short: it replays records a consumer has already consumed, at offsets already handed out, which
     * is the failure [Bolt] exists to make impossible rather than a milder version of the hole.
     *
     * **Frames must survive behind the jump**, for the reason [newTruncatedBolt] gives one level down:
     * without them, "stop at the jump" and "step over it" emit identical events. Here they also carry
     * the harm — the frames behind a backwards jump are *duplicates*, so a backend that steps over one
     * emits a record twice rather than merely skipping some.
     *
     * **The jump must go back past a WHOLE frame**, not to a boundary inside the last one, and
     * [BackwardsJumpFixture] refuses to be built otherwise. That is what lets the suite verify the
     * fixture's account instead of trusting it: a frame the replay already emitted must be sitting at
     * [BackwardsJumpFixture.jumpsBackTo].
     *
     * The obligation asserts its own precondition, so a backend that returns a healthy archive — or
     * one damaged some other way, a forward gap in particular — fails loudly rather than passing
     * quietly.
     */
    protected abstract suspend fun newBackwardsJumpBolt(clock: Clock, intactFrames: Int): BackwardsJumpFixture

    /**
     * A bolt of this backend whose durability operation **cannot succeed** — its `msync`, its
     * `force`, whatever this backend flushes with — in whichever configuration this subclass is
     * testing, together with this backend's own statement of what that configuration *promised*.
     *
     * ### Why this one is not simply non-nullable, when the three above are
     *
     * [newExhaustedBolt], [newTruncatedBolt] and [newDiscontinuousBolt] are non-nullable because an
     * "I cannot reach this state" opt-out moves the vacuity one level up, where it is harder to see.
     * **That reasoning does not transfer here unmodified**, and getting it wrong in either direction
     * is worse than the hook it would produce.
     *
     * [Bolt.durability] is **relative**: it reports whether a backend is meeting the level *it*
     * promised. A backend that promised nothing — an in-memory archive; a mapped one told to let the
     * operating system flush when it likes — is [DurabilityState.AsPromised] forever, and that is not
     * an opt-out, it is the contract being satisfied. A non-nullable hook would demand a degraded bolt
     * from a backend for which no such bolt can correctly exist, and it would be *right* to fail. A
     * plain nullable hook would go the other way and hand every backend the silent skip the other
     * three obligations exist to remove.
     *
     * So the subclass **declares** which of three cases it is, and every one of them carries
     * assertions:
     *
     * - [DurabilityFixture.Promised] — promised per-record durability, cannot deliver it, must
     *   report [DurabilityState.Degraded];
     * - [DurabilityFixture.PromisedNothingButFlushes] — promised nothing, **still attempts a flush**,
     *   which is rigged to fail; must report [DurabilityState.AsPromised], and must **prove the rig
     *   fired**;
     * - [DurabilityFixture.PromisedNothingAndNeverFlushes] — promised nothing and issues no flush at
     *   any configuration, so there is nothing to rig; must report [DurabilityState.AsPromised].
     *
     * The declaration is a claim, not a skip: an all-red table is not what makes the last two arms
     * strong, their own assertions are.
     *
     * **What this cannot detect, said plainly, and what the third arm narrows.** A backend that offers
     * a durability upgrade and declares one of the promised-nothing arms anyway is invisible here —
     * there is no `durabilityLevel()` on [Bolt] to check the claim against, and inventing one to make
     * a test checkable would put a knob in the contract that no consumer asked for. What the split
     * *does* buy is that a backend which **flushes** can no longer sit in the arm that asks nothing of
     * it: [DurabilityFixture.PromisedNothingButFlushes] makes it prove the flush happened and failed,
     * so "records a flush it never promised" becomes a catchable bug rather than an unasserted one.
     * The residual is narrower, not gone.
     *
     * ### And the configuration must be one in which the failure can occur
     *
     * The trap #2240 named, one level down again: a fixture that picks the configuration where the
     * property cannot fail passes for free. Two shapes of it live here, and this suite now catches
     * both. A subclass handing back an asynchronous bolt under [DurabilityFixture.Promised] fails the
     * precondition below. And a subclass claiming [DurabilityFixture.PromisedNothingButFlushes] over a
     * segment budget at which nothing ever rolls — so the only ungated flush never happens — fails its
     * `attemptedFlushes` assertion. **That second one was a live hole in this file**, closed by the
     * arm rather than by choosing a better literal, because the literal was never the thing under
     * assertion.
     */
    protected abstract fun newBoltThatCannotFlush(clock: Clock): DurabilityFixture

    private val alice = ReplicaId("alice")
    private val bob = ReplicaId("bob")
    private val epoch = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    // ── 1. Round-trip ─────────────────────────────────────────────────────────

    @Test
    fun appendedOpsReplayIdenticallyAndInOrder() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = newBolt(FixedClock(epoch))
        val (afterFirst, first) = Rga.empty<String>().mintInserts("first")
        val (_, second) = afterFirst.mintInserts("second")

        val one = bolt.append(first)
        val two = bolt.append(second)
        val frames = bolt.replay(ReplayScope.All).frames().toList()

        assertAll(
            { assertIs<AppendResult.Written>(one, "a content append must be written") },
            { assertIs<AppendResult.Written>(two, "a content append must be written") },
            { assertEquals(2, frames.size, "one frame per append") },
            { assertEquals(first, frames[0].ops, "frame 1 replays its ops, in order") },
            { assertEquals(second, frames[1].ops, "frame 2 replays its ops, in order") },
            { assertEquals(0L, frames[0].offset, "the first frame starts the offset space at 0") },
            {
                assertEquals(
                    frames[0].endOffset,
                    frames[1].offset,
                    "offsets are contiguous — one frame's end is the next frame's start",
                )
            },
            { assertTrue(frames[1].endOffset > frames[1].offset, "a frame occupies a non-empty range") },
            {
                assertEquals(
                    first.map { it.id.dot }.toSet(),
                    frames[0].insertDots,
                    "the frame carries the dots its inserts minted",
                )
            },
            { assertEquals(null, frames[0].key, "the key slot is reserved, and unwritten in v1") },
        )
    }

    // ── 2. The firewall ───────────────────────────────────────────────────────

    /**
     * **The safety property of the whole module.** A bolt is fed content ops and then the
     * `RgaOp.Compact` that suppresses one of them. It must keep the suppressed content and drop the
     * compaction record — that asymmetry is the only thing letting an archive outlive its source's
     * forgetting.
     *
     * **Mutation receipt:** deleting the `Compact` filter in `BoltArchiveFormat.contentOnly` reddens
     * the first assertion below, because the replayed op list gains the `Compact`.
     *
     * **Fixture hazard this test is written around.** Both `Rga` and `Fugue` silently return `null`
     * from `compact()` for an id a live insert still anchors — `after` for `Rga`. A fixture that
     * compacted the *leading* element of a two-element sequence would get no compaction at all, and
     * because `compact` returns `null` rather than throwing, this test would then run against an
     * **uncompacted** log and pass even with the discard removed — the one outcome its mutation
     * check exists to prevent. [rgaWithACompaction] therefore compacts the **trailing** element and
     * asserts the result is non-null.
     */
    @Test
    fun aBoltDiscardsCompactionRecordsAndKeepsWhatTheySuppress() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = newBolt(FixedClock(epoch))
        val fixture = rgaWithACompaction()

        bolt.append(fixture.contentOps)
        val compactAppend = bolt.append(listOf(fixture.compactOp))
        val archived = bolt.replay(ReplayScope.All).frames().toList().flatMap { it.ops }
        val stillLive = fixture.compacted.operations().toList()

        assertAll(
            {
                assertEquals(
                    fixture.contentOps,
                    archived,
                    "the archive keeps every content op, in order, and no compaction record",
                )
            },
            { assertTrue(archived.none { it is RgaOp.Compact }, "a Compact must never reach the archive") },
            {
                assertIs<AppendResult.Skipped>(
                    compactAppend,
                    "an append of nothing but compaction records writes no frame",
                )
            },
            // The other half of the asymmetry: the source really did forget.
            {
                assertTrue(
                    stillLive.none { it == fixture.suppressedInsert || it == fixture.suppressedRemove },
                    "the live replica dropped the ops its own compaction suppressed",
                )
            },
            {
                assertTrue(
                    archived.containsAll(listOf(fixture.suppressedInsert, fixture.suppressedRemove)),
                    "and the archive still holds them — the capability the module exists for",
                )
            },
        )
    }

    // ── 3. Asymmetric retention, end to end ───────────────────────────────────

    /**
     * The headline capability, pinned directly rather than inferred: a live replica windowed down
     * to its newest records, and a bolt beside it that still replays every one it was fed.
     *
     * Windowing via `Rga.dropWindow` is the harder half of the `Rga`/`Fugue` asymmetry — it raises
     * the `compactedBelow` floor and records **no** `Compact` op naming what it dropped. So a bolt
     * that tried to reconstruct history from a live replica afterwards would find nothing to
     * reconstruct, which is exactly why a bolt is fed at append time instead.
     */
    @Test
    fun aWindowedReplicaForgetsWhileTheBoltBesideItDoesNot() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = newBolt(FixedClock(epoch))
        val (live, inserts) = Rga.empty<String>().mintInserts("record", count = RECORDS)
        bolt.append(inserts)

        val dropped = inserts.take(WINDOW_DROP).map { it.id }.toSet()
        val (windowed, _) = assertNotNull(live.dropWindow(alice, dropped), "dropWindow takes a non-empty set")
        val survivingIds = windowed.operations().filterIsInstance<RgaOp.Insert<String>>().map { it.id }.toSet()
        val archivedIds = bolt.replay(ReplayScope.All).frames().toList()
            .flatMap { it.ops }
            .filterIsInstance<RgaOp.Insert<String>>()
            .map { it.id }
            .toSet()

        assertAll(
            { assertEquals(RECORDS - WINDOW_DROP, windowed.size, "the live replica kept only its window") },
            { assertTrue(survivingIds.intersect(dropped).isEmpty(), "the live replica really forgot them") },
            { assertTrue(archivedIds.containsAll(dropped), "the bolt still replays every windowed-away record") },
            { assertEquals(inserts.map { it.id }.toSet(), archivedIds, "and everything else it was fed") },
        )
    }

    // ── 4. Scoped replay ──────────────────────────────────────────────────────

    @Test
    fun replayFromAnOffsetResumesExactlyWhereTheCursorLeftOff() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = newBolt(FixedClock(epoch))
        val (r1, one) = Rga.empty<String>().mintInserts("one")
        val (r2, two) = r1.mintInserts("two")
        val (_, three) = r2.mintInserts("three")
        val written = listOf(one, two, three).map { assertIs<AppendResult.Written>(bolt.append(it)) }

        val fromSecond = bolt.replay(ReplayScope.FromOffset(written[0].endOffset)).frames().toList()
        val fromThird = bolt.replay(ReplayScope.FromOffset(written[1].endOffset)).frames().toList()
        val fromTail = bolt.replay(ReplayScope.FromOffset(written[2].endOffset)).frames().toList()
        val midFrame = bolt.replay(ReplayScope.FromOffset(written[1].offset + 1)).frames().toList()

        assertAll(
            { assertEquals(listOf(two, three), fromSecond.map { it.ops }, "resume skips consumed frames") },
            { assertEquals(listOf(three), fromThird.map { it.ops }, "and again") },
            { assertTrue(fromTail.isEmpty(), "a cursor at the tail yields nothing until the next append") },
            {
                assertEquals(
                    listOf(two, three),
                    midFrame.map { it.ops },
                    "an offset inside a frame yields it whole — a cursor never points at half a record",
                )
            },
        )
    }

    @Test
    fun replayScopedByArrivalTimeReturnsExactlyTheFramesInRange() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val clock = MutableClock(epoch)
        val bolt = newBolt(clock)
        val (r1, early) = Rga.empty<String>().mintInserts("early")
        val (r2, middle) = r1.mintInserts("middle")
        val (_, late) = r2.mintInserts("late")

        bolt.append(early)
        clock.advanceBy(STEP)
        bolt.append(middle)
        clock.advanceBy(STEP)
        bolt.append(late)
        val inWindow = bolt.replay(ReplayScope.Arrived(epoch + STEP, epoch + STEP * 2)).frames().toList()
        val atStart = bolt.replay(ReplayScope.Arrived(epoch, epoch + STEP)).frames().toList()
        val all = bolt.replay(ReplayScope.All).frames().toList()

        assertAll(
            { assertEquals(listOf(middle), inWindow.map { it.ops }, "half-open: [from, untilExclusive)") },
            { assertEquals(listOf(early), atStart.map { it.ops }, "the lower bound is inclusive") },
            {
                assertEquals(
                    listOf(epoch, epoch + STEP, epoch + STEP * 2),
                    all.map { it.arrivedAt },
                    "every frame is stamped from the injected clock, never a wall clock",
                )
            },
        )
    }

    /**
     * Dot-scoped replay is **insert-only**, and this pins both halves of that: a frame is selected
     * by the inserts it carries, and a frame of pure removes is selected by **no** floor, not even
     * the empty one.
     *
     * That is the format's promise, not a gap. A `Remove` mints no dot — it reuses its target
     * `Insert`'s id, and it arrives arbitrarily later than that insert. A frame of removes could
     * only claim its targets' *old* dots, in which case a resume-from-dot cursor would skip the
     * frame and replay a removed record as live. So dots are informational, the append offset is
     * the resume cursor, and a test that scoped removes by dot would be testing something this
     * format deliberately does not offer.
     */
    @Test
    fun dotScopedReplaySelectsInsertsOnlyAndNeverAFrameOfRemoves() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = newBolt(FixedClock(epoch))
        val (afterEarly, early) = Rga.empty<String>().mintInserts("early", count = 2)
        val (afterLate, late) = afterEarly.insertAt(alice, afterEarly.size, "late")
        val (_, removal) = assertNotNull(afterLate.removeAt(0), "removeAt(0) must find a visible element")

        bolt.append(early)
        val lateFrame = assertIs<AppendResult.Written>(bolt.append(listOf(late)))
        val removeFrame = assertIs<AppendResult.Written>(bolt.append(listOf(removal)))
        val aboveTwo = bolt.replay(ReplayScope.InsertsAbove(VersionVector.of(mapOf(alice to 2L)))).frames().toList()
        val aboveNothing = bolt.replay(ReplayScope.InsertsAbove(VersionVector.EMPTY)).frames().toList()
        val byOffset = bolt.replay(ReplayScope.FromOffset(removeFrame.offset)).frames().toList()

        assertAll(
            { assertEquals(listOf(listOf(late)), aboveTwo.map { it.ops }, "only the frame above the floor") },
            {
                assertEquals(
                    listOf(early, listOf(late)),
                    aboveNothing.map { it.ops },
                    "an empty floor selects every frame carrying an insert — and only those",
                )
            },
            { assertEquals(emptySet<Dot>(), removeFrame.insertDots, "a frame of removes mints no dots") },
            { assertEquals(listOf(listOf(removal)), byOffset.map { it.ops }, "removes are reachable — by OFFSET") },
            { assertTrue(lateFrame.insertDots.isNotEmpty(), "the insert frame does carry dots, or the above is empty") },
        )
    }

    // ── 5. An append with no content writes no frame ──────────────────────────

    @Test
    fun anAppendWithNoContentWritesNoFrame() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = newBolt(FixedClock(epoch))
        val fixture = rgaWithACompaction()

        val empty = bolt.append(emptyList())
        val onlyCompaction = bolt.append(listOf(fixture.compactOp))
        val afterBoth = bolt.replay(ReplayScope.All).frames().toList()
        val real = bolt.append(fixture.contentOps)
        val afterReal = bolt.replay(ReplayScope.All).frames().toList()

        assertAll(
            { assertIs<AppendResult.Skipped>(empty, "an empty append is a no-op") },
            { assertIs<AppendResult.Skipped>(onlyCompaction, "so is an append of nothing but compaction records") },
            { assertTrue(afterBoth.isEmpty(), "neither wrote a frame") },
            { assertEquals(0L, assertIs<AppendResult.Written>(real).offset, "and neither consumed offset space") },
            { assertEquals(1, afterReal.size, "the first real append is the archive's first frame") },
        )
    }

    // ── 6. availability() is honest ───────────────────────────────────────────

    /**
     * The **healthy-state** half of the availability contract, and on its own it is weak: a bolt
     * built by [newBolt] is writable on every backend in the tree, so only the `Available` branch is
     * ever taken and the assertion it makes duplicates property 1.
     *
     * Said plainly because the vacuity is the point: the half that discriminates is
     * [anExhaustedBoltReportsUnavailableAndRefusesTheAppend], which drives the *other* state. Keep
     * them together — this one alone would go green against a bolt that reported `Available` while
     * every append failed, which is exactly the bug the pair exists to catch.
     */
    @Test
    fun availabilityAgreesWithWhetherAnAppendIsAccepted() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = newBolt(FixedClock(epoch))
        val availability = bolt.availability()
        val (_, ops) = Rga.empty<String>().mintInserts("probe")
        val result = bolt.append(ops)

        assertAll(
            {
                when (availability) {
                    BoltAvailability.Available ->
                        assertIs<AppendResult.Written>(result, "Available must mean an append is accepted")
                    is BoltAvailability.Unavailable ->
                        assertIs<AppendResult.Failed>(result, "Unavailable must not then claim a write")
                    is BoltAvailability.Unknown ->
                        assertTrue(result !is AppendResult.Skipped, "content was offered, so this is not a no-op")
                }
            },
        )
    }

    /**
     * The half of the availability contract that can fail: a bolt with no room must **say so** and
     * must **refuse**, reporting the identities it could not keep.
     *
     * This is the obligation that catches "reports `Available` while every append fails". It is a
     * conformance property rather than a backend test because exhaustion is where the backends
     * differ most — a byte budget in memory, a full disk under mmap, no filesystem at all on
     * wasm — and it is the one state where a wrong answer costs records permanently: the live
     * replica windows them away next, so a lost append is lost from both sides.
     */
    @Test
    fun anExhaustedBoltReportsUnavailableAndRefusesTheAppend() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = newExhaustedBolt(FixedClock(epoch))
        val (_, ops) = Rga.empty<String>().mintInserts("probe")

        val availability = bolt.availability()
        val result = bolt.append(ops)
        val archived = bolt.replay(ReplayScope.All).frames().toList()

        assertAll(
            {
                assertIs<BoltAvailability.Unavailable>(
                    availability,
                    "newExhaustedBolt must hand back a bolt that reports itself unusable — returning a " +
                        "healthy one makes this obligation vacuous, so it fails here rather than passing",
                )
            },
            { assertIs<AppendResult.Failed>(result, "and it must refuse the append rather than claim a write") },
            {
                assertEquals(
                    ops.map { it.id.dot }.toSet(),
                    assertIs<AppendResult.Failed>(result).insertDots,
                    "reporting WHICH records it lost — a tally makes every recovery unimplementable",
                )
            },
            { assertTrue(archived.isEmpty(), "and leaving no partial frame behind") },
        )
    }

    /**
     * Every replay ends with exactly one verdict, and on an intact archive it is [CleanTail].
     *
     * Without this, a consumer cannot tell a complete history from one that stopped at damage — and
     * "I still hold what the live replica forgot" is the only thing a bolt sells.
     */
    @Test
    fun everyReplayEndsWithExactlyOneTerminalVerdict() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = newBolt(FixedClock(epoch))
        val (afterFirst, first) = Rga.empty<String>().mintInserts("first")
        val (_, second) = afterFirst.mintInserts("second")

        bolt.append(first)
        bolt.append(second)
        val events = bolt.replay(ReplayScope.All).toList()
        val empty = newBolt(FixedClock(epoch)).replay(ReplayScope.All).toList()

        assertAll(
            { assertEquals(CleanTail, events.lastOrNull(), "an intact archive ends clean") },
            { assertEquals(1, events.count { it !is Archived<*> }, "exactly one terminal event, never two") },
            { assertEquals(2, events.filterIsInstance<Archived<RgaOp<String>>>().size, "and the frames precede it") },
            { assertEquals(listOf(CleanTail), empty, "an empty archive is a clean tail, not a silent nothing") },
        )
    }

    /**
     * The **damaged-archive** half of the verdict contract, and the one that can fail.
     *
     * [everyReplayEndsWithExactlyOneTerminalVerdict] drives only the [CleanTail] arm, which every
     * backend reaches for free — so on its own it is the same vacuity property 6 had before the
     * exhaustion hook: the branch that discriminates is never taken. This drives the other arm.
     *
     * Four assertions, and each pins a decision nothing else in the tree does:
     *
     * 1. the verdict is [Truncated], not a clean tail — replay does not paper over damage;
     * 2. its `atOffset` is the last intact frame's `endOffset` — it is a *resume cursor*, so a
     *    consumer can re-read from exactly there once the writer catches up;
     * 3. **no frame follows it**, and none from a later, healthy region either — a torn segment
     *    stops the WHOLE replay rather than skipping ahead and handing back a history with a silent
     *    hole in it and offsets that jump;
     * 4. every frame before the damage survives — the intact prefix is not discarded over a bad tail.
     *
     * **Mutation receipts.** Turning the stop into a bare `continue` reddens (1) and (4) — the
     * verdict becomes [CleanTail] and a frame from beyond the damage replays. Zeroing
     * `Truncated.atOffset` reddens (2). And the one the fixture's shape exists for: hoisting the
     * verdict out of the segment loop and `continue`-ing past a damaged segment emits exactly one
     * plausible-looking verdict, so it is **green** against a fixture whose damage is last and reds
     * only because the backend puts a healthy region behind it.
     */
    @Test
    fun aTruncatedArchiveStopsAtTheDamageAndSaysSo() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = newTruncatedBolt(FixedClock(epoch), INTACT_FRAMES)

        val events = bolt.replay(ReplayScope.All).toList()
        val frames = events.filterIsInstance<Archived<RgaOp<String>>>()
        val verdict = events.lastOrNull()

        assertAll(
            {
                assertIs<Truncated>(
                    verdict,
                    "newTruncatedBolt must hand back a DAMAGED archive — a healthy one makes this " +
                        "obligation vacuous, so it fails here rather than passing",
                )
            },
            { assertEquals(1, events.count { it !is Archived<*> }, "exactly one terminal event, never two") },
            {
                assertEquals(
                    INTACT_FRAMES,
                    frames.size,
                    "every frame before the damage survives — and nothing from beyond it is replayed, " +
                        "because a torn region stops the whole replay rather than leaving a silent hole",
                )
            },
            {
                assertEquals(
                    frames.last().endOffset,
                    assertIs<Truncated>(verdict).atOffset,
                    "the verdict stops at the last intact frame's end — it is a resume cursor",
                )
            },
            {
                assertEquals(
                    frames.size,
                    events.indexOfFirst { it !is Archived<*> },
                    "the verdict is LAST — every event before it is a frame, and none follows it",
                )
            },
        )
    }

    /**
     * The **discontinuous-archive** arm of the verdict contract, and the one no backend reached for
     * free.
     *
     * [aTruncatedArchiveStopsAtTheDamageAndSaysSo] drives damage *inside* a frame, which every layer
     * of the format is built to catch: a bad frame fails its own checksum. A hole between segments
     * presents **no bad bytes at all** — the surviving segments are perfectly intact, they simply do
     * not join up — so nothing checks it unless a backend compares each segment's absolute base
     * offset against where the previous one stopped. Two backends shipped without that check (#2240).
     *
     * Eight assertions, in the order they appear below, and the numbering the receipts use:
     *
     * 1. the verdict is [Truncated], not a clean tail — a hole is damage, not something to step over;
     * 2. exactly one terminal event;
     * 3. **nothing from beyond the hole** is replayed, however intact those frames are, and every frame
     *    before it survives — a history with a silent gap in it is the one thing [Bolt] cannot hand
     *    back;
     * 4. its `atOffset` is the last intact frame's `endOffset`, i.e. where the hole **starts** and not
     *    where the next segment picks up. Those differ by exactly the missing region, and the wrong
     *    one names an offset no frame this replay emitted ever ended at;
     * 5. its `reason` is [TruncationReason.MissingRegion] — the constant a consumer branches on to
     *    learn that `atOffset` is **not** somewhere to resume from, because the records between here
     *    and the next segment exist nowhere;
     * 6. the verdict is last;
     * 7. [ReplayScope.Arrived] reaches the **same verdict** — a hole is damage under a scope with no
     *    offset predicate at all;
     * 8. and so does [ReplayScope.InsertsAbove], which selects on a frame's *body* and so has even
     *    less occasion to look at where segments join.
     *
     * **Mutation receipts**, measured on this branch — each mutation applied alone, reverted, and the
     * verdict read out of the results XML rather than the console:
     *
     * | Mutation | Reds |
     * |---|---|
     * | Delete the cross-segment continuity check in `InMemoryBolt.emitFrames` | 1, 3, 4, 5 |
     * | Delete it in `MappedBolt.emitSegment` | 1, 3, 4, 5 |
     * | Delete it in `PosixMappedBolt.emitFrames` | 1, 3, 4, 5 |
     * | Report the *next* segment's `baseOffset` instead of the previous segment's end | 4 only |
     * | Report [TruncationReason.SegmentHeader] (what both mmap backends did, knowingly) | 5 only |
     * | Treat a **derived** segment extent as evidence (`PosixMappedBolt`) | 5 only — see below |
     * | Emit the verdict without stopping the replay | 1, 2, 4, 5 |
     * | **Fixture:** hand back a healthy archive (skip the `loseSegment`/`delete`) | 1, 3, 4, 5 |
     *
     * The first three rows are the finding: with the check gone, every backend replays a lost segment
     * as a [CleanTail] over a history with a gap in it. Two are not hypothetical — rows 1 and 3 are
     * `InMemoryBolt` and `PosixMappedBolt` exactly as they shipped. The two single-assertion rows are
     * what stop assertions 4 and 5 riding on 1. The last row is the vacuity guard: the precondition
     * fails first and loudest, so a backend that quietly returns a healthy bolt cannot go green.
     *
     * **Row 6 is why [newDiscontinuousBolt] mandates a realistic segment tail, and it is the sharpest
     * lesson here.** It reddens **only** on the subclasses whose fixture leaves a pre-allocated zero
     * tail behind the last frame, and is **green** on the one whose segments end exactly on a frame
     * boundary — because that configuration exits the parse loop without ever consulting the extent.
     * Every fixture in the first draft of this property was the second kind. So a real backend defect,
     * on a shipped configuration, sat under a table that was otherwise all red. And note its shape:
     * assertions 1–4 and 6 all still passed, only the *reason* was wrong, which is the least visible
     * red a conformance table can produce.
     *
     * **The green assertion, and what this property cannot reach** — said plainly, because an all-red
     * table invites exactly the wrong conclusion. **Assertion 6 was green under every mutation above.**
     * It is kept because it is the only one that would catch a replay emitting its verdict and then
     * more frames, and no mutation of the *current* code produces that shape (the verdict is a
     * `return`); its justification is inherited from [aTruncatedArchiveStopsAtTheDamageAndSaysSo],
     * which pins the same pair one level down.
     *
     * **Assertions 7 and 8 close a gap the first draft of this property left open**, and it was the
     * same shape as everything else here: the hole was only ever asked about under scopes that carry
     * an *offset*. A backend whose continuity check hung off the offset predicate — reporting the hole
     * to [ReplayScope.All] and [ReplayScope.FromOffset], stepping over it for [ReplayScope.Arrived] and
     * [ReplayScope.InsertsAbove] — was green across this entire suite. The three in-tree backends
     * avoid it only because the check sits in the shared per-segment path, which is an implementation
     * accident and exactly what #2247 exists to stop resting on. **Receipt:** gating the check on
     * `scope is All || scope is FromOffset` in `InMemoryBolt.emitFrames` reds **only** assertions 7 and
     * 8, on both `InMemoryBolt` subclasses, and nothing else in `:kuilt-bolt:jvmTest`.
     *
     * Beyond that, this drives **one shape of hole** — a single whole segment lost out of the middle.
     * [resumingFromTheHoleReachesTheSameVerdictRatherThanACleanTail] and
     * [resumingFromBeyondTheHoleReachesTheSameVerdictRatherThanACleanTail] drive the pruning scope at
     * the hole's two edges, and their KDoc lists what *they* cannot reach;
     * [aHoleSpanningSeveralLostSegmentsIsReportedAtItsStartLikeAnyOther] drives a wider one and
     * [anArchiveWhoseSegmentsDoubleBackStopsAtTheJumpAndSaysSo] the one shape that is not a gap at all
     * (#2331). Still unpinned anywhere: a hole in an archive whose surviving segments were written by
     * more than one format version.
     */
    @Test
    fun anArchiveMissingAMiddleRegionStopsAtTheHoleAndSaysSo() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = newDiscontinuousBolt(FixedClock(epoch), INTACT_FRAMES, ONE_LOST_SEGMENT).bolt

        val events = bolt.replay(ReplayScope.All).toList()
        val frames = events.filterIsInstance<Archived<RgaOp<String>>>()
        val verdict = events.lastOrNull()
        // The two scopes with no offset predicate. Both select every frame this fixture wrote — one
        // fixed clock, all inserts — so a correct backend owes them the identical verdict.
        val byArrival = bolt.replay(ReplayScope.Arrived(epoch, epoch + STEP)).toList()
        val byDots = bolt.replay(ReplayScope.InsertsAbove(VersionVector.EMPTY)).toList()

        assertAll(
            {
                assertIs<Truncated>(
                    verdict,
                    "newDiscontinuousBolt must hand back an archive with a HOLE in it — a healthy one " +
                        "makes this obligation vacuous, so it fails here rather than passing",
                )
            },
            { assertEquals(1, events.count { it !is Archived<*> }, "exactly one terminal event, never two") },
            {
                assertEquals(
                    INTACT_FRAMES,
                    frames.size,
                    "every frame before the hole survives — and nothing from beyond it is replayed, " +
                        "however intact those frames are, because a history with a silent gap in it and " +
                        "offsets that jump is worse than a short answer that says it is short",
                )
            },
            {
                assertEquals<Long?>(
                    frames.lastOrNull()?.endOffset,
                    assertIs<Truncated>(verdict).atOffset,
                    "the verdict stops where the hole STARTS — the last intact frame's end, not where " +
                        "the next segment picks up",
                )
            },
            {
                assertEquals(
                    TruncationReason.MissingRegion,
                    assertIs<Truncated>(verdict).reason,
                    "a region that is GONE is not a torn tail: the two have opposite remedies, and this " +
                        "is the one a consumer must not resume from",
                )
            },
            {
                assertEquals(
                    frames.size,
                    events.indexOfFirst { it !is Archived<*> },
                    "the verdict is LAST — every event before it is a frame, and none follows it",
                )
            },
            {
                assertEquals(
                    verdict,
                    byArrival.lastOrNull(),
                    "a hole is damage under EVERY scope, not only the ones carrying an offset — a " +
                        "backend whose continuity check hangs off the offset predicate steps over it " +
                        "here and is otherwise green across this whole suite",
                )
            },
            {
                assertEquals(
                    verdict,
                    byDots.lastOrNull(),
                    "and the same for dot-scoped replay, which selects by a frame's BODY and so has " +
                        "even less occasion to look at where segments join",
                )
            },
        )
    }

    /**
     * A resume from the hole's own offset must reach the **same verdict**, not a [CleanTail].
     *
     * [anArchiveMissingAMiddleRegionStopsAtTheHoleAndSaysSo] only ever asks with [ReplayScope.All],
     * and that is the scope under which a backend has the least excuse to miss a hole: it reads every
     * segment, so the boundary either lines up or it does not. [ReplayScope.FromOffset] is the
     * interesting one, and it is interesting for the worst possible reason — it is the **documented
     * resume cursor** (`ReplayScope.FromOffset`: "hand back `Archived.endOffset` of the last frame you
     * consumed"), so the offset a consumer hands back after the verdict above is *precisely* the
     * offset the missing region starts at. A backend that prunes whole segments below the cursor can
     * prune away the last segment before the hole — and with it the only boundary this scope had to
     * check — and then report a gapped history as complete to the one caller most likely to act on it.
     *
     * That is not hypothetical: `InMemoryBolt` did exactly this, replying `Truncated(E, MissingRegion)`
     * to `All` and [CleanTail] to `FromOffset(E)` for the same archive in the same test — the
     * **reference** backend, and so the one every other backend gets checked against. The two mmap
     * backends escaped it, and neither on purpose: `MappedBolt` prunes nothing at all, and
     * `PosixMappedBolt` is saved by an inflated derived extent. Three backends, three different
     * reasons, one of them wrong — which is the dimension #2240 exists to close.
     *
     * **Mutation receipts** — three assertions: **1** the fixture really is discontinuous, **2** the
     * resume replays no frames, **3** it reaches the same verdict.
     *
     * | Mutation | Reds |
     * |---|---|
     * | Drop the cursor across a pruned segment — `InMemoryBolt` | 2, 3 |
     * | Drop the cursor across a pruned segment — `PosixMappedBolt` | **none** |
     * | Drop the cursor across a pruned segment — `MappedBolt` | **none** — see the note under row 2 |
     * | Carry the cursor from bookkeeping rather than parsing — `InMemoryBolt` | **none** |
     * | Delete the continuity check in any backend | 1 only |
     *
     * **Three green rows, and they are the honest part.** Row 1 is the live bug: `InMemoryBolt`
     * answered `Truncated(E, MissingRegion)` to [ReplayScope.All] and [CleanTail] to `FromOffset(E)`
     * for the same archive — `E` being the offset the first replay had just told a consumer to resume
     * from.
     *
     * Row 2 says the same defect is **not reachable on `PosixMappedBolt`**, and the reason is worth
     * writing down because it is pure luck. Pruning asks whether `baseOffset + extent <= cursor`, and
     * a middle segment's extent there is *derived* as the next segment's base minus its own — inflated
     * by exactly the hole. So the segment before a hole is prunable only when the cursor already sits
     * at or past the segment *after* the hole, and then the missing region lies entirely below the
     * cursor, outside what was asked for. The bug and the thing that hides it have the same cause.
     * The fix is applied to both backends anyway: relying on an accident of inflated bookkeeping to
     * stay correct is how the next reader loses it.
     *
     * **Row 3 was measured when `MappedBolt` pruned nothing, and #2236 has since given it a
     * `skippable` — the row is still green, and that is the finding rather than a leftover.** Its
     * predicate is the *successor's* `baseOffset`, which is row 2's derived extent by another route,
     * so this fixture's cursor — the **start** of the hole — leaves the segment before the hole
     * unprunable and its boundary read either way. Dropping that backend's continuity carry is a real
     * defect and **this property does not see it**: the sub-case that reaches it is a consumer
     * resuming from *past* the hole, which this fixture cannot express because it derives its cursor
     * from the [ReplayScope.All] replay, and that replay stops **at** the hole.
     * [resumingFromBeyondTheHoleReachesTheSameVerdictRatherThanACleanTail] is that sub-case, and it is
     * why [newDiscontinuousBolt] hands back an offset as well as a bolt (#2268). **Keep both.** This
     * one is the only one whose cursor a *consumer* can obtain — it is the offset the previous replay
     * just handed it — so it is the shape a reader reaches first; its sibling drives the shape a
     * retention sweep produces.
     *
     * Row 4 is the trap this test was expected to close and **does not**: carrying the cursor from
     * bookkeeping is exactly right for `InMemoryBolt` (whose extents are exact) and a tautology on
     * `PosixMappedBolt` (where the check would compare a value with itself) — but because of row 2 the
     * tautology is never reached, so that implementation is green too. Parsing the last pruned segment
     * is chosen on the argument, not on a red: no test in this tree distinguishes the two.
     *
     * **What it cannot reach:** a cursor *inside* the hole rather than at its start; an archive all of
     * whose segments are prunable (nothing is emitted and no boundary survives to check — that replay
     * is a [CleanTail] and this test does not claim otherwise); and a hole lying entirely below the
     * cursor — which its sibling now drives, and which the three backends agree is still reportable.
     */
    @Test
    fun resumingFromTheHoleReachesTheSameVerdictRatherThanACleanTail() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = newDiscontinuousBolt(FixedClock(epoch), INTACT_FRAMES, ONE_LOST_SEGMENT).bolt

        val whole = bolt.replay(ReplayScope.All).toList()
        val verdict = whole.lastOrNull()
        // The cursor a consumer is told to hand back: the end of the last frame it consumed.
        val cursor = whole.filterIsInstance<Archived<RgaOp<String>>>().last().endOffset
        val resumed = bolt.replay(ReplayScope.FromOffset(cursor)).toList()
        val resumedFrames = resumed.filterIsInstance<Archived<RgaOp<String>>>()

        assertAll(
            {
                assertIs<Truncated>(
                    verdict,
                    "newDiscontinuousBolt must hand back an archive with a HOLE in it — a healthy one " +
                        "makes this obligation vacuous, so it fails here rather than passing",
                )
            },
            {
                assertEquals(
                    0,
                    resumedFrames.size,
                    "a resume from the hole replays NOTHING — the frames beyond it are real, but the " +
                        "records between the cursor and them are gone, so their history is not",
                )
            },
            {
                assertEquals(
                    verdict,
                    resumed.lastOrNull(),
                    "and reaches the SAME verdict: a scope that prunes whole segments must not prune " +
                        "the one boundary that proves the archive joins up, least of all at the offset " +
                        "the previous replay just told a consumer to resume from",
                )
            },
        )
    }

    /**
     * A resume from **beyond** a lost region still reports it — the shape a *retention sweep*
     * produces, and the one no scope can reach on its own.
     *
     * Not an exotic corruption: a consumer read up to `C`, the segment holding the records below `C`
     * was swept, and it resumes from `C`. Every record it is asking for survives; the ones it says it
     * already has do not exist anywhere. A backend that prunes whole segments below the cursor prunes
     * away the last boundary that could notice, and then answers [CleanTail] — "your history joins
     * up" — to an archive whose whole product is *I still hold what the live replica forgot*.
     *
     * ### Why this needed a fixture hook, and why it is not the sibling above
     *
     * [resumingFromTheHoleReachesTheSameVerdictRatherThanACleanTail] derives its cursor from the
     * [ReplayScope.All] replay, and that replay **stops at the hole** — so the offset it can name is
     * the hole's *start*, never its far side. Nor can any other scope name it: [ReplayScope.Arrived]
     * and [ReplayScope.InsertsAbove] both scan from the oldest surviving segment and stop at the same
     * boundary. The far side is simply not observable through this contract once the archive is
     * discontinuous, which is why [DiscontinuousFixture] carries it.
     *
     * And the distinction is load-bearing rather than fastidious. At the hole's *start* the segment
     * before it is unprunable on both mmap backends — their predicates reduce to the successor's
     * `baseOffset`, which is inflated by exactly the hole — so its boundary is read either way and the
     * sibling's table records **two green rows** there. Move the cursor past the hole and that
     * inflation stops covering: the segment becomes prunable, and only a deliberate minus-one keeps
     * the boundary. Before this property, dropping that minus-one reddened nothing on
     * `PosixMappedBolt` anywhere in the tree — the exact unpinned state in which two workers
     * independently shipped the same defect (#2240), with `MappedBolt` pinned only by a JVM-only test
     * (#2264).
     *
     * Three assertions, and the numbering the receipts use: **1** the cursor really is past the hole,
     * **2** the resume replays nothing, **3** it reaches the same verdict [ReplayScope.All] does.
     *
     * **Mutation receipts** — each applied alone, reverted, the revert grep-verified, the results XML
     * deleted first, and the log checked for a compile failure (a mutation that does not compile
     * leaves Gradle serving the previous run's XML, which fabricates a plausible copy of the row
     * above it).
     *
     * | Mutation | Reds here | Reds elsewhere in `:kuilt-bolt` |
     * |---|---|---|
     * | Drop the minus-one — `InMemoryBolt.replay` | 2, 3 | the sibling above |
     * | Drop the minus-one — `PosixMappedBolt.replay` | 2, 3 | **nothing at all** |
     * | Drop the minus-one — `MappedBolt.firstSegmentToRead` | 2, 3 | **nothing at all** |
     * | Prune the boundary segment and seed its cursor from the surviving *header* | 2, 3 | one `MappedBoltDamageTest` case |
     * | Delete the continuity check outright — `InMemoryBolt` | **the precondition**, before any assertion | both siblings |
     * | **Fixture:** hand back the hole's start as `beyondTheHole` | **1 only** | — |
     * | **Fixture:** hand back an offset past the archive's end | **3 only** | — |
     *
     * **Rows 2 and 3 are the finding this property exists for, and their right-hand column is the
     * measurement.** Dropping either mmap backend's continuity carry now reds this property and
     * *nothing else in the module* — measured over the whole `macosArm64Test` and `jvmTest` suites.
     * For `PosixMappedBolt` that was already true before this property existed, which is the unpinned
     * state #2240 shipped out of twice. For `MappedBolt` it became true **because of this change**:
     * `MappedBoltPruningTest` used to carry that pin, and handing it over is the point — a correctness
     * property belongs where every backend meets it, and what is left in that file is the one claim a
     * conformance property structurally cannot make.
     *
     * Row 4 is what stops rows 1–3 riding on "it read one more segment": the mutated code still reads
     * the boundary segment's *header*, and still answers [CleanTail]. Only **parsing** that segment
     * recovers where its frames really ended, which is the argument `InMemoryBolt.replay` makes in
     * prose and this row makes as a red. It has one piece of collateral — a `MappedBoltDamageTest`
     * case that also depends on the prune boundary — so it is not a clean single-mechanism row.
     *
     * **What the suite cannot check about the hook, said plainly, including the part that is not
     * guarded.** It cannot verify that [DiscontinuousFixture.beyondTheHole] names a frame that really
     * exists — per the paragraph above, nothing in this contract can see past the hole, so there is no
     * positive check to make. Two of the three ways to get it wrong are loud, and the last two receipt
     * rows are those mutations rather than an argument: an offset **at** the hole's start fails
     * assertion **1 and only 1** (2 and 3 stay green there, which is the sibling's whole table
     * restated), and an offset **past the archive's end** makes every segment prunable, which is a
     * [CleanTail], and fails assertion 3.
     *
     * **The third way was silent, and closing it is why [DiscontinuousFixture] takes two appends
     * rather than an offset.** An offset strictly *inside* the missing region clears assertion 1,
     * which only demands `> atOffset`, and it is vacuous on both mmap backends — their predicates
     * prune a segment only when its *successor* starts at or below the cursor, and the successor of
     * the segment before the hole is the segment *after* it, so a mid-hole cursor leaves the boundary
     * unprunable and its continuity read either way. Measured, while the hook was a bare `Long`: a
     * fixture returning `holeStart + 1` **and** `MappedBolt`'s minus-one dropped left this property
     * green.
     *
     * The first draft answered that with a convention — "take it from the first surviving append" —
     * and a helper that enforced it. That is the mechanism this whole PR argues is insufficient: a TCK
     * exists for the fixtures that have not been written, and a helper is a thing their author is free
     * never to call. So the band is not documented, it is **unrepresentable**: [DiscontinuousFixture]
     * takes the lost frame and the first survivor and proves the far edge from them, and a mid-hole
     * offset is not a value that constructor can be handed. See its KDoc for the receipts on each way
     * of slipping it.
     *
     * **What it does not reach.** It never proves the pruned prefix was *skipped* rather than read and
     * forgiven — every assertion here would hold on a backend that read the whole archive every time.
     * That claim is about bytes off a filesystem, so it is not expressible over [Bolt];
     * `MappedBoltPruningTest` carries it for the one backend that makes the claim. A wider hole and a
     * backwards jump were also unreached here, and are now driven by
     * [aHoleSpanningSeveralLostSegmentsIsReportedAtItsStartLikeAnyOther] and
     * [anArchiveWhoseSegmentsDoubleBackStopsAtTheJumpAndSaysSo] (#2331).
     */
    @Test
    fun resumingFromBeyondTheHoleReachesTheSameVerdictRatherThanACleanTail() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val fixture = newDiscontinuousBolt(FixedClock(epoch), INTACT_FRAMES, ONE_LOST_SEGMENT)
            val bolt = fixture.bolt

            val whole = bolt.replay(ReplayScope.All).toList()
            val verdict = assertIs<Truncated>(
                whole.lastOrNull(),
                "newDiscontinuousBolt must hand back an archive with a HOLE in it — a healthy one " +
                    "makes this obligation vacuous, so it fails here rather than passing",
            )
            val resumed = bolt.replay(ReplayScope.FromOffset(fixture.beyondTheHole)).toList()
            val resumedFrames = resumed.filterIsInstance<Archived<RgaOp<String>>>()

            assertAll(
                {
                    assertTrue(
                        fixture.beyondTheHole > verdict.atOffset,
                        "the fixture's cursor must sit BEYOND the missing region — it measured " +
                            "${fixture.beyondTheHole} against a hole starting at ${verdict.atOffset}, and at " +
                            "the hole's start this is the shape the sibling property already drives, where " +
                            "the boundary segment is unprunable and every backend is green for free",
                    )
                },
                {
                    assertEquals(
                        0,
                        resumedFrames.size,
                        "a resume from past the hole replays NOTHING — the frames beyond it are real, but " +
                            "the records this cursor claims to have consumed are gone, so their history is " +
                            "not, and a frame handed back here carries offsets that jump",
                    )
                },
                {
                    assertEquals<ReplayEvent<RgaOp<String>>?>(
                        verdict,
                        resumed.lastOrNull(),
                        "and reaches the SAME verdict: a scope that prunes whole segments below the cursor " +
                            "must not prune the one boundary that proves the surviving archive joins up — " +
                            "least of all for the retention sweep, which is the ordinary way a consumer " +
                            "ends up holding a cursor past a region nobody kept",
                    )
                },
            )
        }

    /**
     * A hole spanning **several consecutive segments** is reported exactly as a one-segment hole is —
     * at its start, with the same reason, under a whole-archive replay and under a resume from past
     * it.
     *
     * Not a bigger number for its own sake. Every other hole property in this suite drives an archive
     * that lost exactly one segment, so a backend is only ever asked about a gap *one segment wide*,
     * and "recovers across a wider one" is a live shape: a continuity check written against a
     * bookkeeping-derived expectation rather than a parsed one — segment-index arithmetic, a
     * fixed-stride assumption, a resynchronisation that tolerates a plausible single loss — is
     * single-gap-correct and blind here. A partial retention sweep, or a directory that lost a day's
     * files rather than an hour's, produces this archive and not the narrow one.
     *
     * ### What the suite can prove about the width, and what it cannot — because the second half is
     * the interesting half
     *
     * Nothing in [Bolt] mentions a segment, so no assertion can read back how many were destroyed: a
     * hole is two offsets, and two adjacent lost segments are indistinguishable through this contract
     * from one lost segment twice the size. **There is therefore no archive-side check of the width at
     * all** — every proxy that suggests itself (the missing region is wider than the widest frame
     * replayed; it is `n` times the narrowest) smuggles in an assumption about how this fixture's
     * payloads are sized, which is the kind of thing that goes quietly wrong later and is exactly the
     * #2247 shape one level down.
     *
     * So the width arrives as **evidence** — one [AppendResult.Written] per destroyed segment — and
     * assertion 1 counts it against what this test asked for, so a fixture that quietly lost one
     * segment when told to lose two reds instead of restating its sibling. Two residuals, both named
     * rather than argued away: the step from *frame* to *segment* belongs to the subclass, which is the
     * only place the notion exists (all three in-tree fixtures `check` one frame per segment beside
     * their budget); and a fixture that destroyed one segment while *listing* two lost frames satisfies
     * everything here, because the archive it hands back is then a consistent narrower hole. See
     * [DiscontinuousFixture].
     *
     * Five assertions, in the order below: **1** the hole really spans more than one lost segment;
     * **2** the verdict is [Truncated] with [TruncationReason.MissingRegion], at the offset the missing
     * region starts; **3** the fixture's account of what it destroyed agrees with the archive — the
     * hole starts where the last surviving frame ended; **4** every frame ahead of the hole survives
     * and nothing beyond it is replayed; **5** a resume from past the wider hole reaches the same
     * verdict and replays nothing.
     *
     * **Mutation receipts** — each applied alone, reverted, and read out of the results XML.
     *
     * | Mutation | Reds here | Reds in the one-segment properties |
     * |---|---|---|
     * | Report a gap only while it is no wider than one lost frame — `InMemoryBolt` | 2, 4, 5 | nothing |
     * | The same in `MappedBolt.emitSegment` | 2, 4, 5 | nothing |
     * | The same in `PosixMappedBolt.emitFrames` | 2, 4, 5 | nothing |
     * | Delete the continuity check outright | 2, 4, 5 | all three |
     * | **Fixture:** lose one segment however many were asked for | **1 only** | nothing |
     *
     * **Row 1 is synthetic and is labelled so rather than dressed up.** No *natural* mutation of the
     * three shipped backends distinguishes a wide hole from a narrow one, because all three compare a
     * **parsed** resume offset against the successor header's absolute base, and that comparison
     * cannot see how far apart the two are. That is the honest measurement, and it is the argument for
     * the property rather than against it: the check being width-blind is a property of *these three*
     * implementations, held by nothing, and the first backend to derive its expectation instead of
     * parsing it inherits the gap silently. The rows show the property has the discriminating power
     * the argument needs — a check that recovers across a double gap reds here and **nowhere else**.
     *
     * The last row is the vacuity guard, and it is the one that matters day to day: it is the mutation
     * a fixture author commits by accident.
     */
    @Test
    fun aHoleSpanningSeveralLostSegmentsIsReportedAtItsStartLikeAnyOther() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val fixture = newDiscontinuousBolt(FixedClock(epoch), INTACT_FRAMES, SEVERAL_LOST_SEGMENTS)
            val bolt = fixture.bolt

            val events = bolt.replay(ReplayScope.All).toList()
            val frames = events.filterIsInstance<Archived<RgaOp<String>>>()
            val verdict = events.lastOrNull()
            val resumed = bolt.replay(ReplayScope.FromOffset(fixture.beyondTheHole)).toList()

            assertAll(
                {
                    assertEquals(
                        SEVERAL_LOST_SEGMENTS,
                        fixture.lostFrameCount,
                        "newDiscontinuousBolt was asked to destroy several segments, and this obligation " +
                            "is a restatement of its one-segment sibling unless it did — a hole is two " +
                            "offsets through this contract, so the count is the only evidence there is",
                    )
                },
                {
                    assertEquals(
                        Truncated(fixture.holeStartsAt, TruncationReason.MissingRegion),
                        verdict,
                        "a wider hole is the same damage as a narrow one and gets the same verdict, at the " +
                            "offset the missing region STARTS — not at its far edge, and not a CleanTail",
                    )
                },
                {
                    assertEquals<Long?>(
                        frames.lastOrNull()?.endOffset,
                        fixture.holeStartsAt,
                        "and the fixture's account of what it destroyed agrees with the archive it handed " +
                            "back: the hole starts where the last surviving frame ended",
                    )
                },
                {
                    assertEquals(
                        INTACT_FRAMES,
                        frames.size,
                        "every frame ahead of the hole survives, and nothing beyond it is replayed — a " +
                            "wider hole is more records missing, not a licence to step over them",
                    )
                },
                {
                    assertEquals<ReplayEvent<RgaOp<String>>?>(
                        verdict,
                        resumed.lastOrNull(),
                        "and a resume from past the wider hole reaches the same verdict, replaying " +
                            "${resumed.filterIsInstance<Archived<RgaOp<String>>>().size} frames where it owes " +
                            "none: more segments below the cursor are prunable here than in the narrow case, " +
                            "and the boundary that proves the archive joins up is among them",
                    )
                },
            )
        }

    /**
     * An archive whose segments **double back** — a header claiming an absolute `baseOffset` *below*
     * where its predecessor's frames ended — stops at the jump rather than replaying records it has
     * already handed out.
     *
     * ### The one hole shape that is not a hole, and the check that reads correctly and is wrong
     *
     * Every other discontinuity property in this suite drives a gap **forward**: [newDiscontinuousBolt]
     * can widen a hole without limit and can never invert one, because losing a segment only ever
     * moves the next header's base further from the previous segment's end. So a backend is only ever
     * asked "does this segment start too far along?", and `header.baseOffset > resumeOffset` — which
     * reads perfectly, and is how the question is naturally phrased — is green across this entire
     * suite while replaying *this* archive as a [CleanTail].
     *
     * And the failure it lets through is worse than the one #2240 shipped, not milder. A hole hands
     * back a short history that says it is short. A backwards jump hands back the same records
     * **twice**, at offsets a consumer has already consumed and acknowledged — so a consumer resuming
     * by offset sees an old `(replica, seq)` arrive as a new one, which is the exact confusion the
     * [Bolt] KDoc says a gapped history causes, reached from the other side.
     *
     * Six assertions: **1** the archive really is damaged; **2** the jump really goes *backwards*,
     * into a frame this replay already emitted; **3** the verdict stops where the fixture says the
     * readable history ends; **4** every frame ahead of the jump survives and none from beyond it is
     * replayed; **5** exactly one terminal event, and it is last; **6** the scopes with no offset
     * predicate reach the same verdict.
     *
     * **Assertion 2 is what stops this being its sibling restated**, and it is checkable rather than
     * declared: the successor's claimed base must be the start of a frame the replay *already handed
     * out*. A fixture describing a forward gap cannot construct [BackwardsJumpFixture] at all, and one
     * whose evidence has slipped off the archive it built reds here or at assertion 3.
     *
     * **Mutation receipts** — each applied alone, reverted, and read out of the results XML.
     *
     * | Mutation | Reds here | Reds elsewhere in `:kuilt-bolt` |
     * |---|---|---|
     * | `header.baseOffset != resumeOffset` → `>` — `InMemoryBolt.emitFrames` | 1, 3, 4, 5 | **nothing** |
     * | The same in `MappedBolt.emitSegment` | 1, 3, 4, 5 | **nothing** |
     * | The same in `PosixMappedBolt.emitFrames` | 1, 3, 4, 5 | **nothing** |
     * | Delete the continuity check outright | 1, 3, 4, 5 | the three hole properties |
     * | **Fixture:** restore the stale segment over the newest one instead | the fixture's own `check` | — |
     *
     * **The first three rows are the finding, and their right-hand column is the whole of it.**
     * Narrowing the comparison to a forward gap is a real defect on all three backends and, before
     * this property, reddened *nothing anywhere in the module* — the unpinned state #2240 shipped out
     * of twice, in a second dimension nobody had looked at. Row 4 is what keeps assertions 1 and 3
     * from riding on the hole properties: deleting the check reds those too, so it says nothing about
     * this shape on its own.
     *
     * **The green assertions, said plainly.** Assertions 2 and 6 were green under every mutation
     * above. Assertion 2 is a *precondition* rather than a claim about the backend — its job is to red
     * when the fixture drifts, and the last row is that measurement. Assertion 6 inherits its
     * justification from [anArchiveMissingAMiddleRegionStopsAtTheHoleAndSaysSo], where the same pair of
     * scopes closed a real gap: a continuity check hung off the offset predicate is invisible to every
     * other property, and there is no reason to believe a backwards jump would be pinned by a
     * different accident than a forward one.
     *
     * **What it does not reach.** A jump backwards to an offset that is not a frame boundary — the
     * fixture is obliged to land the successor on a frame the replay emitted, because that is the only
     * form the suite can verify rather than take on trust. And the reason it asserts is
     * [TruncationReason.MissingRegion], which all three backends already report for any disagreement:
     * a backwards jump is not literally a *missing* region, but the constant's consumer contract —
     * `atOffset` is the honest end of the readable history and is **not** somewhere to resume from —
     * is exactly right here, and inventing a fourth constant for a shape no backend distinguishes
     * would put a knob in the contract no consumer asked for.
     */
    @Test
    fun anArchiveWhoseSegmentsDoubleBackStopsAtTheJumpAndSaysSo() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val fixture = newBackwardsJumpBolt(FixedClock(epoch), INTACT_FRAMES)
        val bolt = fixture.bolt

        val events = bolt.replay(ReplayScope.All).toList()
        val frames = events.filterIsInstance<Archived<RgaOp<String>>>()
        val verdict = events.lastOrNull()
        // The two scopes with no offset predicate, exactly as the forward-hole property drives them.
        val byArrival = bolt.replay(ReplayScope.Arrived(epoch, epoch + STEP)).toList()
        val byDots = bolt.replay(ReplayScope.InsertsAbove(VersionVector.EMPTY)).toList()

        assertAll(
            {
                assertIs<Truncated>(
                    verdict,
                    "newBackwardsJumpBolt must hand back an archive that DOUBLES BACK — a healthy one " +
                        "makes this obligation vacuous, so it fails here rather than passing",
                )
            },
            {
                assertTrue(
                    frames.any { it.offset == fixture.jumpsBackTo },
                    "the successor must re-enter at a frame this replay ALREADY emitted — that is what " +
                        "makes the jump backwards rather than a forward gap, which is the property one " +
                        "screen up; it claimed ${fixture.jumpsBackTo}, and the frames replayed start at " +
                        "${frames.map { it.offset }}",
                )
            },
            {
                assertEquals(
                    Truncated(fixture.stopsAt, TruncationReason.MissingRegion),
                    verdict,
                    "the verdict stops where the readable history ends — at the last intact frame, not " +
                        "at the offset the successor doubles back to, and not a CleanTail over a history " +
                        "that hands the same records out twice",
                )
            },
            {
                assertEquals(
                    INTACT_FRAMES,
                    frames.size,
                    "every frame ahead of the jump survives, and nothing from beyond it is replayed — " +
                        "those frames are DUPLICATES, so stepping over the jump emits a record a second " +
                        "time at an offset a consumer has already consumed",
                )
            },
            {
                assertEquals(
                    frames.size,
                    events.indexOfFirst { it !is Archived<*> },
                    "the verdict is LAST — every event before it is a frame, and none follows it",
                )
            },
            {
                assertAll(
                    { assertEquals(verdict, byArrival.lastOrNull(), "a jump backwards is damage under EVERY scope") },
                    { assertEquals(verdict, byDots.lastOrNull(), "including the one that selects by a frame's BODY") },
                )
            },
        )
    }

    // ── 7. durability() is honest about the promise this backend made ─────────

    /**
     * The **healthy-state** half of the durability contract, and on its own it is weak in the same
     * way [availabilityAgreesWithWhetherAnAppendIsAccepted] is: a bolt built by [newBolt] flushes
     * successfully on every backend in the tree, so only the [DurabilityState.AsPromised] branch is
     * ever taken.
     *
     * Weak is not empty. It is the only property in this suite that reds when a backend records a
     * *successful* flush as a failure — an inverted `msync` return test, a `catch` around a call that
     * did not throw — and that mistake is invisible to its discriminating sibling
     * [aBoltThatCannotFlushReportsDegradedExactlyWhenItPromisedDurability], which only needs
     * [DurabilityState.Degraded] to appear. Keep the pair.
     *
     * It asks **before** any append as well as after, because sticky state is the sort that latches
     * at construction.
     *
     * **Mutation receipt.** Inverting the `msync` return test in `PosixMappedBolt.syncRange` — so a
     * flush that worked is recorded as one that did not — reds the two post-append assertions here,
     * and is the only mutation measured on this branch that does. The **before-any-append** assertion
     * was green under every one of them: nothing in the current code can latch a doubt at
     * construction, and it is kept as the guard against a future implementation that does.
     */
    @Test
    fun aHealthyBoltIsMeetingTheDurabilityItPromised() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = newBolt(FixedClock(epoch))
        val (afterFirst, first) = Rga.empty<String>().mintInserts("first")
        val (_, second) = afterFirst.mintInserts("second")

        val beforeAnyAppend = bolt.durability()
        bolt.append(first)
        val afterOne = bolt.durability()
        bolt.append(second)
        val afterTwo = bolt.durability()

        assertAll(
            {
                assertEquals(
                    DurabilityState.AsPromised,
                    beforeAnyAppend,
                    "a bolt that has flushed nothing has broken no promise — sticky state must not latch " +
                        "at construction",
                )
            },
            {
                assertEquals(
                    DurabilityState.AsPromised,
                    afterOne,
                    "a flush that succeeded, or was never promised, is not a degradation",
                )
            },
            { assertEquals(DurabilityState.AsPromised, afterTwo, "and it stays that way") },
        )
    }

    /**
     * The half of the durability contract that can fail — and the only place in this tree where a
     * flush failure is reached at all.
     *
     * **The point of the whole signal is that it is relative.** So this drives both answers from one
     * fixture: a configuration that promised per-record durability must report
     * [DurabilityState.Degraded] when it cannot deliver it, and a configuration that promised nothing
     * must report [DurabilityState.AsPromised] **under the identical rigged failure**. An absolute
     * reading of durability passes the first and fails the second, which is exactly what the second
     * arm is for. See [newBoltThatCannotFlush] for why the fixture declares its case rather than
     * returning `null`, and what that cannot detect.
     *
     * Assertions, in the order they appear below, and the numbering the receipts use. The first four
     * are shared; 5–8 are the [DurabilityFixture.Promised] arm and 9 is the other one.
     *
     * 1. every append is still [AppendResult.Written] — a bolt that cannot flush has **not** failed
     *    the append, and answering [AppendResult.Failed] would invite a consumer to re-feed a record
     *    that is already in the archive;
     * 2. every frame replays — the records really are there, which is the whole reason this is not a
     *    failed append;
     * 3. the verdict is a clean tail, so nothing about the archive's *integrity* changed;
     * 4. `durability()` is stable across two calls — it is state, not an event, so a consumer that
     *    polls sees the same answer twice;
     * 5. the promised arm reports [DurabilityState.Degraded] at all (the precondition, which is also
     *    what catches a fixture handing back an asynchronous bolt);
     * 6. its range covers the **first** append after one append — a failed flush puts the frames in
     *    doubt from the archive's start, not just the one that triggered it;
     * 7. it **widens** to the third append's end rather than resetting to it — the range grows with
     *    each failure, which is what preserves a once-and-then-cleared `EIO`;
     * 8. its `reason` is not blank — a bare "durability degraded" makes every recovery unimplementable;
     * 9. **the flushing-but-unpromised arm proves its rig fired**, so "it flushed and was not degraded
     *    by it" is an assertion rather than a coincidence of the fixture's segment budget;
     * 10. and both unpromised arms report [DurabilityState.AsPromised] after the same appends.
     *
     * **Mutation receipts**, measured on this branch — each mutation applied alone, the verdict read
     * out of the results XML, then reverted and the revert grep-verified:
     *
     * | Mutation | Reds |
     * |---|---|
     * | Restore the swallow in `MappedBolt.flushQuietly` | 5, 6, 7, 8 |
     * | Restore the swallow in `PosixMappedBolt.flushQuietly` | 5, 6, 7, 8 |
     * | Report `AppendResult.Failed` when the flush failed | 1, 6, 7 |
     * | Invert the `msync` return test | 5, 6, 7, 8 — **and** [aHealthyBoltIsMeetingTheDurabilityItPromised] |
     * | Record an empty range on every failure | 6, 7 |
     * | Record a blank `reason` | 8 only |
     * | `DurabilityLedger.flushFailed` overwrites instead of widening | **7 only, and only on some subclasses** |
     * | Flush regardless of the durability flag — the absolute reading | **10 only** |
     * | Record a flush the bolt never promised (`MappedBolt`'s ungated retiring-segment flush) | **10 only** |
     * | Move the promise gate from the ledger to [roll], so an async bolt never flushes | **9 only** |
     * | Make `durability()` an event: reading it clears the state | **4 only** |
     * | `DurabilityLedger.flushSucceeded` clears unconditionally | **none** |
     * | **Fixture:** hand back an un-rigged bolt under [DurabilityFixture.Promised] | 5, 6, 7, 8 |
     * | **Fixture:** give the flushing-but-unpromised arm a budget that never rolls | **9 only** |
     *
     * **Two rows are the same defect from both sides, and this property shipped green against it.**
     * `MappedBolt` flushes the retiring segment at a roll whatever `forceOnAppend` says; recording that
     * flush latched [DurabilityState.Degraded] on a bolt that had promised nothing — permanently, since
     * no later flush can re-cover a retired segment. Row 6 is that bug, and the property could not see
     * it, because the asynchronous fixture used a budget at which three small appends never roll, so
     * the rig was never reached and the arm asserted nothing at all.
     *
     * **The last row is why assertion 9 exists, and it is the correction to the first fix.** Choosing a
     * rolling budget made the arm non-vacuous *by consequence* — a literal in a test helper, one edit
     * away from silently reverting while this table still claimed the row. Assertion 9 makes the fixture
     * **prove** the rig fired instead. Row 3 is the sharper receipt: moving the gate to [roll] is a real
     * durability regression that still *rolls*, so an assertion on segment count would stay green, and
     * only "a flush was attempted and failed" catches it.
     *
     * **Row 7 is the sharpest one, and it is #2240's thesis recurring here.** Overwriting instead of
     * widening reds **only** on the one-frame-per-segment subclasses. At the shipped 1 MiB budget all
     * three appends land in one segment, whose flush covers the whole segment every time, so
     * "widen from 0" and "reset to the newest failure" compute the *same range* and the assertion
     * cannot tell them apart. The property is genuinely vacuous in the default configuration and is
     * saved only by the Tiny subclasses driving the other one.
     *
     * **The green rows, and the green assertions — said plainly, because a near-all-red table invites
     * exactly the wrong conclusion.** Clearing unconditionally is invisible here: the fixture's flush
     * can never succeed, so no success is ever recorded and every mutation of `flushSucceeded` is a
     * mutation of dead code *for this test*. `DurabilityLedgerTest` is what reds it, and each mapped
     * backend's own `aDoubtRaisedByAFailed…` test is what proves the wiring reaches it at all.
     * Assertions **2** and **3** were green under every mutation above: they say a durability change
     * did not disturb the archive itself, which no mutation of the durability path produces, and they
     * are kept as the anti-regression they are rather than as discriminators.
     *
     * **What this property cannot reach.** It never drives *recovery*, per the above. It never drives
     * a **real** flush failure either: no unprivileged, deterministic condition makes a healthy volume
     * refuse one, so both mapped backends rig it — and only `PosixMappedBolt` rigs the *syscall*
     * (handing `msync` an address the kernel refuses, so the `ENOMEM` and its `strerror` text are
     * real); `MappedBolt` can only rig the verdict, because `force()` takes no arguments. What is
     * driven end to end on both is the wiring from "the flush said no" to "[Bolt.durability] says so".
     * Also unreached: a doubt raised on one backend and read back by another over the same archive
     * (durability is per-instance, not written down), and concurrent appends racing a `durability()`
     * poll.
     */
    @Test
    fun aBoltThatCannotFlushReportsDegradedExactlyWhenItPromisedDurability() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val fixture = newBoltThatCannotFlush(FixedClock(epoch))
            val bolt = fixture.bolt
            val (r1, one) = Rga.empty<String>().mintInserts("one")
            val (r2, two) = r1.mintInserts("two")
            val (_, three) = r2.mintInserts("three")

            val first = bolt.append(one)
            val afterFirst = bolt.durability()
            val second = bolt.append(two)
            val third = bolt.append(three)
            val afterThird = bolt.durability()
            val polledAgain = bolt.durability()
            val events = bolt.replay(ReplayScope.All).toList()
            val frames = events.filterIsInstance<Archived<RgaOp<String>>>()

            assertAll(
                {
                    listOf(first, second, third).forEach {
                        assertIs<AppendResult.Written>(
                            it,
                            "a bolt that cannot flush still WRITES — the frame is in the archive, so Failed " +
                                "would have a consumer re-feed a record already there",
                        )
                    }
                },
                {
                    assertEquals(
                        listOf(one, two, three),
                        frames.map { it.ops },
                        "and every one of those frames replays, in order — the records really are there, " +
                            "which is the whole reason this is not a failed append",
                    )
                },
                { assertEquals(CleanTail, events.lastOrNull(), "a failed flush is not damage — the archive is intact") },
                { assertEquals(afterThird, polledAgain, "durability() is STATE: polling it twice answers the same") },
                {
                    when (fixture) {
                        is DurabilityFixture.Promised -> assertAll(
                            {
                                assertIs<DurabilityState.Degraded>(
                                    afterFirst,
                                    "newBoltThatCannotFlush must hand back a bolt that really cannot flush, in a " +
                                        "configuration that promised it would — an asynchronous one promised " +
                                        "nothing and makes this obligation vacuous, so it fails here rather " +
                                        "than passing",
                                )
                            },
                            {
                                assertEquals(
                                    assertIs<AppendResult.Written>(first).endOffset,
                                    assertIs<DurabilityState.Degraded>(afterFirst).toOffset,
                                    "the range covers what the failed flush left in doubt",
                                )
                            },
                            {
                                assertEquals(
                                    0L to assertIs<AppendResult.Written>(third).endOffset,
                                    assertIs<DurabilityState.Degraded>(afterThird).let { it.fromOffset to it.toOffset },
                                    "and it WIDENS across further failures rather than resetting to the newest — " +
                                        "everything since the last good flush is at risk, not just the frame " +
                                        "that triggered this one",
                                )
                            },
                            {
                                assertTrue(
                                    assertIs<DurabilityState.Degraded>(afterThird).reason.isNotBlank(),
                                    "carrying WHY, not just that something went wrong",
                                )
                            },
                        )

                        is DurabilityFixture.PromisedNothingButFlushes -> assertAll(
                            {
                                assertTrue(
                                    fixture.attemptedFlushes() > 0,
                                    "this arm claims the bolt FLUSHED and was not degraded by it, so the rig " +
                                        "must actually have fired — a fixture whose budget never rolls asserts " +
                                        "nothing here, and would go green against a backend that records every " +
                                        "flush it makes, promised or not",
                                )
                            },
                            {
                                assertEquals(
                                    DurabilityState.AsPromised,
                                    afterThird,
                                    "a backend that promised no durability cannot fall short of it — not even " +
                                        "when a flush it DID make failed, which is what makes this the arm an " +
                                        "ABSOLUTE reading of durability reddens",
                                )
                            },
                        )

                        is DurabilityFixture.PromisedNothingAndNeverFlushes -> assertAll(
                            {
                                assertEquals(
                                    DurabilityState.AsPromised,
                                    afterFirst,
                                    "a backend that promised no durability cannot fall short of it — not even " +
                                        "with the flush it would have used rigged to fail, which is what makes " +
                                        "this the arm an ABSOLUTE reading of durability reddens",
                                )
                            },
                            { assertEquals(DurabilityState.AsPromised, afterThird, "and it stays that way") },
                        )
                    }
                },
            )
        }

    // ── fixtures ──────────────────────────────────────────────────────────────

    /**
     * [count] inserts appended to this replica by [alice], returned with the replica that holds
     * them. Threading one replica through successive calls is what keeps the minted ids distinct —
     * minting each batch from a fresh `Rga.empty()` would hand every batch the same `(lamport, seq)`
     * pairs and quietly collapse the dot assertions.
     */
    private fun Rga<String>.mintInserts(label: String, count: Int = 3): Pair<Rga<String>, List<RgaOp.Insert<String>>> {
        var live = this
        val ops = (0 until count).map { index ->
            val (next, op) = live.insertAt(alice, live.size, "$label-$index")
            live = next
            op
        }
        return live to ops
    }

    /**
     * An `Rga` that has compacted its **trailing** element, plus the ops that produced it.
     *
     * Trailing, not leading: `Rga.compact` refuses to GC an id a live insert still anchors on via
     * `after`, and signals that refusal by returning `null` rather than throwing. Compacting the
     * first of two elements would therefore yield no compaction at all — silently.
     */
    private fun rgaWithACompaction(): CompactionFixture {
        val (r1, first) = Rga.empty<String>().insertAt(alice, 0, "kept")
        val (r2, second) = r1.insertAt(bob, 1, "suppressed")
        val (r3, removal) = assertNotNull(r2.removeAt(1), "removeAt(1) must find the trailing element")

        val cut = VersionVector.of(mapOf(alice to first.id.seq, bob to second.id.seq))
        val (compacted, compactOp) = assertNotNull(
            r3.compact(stableCut = cut, frontierMax = cut, delivered = cut),
            "the tombstoned, causally-stable, unanchored trailing insert must be GC-eligible",
        )
        return CompactionFixture(
            contentOps = listOf(first, second, removal),
            compactOp = compactOp,
            compacted = compacted,
            suppressedInsert = second,
            suppressedRemove = removal,
        )
    }

    private class CompactionFixture(
        val contentOps: List<RgaOp<String>>,
        val compactOp: RgaOp.Compact,
        val compacted: Rga<String>,
        val suppressedInsert: RgaOp.Insert<String>,
        val suppressedRemove: RgaOp.Remove<String>,
    )

    private class FixedClock(private val at: Instant) : Clock {
        override fun now(): Instant = at
    }

    private class MutableClock(private var current: Instant) : Clock {
        override fun now(): Instant = current

        fun advanceBy(step: Duration) {
            current += step
        }
    }

    private companion object {
        const val RECORDS = 4
        const val WINDOW_DROP = 2

        /** More than one, so "stopped at the damage" is distinguishable from "stopped at the start". */
        const val INTACT_FRAMES = 3

        /**
         * The hole the three original discontinuity properties drive: one segment, gone.
         *
         * Named rather than written as a `1` at each call site so the *narrow* case reads as a
         * deliberate choice next to [SEVERAL_LOST_SEGMENTS], which is the whole subject of #2331 —
         * a bare literal is what let three properties agree on one width without anyone noticing.
         */
        const val ONE_LOST_SEGMENT = 1

        /**
         * More than one, so "recovered across the gap" is distinguishable from "recovered across a
         * gap of the one width every other property drives".
         *
         * Two rather than a larger number because the step from one to two is the whole of the
         * claim: a check that cannot see how wide a gap is behaves identically at two and at twenty,
         * and every extra segment costs a fixture an append and a file on three backends.
         */
        const val SEVERAL_LOST_SEGMENTS = 2
        val STEP = 10.seconds
    }
}

/**
 * An archive with a whole region missing out of its middle, and the offset the first surviving frame
 * **beyond** that region starts at — the fixture [BoltConformanceSuite.newDiscontinuousBolt] hands
 * back.
 *
 * More than a bare bolt because of an asymmetry in what the two sides can see. The suite can find
 * where a hole *starts* — it is the offset the [ReplayScope.All] verdict reports — but not where it
 * *ends*, because every scope this contract offers scans forward and stops at the same boundary. The
 * fixture wrote those frames and knows.
 *
 * ### Why it takes two appends and not the answer
 *
 * [beyondTheHole] is derived here rather than supplied, and that is the whole design. As a plain
 * `Long` parameter it was **unvalidated**: the suite can only assert it lies above where the hole
 * starts, and an offset landing strictly *inside* the missing region clears that check while being
 * vacuous on any backend whose pruning predicate reduces to the successor's `baseOffset` — which is
 * both mmap backends. Measured, in that shape: a fixture returning `holeStart + 1` made
 * [BoltConformanceSuite.resumingFromBeyondTheHoleReachesTheSameVerdictRatherThanACleanTail] pass on a
 * `MappedBolt` with its continuity carry deleted. A silent band, in the one obligation added to close
 * a silent gap.
 *
 * A convention ("take it from the first surviving append") plus a helper function does not close it,
 * because a TCK's whole subject is the fixtures that do not exist yet and a helper is a thing a new
 * backend's author is free never to call. Taking the two **appends** instead makes the band
 * *unrepresentable*: an offset inside the hole is not a value this constructor can be handed. It is
 * `DurabilityFixture`'s argument one screen down — sealed rather than a bolt plus a boolean, so the
 * two cannot drift apart — and [newExhaustedBolt]'s argument one file up, that an opt-out only moves
 * the vacuity somewhere harder to see.
 *
 * **Receipts, because an `init` that cannot fail is worse than none.** Measured by slipping
 * `MappedBoltConformanceTest`'s call site, so "every subclass" below means that backend's three:
 *
 * | Fixture mutation | What happens |
 * |---|---|
 * | Slip [firstSurvivor] alone | the `init` reds — **9 tests**, all three hole properties × every subclass |
 * | Slip **both**, staying contiguous | `init` passes; the cursor lands one frame past the far edge, reddening assertions 2 and 3 |
 * | A contiguous pair from **before** the hole | `init` passes; the cursor is below the hole, reddening assertions 1 and 2 |
 * | An offset strictly **inside** the hole | not constructible |
 *
 * The last row is the point of the shape, and the three above it are why it is not enough to say so:
 * the pair can still be wrong, and every way of being wrong lands on an assertion.
 *
 * ### And why the lost frames are a LIST
 *
 * A hole is a hole: through [Bolt] it is a start offset, an end offset, and nothing else. Whether the
 * missing bytes were one segment or four is a *backend-private* fact, so no assertion in this suite
 * can read it back — which is precisely the shape of vacuity #2331 names, because the fixture is then
 * free to lose one segment however many the suite asked for and stay green.
 *
 * So the width comes back as evidence rather than as a claim: one [AppendResult.Written] per
 * destroyed segment, checked here to be a *contiguous run* ending exactly where the survivor begins,
 * and counted by [lostFrameCount] against what
 * [BoltConformanceSuite.newDiscontinuousBolt] was asked for. What that leaves to the subclass — and it
 * is the residual, said plainly — is the step from *frame* to *segment*: only a fixture holding the
 * backend's own notion of a segment can assert that each of these frames occupied one, and all three
 * in-tree fixtures do it with a `check` beside their segment budget. A backend packing two frames per
 * segment could satisfy this list with one lost segment; nothing at this level sees it.
 *
 * Top-level, and mirroring `DurabilityFixture`, so a fixture helper outside a suite subclass can
 * build one.
 *
 * @property bolt the discontinuous archive.
 * @param lostFrames the appends whose segments were then destroyed, oldest first — the **evidence**
 *   for both edges of the hole and for how wide it is, and different appends' reported offsets than
 *   the one handed back.
 * @param firstSurvivor the first append whose frame is still readable behind the hole.
 */
class DiscontinuousFixture(
    val bolt: Bolt<RgaOp<String>>,
    lostFrames: List<AppendResult.Written>,
    firstSurvivor: AppendResult.Written,
) {
    init {
        check(lostFrames.isNotEmpty()) { "an archive that lost nothing has no hole in it" }
        lostFrames.zipWithNext { earlier, later ->
            check(later.offset == earlier.endOffset) {
                "the destroyed frames must be a CONTIGUOUS run, or what is missing is several holes " +
                    "with surviving records between them rather than one wider one — ${later.offset} " +
                    "does not follow ${earlier.endOffset}"
            }
        }
        check(firstSurvivor.offset == lostFrames.last().endOffset) {
            "the first frame behind the hole must start exactly where the LAST lost frame ended, or " +
                "this fixture is naming an offset inside the missing region rather than its far edge " +
                "— ${firstSurvivor.offset} against ${lostFrames.last().endOffset}"
        }
    }

    /**
     * The offset of the first frame **after** the missing region — what a consumer that had already
     * read past it hands back, and the one quantity no [ReplayScope] can name once the archive is
     * discontinuous.
     */
    val beyondTheHole: Long = firstSurvivor.offset

    /**
     * Where the missing region **starts**: the offset the first destroyed frame was written at, and
     * so the offset a correct replay stops at.
     *
     * The suite can find this for itself — it is the last surviving frame's `endOffset` — which is
     * what makes it worth carrying: asserting the two agree ties the fixture's account of what it
     * destroyed to the archive it actually handed back.
     */
    val holeStartsAt: Long = lostFrames.first().offset

    /** How many whole frames the hole swallowed. One per destroyed segment; see the class KDoc. */
    val lostFrameCount: Int = lostFrames.size
}

/**
 * An archive whose segments **double back**: one segment's header claims an absolute `baseOffset`
 * *below* where its predecessor's frames ended, rather than above it — the fixture
 * [BoltConformanceSuite.newBackwardsJumpBolt] hands back.
 *
 * ### Why this is not the discontinuous fixture with a different number in it
 *
 * [DiscontinuousFixture] can widen a hole arbitrarily and can never invert one. Every archive it
 * builds has each header claiming an offset *above* where the previous segment stopped, so a backend
 * whose continuity check is spelled `header.baseOffset > resumeOffset` — a perfectly natural way to
 * write "this segment starts too far along" — passes every property in this suite and replays *this*
 * archive as a [CleanTail]. What it then hands back is worse than a hole: the same records a second
 * time, at offsets a consumer has already consumed and told the world it holds.
 *
 * ### Why it takes two appends and not the answer
 *
 * Same argument as its sibling one screen up, and the same trap avoided. As a pair of `Long`s the
 * fixture could name any two numbers, including a *forward* gap — at which point this obligation
 * silently becomes the existing hole property restated, on every backend, forever. Taking the two
 * **appends** and checking them here makes a forward jump not a value this constructor can be handed:
 * the frame the successor re-enters at must end strictly *before* the last frame ahead of the jump
 * does. Strictly, and by a whole frame, so "backwards" is unmistakable rather than an off-by-one at a
 * boundary — and so the suite has a *verifiable* handle on it, because a frame it already replayed
 * must be sitting at [jumpsBackTo].
 *
 * **What this cannot check.** That [revisited] and [lastFrameBeforeTheJump] describe the archive
 * actually handed back, rather than two appends the fixture liked the look of. The suite closes most
 * of that from the other side — the verdict must stop at [stopsAt], and a frame it replayed must
 * start at [jumpsBackTo] — so a slipped pair reds rather than passing quietly. See
 * [BoltConformanceSuite.anArchiveWhoseSegmentsDoubleBackStopsAtTheJumpAndSaysSo].
 *
 * @property bolt the archive that doubles back.
 * @param revisited the append the successor segment's header now claims to start at — a frame the
 *   replay has **already emitted**, which is what makes the jump backwards rather than forward.
 * @param lastFrameBeforeTheJump the last append still readable ahead of the jump.
 */
class BackwardsJumpFixture(
    val bolt: Bolt<RgaOp<String>>,
    revisited: AppendResult.Written,
    lastFrameBeforeTheJump: AppendResult.Written,
) {
    init {
        check(revisited.endOffset < lastFrameBeforeTheJump.endOffset) {
            "the successor must re-enter the offset space BEHIND a whole frame this replay already " +
                "handed out, or this fixture is describing a forward gap — which is the property one " +
                "screen up, and would make this one a restatement of it — ${revisited.endOffset} " +
                "against ${lastFrameBeforeTheJump.endOffset}"
        }
    }

    /**
     * The offset the successor segment's header claims to start at: **below** [stopsAt], and the
     * start of a frame this replay has already emitted.
     */
    val jumpsBackTo: Long = revisited.offset

    /** Where the readable history ends, and so the offset a correct replay's verdict reports. */
    val stopsAt: Long = lastFrameBeforeTheJump.endOffset
}

/**
 * A bolt whose durability operation cannot succeed, and what its backend **promised** in the
 * configuration under test — the fixture [BoltConformanceSuite.newBoltThatCannotFlush] hands back.
 *
 * Sealed rather than a bolt plus a boolean, so the two cannot drift apart: a subclass makes one
 * decision, in one place, and the suite dispatches on it. Top-level rather than nested so a fixture
 * helper outside a suite subclass can build one.
 */
sealed interface DurabilityFixture {

    val bolt: Bolt<RgaOp<String>>

    /**
     * This configuration promised to make each record durable before [Bolt.append] returned, and the
     * flush that would do it cannot succeed. It must report [DurabilityState.Degraded].
     */
    class Promised(override val bolt: Bolt<RgaOp<String>>) : DurabilityFixture

    /**
     * This configuration promised no durability, **and still attempts a flush** — which this bolt has
     * rigged to fail. It must report [DurabilityState.AsPromised] anyway.
     *
     * The strongest of the three, and the only one that can catch a backend *recording* a flush it
     * never promised. [attemptedFlushes] is what makes it an assertion rather than a coincidence: on a
     * backend whose only ungated flush happens at a segment roll, whether the rig fires at all depends
     * on the fixture picking a segment budget that rolls, and that is an emergent property of a
     * literal in a test helper. The suite **demands** it fired rather than hoping, so a later change
     * to the budget, the append count or the backend's minimum segment size reds here instead of
     * silently reverting the arm to asserting nothing.
     *
     * @property attemptedFlushes how many flushes this fixture's rig has made fail, read after the
     *   appends. Must be positive, or the arm proved nothing.
     */
    class PromisedNothingButFlushes(
        override val bolt: Bolt<RgaOp<String>>,
        val attemptedFlushes: () -> Int,
    ) : DurabilityFixture

    /**
     * This configuration promised no durability and **issues no flush at all**, at any configuration
     * of this backend, so there is nothing to rig. It must report [DurabilityState.AsPromised].
     *
     * The honest arm for a backend with no flush to speak of — [InMemoryBolt], and a mapped backend
     * all of whose flush sites are gated on its durability flag. It still reds an *absolute* reading
     * of durability, which is what it is for. It cannot red a "records a flush it never promised" bug,
     * because such a backend has no site that could commit one; [PromisedNothingButFlushes] carries
     * that half, and a backend that *does* have an ungated flush belongs there instead.
     */
    class PromisedNothingAndNeverFlushes(override val bolt: Bolt<RgaOp<String>>) : DurabilityFixture
}
