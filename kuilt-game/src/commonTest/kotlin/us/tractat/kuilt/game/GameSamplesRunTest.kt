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
 * Every sample in `GameSamples.kt` runs here, and `unrunSampleBaseline` is now empty (#2289).
 * Two of the three samples that failed the moment #2116 first executed them were in this module,
 * and both were **the same bug**: under `runTest`'s default `StandardTestDispatcher` an `async` is
 * only *queued*, so its body has not begun when the next line runs.
 *
 * - `sampleSpeculativeSequencer` asserted `speculativeState` already held the action right after
 *   `async { propose(42) }` — a true statement about `SpeculativeSequencer`, whose KDoc promises the
 *   optimistic apply precedes the first suspension, that the sample never reached. It now runs on an
 *   `UnconfinedTestDispatcher` and holds the quorum open so the assertion is load-bearing.
 * - `sampleGameNode` **wedged for the whole backstop**, and the cause was the same queued `async`
 *   one layer out: the receiver had not subscribed to `appChannel("chat")` when the sender
 *   broadcast, and `appChannel` delivery is `replay = 0`, so the frame was dropped and the receiver
 *   waited forever. It reads as a hang rather than a failure because the Raft election and heartbeat
 *   timers re-arm forever, so `runTest` keeps advancing virtual time instead of detecting an idle
 *   deadlock. A `runCurrent()` between the subscribe and the broadcast fixes it.
 *
 * `sampleGameHostJoin` carries the identical three lines and passed only because its earlier
 * `propose` calls had already pumped the mux; it got the same explicit `runCurrent()` rather than
 * being left to rely on that.
 */
class GameSamplesRunTest {

    @Test
    fun gameNodeHolds(): TestResult = sampleGameNode()

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
