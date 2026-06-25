package us.tractat.kuilt.test.fabric

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.Mesh
import us.tractat.kuilt.core.fabric.meshSeam
import us.tractat.kuilt.gossip.ActiveViewPolicy
import us.tractat.kuilt.gossip.GossipSeam
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.time.Instant

/**
 * A started in-memory star: a [hub] GossipSeam (FullFanout) and n client GossipSeams. [hubMesh]
 * is the hub's base mesh, exposed so reconnect tests can admit a fresh client link via [Mesh.addLink].
 */
public class Star(
    public val hub: GossipSeam,
    public val clients: List<GossipSeam>,
    public val hubMesh: Mesh,
)

/**
 * Build a star of [n] clients around one hub over [connectionPair] links, wrap each end in a
 * [GossipSeam] (hub = [ActiveViewPolicy.FullFanout], clients = default), and [GossipSeam.start]
 * them on the receiver scope. The hub holds one link per client; each client holds one link to
 * the hub. Client i is `PeerId("client-i")`. Per-peer seeded RNG.
 *
 * All [meshSeam] handshakes run concurrently (hub and each client call in parallel) — serial
 * wiring would deadlock because each [Hello] preamble exchange requires both ends to be reading
 * simultaneously.
 */
public suspend fun CoroutineScope.inMemoryStarOf(
    n: Int,
    hubId: PeerId = PeerId("hub"),
    random: Random = Random(0L),
): Star {
    val dispatcher = currentCoroutineContext()[ContinuationInterceptor]!!
    val clock: () -> Instant = { Instant.fromEpochMilliseconds(0) }

    val hubConnections = ArrayList<Connection>(n)
    val clientConnections = ArrayList<Pair<PeerId, Connection>>(n)
    for (i in 0 until n) {
        val (hubEnd, clientEnd) = connectionPair()
        hubConnections += hubEnd
        clientConnections += PeerId("client-$i") to clientEnd
    }

    // Hub and all clients must handshake concurrently — Hello preambles must cross in parallel.
    val (hubBase, clientBases) = coroutineScope {
        val hubDeferred = async {
            meshSeam(selfId = hubId, connections = hubConnections, dispatcher = dispatcher)
        }
        val clientDeferreds = clientConnections.map { (id, conn) ->
            id to async {
                meshSeam(selfId = id, connections = listOf(conn), dispatcher = dispatcher)
            }
        }
        val hub = hubDeferred.await()
        val clients = clientDeferreds.map { (id, deferred) -> id to deferred.await() }
        hub to clients
    }

    val hub = GossipSeam(
        base = hubBase,
        random = Random(random.nextLong()),
        clock = clock,
        activeViewPolicy = ActiveViewPolicy.FullFanout,
    ).also { it.start(this) }

    val clients = clientBases.map { (_, base) ->
        GossipSeam(
            base = base,
            random = Random(random.nextLong()),
            clock = clock,
        ).also { it.start(this) }
    }

    return Star(hub, clients, hubBase)
}
