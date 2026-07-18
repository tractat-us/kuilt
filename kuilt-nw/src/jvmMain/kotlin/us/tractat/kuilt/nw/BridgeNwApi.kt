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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 * the five native callback streams — four event [Flow]s plus the
 * [connectionViability] state — exactly as `NwLoom`/`NwSeam` consume `RealNwApi`.
 *
 * ## JNA-callback → coroutine-flow bridge (the crown jewel)
 * The five native callbacks fire on JNA threads and must not suspend. Each one
 * therefore does the minimum synchronous work — copy the payload out of the raw
 * pointer, wrap it in the event type — then deposits it into a bounded staging
 * [Channel] via [Channel.trySend] (never blocks). A single per-flow drain
 * coroutine forwards from the staging channel to the corresponding
 * [MutableSharedFlow] in FIFO order, so ordering is preserved and no event is
 * ever emitted from a JNA thread. Each staging channel is [BufferOverflow.DROP_OLDEST]
 * so a burst is lossy at the JNA boundary rather than blocking the native caller —
 * matching `RealNwApi`'s `tryEmit`-drops-when-full backpressure.
 *
 * ## Closure is drop-tolerant monotone STATE, not just an event (#1522)
 * The `connectionClosed` staging channel is DROP_OLDEST, so a dropped close EVENT would strand a zombie peer.
 * [closedConnections] is the drop-tolerant backstop: the connectionClosed callback synthesizes a MONOTONE
 * close marker (id → reason) directly — a CAS `update{}` add (safe from the JNA thread, no drain) plus a FIFO
 * cap — bypassing the staging that can drop the event. So a close is reflected in [closedConnections] whether
 * or not the EVENT survives, and the seam reconciles it. (Stage 1: this synthesizes the state JVM-side with no
 * dylib touch; re-sourcing it from a native `closedConnections` signal is the deferred follow-up.)
 *
 * ## Viability is drop-tolerant STATE, not an event (#1507/#1509)
 * [connectionViability] mirrors `RealNwApi`'s `MutableStateFlow<Map>`: the fifth native callback delivers a
 * per-connection `(id, viable)` change, and a single drain applies it as a latest-wins delta so the map
 * converges to each connection's LATEST path-viability even if intermediate transitions coalesce or drop at
 * the JNA boundary. A close (from the `connectionClosed` stream) is routed through the *same* drain as a
 * prune, so the two are serialized without a lock; a per-drain guard set makes the prune permanently win,
 * so a late viability change can never resurrect a closed connection (the order the two JNA callbacks fire
 * from their K/N threads does not matter). Result: "absent ⇒ closed", matching the appleMain contract.
 *
 * ## Strong callback references
 * The five [com.sun.jna.Callback] objects are held as fields so JNA's trampolines
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

    // Per-connection LATEST viability as drop-tolerant STATE (#1509), the JVM analogue of RealNwApi's
    // MutableStateFlow. Written only by the single viability drain (below) via atomic update{}, so no lock
    // is needed even though the JNA callbacks fire on many threads.
    private val _connectionViability = MutableStateFlow<Map<NwConnectionId, Boolean>>(emptyMap())

    // Drop-tolerant MONOTONE close markers (#1522), the JVM analogue of RealNwApi's MutableStateFlow. Synthesized
    // directly in the connectionClosed callback via a CAS update{} add (monotone ⇒ safe from the JNA thread with
    // no drain) plus a FIFO cap under [closedOrderLock]. This bypasses the DROP_OLDEST close-EVENT staging, so a
    // dropped close EVENT still leaves a positive closure marker — fixing the JVM-side event-drop zombie (#1522).
    // (Re-sourcing this from a NATIVE closedConnections signal via a diff-forwarder is the deferred follow-up.)
    private val _closedConnections = MutableStateFlow<Map<NwConnectionId, String?>>(emptyMap())
    private val closedOrderLock = Any()
    private val closedOrder = ArrayDeque<NwConnectionId>()

    override val endpointFound: Flow<NwEndpoint> = _endpointFound.asSharedFlow()
    override val connectionOpened: Flow<NwConnectionOpened> = _connectionOpened.asSharedFlow()
    override val bytesReceived: Flow<NwBytesReceived> = _bytesReceived.asSharedFlow()
    override val connectionClosed: Flow<NwConnectionClosed> = _connectionClosed.asSharedFlow()
    override val connectionViability: StateFlow<Map<NwConnectionId, Boolean>> = _connectionViability.asStateFlow()
    override val closedConnections: StateFlow<Map<NwConnectionId, String?>> = _closedConnections.asStateFlow()

    /**
     * Latch [id]'s closure into the monotone [closedConnections] STATE (#1522). Called directly from the JNA
     * [connectionClosedCallback] thread: the map publish is a CAS `update{}` (thread-safe), and the FIFO order
     * tracking (for the [CLOSED_RETENTION_CAP] prune) is guarded by the small [closedOrderLock].
     */
    private fun markClosedFromCallback(id: NwConnectionId, reason: String?) {
        synchronized(closedOrderLock) {
            closedOrder.addLast(id)
            if (closedOrder.size > CLOSED_RETENTION_CAP) {
                // Hoist the FIFO mutation OUT of the CAS lambda (see RealNwApi.markClosed): `update{}` may
                // re-run its lambda on contention, so a `removeFirst()` inside it would pop twice.
                val evicted = closedOrder.removeFirst()
                _closedConnections.update { it - evicted }
            }
            _closedConnections.update { it + (id to reason) }
        }
    }

    // Bounded staging channels: the JNA callbacks deposit here non-suspendingly; per-flow drains
    // forward to the SharedFlows in FIFO order. DROP_OLDEST so the JNA thread never blocks.
    private val endpointFoundStaging = Channel<NwEndpoint>(EVENT_BUFFER, BufferOverflow.DROP_OLDEST)
    private val connectionOpenedStaging = Channel<NwConnectionOpened>(EVENT_BUFFER, BufferOverflow.DROP_OLDEST)
    private val bytesReceivedStaging = Channel<NwBytesReceived>(BYTES_BUFFER, BufferOverflow.DROP_OLDEST)
    private val connectionClosedStaging = Channel<NwConnectionClosed>(EVENT_BUFFER, BufferOverflow.DROP_OLDEST)

    // Viability deltas (set-latest) AND close-prunes share ONE staging channel drained by ONE coroutine,
    // so every mutation of _connectionViability is serialized without a lock and resurrection is
    // impossible: once a Prune is processed, that connection is remembered as closed and any later Set for
    // it is ignored — regardless of the order the two JNA callbacks happen to fire from their K/N threads.
    private val connectionViabilityStaging = Channel<ViabilityDelta>(EVENT_BUFFER, BufferOverflow.DROP_OLDEST)

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
            val id = NwConnectionId(connectionId)
            val mapped = reason.ifEmpty { null }
            connectionClosedStaging.trySend(NwConnectionClosed(id, mapped))
            // #1522: synthesize the drop-tolerant closedConnections STATE directly here, bypassing the
            // DROP_OLDEST close-EVENT staging that can drop the event under a burst. A monotone map add is
            // safe from this JNA thread; a dropped close EVENT therefore still leaves a positive closure
            // marker the seam reconciles — the JVM-side fix for the event-drop zombie.
            markClosedFromCallback(id, mapped)
            // A closed connection's viability entry must be dropped so "absent ⇒ closed" holds (mirrors
            // RealNwApi.clearViability). Routing the prune through the SAME viability channel serializes it
            // with the viability sets — the reconciliation that keeps a late Set from resurrecting the entry.
            connectionViabilityStaging.trySend(ViabilityDelta.Prune(id))
        }

    private val viabilityCallback =
        NwNativeLib.ViabilityCallback { connectionId, viable ->
            connectionViabilityStaging.trySend(ViabilityDelta.Set(NwConnectionId(connectionId), viable != 0))
        }

    init {
        // Register all five callbacks BEFORE any start op (subscribe-before-start): the K/N side
        // subscribes its forwarding collectors here, so no hot no-replay event is missed.
        nativeLib.nw_set_endpoint_found_callback(handle, endpointFoundCallback)
        nativeLib.nw_set_connection_opened_callback(handle, connectionOpenedCallback)
        nativeLib.nw_set_bytes_received_callback(handle, bytesReceivedCallback)
        nativeLib.nw_set_connection_closed_callback(handle, connectionClosedCallback)
        nativeLib.nw_set_connection_viability_callback(handle, viabilityCallback)

        // One drain per flow: forwards staged events to the SharedFlow in FIFO order. Running a
        // single coroutine (not one per event) preserves delivery ordering.
        scope.launch { for (event in endpointFoundStaging) _endpointFound.emit(event) }
        scope.launch { for (event in connectionOpenedStaging) _connectionOpened.emit(event) }
        scope.launch { for (event in bytesReceivedStaging) _bytesReceived.emit(event) }
        scope.launch { for (event in connectionClosedStaging) _connectionClosed.emit(event) }

        // The single viability drain: applies each Set as a latest-wins delta and each Prune as a removal,
        // in FIFO order, so _connectionViability converges to the per-connection LATEST value (#1509). Its
        // private closedIds guard makes a Prune permanently win over any Set for that id, so a late
        // viability Set can never resurrect a closed connection (no lock — this coroutine is the sole
        // writer). Bounded to recent closures: the in-flight reorder window is at most the channel buffer.
        scope.launch {
            // Sole-writer drain, so these need no synchronization. closedIds gives O(1) membership; the
            // FIFO deque bounds it to the CLOSED_ID_GUARD most-recent closures (evicting the eldest).
            val closedIds = HashSet<NwConnectionId>()
            val closedOrder = ArrayDeque<NwConnectionId>()
            for (delta in connectionViabilityStaging) {
                when (delta) {
                    is ViabilityDelta.Set ->
                        if (delta.id !in closedIds) _connectionViability.update { it + (delta.id to delta.viable) }
                    is ViabilityDelta.Prune -> {
                        if (closedIds.add(delta.id)) {
                            closedOrder.addLast(delta.id)
                            if (closedOrder.size > CLOSED_ID_GUARD) closedIds.remove(closedOrder.removeFirst())
                        }
                        _connectionViability.update { it - delta.id }
                    }
                }
            }
        }
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

    /**
     * A single mutation of [_connectionViability], staged by a JNA callback and applied by the one
     * viability drain. [Set] carries a connection's latest `ready`/`waiting` viability; [Prune] drops a
     * connection whose close arrived on the `connectionClosed` stream. Draining both through one channel is
     * what serializes them without a lock.
     */
    private sealed interface ViabilityDelta {
        data class Set(val id: NwConnectionId, val viable: Boolean) : ViabilityDelta
        data class Prune(val id: NwConnectionId) : ViabilityDelta
    }

    private companion object {
        private const val EVENT_BUFFER = 16
        private const val BYTES_BUFFER = 64

        // Cap on the drain's remembered-closed-ids guard. Only needs to exceed the reorder window (a late
        // viability Set racing its own close), which is bounded by the staging channel buffer — so this is
        // generous. Bounding it keeps a long-lived, high-churn session from leaking closed-connection ids.
        private const val CLOSED_ID_GUARD = 256

        // FIFO retention bound on the synthesized closedConnections STATE (#1522): the newest N close markers
        // are retained, the oldest pruned — bounding the map on a long-lived, high-churn session. Matched to
        // NwSeam.TOMBSTONE_CAP (1024): a pruned-before-observed marker recreates the permanent zombie.
        private const val CLOSED_RETENTION_CAP = 1024

        // One shared Cleaner (its own daemon thread) for all bridge instances.
        private val CLEANER: Cleaner = Cleaner.create()
    }
}
