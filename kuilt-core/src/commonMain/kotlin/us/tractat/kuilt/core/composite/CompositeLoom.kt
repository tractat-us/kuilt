package us.tractat.kuilt.core.composite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import kotlin.coroutines.CoroutineContext

/**
 * A [Loom] that weaves one logical session from several constituent [Loom]s
 * ("plies"). The union of plies covers the session's peer set; the list order is a
 * send-preference hint (most-preferred first).
 *
 * The ply set may change while the session is live: construct with a
 * [StateFlow] of the **desired** set and push a new list to attach or detach
 * plies. The list constructor is the degenerate case of a never-changing flow.
 * See `docs/superpowers/specs/2026-06-04-dynamic-ply-attach-detach-design.md`.
 *
 * @param plies The desired ply set; emit a new value to reconcile (attach/detach).
 * @param dispatcher Forwarded to each [CompositeSeam] as the scope for its internal
 *   coroutines (scheduling only — the woven seam's thread-safety is via a lock + atomics,
 *   so it is correct under a multi-threaded dispatcher). Production default
 *   ([Dispatchers.Default]); tests inject a dispatcher derived from the test scheduler.
 */
public class CompositeLoom(
    private val plies: StateFlow<List<Pair<PlyId, Loom>>>,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : Loom {

    /** Static convenience: a fixed ply set that never changes after `weave()`. */
    public constructor(
        plies: List<Pair<PlyId, Loom>>,
        dispatcher: CoroutineContext = Dispatchers.Default,
    ) : this(MutableStateFlow(plies), dispatcher)

    override suspend fun weave(rendezvous: Rendezvous): Seam {
        val current = plies.value
        require(current.isNotEmpty()) { "CompositeLoom desired set must be non-empty at weave()" }
        require(current.map { it.first }.toSet().size == current.size) { "duplicate PlyId" }
        val initial = current.map { (id, loom) -> id to loom.weave(rendezvous) }
        return CompositeSeam(initial, rendezvous, plies, dispatcher)
    }

    override fun availability(): FabricAvailability =
        if (plies.value.any { it.second.availability() == FabricAvailability.Available }) {
            FabricAvailability.Available
        } else {
            FabricAvailability.Unavailable("no ply available")
        }
}
