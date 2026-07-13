package us.tractat.kuilt.nw

import com.sun.jna.Pointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.tractat.kuilt.core.FabricAvailability
import java.lang.ref.Cleaner
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * JVM [NwApi] proxying through the macOS K/N Network.framework bridge
 * ([NwNativeLib] over `libkuilt.dylib`). The JVM analogue of the appleMain
 * `RealNwApi`: it forwards the seven suspend ops to the dylib and re-publishes
 * the four native callback streams as coroutine [Flow]s that `NwLoom`/`NwSeam`
 * collect exactly as they collect `RealNwApi`.
 *
 * ## JNA-callback → coroutine-flow bridge (the crown jewel)
 * The four native callbacks fire on JNA threads and must not suspend. Each one
 * therefore does the minimum synchronous work — copy the payload out of the raw
 * pointer, wrap it in the event type — then deposits it into a bounded staging
 * [Channel] via [Channel.trySend] (never blocks). A single per-flow drain
 * coroutine forwards from the staging channel to the corresponding
 * [MutableSharedFlow] in FIFO order, so ordering is preserved and no event is
 * ever emitted from a JNA thread. Each staging channel is [BufferOverflow.DROP_OLDEST]
 * so a burst is lossy at the JNA boundary rather than blocking the native caller —
 * matching `RealNwApi`'s `tryEmit`-drops-when-full backpressure.
 *
 * ## Strong callback references
 * The four [com.sun.jna.Callback] objects are held as fields so JNA's trampolines
 * survive this object's lifetime; releasing them early would SIGSEGV the K/N side.
 *
 * ## Native-runtime lifecycle (GC parity with appleMain)
 * `nw_runtime_create` roots the native runtime in a K/N `StableRef`, which — unlike
 * a plain K/N object — is **never** GC-eligible until `nw_runtime_destroy` runs. So,
 * unlike appleMain (where dropping `RealNwApi` lets K/N GC cancel the `NWListener`/
 * `NWBrowser`), a JVM bridge whose consumer simply discards the `Seam` would keep the
 * native advertiser/browser running forever. To restore GC parity a [Cleaner] runs
 * [NativeRuntimeDisposer] — `nw_runtime_destroy` — when this object becomes
 * unreachable. **The disposer captures only `nativeLib`/`handle`/[disposed] — never
 * `this` or [scope]** (rooting `scope` via the Cleaner would transitively root the
 * drain coroutines and hence `this`, defeating collection). [disposed] gates the
 * destroy through a CAS so the explicit [close] and the Cleaner can never
 * double-dispose the handle (a use-after-free per the bridge ABI). Proactively
 * stopping advertising on `Seam.close()` (both platforms) is tracked separately.
 *
 * ## availability()
 * Delegates to [NwNativeLib.jvmAvailability]: [FabricAvailability.Available] only
 * on a macOS-arm64 host with the dylib loaded, else [FabricAvailability.Unavailable].
 *
 * @param nativeLib  the loaded JNA façade (real dylib, or a test fake).
 * @param handle     the runtime handle from [NwNativeLib.nw_runtime_create].
 * @param dispatcher scope for the drain coroutines AND the context the suspend
 *   ops call into the dylib on (scheduling only). Production default
 *   [Dispatchers.Default]; tests inject a `StandardTestDispatcher`.
 */
public class BridgeNwApi internal constructor(
    private val nativeLib: NwNativeLib,
    private val handle: Pointer,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : NwApi {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    // Shared exactly-once gate for nw_runtime_destroy. Referenced by BOTH close() and the
    // Cleaner disposer so the handle is disposed at most once (double dispose = UAF per the ABI).
    private val disposed = AtomicBoolean(false)

    // GC-parity finalizer: fires nw_runtime_destroy when this bridge is unreachable, so a discarded
    // Seam doesn't leave the native advertiser/browser running (see class KDoc). The disposer holds
    // NO reference to `this`/`scope`, so registering it does not pin this object.
    private val cleanable: Cleaner.Cleanable =
        CLEANER.register(this, NativeRuntimeDisposer(nativeLib, handle, disposed))

    private val _endpointFound = MutableSharedFlow<NwEndpoint>(extraBufferCapacity = EVENT_BUFFER)
    private val _connectionOpened = MutableSharedFlow<NwConnectionOpened>(extraBufferCapacity = EVENT_BUFFER)
    private val _bytesReceived = MutableSharedFlow<NwBytesReceived>(extraBufferCapacity = BYTES_BUFFER)
    private val _connectionClosed = MutableSharedFlow<NwConnectionClosed>(extraBufferCapacity = EVENT_BUFFER)

    override val endpointFound: Flow<NwEndpoint> = _endpointFound.asSharedFlow()
    override val connectionOpened: Flow<NwConnectionOpened> = _connectionOpened.asSharedFlow()
    override val bytesReceived: Flow<NwBytesReceived> = _bytesReceived.asSharedFlow()
    override val connectionClosed: Flow<NwConnectionClosed> = _connectionClosed.asSharedFlow()

    // Bounded staging channels: the JNA callbacks deposit here non-suspendingly; per-flow drains
    // forward to the SharedFlows in FIFO order. DROP_OLDEST so the JNA thread never blocks.
    private val endpointFoundStaging = Channel<NwEndpoint>(EVENT_BUFFER, BufferOverflow.DROP_OLDEST)
    private val connectionOpenedStaging = Channel<NwConnectionOpened>(EVENT_BUFFER, BufferOverflow.DROP_OLDEST)
    private val bytesReceivedStaging = Channel<NwBytesReceived>(BYTES_BUFFER, BufferOverflow.DROP_OLDEST)
    private val connectionClosedStaging = Channel<NwConnectionClosed>(EVENT_BUFFER, BufferOverflow.DROP_OLDEST)

    // Strong refs so JNA trampolines aren't GC'd while the K/N side may still fire them.
    private val endpointFoundCallback =
        NwNativeLib.EndpointFoundCallback { endpointId, serviceName ->
            endpointFoundStaging.trySend(NwEndpoint(id = endpointId, serviceName = serviceName))
        }

    private val connectionOpenedCallback =
        NwNativeLib.ConnectionOpenedCallback { connectionId, endpointId, serviceName ->
            // Empty endpointId ⇒ inbound/host connection with no dialled endpoint.
            val endpoint = if (endpointId.isEmpty()) null else NwEndpoint(id = endpointId, serviceName = serviceName)
            connectionOpenedStaging.trySend(NwConnectionOpened(NwConnectionId(connectionId), endpoint))
        }

    private val bytesReceivedCallback =
        NwNativeLib.BytesReceivedCallback { connectionId, data, len ->
            // Copy out of the raw pointer immediately — it is valid only for this call.
            val bytes = if (len > 0) data.getByteArray(0, len) else ByteArray(0)
            bytesReceivedStaging.trySend(NwBytesReceived(NwConnectionId(connectionId), bytes))
        }

    private val connectionClosedCallback =
        NwNativeLib.ConnectionClosedCallback { connectionId, reason ->
            connectionClosedStaging.trySend(NwConnectionClosed(NwConnectionId(connectionId), reason.ifEmpty { null }))
        }

    init {
        // Register all four callbacks BEFORE any start op (subscribe-before-start): the K/N side
        // subscribes its forwarding collectors here, so no hot no-replay event is missed.
        nativeLib.nw_set_endpoint_found_callback(handle, endpointFoundCallback)
        nativeLib.nw_set_connection_opened_callback(handle, connectionOpenedCallback)
        nativeLib.nw_set_bytes_received_callback(handle, bytesReceivedCallback)
        nativeLib.nw_set_connection_closed_callback(handle, connectionClosedCallback)

        // One drain per flow: forwards staged events to the SharedFlow in FIFO order. Running a
        // single coroutine (not one per event) preserves delivery ordering.
        scope.launch { for (event in endpointFoundStaging) _endpointFound.emit(event) }
        scope.launch { for (event in connectionOpenedStaging) _connectionOpened.emit(event) }
        scope.launch { for (event in bytesReceivedStaging) _bytesReceived.emit(event) }
        scope.launch { for (event in connectionClosedStaging) _connectionClosed.emit(event) }
    }

    override fun availability(): FabricAvailability = NwNativeLib.jvmAvailability()

    override suspend fun startListening(serviceName: String, serviceType: String) {
        withContext(dispatcher) { nativeLib.nw_start_listening(handle, serviceName, serviceType) }
    }

    override suspend fun stopListening() {
        withContext(dispatcher) { nativeLib.nw_stop_listening(handle) }
    }

    override suspend fun startBrowsing(serviceType: String) {
        withContext(dispatcher) { nativeLib.nw_start_browsing(handle, serviceType) }
    }

    override suspend fun stopBrowsing() {
        withContext(dispatcher) { nativeLib.nw_stop_browsing(handle) }
    }

    override suspend fun connect(endpoint: NwEndpoint) {
        // connect uses only the endpoint id (RealNwApi resolves it in its own registry).
        withContext(dispatcher) { nativeLib.nw_connect(handle, endpoint.id) }
    }

    override suspend fun disconnect(connectionId: NwConnectionId) {
        withContext(dispatcher) { nativeLib.nw_disconnect(handle, connectionId.value) }
    }

    override suspend fun send(connectionId: NwConnectionId, bytes: ByteArray) {
        val result = withContext(dispatcher) { nativeLib.nw_send(handle, connectionId.value, bytes, bytes.size) }
        // Best-effort per the NwApi contract: a synchronous failure is NwSeam's cue to evict the
        // connection, so a negative result code throws (mirrors RealNwApi's best-effort semantics —
        // most real failures still arrive asynchronously via connectionClosed).
        if (result < 0) error("nw_send failed for ${connectionId.value} (result=$result)")
    }

    /**
     * Releases the native runtime deterministically and cancels the drain [scope].
     *
     * `NwApi` has no `close` in its contract, so `NwLoom` never calls this — the [Cleaner]
     * registered at construction is what guarantees the native runtime is eventually released even
     * when the consumer only discards the `Seam` (GC parity with appleMain). Calling `close()`
     * simply makes that release *deterministic*. Idempotent, and safe to interleave with the
     * Cleaner: [cleanable]`.clean()` runs the disposer at most once, and the disposer's [disposed]
     * CAS backs that up, so `nw_runtime_destroy` is issued exactly once regardless of ordering.
     */
    public fun close() {
        scope.cancel()
        cleanable.clean()
    }

    /**
     * The Cleaner-registered teardown action. A top-level `private` class (NOT a lambda over
     * `BridgeNwApi`) so it provably captures only what it is given — `nativeLib`, `handle`, and the
     * shared [disposed] gate — and therefore never roots the enclosing bridge. The [AtomicBoolean]
     * CAS makes `nw_runtime_destroy` run at most once across the explicit-close and phantom paths.
     */
    private class NativeRuntimeDisposer(
        private val nativeLib: NwNativeLib,
        private val handle: Pointer,
        private val disposed: AtomicBoolean,
    ) : Runnable {
        override fun run() {
            if (disposed.compareAndSet(false, true)) nativeLib.nw_runtime_destroy(handle)
        }
    }

    private companion object {
        private const val EVENT_BUFFER = 16
        private const val BYTES_BUFFER = 64

        // One shared Cleaner (its own daemon thread) for all bridge instances.
        private val CLEANER: Cleaner = Cleaner.create()
    }
}
