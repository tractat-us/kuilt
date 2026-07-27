package us.tractat.kuilt.nw

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import us.tractat.kuilt.core.FabricAvailability

/**
 * Shared empty default for [NwApi.connectionStates] — a single immutable, never-updated [StateFlow]
 * so the default getter allocates nothing per call. A binding that has not yet wired the underlying
 * `ready`/`waiting`/close transitions inherits "every connection's state is unknown" (an empty map).
 */
private val EMPTY_CONNECTION_STATES: StateFlow<Map<NwConnectionId, NwConnState>> =
    MutableStateFlow(emptyMap<NwConnectionId, NwConnState>()).asStateFlow()

/**
 * Shared default for [NwApi.pathState] — a single immutable, never-updated [StateFlow] holding `null`
 * ("path unknown") so the default getter allocates nothing per call. A binding that has not wired an
 * `NWPathMonitor` (e.g. the JVM dylib bridge) inherits "unknown", and the seam reports
 * [us.tractat.kuilt.core.FabricAvailability.Unknown] rather than guessing — the loom's static report
 * supplies only the ROLES, never a path verdict (#1712).
 */
private val EMPTY_PATH_STATE: StateFlow<NwPathState?> =
    MutableStateFlow<NwPathState?>(null).asStateFlow()

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
     * by throwing here. Callers must therefore rely on [connectionClosed] (the fast reason-carrying
     * path) — backstopped by the drop-tolerant [connectionStates] `Closed` STATE — as the authoritative
     * teardown signal, and treat a non-throwing `send` as "handed off", not "delivered".
     */
    public suspend fun send(connectionId: NwConnectionId, bytes: ByteArray)

    // ── event flows ──────────────────────────────────────────────────────────

    /** Emits when a remote endpoint is found while browsing. */
    public val endpointFound: Flow<NwEndpoint>

    /**
     * Emits when a previously-discovered endpoint is removed while browsing — the browse-time inverse of
     * [endpointFound]. A consumer maintaining a discovery roster (e.g. `NwLoom.visiblePeers`) prunes the
     * endpoint on this signal so departed peers don't accumulate as ghosts (#1447).
     *
     * Best-effort and lossy like the other event streams. A binding that has not wired the underlying
     * browser removal callback inherits the **empty default** — no removals are ever reported, so a
     * discovery roster keyed off it simply never shrinks (the pre-#1447 behaviour), rather than being
     * forced to implement removal before the ABI lands. `RealNwApi` (appleMain) and the test fakes
     * override it. A removal is best-effort: it may be missed (a browser cancel, TXT-record churn), so a
     * consumer must never treat the roster as authoritative membership — that is `Seam.peers`' job.
     */
    public val endpointLost: Flow<NwEndpoint>
        get() = emptyFlow()

    /** Emits when a connection is established — accepted (host role) or dialled (join role). */
    public val connectionOpened: Flow<NwConnectionOpened>

    /** Emits when a byte chunk arrives on a connection. */
    public val bytesReceived: Flow<NwBytesReceived>

    /**
     * Emits when a connection closes, locally or remotely initiated — the fast, reason-carrying close path.
     *
     * This is a **lossy** event stream (a full buffer drops the event under backpressure), so it is NOT the
     * sole teardown authority: a dropped `failed`/`cancelled` close would otherwise strand a zombie peer that
     * no other signal evicts (#1522). [connectionStates] is the drop-tolerant STATE backstop — a close is
     * reflected there (as [NwConnState.Closed]) whether or not this event survives. `NwSeam` consumes both;
     * whichever observes the closure first tears the connection, the other is an idempotent no-op.
     */
    public val connectionClosed: Flow<NwConnectionClosed>

    /**
     * The per-connection **latest lifecycle state**: each connection's [NwConnectionId] mapped to its current
     * [NwConnState] — [NwConnState.Viable] (path up), [NwConnState.PathLost] (path unsatisfied), or
     * [NwConnState.Closed] (terminally torn, carrying the raw close reason). This ONE drop-tolerant STATE
     * signal unifies the two former parallel maps — `connectionViability` (#1509) and `closedConnections`
     * (#1522) — into a single total sum type (#1539). A connection absent from the map has never established.
     *
     * It is **state, not an event stream**, and it drives two `NwSeam` reconciliations at once:
     *  - **Path loss / recovery (#1478).** Network.framework moves a connection that loses its route from
     *    `ready` to `waiting` (NOT `failed`), so no [NwConnectionClosed] ever fires — the peer is silently
     *    unreachable. The seam arms a bounded grace timer while a connection is [NwConnState.PathLost] and
     *    cancels it on recovery to [NwConnState.Viable]; if the path never recovers the peer is torn.
     *  - **Terminal teardown (#1522).** [NwConnState.Closed] is the drop-tolerant backstop for the lossy
     *    [connectionClosed] event: a `failed`/`cancelled` close dropped under buffer pressure would otherwise
     *    strand a zombie peer. The seam tears any still-tracked connection whose state is [NwConnState.Closed],
     *    IMMEDIATELY and with no grace timer.
     *
     * Modelling it as a **[StateFlow] of the latest value per connection** (rather than a lossy `tryEmit`
     * event flow) makes it **drop-tolerant**: intermediate transitions may coalesce under backpressure, but
     * the LATEST value per connection is never lost. [NwConnState.Closed] is furthermore **terminal, monotone
     * and dominant** — producers latch it so a late [NwConnState.Viable]/[NwConnState.PathLost] for a closed
     * id can never overwrite it (so a closure can never be conflated away the way a boolean live-set's
     * presence-then-absence would be under the same starvation that drops the event).
     *
     * **Absence means NOTHING.** A connection absent from this map may be live, dialling, or never-having-existed
     * — never infer a closure from a key being absent. The ONLY positive signal is a key's *presence* with a
     * [NwConnState.Closed] value.
     *
     * Defaults to a never-updated empty map so a binding that has not yet wired the underlying transitions
     * inherits "every connection's state is unknown" rather than being forced to implement it before the ABI
     * lands. `RealNwApi` (appleMain), `BridgeNwApi` (JVM), and the test fakes override it.
     */
    public val connectionStates: StateFlow<Map<NwConnectionId, NwConnState>>
        get() = EMPTY_CONNECTION_STATES

    /**
     * The device's live network-path state (`NWPathMonitor`), or `null` while unknown. This is what
     * makes a seam's [us.tractat.kuilt.core.Seam.capability] reactive: as the path goes up/down, swaps
     * Wi-Fi↔cellular, or the Local-Network permission is denied, a fresh [NwPathState] appears here and
     * the seam folds its [toAvailability] into the live capability.
     *
     * Modelled as latest-value **[StateFlow] state** (not a lossy event stream) — the current path is a
     * level, and a late subscriber must see the latest value, never miss it. Defaults to a never-updated
     * `null` ("unknown") so a binding without a real monitor (the JVM bridge) inherits "unknown" and the
     * seam keeps its static seed. `RealNwApi` (appleMain) drives it from `nw_path_monitor_*`; the test
     * fakes expose a controllable `MutableStateFlow` so no test touches the OS path monitor.
     */
    public val pathState: StateFlow<NwPathState?>
        get() = EMPTY_PATH_STATE
}
