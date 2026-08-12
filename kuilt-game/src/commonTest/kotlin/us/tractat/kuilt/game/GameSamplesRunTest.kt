package us.tractat.kuilt.game

import kotlinx.coroutines.test.TestResult
import kotlin.test.Test

/**
 * Runs every zero-argument `@sample` in `:kuilt-game`.
 *
 * A sample is *compiled* as part of `commonTest`, so a stale API breaks the build — but its
 * `check(…)` calls execute only if something calls the function. See `CrdtSamplesRunTest` for the
 * incident that motivated this: a sample that asserted something false compiled, was quoted
 * verbatim into the cookbook, and `verifyDocCitations` faithfully proved the quote matched. The
 * citation gate answers "does the quote match the source?"; running the sample is the only thing
 * that answers "is the source true?" (#2116).
 *
 * These samples are `runTest`-based and so return a [TestResult]; each test returns it rather than
 * swallowing it, because on JS and wasm the result is a promise the framework must receive to
 * await. Discarding it would make every one of these pass without running.
 *
 * **`sampleGameNode` and `sampleSpeculativeSequencer` are absent, and both FAILED when first
 * executed** — they are in `verifySamplesAreRun`'s baseline with the diagnosis, and #2289 owns the
 * fix. `sampleGameNode` wedges for the whole backstop. `sampleSpeculativeSequencer` asserts
 * `speculativeState` already holds the proposed action right after `async { propose(42) }`, which
 * is a true statement about `SpeculativeSequencer` — its KDoc promises the optimistic apply happens
 * *before* the first suspension — that the sample never reaches: under `runTest`'s default
 * `StandardTestDispatcher` the `async` is queued and `propose` has not begun. The production
 * contract is right; the sample's test mechanics are wrong. Both are quoted into
 * `Writerside/topics/game-bootstrap.md`, so the fix changes what the guide shows and wants its own
 * review rather than a drive-by.
 */
class GameSamplesRunTest {

    @Test
    fun gameHostJoinHolds(): TestResult = sampleGameHostJoin()

    @Test
    fun serverCorePlacementHolds(): TestResult = sampleServerCorePlacement()

    @Test
    fun turnSequencerHolds(): TestResult = sampleTurnSequencer()

    @Test
    fun gameChatHolds(): TestResult = sampleGameChat()

    @Test
    fun gameRoomsHolds(): TestResult = sampleGameRooms()

    @Test
    fun gameOverRoomHolds(): TestResult = sampleGameOverRoom()
}
