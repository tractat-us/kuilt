package us.tractat.kuilt.otel

import us.tractat.kuilt.store.InMemoryDurableStore

/**
 * [WarpCausalClock.tick] for a test whose clock must be able to mint: the stamp, or an
 * [AssertionError] naming why there is none.
 */
internal fun WarpCausalClock.mint(): CausalStamp = when (val tick = tick()) {
    is ClockTick.Minted -> tick.stamp
    ClockTick.Unrecovered -> throw AssertionError("the clock is unrecovered, so it minted nothing")
    ClockTick.Exhausted -> throw AssertionError("the clock is exhausted, so it minted nothing")
}

/**
 * This clock, recovered from an empty store so its read has landed and it can mint from `seq=1`:
 * the fixture for a test that has no durable state to resume.
 */
internal suspend fun WarpCausalClock.armed(): WarpCausalClock = apply {
    val recovery = recover(InMemoryDurableStore())
    if (recovery != ClockRecovery.Recovered) throw AssertionError("an empty store did not recover: $recovery")
}
