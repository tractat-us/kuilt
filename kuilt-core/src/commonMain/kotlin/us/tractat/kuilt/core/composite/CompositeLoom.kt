package us.tractat.kuilt.core.composite

import kotlinx.coroutines.Dispatchers
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import kotlin.coroutines.CoroutineContext

/**
 * A [Loom] that weaves one logical session from several constituent [Loom]s
 * ("plies") at once. The union of plies covers the session's peer set; the
 * list order is a send-preference hint (most-preferred first). See
 * `docs/superpowers/specs/2026-05-30-multi-transport-ply-fabric-design.md`.
 *
 * @param dispatcher Forwarded to each [CompositeSeam] as its internal dispatcher.
 *   The production default ([Dispatchers.Default.limitedParallelism(1)]) confines
 *   all mutable state access to a single thread, preventing data races. Tests
 *   should inject [UnconfinedTestDispatcher] so reconciliation runs eagerly.
 */
public class CompositeLoom(
    private val plies: List<Pair<PlyId, Loom>>,
    private val dispatcher: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
) : Loom {
    init {
        require(plies.isNotEmpty()) { "CompositeLoom needs at least one ply" }
        require(plies.map { it.first }.toSet().size == plies.size) { "duplicate PlyId" }
    }

    override suspend fun weave(rendezvous: Rendezvous): Seam {
        val woven = plies.map { (id, loom) -> id to loom.weave(rendezvous) }
        return CompositeSeam(woven, dispatcher)
    }

    override fun availability(): FabricAvailability =
        if (plies.any { it.second.availability() == FabricAvailability.Available }) {
            FabricAvailability.Available
        } else {
            FabricAvailability.Unavailable("no ply available")
        }
}
