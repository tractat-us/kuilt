package us.tractat.kuilt.gossip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.ConnectionSource
import us.tractat.kuilt.core.fabric.meshSeam
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Compose a started hub [Seam] from a [ConnectionSource]: an initially-empty [meshSeam] wrapped in
 * a [GossipSeam] with [ActiveViewPolicy.FullFanout] (the hub floods every broadcast to all spokes),
 * plus an accept-pump that [addLink][us.tractat.kuilt.core.fabric.Mesh.addLink]s each accepted
 * [us.tractat.kuilt.core.fabric.Connection] so clients join the running hub as they connect. The
 * pump coroutine lives on the receiver scope and is torn down with it.
 *
 * This is the production form of the in-memory star the test harness composes by hand; the harness
 * is re-expressed on top of it so there is one composition path.
 */
public suspend fun CoroutineScope.hostedOverlay(
    selfId: PeerId,
    source: ConnectionSource,
    dispatcher: CoroutineContext,
    random: Random = Random(0L),
    clock: () -> Instant = { Clock.System.now() },
): Seam {
    val hubMesh = meshSeam(selfId = selfId, connections = emptyList(), dispatcher = dispatcher)
    val hub = GossipSeam(
        base = hubMesh,
        random = random,
        clock = clock,
        activeViewPolicy = ActiveViewPolicy.FullFanout,
    ).also { it.start(this) }
    launch { while (isActive) hubMesh.addLink(source.accept()) }
    return hub
}
