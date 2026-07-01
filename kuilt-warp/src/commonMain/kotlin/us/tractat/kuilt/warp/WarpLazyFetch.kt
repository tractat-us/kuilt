package us.tractat.kuilt.warp

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The all-or-nothing capability bundle that enables lazy bobbin fetch-and-run.
 *
 * A [us.tractat.kuilt.warp.WarpNode] constructed with a [WarpLazyFetch] can resolve an
 * unknown [OpId] at execution time: [opToBobbin] maps the op name to its content address,
 * [creel] supplies cached bytes (or `null` when not yet fetched), and [runtime] compiles
 * those bytes into a runnable [Op] under the capability sandbox.
 *
 * **Carries a [Creel], not a `BobbinExchange`.** The fetch-transport layer (warp slice C5)
 * is wired above this bundle; this type is intentionally pure data so it can be constructed
 * and passed across module boundaries without importing the exchange machinery.
 *
 * **Runtime lifecycle is the caller's.** The [runtime] is **owned by the caller**, not by the
 * [us.tractat.kuilt.warp.WarpNode] it is handed to: it outlives the node, is shared freely, and is
 * **never** closed by the node. The caller MUST `close()` it when done — a Chicory-backed
 * runtime (in `:kuilt-warp-runtime`), for example, holds a daemon executor thread that only
 * the caller's `close()` releases.
 *
 * @param creel The local content-addressed byte cache.
 * @param runtime The caller-owned sandbox that compiles WASM bytes into a runnable [Op]; the
 *   caller retains ownership and must `close()` it (see above).
 * @param opToBobbin Maps a missing [OpId] to the [BobbinHash] that holds its WASM bytes,
 *   or `null` if the op is not backed by a fetchable bobbin.
 * @param fetchTimeout Upper bound on a single lazy fetch. A fetch suspends until some peer
 *   serves the bytes; if none does within this window the [us.tractat.kuilt.warp.WarpNode]
 *   treats it as **transient** — it stands the task by (unclaims it, records no result) so
 *   anti-entropy re-evaluates on a later cycle, rather than hanging forever holding the claim.
 *   A timeout is **never** terminal: an absent bobbin may simply be in flight from a peer that
 *   has not finished joining. The default (30 s) sits well above the per-op execution timeout
 *   (1 s) and the claim settle window (500 ms) so a transiently-slow holder is not abandoned
 *   prematurely, yet well below any horizon at which a permanently-missing bobbin should keep a
 *   task claimed. Drives [kotlinx.coroutines.withTimeoutOrNull], so it honours virtual time.
 */
public class WarpLazyFetch(
    public val creel: Creel,
    public val runtime: WasmRuntime,
    /** How a missing op names the bobbin to fetch. */
    public val opToBobbin: (OpId) -> BobbinHash?,
    public val fetchTimeout: Duration = 30.seconds,
) {
    init {
        require(fetchTimeout.isPositive()) {
            "fetchTimeout must be positive, was $fetchTimeout"
        }
    }
}
