package us.tractat.kuilt.conformance

import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.fabric.Connection

/**
 * The `injectMidSessionDeath` rig shared by [IdentifiedConformanceTest] and
 * [HandshakingConformanceTest]: drop **both** ends of the in-memory [link] out from under a live
 * [host]/[joiner] pair.
 *
 * Both ends, because [Connection.close] closes only that end's *outbound* spool — which is the
 * peer's inbound. One close kills one direction; each side observes its peer's disconnect (a
 * *remote* death, the thing under test) only once both are dropped. No `Seam.close()` is called
 * anywhere: the seams learn of the death from their own read loops reaching EOF, which is exactly
 * the half of the `incoming`-completes-on-`Torn` contract a local close cannot exercise. The same
 * rig `PeerMeshConformanceTest` runs over the same primitive.
 *
 * ## Why the rig asserts its own precondition
 *
 * The `check` is the difference between a conversion and a false green. Returning `true` obliges
 * [SeamConformanceSuite.incomingCompletesOnInjectedMidSessionDeath] to assert, but that assertion
 * only reads terminal state — it would pass just as happily on a pair that was **already** `Torn`
 * before this function ran, crediting a tear this rig had not caused. Asserting liveness first
 * makes the injection prove that it is the thing being observed, rather than inheriting a verdict.
 * A rig nobody can see fire is precisely the silent skip #1442 exists to prevent, one level down.
 *
 * @return `false` when no link was captured — which leaves the harness honestly *unproven*
 *   (and so still obliged to declare a gap) rather than falsely green.
 */
internal suspend fun dropBothEnds(
    link: Pair<Connection, Connection>?,
    host: Seam,
    joiner: Seam,
): Boolean {
    val (hostConnection, joinerConnection) = link ?: return false
    check(host.state.value !is SeamState.Torn && joiner.state.value !is SeamState.Torn) {
        "mid-session-death rig precondition: both seams must be live before the transport is " +
            "dropped, or the obligation would pass on a tear this rig did not cause; got " +
            "host=${host.state.value}, joiner=${joiner.state.value}"
    }
    joinerConnection.close()
    hostConnection.close()
    return true
}
