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
 * Regression for #1984 (the two relations) and #1991 (the floor under [RaftConfig.heartbeatInterval]).
 *
 * `maxTermJump` was validated by #1972; the two relations below were stated in prose and enforced nowhere.
 * Both fail late and unattributably rather than at the constructor that caused them — and #1991 found a
 * third, orthogonal way to reach the same class of late failure, which the two relations jointly admit.
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
        // Asserted by *attribution*, not merely by "something threw". #1991 adds a third check that this
        // pair also breaks (a window under 1 ms forces a heartbeat under 1 ms, since the heartbeat has to
        // be strictly under the floor), so which guard fires is now an ordering question — and an ordering
        // that shadowed the window guard here would leave this case pinning nothing. The window check runs
        // first, so the message still names the empty draw range this test exists for.
        {
            val message = refusalMessage(min = 100.microseconds, max = 900.microseconds, heartbeat = 50.microseconds)
            assertContains(message, "0..0", message = "the window guard, not the heartbeat guard, must claim this: $message")
        },
    )

    // ── heartbeatInterval survives millisecond truncation ─────────────────────

    /**
     * Regression for #1991. The bound the other two leave open: both relations above are satisfied by
     * `heartbeatInterval = 500.microseconds` with `electionTimeoutMin = 900.microseconds` and
     * `electionTimeoutMax = 1900.microseconds` — the heartbeat outpaces the floor, and the window is a
     * non-empty `0..1` — yet the engine then draws every election deadline with `nextLong(0, 1)`, whose
     * only value is `0`. `becomeLeader`'s quorum-check loop is `while (true) { delay(draw); … }`, so a
     * draw pinned at zero spins it as fast as the dispatcher schedules it: an unbounded hot loop on the
     * leader in production, and under virtual time a loop that never yields the clock — the test does not
     * fail, it **hangs**, and `runTest` reports an `UncompletedCoroutinesError` with no state to read.
     *
     * The floor is stated on the heartbeat rather than on the election window because it is the *stronger*
     * place to put it: `heartbeatInterval < electionTimeoutMin` already holds, so a heartbeat of at least
     * one whole millisecond drags `electionTimeoutMin` strictly above one millisecond, hence its truncation
     * to at least `1`, hence a draw of at least `1`. One check bounds both loops away from zero.
     *
     * It is stated **in whole milliseconds** for the same reason the window relation is: `nextLong` takes
     * `Long` bounds and cannot take a `Duration`, so the quantisation is what the guard has to speak about.
     *
     * The heartbeat loop's own truncation — a separate half of #1991 — is not fixed here but at the call
     * site, which now passes the `Duration` to `delay` and never floors it; see `HeartbeatCadenceTest`.
     */
    @Test
    fun aHeartbeatUnderOneWholeMillisecondIsRefusedBecauseItWouldPinTheElectionDrawAtZero() = assertAll(
        // The reachable config the other two guards admit today, and the draw it produces.
        { assertRefused(min = 900.microseconds, max = 1900.microseconds, heartbeat = 500.microseconds) },
        { assertEquals(0L, 500.microseconds.inWholeMilliseconds, "the premise: a sub-millisecond heartbeat floors to zero") },
        { assertEquals(0L, Random(RAFT_TEST_SEED).nextLong(0L, 1L), "…and nextLong(0, 1) can only ever draw it") },
        // Both sides of the edge, since a bound tested from one side is satisfied by a bound in the wrong place.
        { assertRefused(heartbeat = 999.microseconds) },
        { assertEquals(1.milliseconds, admitted(heartbeat = 1.milliseconds).heartbeatInterval, "one whole millisecond is the smallest admitted") },
        // Zero and negative reach the same draw, and neither of the other two relations refuses them.
        { assertRefused(heartbeat = Duration.ZERO) },
        { assertRefused(heartbeat = -1.milliseconds) },
    )

    /**
     * The mirror of the guard above: adding a check that the window guard's own sub-millisecond case also
     * breaks must not leave that guard with nothing of its own to refuse. It does not — a window can be
     * sub-millisecond *above* one millisecond (`1.5ms..1.9ms`), where the heartbeat floor is satisfied and
     * only the window relation bites.
     */
    @Test
    fun theWindowGuardStillRefusesAPairTheHeartbeatFloorAdmits() = assertAll(
        { assertEquals(1L, 1.milliseconds.inWholeMilliseconds, "the heartbeat floor is satisfied here…") },
        {
            val message = refusalMessage(min = 1500.microseconds, max = 1900.microseconds, heartbeat = 1.milliseconds)
            assertContains(message, "1..1", message = "…so only the window guard can refuse this pair: $message")
        },
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
        {
            val message = refusalMessage(heartbeat = 750.microseconds)
            assertAll(
                { assertContains(message, "heartbeatInterval=750us", message = message) },
                { assertContains(message, "0ms", message = "names the value it truncates to: $message") },
            )
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
        { assertFailsWith<IllegalArgumentException> { RaftConfig().copy(heartbeatInterval = 999.microseconds) } },
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
