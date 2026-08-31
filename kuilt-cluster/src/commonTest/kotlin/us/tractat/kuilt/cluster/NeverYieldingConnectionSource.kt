package us.tractat.kuilt.cluster

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.ConnectionSource

/**
 * A [ConnectionSource] whose [accept] suspends forever — the "this voter's roster never fills" stall
 * every formation-timeout test is built on, plus the observability that makes the *teardown* provable.
 *
 * [accepting] completes when the accept-pump has actually entered [accept]; [cancelled] completes from
 * that suspension's cancellation `finally`. The pair is what lets a test tell **"the pump never
 * started"** from **"the pump started and was left running"** — without [accepting] a green would be
 * indistinguishable from a rig that never fired.
 *
 * ## Why [cancelled] can only mean "the pump job was cancelled"
 *
 * `acceptPump` bounds `handle(conn)` — **not** `source.accept()` — with its `handshakeTimeout`, so no
 * timer inside the pump can ever cancel this suspension. Nothing offers a connection here either. The
 * only reachable cancellation is the pump [kotlinx.coroutines.Job]'s own, which on the formation-failure
 * path arrives from `assembleVoterMesh`'s `meshScope.cancel()`. [cancelled] is therefore a signal for
 * that teardown and nothing else.
 *
 * Shared by the deterministic [VoterMeshFormationTimeoutTest] and the opt-in real-socket
 * `WebSocketVoterMeshFormationTimeoutTest`, so both pin the same signal with one definition of it.
 */
internal class NeverYieldingConnectionSource : ConnectionSource {

    /** Completes once the accept-pump has entered [accept] — the rig-fired precondition. */
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
