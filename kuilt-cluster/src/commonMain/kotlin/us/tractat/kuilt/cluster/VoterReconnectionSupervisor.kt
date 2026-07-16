package us.tractat.kuilt.cluster

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.Mesh
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.core.util.ExponentialBackoff
import kotlin.time.Duration

/**
 * Keep [mesh]'s links to [dialTargets] alive forever by re-dialing any that drop. One child coroutine
 * per target watches "is this peer present?" and, while absent, re-dials under [backoff]; [collectLatest]
 * cancels the redial the instant the peer returns. Only the peers this voter is the *designated dialer*
 * for are in [dialTargets] (the lower-id-dials-higher rule), so no pair is ever double-dialed.
 *
 * Start this AFTER formation, when every [dialTargets] peer is already present — the loops then sit idle
 * until a real drop. Launch on the mesh's own lifecycle scope so it is cancelled with the voter.
 *
 * **Bounded dial ([dialTimeout]).** A redial is fired the instant a peer drops — which routinely
 * coincides with the peer still being *unreachable in a byte-dropping way*: a half-open TCP corpse whose
 * bytes are silently discarded (no FIN/RST), a black-holing firewall, a peer wedged after accepting the
 * TCP connection but before completing the WebSocket upgrade. A raw `dial(peer)` has **no** upper bound
 * on the WebSocket negotiation itself — the client ping only reaps an *established* session, not a
 * negotiation that never reaches `101` — so such a dial can hang **indefinitely**. Because the redial
 * loop is single-flight (it suspends *inside* `dial(peer)`, before `addLink`, before `delay(backoff)`),
 * one hung negotiation wedges the whole loop: the clean redial that would heal the edge once the path
 * recovers is never issued, and the drop becomes permanent. [dialTimeout] bounds every negotiation so a
 * hung dial is abandoned and the backoff loop retries. It is applied with [withTimeoutOrNull] — **not**
 * `withTimeout` — deliberately: `withTimeout` raises a `TimeoutCancellationException`, which
 * [runCatchingCancellable] (correctly) re-throws as cancellation, which would kill the redial coroutine
 * instead of retrying. `withTimeoutOrNull` turns the timeout into a plain `null` the loop can act on.
 */
internal fun CoroutineScope.superviseVoterReconnection(
    mesh: Mesh,
    dialTargets: Set<PeerId>,
    dial: suspend (PeerId) -> Connection,
    backoff: ExponentialBackoff,
    dialTimeout: Duration,
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
                            // Bound the negotiation (see kdoc): a hung dial returns null rather than
                            // wedging the loop. withTimeoutOrNull (not withTimeout) so the timeout is a
                            // value, not a cancellation runCatchingCancellable would re-throw.
                            val conn = withTimeoutOrNull(dialTimeout) { dial(peer) }
                            if (conn == null) {
                                onDialFailure(peer, DialTimeoutException(peer, dialTimeout))
                            } else {
                                runCatchingCancellable { mesh.addLink(conn) }
                                    .onFailure { failure ->
                                        onDialFailure(peer, failure)
                                        // Close the dialed conn on a failed admit so a rejected/errored
                                        // redial does not leak a live WebSocket session (its ping would
                                        // otherwise keep the orphaned socket alive indefinitely).
                                        runCatchingCancellable { conn.close() }
                                    }
                            }
                            delay(backoff.delay(attempt++))
                        }
                    }
                    // present == true → stay suspended until `peer` leaves again.
                }
        }
    }
}

/** Raised through [superviseVoterReconnection]'s `onDialFailure` when a redial negotiation exceeds the dial timeout. */
internal class DialTimeoutException(peer: PeerId, timeout: Duration) :
    Exception("redial to $peer did not complete its WebSocket negotiation within $timeout")
