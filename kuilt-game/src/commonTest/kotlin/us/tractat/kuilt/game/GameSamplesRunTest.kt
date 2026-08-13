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
 * **`sampleGameNode` is absent, and it FAILED when first executed** — it wedges for the whole
 * wedge-backstop, and is in `verifySamplesAreRun`'s baseline with the diagnosis while #2289 owns
 * the fix. It is quoted into `Writerside/topics/game-bootstrap.md`, so the fix changes what the
 * guide shows.
 *
 * `sampleSpeculativeSequencer` failed the same way and is fixed: it asserts `speculativeState`
 * already holds the proposed action right after `async { propose(42) }`, which is a true statement
 * about `SpeculativeSequencer` — its KDoc promises the optimistic apply happens *before* the first
 * suspension — that the sample never reached, because `runTest`'s default `StandardTestDispatcher`
 * only *queues* the `async`. The production contract was right and the sample's test mechanics were
 * wrong, so the sample now runs on an `UnconfinedTestDispatcher` with every assertion intact.
 */
class GameSamplesRunTest {

    @Test
    fun gameHostJoinHolds(): TestResult = sampleGameHostJoin()

    @Test
    fun serverCorePlacementHolds(): TestResult = sampleServerCorePlacement()

    @Test
    fun turnSequencerHolds(): TestResult = sampleTurnSequencer()

    @Test
    fun speculativeSequencerHolds(): TestResult = sampleSpeculativeSequencer()

    @Test
    fun gameChatHolds(): TestResult = sampleGameChat()

    @Test
    fun gameRoomsHolds(): TestResult = sampleGameRooms()

    @Test
    fun gameOverRoomHolds(): TestResult = sampleGameOverRoom()
}
