/*
 * The StableRef-rooted runtime behind a JVM `nw_runtime_*` handle.
 *
 * One [NwBridgeRuntime] owns exactly one [us.tractat.kuilt.nw.RealNwApi] (the real
 * Apple Network.framework binding), a [CoroutineScope] for the flow-forwarding
 * collectors, and a snapshot of the currently-open connection ids (so `destroy`
 * can gracefully disconnect everything). The JVM stores the runtime as an opaque
 * `COpaquePointer` — internally a `StableRef<NwBridgeRuntime>` — that roots the
 * runtime so K/N's GC won't reclaim it while the JVM still holds the pointer.
 *
 * The five `set*Callback` methods each launch ONE collector on [scope] that
 * forwards a `RealNwApi` flow to the registered JVM cdecl callback. Exactly one
 * collector per flow preserves the single-collection contract of `RealNwApi`'s
 * `MutableSharedFlow`s (a second collector would duplicate delivery); the fifth,
 * `connectionViability`, observes a `StateFlow<Map>` and forwards per-connection
 * changes (#1507/#1509). Collectors start `UNDISPATCHED` so they subscribe
 * synchronously before the registering `nw_set_*` call returns — the JVM registers
 * all five callbacks before it issues any start op, so no hot no-replay event is
 * missed (subscribe-before-start).
 */
package us.tractat.kuilt.nw.bridge

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pin
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.nw.NwConnectionId
import us.tractat.kuilt.nw.NwEndpoint
import us.tractat.kuilt.nw.NwLoopbackConfig
import us.tractat.kuilt.nw.NwPskMaterial
import us.tractat.kuilt.nw.RealNwApi

/**
 * `(endpointId: char*, serviceName: char*) -> void` — the C signature of the
 * JNA-side `endpointFound` callback. K/N receives it as this `CPointer<CFunction<…>>`.
 */
@OptIn(ExperimentalForeignApi::class)
internal typealias EndpointFoundCb = CFunction<(CPointer<ByteVar>?, CPointer<ByteVar>?) -> Unit>

/**
 * `(connectionId: char*, endpointId: char*, serviceName: char*) -> void` — the
 * JNA-side `connectionOpened` callback. `endpointId`/`serviceName` are empty
 * strings for an inbound (host-role) connection with no dialled endpoint.
 */
@OptIn(ExperimentalForeignApi::class)
internal typealias ConnectionOpenedCb = CFunction<(CPointer<ByteVar>?, CPointer<ByteVar>?, CPointer<ByteVar>?) -> Unit>

/**
 * `(connectionId: char*, data: char*, len: int) -> void` — the JNA-side
 * `bytesReceived` callback. The data pointer is valid only for the duration of
 * the call; the JVM copies bytes out immediately.
 */
@OptIn(ExperimentalForeignApi::class)
internal typealias BytesReceivedCb = CFunction<(CPointer<ByteVar>?, CPointer<ByteVar>?, Int) -> Unit>

/**
 * `(connectionId: char*, reason: char*) -> void` — the JNA-side
 * `connectionClosed` callback. `reason` is empty when the close reason is `null`
 * (graceful); the JVM maps empty back to `null`.
 */
@OptIn(ExperimentalForeignApi::class)
internal typealias ConnectionClosedCb = CFunction<(CPointer<ByteVar>?, CPointer<ByteVar>?) -> Unit>

/**
 * `(connectionId: char*, viable: int) -> void` — the JNA-side `connectionViability` callback (#1507).
 * `viable` is `1` when the connection's path is up (`ready`) and `0` when it is lost (`ready → waiting`).
 * Fires once per **per-connection change** in [RealNwApi.connectionViability]; the JVM applies each as a
 * latest-wins delta into its own drop-tolerant `StateFlow<Map>`. Entry *removals* (a closed connection)
 * are NOT signalled here — the JVM prunes them from the observed `connectionClosed` stream instead.
 */
@OptIn(ExperimentalForeignApi::class)
internal typealias ConnectionViabilityCb = CFunction<(CPointer<ByteVar>?, Int) -> Unit>

@OptIn(ExperimentalForeignApi::class)
internal class NwBridgeRuntime private constructor(private val api: RealNwApi) {

    /** P2P/Bonjour runtime — the default `nw_runtime_create` path. */
    constructor(psk: ByteArray, identity: ByteArray) : this(RealNwApi(NwPskMaterial(psk = psk, identity = identity)))

    /**
     * Direct-loopback runtime — the `nw_runtime_create_loopback` path. Binds an ephemeral
     * `127.0.0.1` listener and (as joiner) dials the host's real bound port over the shared
     * [loopback] rendezvous instead of discovering over Bonjour. This is the JVM↔JVM
     * `SeamConformanceSuite` path that proves the TLS-PSK link end-to-end through the real dylib.
     */
    constructor(psk: ByteArray, identity: ByteArray, loopback: NwLoopbackConfig)
        : this(RealNwApi(NwPskMaterial(psk = psk, identity = identity), loopback))

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Snapshot of the connections currently open, maintained by the connectionOpened/closed
    // forwarding collectors so [destroy] can gracefully disconnect all of them. Guarded by an
    // explicit lock — the forwarding collectors run on a real multi-threaded dispatcher.
    private val lock = reentrantLock()
    private val openConnections = mutableSetOf<NwConnectionId>()

    fun setEndpointFoundCallback(cb: CPointer<EndpointFoundCb>) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            api.endpointFound.collect { endpoint ->
                memScoped { cb.invoke(endpoint.id.cstr.ptr, endpoint.serviceName.cstr.ptr) }
            }
        }
    }

    fun setConnectionOpenedCallback(cb: CPointer<ConnectionOpenedCb>) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            api.connectionOpened.collect { event ->
                lock.withLock { openConnections.add(event.connectionId) }
                // Empty strings for the inbound/host case (endpoint == null).
                val endpointId = event.endpoint?.id ?: ""
                val serviceName = event.endpoint?.serviceName ?: ""
                memScoped {
                    cb.invoke(event.connectionId.value.cstr.ptr, endpointId.cstr.ptr, serviceName.cstr.ptr)
                }
            }
        }
    }

    fun setBytesReceivedCallback(cb: CPointer<BytesReceivedCb>) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            api.bytesReceived.collect { event ->
                val bytes = event.bytes
                memScoped {
                    if (bytes.isEmpty()) {
                        val empty = ByteArray(1).pin()
                        try {
                            cb.invoke(event.connectionId.value.cstr.ptr, empty.addressOf(0), 0)
                        } finally {
                            empty.unpin()
                        }
                    } else {
                        bytes.usePinned { pinned ->
                            cb.invoke(event.connectionId.value.cstr.ptr, pinned.addressOf(0), bytes.size)
                        }
                    }
                }
            }
        }
    }

    fun setConnectionClosedCallback(cb: CPointer<ConnectionClosedCb>) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            api.connectionClosed.collect { event ->
                lock.withLock { openConnections.remove(event.connectionId) }
                val reason = event.reason ?: "" // empty ⇒ graceful/null on the JVM side
                memScoped { cb.invoke(event.connectionId.value.cstr.ptr, reason.cstr.ptr) }
            }
        }
    }

    /**
     * Forwards [RealNwApi.connectionViability] — a drop-tolerant `StateFlow<Map>` (#1509) — to the JVM as
     * per-connection `(id, viable)` callbacks. The collector diffs each new map snapshot against the
     * previous one and fires the callback only for connections whose latest value *changed* (a new key or a
     * flipped `true`/`false`). Because it observes the STATE flow, intermediate transitions may coalesce
     * under backpressure, but the LATEST value per connection is never lost — so a recovery (`true`) can
     * never be dropped and a loss (`false`) can never be dropped. Removals (a connection cleared from the
     * map on close) are deliberately NOT forwarded here: the JVM learns "closed" from the `connectionClosed`
     * stream and prunes the corresponding viability entry itself.
     */
    fun setConnectionViabilityCallback(cb: CPointer<ConnectionViabilityCb>) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var previous = emptyMap<NwConnectionId, Boolean>()
            api.connectionViability.collect { current ->
                for ((id, viable) in current) {
                    if (previous[id] != viable) {
                        memScoped { cb.invoke(id.value.cstr.ptr, if (viable) 1 else 0) }
                    }
                }
                previous = current
            }
        }
    }

    suspend fun startListening(serviceName: String, serviceType: String): Unit =
        api.startListening(serviceName, serviceType)

    suspend fun stopListening(): Unit = api.stopListening()

    suspend fun startBrowsing(serviceType: String): Unit = api.startBrowsing(serviceType)

    suspend fun stopBrowsing(): Unit = api.stopBrowsing()

    // connect() uses only the endpoint id (RealNwApi looks it up in its own registry), so the
    // serviceName is irrelevant here — reconstruct the endpoint with an empty name.
    suspend fun connect(endpointId: String): Unit = api.connect(NwEndpoint(id = endpointId, serviceName = ""))

    suspend fun disconnect(connectionId: String): Unit = api.disconnect(NwConnectionId(connectionId))

    suspend fun send(connectionId: String, bytes: ByteArray): Unit = api.send(NwConnectionId(connectionId), bytes)

    /**
     * Graceful teardown: stop advertising/browsing, disconnect every still-open connection, then
     * cancel the forwarding scope. Runs on the caller's (JNA) thread via [runBlocking]; each step is
     * best-effort so a single failure never leaks the rest.
     */
    fun destroy() {
        runBlocking {
            runCatchingCancellable { api.stopListening() }
            runCatchingCancellable { api.stopBrowsing() }
            val targets = lock.withLock { openConnections.toList() }
            for (id in targets) {
                runCatchingCancellable { api.disconnect(NwConnectionId(id.value)) }
            }
        }
        scope.cancel()
    }
}
