@file:OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)

package us.tractat.kuilt.websocket

import io.ktor.websocket.DefaultWebSocketSession
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.TransportRole
import us.tractat.kuilt.core.fabric.identified
import kotlin.coroutines.CoroutineContext

/**
 * A 2-peer [Seam] backed by a raw Ktor [DefaultWebSocketSession].
 *
 * Works for both the client path ([io.ktor.client.plugins.websocket.DefaultClientWebSocketSession])
 * and the server path ([io.ktor.server.websocket.DefaultWebSocketServerSession]) — both
 * extend [DefaultWebSocketSession].
 *
 * The seam itself is the shared [identified] 2-peer fabric; this factory only adapts the
 * Ktor session into a [WebSocketConnection], supplies the known identities, and layers the live
 * [Seam.capability] view on top. The receive loop, outbound serialization, sequence numbering and
 * single-shot teardown all live in `identified`.
 *
 * **Wire format:** byte-transparent. Each binary WebSocket frame's payload is delivered
 * verbatim as the [us.tractat.kuilt.core.Swatch]'s logical bytes; no framing prefix, no in-band
 * handshake (see [WebSocketConnection]).
 *
 * **PeerId discovery:** both [selfId] and [remoteId] are supplied at construction time by
 * the calling factory. Identity is exchanged out of band — the client passes its [PeerId]
 * in the URL query (`?peer=<id>`), and the server's [PeerId] is part of the
 * [WebSocketAdvertisement].
 *
 * @param dispatcher Scheduling scope for the seam's read/write loops. `identified` is
 *   thread-safe via atomics, so this is purely a scheduler — the Looms default it to
 *   `Dispatchers.Default.limitedParallelism(1)`.
 * @param roles The fabric's static [TransportRole] set, supplied by the calling loom. Unlike
 *   `:kuilt-nearby`, these do **not** narrow with the live signal: a WebSocket relay is a
 *   `ServerRelay`/`Data` fabric whether or not the radio is up, and "what is this fabric" is a
 *   different question from "can it be used right now" (#1712).
 * @param connectivity The live reachability observer whose readings become
 *   [TransportCapability.availability]. Defaults, at the loom, to [UnobservedConnectivity].
 */
internal fun WebSocketSeam(
    selfId: PeerId,
    remoteId: PeerId,
    session: DefaultWebSocketSession,
    dispatcher: CoroutineContext,
    roles: Set<TransportRole>,
    connectivity: ConnectivityObserver,
): Seam = ObservedCapabilitySeam(
    inner = identified(WebSocketConnection(session), selfId, remoteId, dispatcher),
    capability = ReachabilityCapability(connectivity.reachability, roles),
)

/**
 * The [identified] 2-peer seam with one member replaced: a live [capability] driven by a platform
 * connectivity observer instead of the roleless `Unknown` interface default (#1725).
 *
 * Delegation rather than a hand-written forwarder so that a member added to [Seam] later is carried
 * through automatically and cannot be silently dropped here.
 */
private class ObservedCapabilitySeam(
    inner: Seam,
    override val capability: StateFlow<TransportCapability>,
) : Seam by inner

/**
 * A scope-free view of [source] as the seam's [TransportCapability] — the fabric's static [roles]
 * with the observer's latest reading folded into the availability half.
 *
 * Scope-free is the point: a seam that owned a coroutine to pump this would have to cancel it on
 * tear, and a missed cancellation would freeze `capability` at a stale value forever (the class of
 * bug `CompositeSeam`'s single-writer machinery exists to kill). With no scope there is no pump and
 * nothing to leak — reads and collections go straight through to the observer, which owns the only
 * lifecycle. Mirrors `:kuilt-core`'s internal `MappedStateFlow`, which is not visible here.
 *
 * Valid as a [StateFlow] because [NetworkReachability.toAvailability] is injective over the four
 * inputs, so conflation and distinct-until-changed survive the mapping.
 */
private class ReachabilityCapability(
    private val source: StateFlow<NetworkReachability?>,
    private val roles: Set<TransportRole>,
) : StateFlow<TransportCapability> {

    private fun capabilityOf(reachability: NetworkReachability?): TransportCapability =
        TransportCapability(roles = roles, availability = reachability.toAvailability())

    override val value: TransportCapability get() = capabilityOf(source.value)

    override val replayCache: List<TransportCapability> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<TransportCapability>): Nothing {
        source.collect { collector.emit(capabilityOf(it)) }
    }
}
