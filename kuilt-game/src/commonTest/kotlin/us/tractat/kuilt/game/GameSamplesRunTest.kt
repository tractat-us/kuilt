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
 */
class GameSamplesRunTest {

    @Test
    fun gameHostJoinHolds(): TestResult = sampleGameHostJoin()

    @Test
    fun gameNodeHolds(): TestResult = sampleGameNode()

    @Test
    fun serverCorePlacementHolds(): TestResult = sampleServerCorePlacement()

    @Test
    fun speculativeSequencerHolds(): TestResult = sampleSpeculativeSequencer()

    @Test
    fun turnSequencerHolds(): TestResult = sampleTurnSequencer()

    @Test
    fun gameChatHolds(): TestResult = sampleGameChat()

    @Test
    fun gameRoomsHolds(): TestResult = sampleGameRooms()

    @Test
    fun gameOverRoomHolds(): TestResult = sampleGameOverRoom()
}
