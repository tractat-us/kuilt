package us.tractat.kuilt.raft

import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Regression for #1984: [RaftConfig] refuses the two timing relations its own KDoc calls constraints.
 *
 * `maxTermJump` was validated by #1972; these two were stated in prose and enforced nowhere. Both fail late
 * and unattributably rather than at the constructor that caused them.
 *
 * ### `heartbeatInterval < electionTimeoutMin`
 *
 * The KDoc states it outright. Violating it throws nothing at all — the leader heartbeats no faster than its
 * followers time out, so every follower campaigns, the leader steps down, and the cluster re-elects forever.
 * A liveness failure with no exception to trace back to the typo that caused it.
 *
 * ### `electionTimeoutMin < electionTimeoutMax`, **in whole milliseconds**
 *
 * `RaftEngine.randomElectionTimeoutMillis()` draws with
 * `random.nextLong(electionTimeoutMin.inWholeMilliseconds, electionTimeoutMax.inWholeMilliseconds)`, and
 * [kotlin.random.Random.nextLong] throws [IllegalArgumentException] on an empty range — pinned by
 * [theStdlibDrawThisGuardStandsInForRejectsAnEmptyRange], because the `require`'s message asserts it. It draws
 * *inside* the election timer's `scope.launch`, so the throw lands in a timer coroutine long after the node
 * appeared to start cleanly. `electionTimeoutMin == electionTimeoutMax` is the natural way to disable jitter
 * and pin a fixed timeout, so this is a reachable configuration, not exotic misuse.
 *
 * ### The two relations are checked in *different units*, deliberately
 *
 * Reading that as an inconsistency is the error to avoid — each is checked in the unit its own failure lives
 * in:
 *
 * - The heartbeat relation degrades **continuously**: a heartbeat merely *close* to the election floor is
 *   merely *fragile*. There is no cliff, so the honest bound is the one the KDoc states, on the [Duration]s
 *   themselves.
 * - The window relation is a **hard throw** whose precondition the engine states in milliseconds, because
 *   that is what it passes to `nextLong`. Comparing the `Duration`s would leave the throw reachable: a
 *   sub-millisecond window like `1.5ms..1.9ms` satisfies `min < max` and still truncates to the empty range
 *   `1..1` — see [aSubMillisecondWindowIsRefusedBecauseTheEngineDrawsInWholeMilliseconds]. Millisecond
 *   truncation is monotone, so this bound is *strictly stronger* than the `Duration` comparison, never
 *   weaker.
 *
 * Both edges are asserted from *both* sides — the refused value and its admitted neighbour — because a bound
 * tested on one side only is satisfied by a bound in the wrong place.
 *
 * Failing at construction is right here and wrong for a frame: this is local, deterministic,
 * consumer-supplied configuration evaluated once, so #1818's "never throw on a path a peer controls" does not
 * reach it — the same exemption #1972/#1976 settled for `maxTermJump`.
 */
internal class RaftConfigTimingValidationTest {

    /**
     * The shipped defaults, written as literals rather than read off [RaftConfig] so a silent change to them
     * reddens here instead of quietly rewriting what these tests assert — the same reason
     * `RaftConfigMaxTermJumpValidationTest` spells out its bounds by hand.
     */
    private val defaultMin = 150.milliseconds
    private val defaultMax = 300.milliseconds
    private val defaultHeartbeat = 50.milliseconds

    // ── heartbeatInterval < electionTimeoutMin ─────────────────────────────────

    @Test
    fun aHeartbeatThatDoesNotOutpaceTheElectionFloorIsRefused() = assertAll(
        { assertRefused(heartbeat = defaultMin) },
        { assertRefused(heartbeat = defaultMin + 1.milliseconds) },
        { assertRefused(heartbeat = defaultMax) },
        { assertRefused(heartbeat = 1.seconds) },
    )

    @Test
    fun aHeartbeatBelowTheElectionFloorIsAdmitted() = assertAll(
        {
            val largestAdmitted = defaultMin - 1.milliseconds
            assertEquals(
                largestAdmitted,
                admitted(heartbeat = largestAdmitted).heartbeatInterval,
                "the boundary is exclusive, so one millisecond under the floor must still be usable",
            )
        },
        { assertEquals(defaultHeartbeat, RaftConfig().heartbeatInterval, "the shipped default must pass") },
        {
            // The ratio the KDoc calls typical, and the two most conservative pairings the suite ships.
            admitted(min = 30.seconds, max = 60.seconds, heartbeat = 1.seconds)
            admitted(min = 5.milliseconds, max = 10.milliseconds, heartbeat = 2.milliseconds)
        },
    )

    // ── electionTimeoutMin < electionTimeoutMax (in whole milliseconds) ────────

    @Test
    fun aCollapsedOrInvertedElectionWindowIsRefused() = assertAll(
        // The reachable case: what someone writes to disable jitter and pin a fixed timeout.
        { assertRefused(min = defaultMin, max = defaultMin) },
        { assertRefused(min = defaultMax, max = defaultMin) },
        { assertRefused(min = 10.milliseconds, max = 1.milliseconds, heartbeat = 1.milliseconds) },
    )

    @Test
    fun anElectionWindowOfOneMillisecondIsAdmitted() = assertAll(
        {
            val config = admitted(min = 5.milliseconds, max = 6.milliseconds, heartbeat = 2.milliseconds)
            assertEquals(6.milliseconds, config.electionTimeoutMax, "one millisecond of jitter is enough")
        },
        { assertEquals(defaultMax, RaftConfig().electionTimeoutMax, "the shipped default must pass") },
    )

    /**
     * The case a `Duration`-valued comparison would wave through. `1.5ms < 1.9ms` holds, yet the engine passes
     * `nextLong(1, 1)` and throws in the timer coroutine — so the bound has to be stated where the engine
     * states it.
     */
    @Test
    fun aSubMillisecondWindowIsRefusedBecauseTheEngineDrawsInWholeMilliseconds() = assertAll(
        { assertRefused(min = 1500.microseconds, max = 1900.microseconds, heartbeat = 1.milliseconds) },
        { assertRefused(min = 100.microseconds, max = 900.microseconds, heartbeat = 50.microseconds) },
    )

    /**
     * The external contract the window guard exists to keep the engine away from. Written as an assertion
     * rather than a comment because the `require`'s message *claims* it: were [kotlin.random.Random.nextLong]
     * ever to start tolerating an empty range, that claim would be false and this reddens.
     */
    @Test
    fun theStdlibDrawThisGuardStandsInForRejectsAnEmptyRange() = assertAll(
        { assertFailsWith<IllegalArgumentException> { Random(RAFT_TEST_SEED).nextLong(5L, 5L) } },
        { assertFailsWith<IllegalArgumentException> { Random(RAFT_TEST_SEED).nextLong(5L, 4L) } },
    )

    // ── Message quality and the copy() route ──────────────────────────────────

    /**
     * A consumer sees the exception with no other context, so each refusal has to name both operands of the
     * relation it broke — a bare "requirement failed" sends them to the source. The window refusal also spells
     * out the *truncated* range, which is the whole reason it fired on a pair that looks ordered.
     */
    @Test
    fun eachRefusalNamesBothOperandsOfTheRelationItBroke() = assertAll(
        {
            val message = refusalMessage(min = 20.milliseconds, max = 60.milliseconds, heartbeat = 40.milliseconds)
            assertAll(
                { assertContains(message, "heartbeatInterval=40ms", message = message) },
                { assertContains(message, "electionTimeoutMin=20ms", message = message) },
            )
        },
        {
            val message = refusalMessage(min = 20.milliseconds, max = 20.milliseconds, heartbeat = 5.milliseconds)
            assertAll(
                { assertContains(message, "electionTimeoutMin=20ms", message = message) },
                { assertContains(message, "electionTimeoutMax=20ms", message = message) },
                { assertContains(message, "20..20", message = "names the empty draw range: $message") },
            )
        },
        {
            val message = refusalMessage(min = 1500.microseconds, max = 1900.microseconds, heartbeat = 1.milliseconds)
            assertContains(message, "1..1", message = "names the range the sub-millisecond pair truncates to: $message")
        },
    )

    /**
     * [RaftConfig] is a `data class`, so `copy` is the likeliest route a consumer takes to a bad value — and it
     * runs the same `init`. Pinned so neither check can be relocated to a factory that `copy` bypasses.
     */
    @Test
    fun copyIsValidatedToo() = assertAll(
        { assertFailsWith<IllegalArgumentException> { RaftConfig().copy(heartbeatInterval = 1.seconds) } },
        { assertFailsWith<IllegalArgumentException> { RaftConfig().copy(electionTimeoutMax = defaultMin) } },
    )

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun assertRefused(
        min: Duration = defaultMin,
        max: Duration = defaultMax,
        heartbeat: Duration = defaultHeartbeat,
    ) {
        refusalMessage(min, max, heartbeat)
    }

    private fun refusalMessage(
        min: Duration = defaultMin,
        max: Duration = defaultMax,
        heartbeat: Duration = defaultHeartbeat,
    ): String = assertFailsWith<IllegalArgumentException>(
        "RaftConfig(electionTimeoutMin=$min, electionTimeoutMax=$max, heartbeatInterval=$heartbeat) " +
            "breaks a stated timing constraint and must be refused at construction",
    ) {
        RaftConfig(electionTimeoutMin = min, electionTimeoutMax = max, heartbeatInterval = heartbeat)
    }.message.orEmpty()

    private fun admitted(
        min: Duration = defaultMin,
        max: Duration = defaultMax,
        heartbeat: Duration = defaultHeartbeat,
    ): RaftConfig = RaftConfig(electionTimeoutMin = min, electionTimeoutMax = max, heartbeatInterval = heartbeat)
}
