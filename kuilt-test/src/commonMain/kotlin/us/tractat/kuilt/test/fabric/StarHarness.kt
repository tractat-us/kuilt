package us.tractat.kuilt.test.fabric

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.meshSeam
import us.tractat.kuilt.gossip.ActiveViewPolicy
import us.tractat.kuilt.gossip.GossipSeam
import us.tractat.kuilt.gossip.hostedOverlay
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.time.Instant

/**
 * A started in-memory star: a [hub] [Seam] (GossipSeam FullFanout) and n client GossipSeams.
 * [source] is the hub's accept handle — admit a fresh client via `source.offer(hubEnd)`.
 */
public class Star(
    public val hub: Seam,
    public val clients: List<GossipSeam>,
    public val source: InMemoryConnectionSource,
)

/**
 * Build a star of [n] clients around one hub over [connectionPair] links, wrap each end in a
 * [GossipSeam] (hub = [ActiveViewPolicy.FullFanout], clients = default), and [GossipSeam.start]
 * them on the receiver scope. The hub is composed by [hostedOverlay] over an
 * [InMemoryConnectionSource] — one composition path, not two; [Star.source] is the production-
 * faithful reconnect handle. Client i is `PeerId("client-i")`. Per-peer seeded RNG.
 *
 * All mesh handshakes run concurrently (the hub's accept-pump and each client handshake in
 * parallel) — serial wiring would deadlock because each Hello preamble exchange requires both
 * ends to be reading simultaneously. The hub's accept-pump is launched on the receiver scope
 * (not inside `coroutineScope`), so it is an infinite background coroutine that does not block
 * the scope from returning.
 */
public suspend fun CoroutineScope.inMemoryStarOf(
    n: Int,
    hubId: PeerId = PeerId("hub"),
    random: Random = Random(0L),
): Star {
    val dispatcher = currentCoroutineContext()[ContinuationInterceptor]!!
    val clock: () -> Instant = { Instant.fromEpochMilliseconds(0) }
    val source = InMemoryConnectionSource()

    // Queue all hub-ends before starting the hub; the accept-pump drains them as soon as it runs.
    val clientConnections = ArrayList<Pair<PeerId, us.tractat.kuilt.core.fabric.Connection>>(n)
    for (i in 0 until n) {
        val (hubEnd, clientEnd) = connectionPair()
        source.offer(hubEnd)
        clientConnections += PeerId("client-$i") to clientEnd
    }

    // Start the hub on the outer scope — its accept-pump is a background coroutine on this scope,
    // not inside coroutineScope, so it never blocks the scope from completing.
    val hub = hostedOverlay(hubId, source, dispatcher, Random(random.nextLong()), clock)

    // All client handshakes run concurrently with the hub's already-running accept-pump.
    val clients = coroutineScope {
        clientConnections.map { (id, conn) ->
            async {
                GossipSeam(meshSeam(id, listOf(conn), dispatcher), Random(random.nextLong()), clock)
                    .also { it.start(this@inMemoryStarOf) }
            }
        }.awaitAll()
    }

    return Star(hub, clients, source)
}
