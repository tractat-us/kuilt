package us.tractat.kuilt.multipeer.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import us.tractat.kuilt.core.Seam

/**
 * What lives behind a session-handle pointer in the JVM ↔ macOS MC bridge.
 *
 * The base session handle just needs to wrap a [Seam] (so `broadcast`
 * and `close` work). Once the JVM side wires data + peer-state callbacks
 * (`mc_session_set_data_callback`, `mc_session_set_peer_state_callback`),
 * the K/N coroutines that pump those events live here too — one [Job] per
 * channel — so [cancelPumps] can cancel them deterministically before the
 * underlying `MCSession` disconnects.
 *
 * Internal-only; the JVM never sees this type.
 */
internal class BridgeSessionState(
    val link: Seam,
) {
    val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    var dataPumpJob: Job? = null
    var peerStatePumpJob: Job? = null

    fun cancelPumps() {
        dataPumpJob?.cancel()
        peerStatePumpJob?.cancel()
        dataPumpJob = null
        peerStatePumpJob = null
        scope.cancel()
    }
}
