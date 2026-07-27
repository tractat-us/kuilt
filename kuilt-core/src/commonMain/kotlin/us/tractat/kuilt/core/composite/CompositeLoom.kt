package us.tractat.kuilt.core.composite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import us.tractat.kuilt.core.CloseReason
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
 * @param onPlyFailure Raised whenever one ply fails inside a live session — a constituent [Loom]'s
 *   `capability()`/`weave()` or a ply [Seam]'s `close()` threw while reconciling, or a peer sent an
 *   inbound frame the ply could not process. The composite absorbs the failure and keeps going: the other
 *   plies still reconcile, a failed attach is retried on the next [plies] emission, and a ply whose
 *   inbound frame failed drops that frame and keeps delivering. `kuilt-core` is logger-free, so this is
 *   how that surfaces to a consumer's own logger. Best-effort and non-suspending; defaults to a silent
 *   absorb. See [PlyReconcileException].
 */
public class CompositeLoom(
    private val plies: StateFlow<List<Pair<PlyId, Loom>>>,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
    private val policy: DeliveryPolicy = DeliveryPolicy.Reliable,
    private val onPlyFailure: (PlyReconcileException) -> Unit = {},
) : Loom {

    /** Static convenience: a fixed ply set that never changes after `weave()`. */
    public constructor(
        plies: List<Pair<PlyId, Loom>>,
        dispatcher: CoroutineContext = Dispatchers.Default,
        policy: DeliveryPolicy = DeliveryPolicy.Reliable,
        onPlyFailure: (PlyReconcileException) -> Unit = {},
    ) : this(MutableStateFlow(plies), dispatcher, policy, onPlyFailure)

    /**
     * Weave every ply in the current desired set and bond them into one [Seam].
     *
     * **All-or-nothing, and it leaks nothing (#1784).** Unlike a live reconciliation — where one ply
     * failing is absorbed and retried — a ply that cannot be woven *here* fails the whole `weave`: the
     * caller gets no [Seam] at all, so `onPlyFailure` never sees it and, with the fixed-list constructor,
     * there is no later list to retry from. Which is why the plies that *did* come up must not simply be
     * dropped: this method is their only holder, and `weave` throwing leaves the caller no handle to close
     * them with. So a failure closes them on the way out before rethrowing. The composite is precisely the
     * type that must not leak a transport across a failed attach.
     */
    override suspend fun weave(rendezvous: Rendezvous): Seam {
        val current = plies.value
        require(current.isNotEmpty()) { "CompositeLoom desired set must be non-empty at weave()" }
        require(current.map { it.first }.toSet().size == current.size) { "duplicate PlyId" }
        // Each initial ply carries its own Loom's roles, captured from THIS snapshot alongside the seam
        // woven from it. Deliberately not looked up from `plies` later: `loom.weave` suspends and `plies`
        // is caller-mutable, so by the time the seam exists the desired set may no longer contain this
        // ply. Pairing them here makes the initial plies' roles total by construction (#1712).
        val woven = mutableListOf<InitialPly>()
        try {
            for ((id, loom) in current) {
                // Roles BEFORE the weave, as in `CompositeSeam.attachDesiredPly` and for the same reason:
                // a throwing `capability()` then has no already-woven transport to orphan.
                val roles = loom.capability().roles
                woven += InitialPly(id = id, seam = loom.weave(rendezvous), roles = roles)
            }
            // Inside the try as well: the constructor starts this composite's pumps, and anything it throws
            // (a consumer seam's `selfId`/flow accessor) would otherwise orphan every transport above.
            return CompositeSeam(woven, rendezvous, plies, dispatcher, policy, onPlyFailure)
        } catch (failure: Throwable) {
            // NonCancellable and per-ply best-effort: the failure may itself BE this coroutine's
            // cancellation, and `Seam.close` suspends on any real transport — so an unshielded close would
            // throw at its first suspension point and leak the very transports being reclaimed. One ply
            // refusing to close must not stop its siblings being closed either.
            withContext(NonCancellable) {
                // Which is why the per-ply guard is `try`/`catch (Throwable)` and NOT
                // `runCatchingCancellable`: inside the shield this block's Job is parented to
                // [NonCancellable], so a `CancellationException` arriving here can only be one the
                // consumer's `close` minted itself (a close-handshake `withTimeout`), never our own.
                // `runCatchingCancellable` rethrows exactly that case — defeating the "one ply must not
                // stop its siblings" half of the paragraph above (#1803). Same shape, same reason, as
                // `CompositeSeam.discardOrphanedPly`.
                woven.forEach {
                    try {
                        it.seam.close(CloseReason.Normal)
                    } catch (_: Throwable) {
                        // Best-effort reclamation: this ply's transport is lost either way.
                    }
                }
            }
            throw failure
        }
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
