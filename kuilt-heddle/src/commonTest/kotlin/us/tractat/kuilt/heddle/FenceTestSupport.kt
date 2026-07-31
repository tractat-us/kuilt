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
    val liveEdge = live.single()
    val retired = retiredInboundEdges(child)
    // Mirrors `HeddleControlPlane.reconcile`'s §5.2 parent gate (#1916) — read its comment for why.
    // The helper claims to model the WHOLE fence, so omitting the gate here would let a ledger-level
    // test "prove" a cross-parent move sound when production refuses it.
    val liveParent = record(liveEdge)?.parent
        ?: return Relocation.Refused("live inbound edge ${liveEdge.value} has no single record")
    for (s in retired) {
        val strandedParent = record(s)?.parent
        if (strandedParent != liveParent) {
            return Relocation.Refused(
                "${s.value} hangs off ${strandedParent?.value} but the live edge ${liveEdge.value} " +
                    "hangs off ${liveParent.value} — a strand may only be re-homed within one parent",
            )
        }
    }
    return relocationPatch(liveEdge, retired.associateWith { baseFinalsOn(it) })
}

/** The derived patch, or `null` when the move was refused or found nothing to do. */
internal fun EntitlementLedger.relocationOrNull(child: GroupId): EntitlementLedger? =
    (relocateFromConvergedView(child) as? Relocation.Moved)?.patch
