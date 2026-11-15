package us.tractat.kuilt.otel

/**
 * [WarpCausalClock.tick] for a test whose clock must be able to mint: the stamp, or an
 * [AssertionError] naming why there is none.
 */
internal fun WarpCausalClock.mint(): CausalStamp = when (val tick = tick()) {
    is ClockTick.Minted -> tick.stamp
    ClockTick.Unrecovered -> throw AssertionError("the clock is unrecovered, so it minted nothing")
}
