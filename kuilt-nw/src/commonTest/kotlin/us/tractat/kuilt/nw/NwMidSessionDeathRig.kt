package us.tractat.kuilt.nw

/**
 * The `injectMidSessionDeath` rig the three `kuilt-nw` conformance harnesses share.
 *
 * ## What it is for, which is NOT proving the obligation
 *
 * `SeamConformanceSuite.incomingCompletesOnInjectedMidSessionDeath` requires both ends to latch
 * `Torn` on an injected transport death. `NwSeam` deliberately does not: since #1513 losing the last
 * remote re-forms `Woven`→`Weaving` and keeps `incoming` open so `NwLoom` can redial, and `Torn`
 * means only an explicit `close()` or a weave timeout. So all three harnesses declare
 * `ObligationDeclaration.NotApplicable.ContractDiffers` — and that arm has to be **demonstrated**,
 * not asserted: `midSessionDeathDeclarationIsHonest` injects through this rig and then requires the
 * obligation's own postcondition to fail. A harness cannot claim its fabric answers a death
 * differently without performing the death and being watched, and the day someone re-introduces
 * tear-on-peer-loss the declaration reds (#2568).
 *
 * ## Why two functions rather than one
 *
 * The two backings expose a live link through different surfaces, and neither covers the other.
 * `NwApi.connectionStates` carries the caveat that **absence means nothing** — `FakeNwApi` populates
 * it only from an explicit viability transition or a close, so a healthy fake link is simply not in
 * the map and [dropEveryLiveConnection] would find nothing there. `FakeNwRadio` is where the fake's
 * links actually live, and it is not an `NwApi` at all. Rather than teach the fake to publish a state
 * it deliberately does not publish, each backing is dropped through the surface that really holds its
 * links.
 *
 * Both return a **count** so the caller can assert its own precondition: a rig that injected nothing
 * must return `false` from the hook and leave the harness honestly undeclared, never credit a
 * deviation to an injection that did not happen.
 */
internal suspend fun dropEveryLiveConnection(apis: List<NwApi>): Int {
    var dropped = 0
    apis.forEach { api ->
        // [NwConnState.Viable] ONLY — not merely "not Closed". A [NwConnState.PathLost] connection has
        // already gone silently unreachable (`ready -> waiting`, with no `connectionClosed` to come), so
        // disconnecting it severs nothing while still incrementing this count — and the count is what the
        // caller's hook turns into "the deviation was demonstrated". Crediting an injection that severed
        // a link already dead is the vacuity this rig exists to prevent, one level down. Snapshot first:
        // `disconnect` mutates the map this is derived from.
        val live = api.connectionStates.value.filterValues { it is NwConnState.Viable }.keys.toList()
        live.forEach { id ->
            api.disconnect(id)
            dropped++
        }
    }
    return dropped
}
