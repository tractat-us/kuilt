package us.tractat.kuilt.cluster

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.Mesh
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.core.util.ExponentialBackoff

/**
 * Keep [mesh]'s links to [dialTargets] alive forever by re-dialing any that drop. One child coroutine
 * per target watches "is this peer present?" and, while absent, re-dials under [backoff]; [collectLatest]
 * cancels the redial the instant the peer returns. Only the peers this voter is the *designated dialer*
 * for are in [dialTargets] (the lower-id-dials-higher rule), so no pair is ever double-dialed.
 *
 * Start this AFTER formation, when every [dialTargets] peer is already present — the loops then sit idle
 * until a real drop. Launch on the mesh's own lifecycle scope so it is cancelled with the voter.
 */
internal fun CoroutineScope.superviseVoterReconnection(
    mesh: Mesh,
    dialTargets: Set<PeerId>,
    dial: suspend (PeerId) -> Connection,
    backoff: ExponentialBackoff,
    onDialFailure: (PeerId, Throwable) -> Unit = { _, _ -> },
): Job = launch {
    for (peer in dialTargets) {
        launch {
            mesh.peers
                .map { peer in it }
                .distinctUntilChanged()
                .collectLatest { present ->
                    if (!present) {
                        var attempt = 0
                        while (true) {
                            // Guard: full jitter's lower bound is ~0ms, so on a multi-threaded dispatcher
                            // the redial below could fire once more before collectLatest processes the
                            // post-addLink `true` emission. Cheap re-check avoids that redundant dial.
                            if (peer in mesh.peers.value) break
                            runCatchingCancellable { mesh.addLink(dial(peer)) }
                                .onFailure { onDialFailure(peer, it) }
                            delay(backoff.delay(attempt++))
                        }
                    }
                    // present == true → stay suspended until `peer` leaves again.
                }
        }
    }
}
