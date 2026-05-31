package us.tractat.kuilt.core.composite

import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam

/**
 * A [Loom] that weaves one logical session from several constituent [Loom]s
 * ("plies") at once. The union of plies covers the session's peer set; the
 * list order is a send-preference hint (most-preferred first). See
 * `docs/superpowers/specs/2026-05-30-multi-transport-ply-fabric-design.md`.
 */
public class CompositeLoom(
    private val plies: List<Pair<PlyId, Loom>>,
) : Loom {
    init {
        require(plies.isNotEmpty()) { "CompositeLoom needs at least one ply" }
        require(plies.map { it.first }.toSet().size == plies.size) { "duplicate PlyId" }
    }

    override suspend fun weave(rendezvous: Rendezvous): Seam {
        val woven = plies.map { (id, loom) -> id to loom.weave(rendezvous) }
        return CompositeSeam(woven)
    }

    override fun availability(): FabricAvailability =
        if (plies.any { it.second.availability() == FabricAvailability.Available }) {
            FabricAvailability.Available
        } else {
            FabricAvailability.Unavailable("no ply available")
        }
}
