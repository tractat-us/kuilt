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
 * The four `set*Callback` methods each launch ONE collector on [scope] that
 * forwards a `RealNwApi` flow to the registered JVM cdecl callback. Exactly one
 * collector per flow preserves the single-collection contract of `RealNwApi`'s
 * `MutableSharedFlow`s (a second collector would duplicate delivery). Collectors
 * start `UNDISPATCHED` so they subscribe synchronously before the registering
 * `nw_set_*` call returns — the JVM registers all four callbacks before it issues
 * any start op, so no hot no-replay event is missed (subscribe-before-start).
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

@OptIn(ExperimentalForeignApi::class)
internal class NwBridgeRuntime(psk: ByteArray, identity: ByteArray) {

    private val api = RealNwApi(NwPskMaterial(psk = psk, identity = identity))
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
