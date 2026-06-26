package us.tractat.kuilt.warp

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
 * **never** closed by the node. The caller MUST `close()` it when done — a [ChicoryWasmRuntime],
 * for example, holds a daemon executor thread that only the caller's `close()` releases.
 *
 * @param creel The local content-addressed byte cache.
 * @param runtime The caller-owned sandbox that compiles WASM bytes into a runnable [Op]; the
 *   caller retains ownership and must `close()` it (see above).
 * @param opToBobbin Maps a missing [OpId] to the [BobbinHash] that holds its WASM bytes,
 *   or `null` if the op is not backed by a fetchable bobbin.
 */
public class WarpLazyFetch(
    public val creel: Creel,
    public val runtime: WasmRuntime,
    /** How a missing op names the bobbin to fetch. */
    public val opToBobbin: (OpId) -> BobbinHash?,
)
