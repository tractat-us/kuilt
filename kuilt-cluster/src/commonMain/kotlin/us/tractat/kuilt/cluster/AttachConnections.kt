package us.tractat.kuilt.cluster

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.runCatchingCancellable

/**
 * Keep an [OverlayServer]'s attachment **directory** in step with a server's live client
 * connections — the production, connection-layer form of "publish each connected player into the
 * routing directory so the rest of the core relays to me".
 *
 * Collects [connectedPeers] (typically `MuxServerLoom.connectedPeers` — a server loom's set of live
 * client links) once, on the injected [scope]:
 *
 * - a peer **appearing** (and not one of the [core] servers) is published via
 *   [OverlayServer.attachDirectoryOnly] — the leader's `RoutedRaftTransport` then reads
 *   `player → this server` from the directory to pick its relay hop;
 * - a peer **disappearing** is retracted via [OverlayServer.detachDirectoryOnly] — parity with a
 *   clean [OverlayServer.evict], a last-writer-wins tombstone that stops peers routing here once it
 *   converges. The tombstone is race-safe on failover: a re-attach on a surviving server supersedes
 *   it under last-writer-wins, so an in-flight detach never strands a re-homed player.
 *
 * ## Directory-only, by design
 *
 * This path drives **only the directory half** of the overlay, not [OverlayServer.admit]'s
 * attach-and-register pair. On the room-hub game topology a player's frames are delivered by the
 * hosting room seam's own membership — there is **no per-connection app-unicast spoke** to register
 * in the [RoutedUnicastRouter] — so [attachDirectoryOnly]/[detachDirectoryOnly] are the correct,
 * documented exception to `admit`'s invariant (see their KDoc). Follow-up **#1384** tracks
 * converging this game-path publisher with `admit`'s spoke-coupled path onto one mechanism.
 *
 * [core] members are excluded so a core server that ever appears among the connections is never
 * published as an attachment of itself.
 *
 * @param connectedPeers the live-client-connection set to mirror into the directory; collected once.
 * @param overlay the [OverlayServer] whose attachment directory is kept in step.
 * @param core the core-server ids to exclude from publication (never directory-attach a core member).
 * @receiver the [CoroutineScope] the collector is launched on — **required**, no real-dispatcher
 *   default; inject a test scope's `backgroundScope` under virtual time.
 */
public fun CoroutineScope.attachConnections(
    connectedPeers: StateFlow<Set<PeerId>>,
    overlay: OverlayServer,
    core: Set<PeerId>,
) {
    launch {
        val attached = mutableSetOf<PeerId>()
        connectedPeers.collect { peers ->
            for (peer in peers) {
                if (peer !in core && peer !in attached) {
                    runCatchingCancellable { overlay.attachDirectoryOnly(peer) }
                        .onSuccess { attached += peer }
                }
            }
            val gone = attached.filter { it !in peers }
            for (peer in gone) {
                runCatchingCancellable { overlay.detachDirectoryOnly(peer) }
                attached -= peer
            }
        }
    }
}
