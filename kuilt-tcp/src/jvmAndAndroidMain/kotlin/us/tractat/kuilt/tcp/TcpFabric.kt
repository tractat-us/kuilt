package us.tractat.kuilt.tcp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.freshPeerId
import kotlin.coroutines.CoroutineContext

/**
 * Open a raw-TCP session on a socket you already listen on, and wait for one peer to dial in.
 *
 * Call this on the machine that owns the listening socket. Bind it first —
 * `aSocket(selector).tcp().bind(host, port)` — hand it here, and the returned loom's
 * [us.tractat.kuilt.core.Loom.host] accepts exactly one connection and gives you a two-peer
 * [us.tractat.kuilt.core.Seam]. The peer at the other end reaches you with [tcpLoomJoin] and
 * the same host and port.
 *
 * This builds the same loom [TcpLoom.host] does. What it adds is the argument *order* every
 * kuilt fabric factory shares (#1430): the fabric's own required arguments first, then the
 * universal knobs — `selfId`, `policy`, then the dispatchers. [TcpLoom.host] keeps its own older
 * order and remains the surface for dependency injection and tests.
 *
 * One knob the convention names is **deliberately absent**, because this fabric cannot honour it,
 * and an argument that is accepted and then ignored is worse than one that does not exist:
 *
 * - **`weaveTimeout`** — `weave` puts no ceiling on `accept()` or `connect()`. A host that waits
 *   indefinitely for its first joiner is this fabric's intended behaviour, not an oversight, so
 *   there is nothing here a timeout could bound without changing what the loom means.
 *
 * `policy` used to be a second such omission, for a reason that has since been fixed rather than
 * worked around: [us.tractat.kuilt.core.fabric.handshaking] — the in-band identity negotiation
 * this fabric goes through — dropped the [DeliveryPolicy] on the way to
 * [us.tractat.kuilt.core.fabric.identified], so nothing passed here could have reached the seam.
 * #2323 made `handshaking` carry it, so the knob is real now and the argument is honoured.
 *
 * @param serverSocket An already-bound listening socket; `weave` accepts one connection on it.
 * @param selector The Ktor selector the socket was built with.
 * @param selfId This peer's identity, announced in the in-band handshake. Defaults to a fresh
 *   random [PeerId], distinct on every call.
 * @param policy Bounds the woven seam's inbox — capacity and overflow strategy. Defaults to
 *   [DeliveryPolicy.Reliable] (bounded, backpressured, lossless).
 * @param dispatcher The canonical `dispatcher` slot — what [TcpLoom] calls its `seamDispatcher`.
 *   It *schedules* the seam's read/write coroutines and is never a mutual-exclusion mechanism;
 *   the seam is thread-safe on its own. It must be a real dispatcher: `weave` rejects a virtual
 *   `TestDispatcher` outright, which is why defaulting it to one here is safe — the hazard the
 *   "no real-dispatcher defaults" rule guards against cannot pass silently on this fabric.
 * @param ioDispatcher Fabric-specific, and **not** interchangeable with [dispatcher]: the
 *   blocking socket reads run here. Collapsing the two would park a blocking read on the
 *   single-threaded scheduling dispatcher and stall the seam.
 */
public fun tcpLoomHost(
    serverSocket: ServerSocket,
    selector: SelectorManager,
    selfId: PeerId = freshPeerId(),
    policy: DeliveryPolicy = DeliveryPolicy.Reliable,
    dispatcher: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): TcpLoom = TcpLoom.host(
    serverSocket = serverSocket,
    selfId = selfId,
    selector = selector,
    seamDispatcher = dispatcher,
    ioDispatcher = ioDispatcher,
    policy = policy,
)

/**
 * Dial a raw-TCP session someone else is already listening for.
 *
 * Call this on the machine that connects out. The returned loom's
 * [us.tractat.kuilt.core.Loom.join] takes a [TcpAddress] — the host and port the other side
 * bound with [tcpLoomHost] — dials it, and gives you a two-peer
 * [us.tractat.kuilt.core.Seam].
 *
 * The same shape convention and the same deliberate omission as [tcpLoomHost] apply; see its
 * documentation for why there is no `weaveTimeout` here.
 *
 * @param selector The Ktor selector the outbound socket is built with.
 * @param selfId This peer's identity, announced in the in-band handshake. Defaults to a fresh
 *   random [PeerId], distinct on every call.
 * @param policy Bounds the woven seam's inbox; see [tcpLoomHost].
 * @param dispatcher The canonical `dispatcher` slot — schedules the seam's read/write
 *   coroutines. Must be a real dispatcher; see [tcpLoomHost].
 * @param ioDispatcher Runs the blocking socket reads. Not interchangeable with [dispatcher].
 */
public fun tcpLoomJoin(
    selector: SelectorManager,
    selfId: PeerId = freshPeerId(),
    policy: DeliveryPolicy = DeliveryPolicy.Reliable,
    dispatcher: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): TcpLoom = TcpLoom.join(
    selfId = selfId,
    selector = selector,
    seamDispatcher = dispatcher,
    ioDispatcher = ioDispatcher,
    policy = policy,
)
