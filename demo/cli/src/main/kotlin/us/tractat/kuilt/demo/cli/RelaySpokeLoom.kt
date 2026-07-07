package us.tractat.kuilt.demo.cli

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.gossip.GossipSeam
import us.tractat.kuilt.websocket.KtorMeshClientLoom
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The client half of the Patchwork star: a [Loom] whose every [weave] opens a
 * fresh WebSocket spoke into the relay hub and wraps it in a started
 * [GossipSeam], so broadcasts ride the hub's full-fanout flood to every other
 * peer (`:demo-relay`'s `hostedOverlay` counterpart — hub-spoke mesh over
 * [KtorMeshClientLoom]).
 *
 * **Fresh fabric identity per connection.** A reconnecting peer that reused
 * its previous [PeerId] would collide with every other peer's gossip-dedup
 * high-water for that origin (a fresh `GossipSeam` restarts its per-origin
 * sequence at 1, which then reads as already-seen) — the same rebooted-author
 * hazard `PatchworkSession` documents for its per-connection replica ids. So
 * each weave mints `"<peerName>-<uuid>"`; the durable identity that tags
 * stitches is the session's `stitcher`, not the fabric peer id.
 *
 * @param httpClient shared Ktor client with the WebSockets plugin installed;
 *   caller owns its lifecycle.
 * @param scope runs each spoke's gossip loops; tear it down to kill them.
 * @param peerName human-readable prefix for the per-connection peer ids.
 */
@OptIn(ExperimentalUuidApi::class)
class RelaySpokeLoom(
    private val httpClient: HttpClient,
    private val scope: CoroutineScope,
    private val peerName: String,
    private val random: Random = Random.Default,
    private val clock: () -> Instant = { Clock.System.now() },
) : Loom {
    override suspend fun weave(rendezvous: Rendezvous): Seam {
        val spoke = KtorMeshClientLoom(
            httpClient = httpClient,
            selfPeerId = PeerId("$peerName-${Uuid.random()}"),
        ).weave(rendezvous)
        // Zero recompute jitter, mirroring hostedOverlay's hub: a spoke's active
        // view is deterministic (the hub, full stop), so the anti-lockstep jitter
        // buys nothing and only opens a window right after connecting where
        // broadcast has no flood targets — a stitch in that window would wait for
        // anti-entropy instead of appearing live.
        return GossipSeam(base = spoke, random = random, clock = clock, jitter = Duration.ZERO..Duration.ZERO)
            .also { it.start(scope) }
    }
}
