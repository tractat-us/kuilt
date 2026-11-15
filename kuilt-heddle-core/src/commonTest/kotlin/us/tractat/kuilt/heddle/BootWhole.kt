package us.tractat.kuilt.heddle

/**
 * [LocalHeddle.fromTopology]'s engine, for a test whose [topology] must boot whole: a refused
 * group fails the test here, rather than leaving a smaller tree for the test to measure.
 */
internal fun LocalHeddle.Companion.bootWhole(topology: Topology, policy: LocalPolicy, epoch: Long): LocalHeddle {
    val boot = fromTopology(topology, policy, epoch)
    check(boot.result.refusals.isEmpty()) { "the boot left out ${boot.result.refusals}" }
    return boot.heddle
}
