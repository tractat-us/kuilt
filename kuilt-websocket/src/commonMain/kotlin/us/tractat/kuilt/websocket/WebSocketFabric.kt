package us.tractat.kuilt.websocket

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Weft
import us.tractat.kuilt.core.freshPeerId
import kotlin.random.Random

/**
 * Join a WebSocket session someone else is already relaying.
 *
 * Call this on the device that connects out. Hand the returned loom a
 * [WebSocketAdvertisement] — the URL and identity the relay published — and its
 * [us.tractat.kuilt.core.Loom.join] dials it and gives you a two-peer
 * [us.tractat.kuilt.core.Seam].
 *
 * This builds the same loom [KtorClientLoom]'s constructor does. What it adds is the argument
 * *order* every kuilt fabric factory shares (#1430): the fabric's own required argument first —
 * the [httpClient] you own the lifecycle of — then the universal knobs, `selfId` then `policy`
 * then `dispatcher`, then this fabric's remaining tuning. [KtorClientLoom] keeps its own older
 * order and remains the surface for dependency injection and tests.
 *
 * **Where the hosting counterpart is.** There is deliberately no `webSocketLoomHost` beside this.
 * Hosting a WebSocket relay means mounting a route on a running server, which exists on JVM and
 * Android only and is [KtorServerLoom]/[KtorRoomHost]'s job — a different construction shape
 * (an `Application` plus a path) that the convention explicitly does not try to flatten. The
 * role split at construction is irreducible here; the convention governs the universal knobs.
 *
 * One knob the convention names is **deliberately absent**, because this fabric does not honour
 * it, and an argument that is accepted and then ignored is worse than one that does not exist:
 *
 * - **`weaveTimeout`** — nothing in this loom puts a clock on the dial. `weave` calls Ktor's
 *   `webSocketSession` and waits as long as the [httpClient] waits, so the only ceiling on a
 *   rendezvous is the one the caller configured on that client (`install(HttpTimeout)`), not
 *   anything this factory could pass. Adding one would be new wiring rather than a forwarded
 *   knob, and it would sit alongside the client's own connect timeout with undefined precedence.
 *   Bound the dial where the dial is configured. See #1430.
 *
 * `policy` used to be a second such omission, for a reason that has since been fixed rather than
 * worked around: `WebSocketSeam` — this fabric's seam adapter — called
 * [us.tractat.kuilt.core.fabric.identified] without a [DeliveryPolicy], so nothing passed here
 * could have reached the inbox. #2686 made it carry one, so the knob is real now and the argument
 * is honoured.
 *
 * @param httpClient The Ktor client every dial goes through. **Not closed by the loom** — the
 *   caller owns its lifecycle, and it is where a rendezvous ceiling is configured.
 * @param selfId This peer's fabric identity, presented as `?peer=<id>` on every dial. Defaults to
 *   a fresh random [PeerId] per loom. Supply a stable value when the far end derives durable
 *   state from it — a cluster learner's identity must survive a reconnect.
 * @param policy Bounds every woven seam's inbox — capacity and overflow strategy. Defaults to
 *   [DeliveryPolicy.Reliable] (bounded, backpressured, lossless).
 * @param dispatcher The canonical `dispatcher` slot: it *schedules* each seam's read/write
 *   coroutines and is never a mutual-exclusion mechanism — the seam is thread-safe on its own.
 * @param weft Supplies a [WebSocketDialContext] fresh on every dial, so a single-use credential
 *   can be minted per redial instead of being baked into a static URL.
 * @param connectivity Live device-reachability observer driving every woven seam's
 *   [us.tractat.kuilt.core.Seam.capability]. Defaults to [UnobservedConnectivity], under which a
 *   seam reports the honest [us.tractat.kuilt.core.FabricAvailability.Unknown] floor.
 */
public fun webSocketLoomJoin(
    httpClient: HttpClient,
    selfId: PeerId = freshPeerId(),
    policy: DeliveryPolicy = DeliveryPolicy.Reliable,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    weft: Weft<WebSocketDialContext> = { WebSocketDialContext() },
    connectivity: ConnectivityObserver = UnobservedConnectivity,
): KtorClientLoom = KtorClientLoom(
    httpClient = httpClient,
    dispatcher = dispatcher,
    selfPeerId = selfId,
    weft = weft,
    connectivity = connectivity,
    policy = policy,
)

/**
 * Join a hub-spoke **mesh** over one WebSocket, rather than the two-peer relay
 * [webSocketLoomJoin] gives you.
 *
 * Same fabric, different topology: the returned loom's `join` completes an in-band mesh preamble
 * with the hub and the resulting [us.tractat.kuilt.core.Seam] sees every other spoke in the room,
 * not just the hub. Pair it with a [us.tractat.kuilt.core.MuxServerLoom] hub — pointing
 * [webSocketLoomJoin] at one instead never completes admit, because a two-peer seam never sends
 * the preamble the hub is waiting for.
 *
 * The same argument order, the same deliberate omission of `weaveTimeout`, and the same
 * `httpClient` lifecycle rule as [webSocketLoomJoin]; see its documentation.
 *
 * @param httpClient The Ktor client every dial goes through; not closed by the loom.
 * @param selfId This spoke's fabric identity, presented as `?peer=<id>` on every dial. Defaults
 *   to a fresh random [PeerId] per loom.
 * @param policy Bounds the spoke seam's inbound spool; see [webSocketLoomJoin].
 * @param dispatcher The canonical `dispatcher` slot — schedules the seam's read/write coroutines.
 * @param random Source of the per-connection mesh nonce that breaks a displacement tie. Fabric-
 *   specific and **not** a general RNG slot: tests pass a seeded [Random] so the tiebreak is
 *   deterministic. Kept out of the universal quartet for the reason [freshPeerId] records — an
 *   identity mint is not a simulation input, but this nonce is.
 */
public fun webSocketMeshLoomJoin(
    httpClient: HttpClient,
    selfId: PeerId = freshPeerId(),
    policy: DeliveryPolicy = DeliveryPolicy.Reliable,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    random: Random = Random.Default,
): KtorMeshClientLoom = KtorMeshClientLoom(
    httpClient = httpClient,
    dispatcher = dispatcher,
    selfPeerId = selfId,
    random = random,
    policy = policy,
)
