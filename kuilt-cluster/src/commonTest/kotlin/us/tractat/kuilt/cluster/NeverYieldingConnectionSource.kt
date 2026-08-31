package us.tractat.kuilt.cluster

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.ConnectionSource

/**
 * A [ConnectionSource] whose [accept] suspends forever — the "this voter's roster never fills" stall
 * every formation-timeout test is built on, plus the observability that makes the *teardown* provable.
 *
 * [accepting] completes when the accept-pump has actually entered `accept()` (so a test can tell
 * "the pump never started" from "the pump started and was left running"), and [cancelled] completes
 * from the cancellation `finally` — which is exactly the "the accept-pump was torn down" signal
 * [assembleVoterMesh]'s formation-failure path is required to produce. A test that awaits [cancelled]
 * is therefore red on any code that fails formation without cancelling the mesh scope.
 *
 * Shared by the deterministic [VoterMeshFormationTimeoutTest] and the real-socket
 * `WebSocketVoterMeshFormationTimeoutTest`, so both pin the same signal with one definition of it.
 */
internal class NeverYieldingConnectionSource : ConnectionSource {

    /** Completes once the accept-pump has entered [accept]. */
    val accepting: CompletableDeferred<Unit> = CompletableDeferred()

    /** Completes once the [accept] suspension is cancelled — i.e. the accept-pump was torn down. */
    val cancelled: CompletableDeferred<Unit> = CompletableDeferred()

    override suspend fun accept(): Connection {
        accepting.complete(Unit)
        try {
            awaitCancellation()
        } finally {
            cancelled.complete(Unit)
        }
    }
}
