package us.tractat.kuilt.heddle

/**
 * Shared support for the #1693 relocation-fence suites.
 *
 * [relocateFromConvergedView] models the **whole** fence in one line for a test that is about the
 * *arithmetic* rather than the protocol: it takes the acked finals to be each replica's base slots
 * on a converged view, which is exactly what every peer's [ControlCommand.QuiesceAck] declares once
 * the barrier has closed and anti-entropy has run. Tests that are about the protocol — who may ack,
 * what a missing ack blocks, what a straggler charge does — drive `HeddleControlPlane` for real
 * (see `HeddleFenceTest`).
 */
internal fun EntitlementLedger.relocateFromConvergedView(child: GroupId): Relocation {
    val live = liveInboundEdges(child)
    if (live.size != 1) return Relocation.Refused("no unique live inbound for ${child.value}")
    val finals = retiredInboundEdges(child).associateWith { baseFinalsOn(it) }
    return relocationPatch(live.single(), finals)
}

/** The derived patch, or `null` when the move was refused or found nothing to do. */
internal fun EntitlementLedger.relocationOrNull(child: GroupId): EntitlementLedger? =
    (relocateFromConvergedView(child) as? Relocation.Moved)?.patch
