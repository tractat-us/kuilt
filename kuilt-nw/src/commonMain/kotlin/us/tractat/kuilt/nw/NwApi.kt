package us.tractat.kuilt.nw

import kotlinx.coroutines.flow.Flow
import us.tractat.kuilt.core.FabricAvailability

/**
 * Abstracts the slice of Apple's Network.framework needed by `NwLoom`.
 *
 * Implementations: `FakeNwApi` (tests, commonTest) and the real `RealNwApi`
 * (appleMain, dispatched separately).
 *
 * Unlike a single-peer accept/initiate handshake API, `NwApi` is stream-oriented
 * and multi-connection: it hosts (advertise + listen) and
 * joins (browse + connect) concurrently, and every accepted or dialled link gets its
 * own [NwConnectionId]. Framing and handshake semantics live above this layer — this
 * interface only moves raw bytes over an already-open connection.
 */
public interface NwApi {

    /** Reports whether the underlying fabric is usable on this runtime. */
    public fun availability(): FabricAvailability

    // ── host role ────────────────────────────────────────────────────────────

    /** Begin advertising [serviceName] under Bonjour service type [serviceType] and accept inbound connections. */
    public suspend fun startListening(serviceName: String, serviceType: String)

    /** Stop listening/advertising. No-op if not listening. */
    public suspend fun stopListening()

    // ── join role ────────────────────────────────────────────────────────────

    /** Begin browsing for endpoints advertising Bonjour service type [serviceType]. */
    public suspend fun startBrowsing(serviceType: String)

    /** Stop browsing. No-op if not browsing. */
    public suspend fun stopBrowsing()

    /** Dial a discovered [endpoint], establishing a new outbound connection. */
    public suspend fun connect(endpoint: NwEndpoint)

    /** Tear down the connection identified by [connectionId]. No-op if already closed. */
    public suspend fun disconnect(connectionId: NwConnectionId)

    // ── data ─────────────────────────────────────────────────────────────────

    /** Send raw [bytes] over [connectionId]. Framing is the caller's responsibility. */
    public suspend fun send(connectionId: NwConnectionId, bytes: ByteArray)

    // ── event flows ──────────────────────────────────────────────────────────

    /** Emits when a remote endpoint is found while browsing. */
    public val endpointFound: Flow<NwEndpoint>

    /** Emits when a connection is established — accepted (host role) or dialled (join role). */
    public val connectionOpened: Flow<NwConnectionOpened>

    /** Emits when a byte chunk arrives on a connection. */
    public val bytesReceived: Flow<NwBytesReceived>

    /** Emits when a connection closes, locally or remotely initiated. */
    public val connectionClosed: Flow<NwConnectionClosed>
}
