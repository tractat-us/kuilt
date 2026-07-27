package us.tractat.kuilt.core.composite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.TransportCapability
import kotlin.coroutines.CoroutineContext

/**
 * A [Loom] that weaves one logical session from several constituent [Loom]s
 * ("plies"). The union of plies covers the session's peer set; the list order is a
 * send-preference hint (most-preferred first).
 *
 * The ply set may change while the session is live: construct with a
 * [StateFlow] of the **desired** set and push a new list to attach or detach
 * plies. Each emission is reconciled against the current live set — new entries
 * are woven in, removed entries are detached. The list constructor is the
 * degenerate case of a never-changing flow.
 *
 * @param plies The desired ply set; emit a new value to reconcile (attach/detach).
 * @param dispatcher Forwarded to each [CompositeSeam] as the scope for its internal
 *   coroutines (scheduling only — the woven seam's thread-safety is via a lock + atomics,
 *   so it is correct under a multi-threaded dispatcher). Production default
 *   ([Dispatchers.Default]); tests inject a dispatcher derived from the test scheduler.
 * @param policy Governs the inbound [us.tractat.kuilt.core.Spool]'s capacity and overflow
 *   behaviour for each woven [CompositeSeam]. Defaults to [DeliveryPolicy.Reliable]
 *   (bounded, backpressured, lossless).
 */
public class CompositeLoom(
    private val plies: StateFlow<List<Pair<PlyId, Loom>>>,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
    private val policy: DeliveryPolicy = DeliveryPolicy.Reliable,
) : Loom {

    /** Static convenience: a fixed ply set that never changes after `weave()`. */
    public constructor(
        plies: List<Pair<PlyId, Loom>>,
        dispatcher: CoroutineContext = Dispatchers.Default,
        policy: DeliveryPolicy = DeliveryPolicy.Reliable,
    ) : this(MutableStateFlow(plies), dispatcher, policy)

    override suspend fun weave(rendezvous: Rendezvous): Seam {
        val current = plies.value
        require(current.isNotEmpty()) { "CompositeLoom desired set must be non-empty at weave()" }
        require(current.map { it.first }.toSet().size == current.size) { "duplicate PlyId" }
        // Each initial ply carries its own Loom's roles, captured from THIS snapshot alongside the seam
        // woven from it. Deliberately not looked up from `plies` later: `loom.weave` suspends and `plies`
        // is caller-mutable, so by the time the seam exists the desired set may no longer contain this
        // ply. Pairing them here makes the initial plies' roles total by construction (#1712).
        val initial = current.map { (id, loom) ->
            InitialPly(id = id, seam = loom.weave(rendezvous), roles = loom.capability().roles)
        }
        return CompositeSeam(initial, rendezvous, plies, dispatcher, policy)
    }

    override fun capability(): TransportCapability {
        val caps = plies.value.map { it.second.capability() }
        val roles = caps.flatMap { it.roles }.toSet()
        // Three-way lattice fold: any ply Available ⇒ Available; else any Unknown ⇒ Unknown
        // (attempt anyway, best-effort — don't collapse an unproven ply to Unavailable); else
        // Unavailable.
        val availability = when {
            caps.any { it.availability is FabricAvailability.Available } -> FabricAvailability.Available
            caps.any { it.availability is FabricAvailability.Unknown } ->
                FabricAvailability.Unknown("no ply available; some unknown")
            else -> FabricAvailability.Unavailable("no ply available")
        }
        return TransportCapability(roles, availability)
    }
}
