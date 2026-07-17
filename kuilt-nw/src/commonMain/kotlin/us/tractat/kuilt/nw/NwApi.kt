package us.tractat.kuilt.nw

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import us.tractat.kuilt.core.FabricAvailability

/**
 * Shared empty default for [NwApi.connectionViability] — a single immutable, never-updated [StateFlow]
 * so the default getter allocates nothing per call. A binding that has not yet wired the underlying
 * `waiting`/`ready` viability transition inherits "every connection's path is unknown" (an empty map).
 */
private val EMPTY_CONNECTION_VIABILITY: StateFlow<Map<NwConnectionId, Boolean>> =
    MutableStateFlow(emptyMap<NwConnectionId, Boolean>()).asStateFlow()

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

    /**
     * Send raw [bytes] over [connectionId]. Framing is the caller's responsibility.
     *
     * **Best-effort.** An implementation MAY throw synchronously to signal an immediately-known
     * failure (e.g. an unknown/closed [connectionId]) — `NwSeam` treats a throw as a cue to evict
     * that connection. But a real datagram transport reports most send failures asynchronously
     * (the link breaks after the call returns), surfacing them via [connectionClosed] rather than
     * by throwing here. Callers must therefore rely on [connectionClosed] as the authoritative
     * teardown signal and treat a non-throwing `send` as "handed off", not "delivered".
     */
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

    /**
     * The per-connection **latest** path-viability state: each live connection's [NwConnectionId] mapped
     * to whether its path is currently up (`true` = `ready`; `false` = a `ready → waiting` path loss).
     * A connection absent from the map has never established or has closed.
     *
     * Viability is **state, not an event stream** (#1509). Network.framework moves a connection that loses
     * its route from `ready` to `waiting` (NOT `failed`), so no [NwConnectionClosed] ever fires — the peer
     * is silently unreachable; `NwSeam` reconciles this map to arm a bounded grace timer on a path loss and
     * tear the peer if the path does not recover in time (the transport-level fix for #1478). Modelling it
     * as a **[StateFlow] of the latest value per connection** (rather than a lossy `tryEmit` event flow)
     * makes it **drop-tolerant**: intermediate transitions may coalesce under backpressure, but the LATEST
     * value per connection is never lost — so a recovery (`true`) can never be dropped and strand an armed
     * grace timer (a spurious tear), and a loss (`false`) can never be dropped and leave a zombie peer.
     *
     * Defaults to a never-updated empty map so a binding that has not yet wired the underlying
     * `waiting`/`ready` transition (the JVM dylib bridge — see #1507) inherits "every connection's path is
     * unknown" rather than being forced to implement it before the ABI lands. `RealNwApi` (appleMain) and
     * the test fakes override it.
     */
    public val connectionViability: StateFlow<Map<NwConnectionId, Boolean>>
        get() = EMPTY_CONNECTION_VIABILITY
}
