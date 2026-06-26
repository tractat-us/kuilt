@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.warp

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.serializer
import us.tractat.kuilt.core.MuxSeam
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig

private val logger = KotlinLogging.logger("us.tractat.kuilt.warp.BobbinExchange")

/** Mux-channel tag for the `GSet<BobbinHash>` manifest Quilter. */
private const val CHANNEL_MANIFEST: Byte = 0x10

/** Mux-channel tag for on-demand fetch request/response frames. */
private const val CHANNEL_FETCH: Byte = 0x11

/**
 * Wire messages that travel on [CHANNEL_FETCH].
 *
 * [Request] is broadcast by a peer that wants the bytes for a known hash.
 * [Response] is unicast back to the requester by any peer that holds those bytes.
 *
 * **Never put [ByteArray] in a CRDT** — referential equality defeats anti-entropy.
 * Kernel bytes travel exclusively on this raw fetch channel.
 */
@Serializable
internal sealed class FetchMessage {
    @Serializable
    internal data class Request(val hash: BobbinHash) : FetchMessage()

    @Serializable
    internal data class Response(val hash: BobbinHash, val bytes: ByteArray) : FetchMessage() {
        // ByteArray equality is referential by default — override so data class equals is structural.
        override fun equals(other: Any?): Boolean =
            other is Response && hash == other.hash && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * hash.hashCode() + bytes.contentHashCode()
    }
}

/**
 * Lazy-bobbin exchange over a [Seam].
 *
 * **What it does.** Peers share a `GSet<BobbinHash>` manifest that is gossiped
 * eagerly via a [Quilter], so every peer knows which bobbins (content-addressed byte
 * blobs) exist across the mesh. The actual bytes are fetched **on demand**: a peer
 * that lacks a bobbin broadcasts a [FetchMessage.Request], suspends until a
 * [FetchMessage.Response] arrives from a peer that holds the bytes, verifies the
 * content hash, caches the bytes in the local [Creel], and returns them.
 *
 * **Single-collection.** [BobbinExchange] takes sole ownership of [seam.incoming]
 * via the internal [MuxSeam] (ADR-034). Do not collect [seam.incoming] elsewhere.
 *
 * **Thread-safety.** Shared mutable state (the in-flight request map) is guarded by
 * an explicit [kotlinx.atomicfu.locks.ReentrantLock]. No `suspend` calls are made
 * inside the locked section. Correct under a multi-threaded dispatcher.
 *
 * **Exception discipline.** Best-effort sends use [runCatchingCancellable] so that
 * [kotlinx.coroutines.CancellationException] is never swallowed.
 *
 * @param seam The [Seam] over which the manifest is gossiped and fetch messages travel.
 *   [BobbinExchange] takes sole ownership of [Seam.incoming] via the internal [MuxSeam].
 * @param creel The local content-addressed byte store shared with the caller. The
 *   exchange populates it on every successful [fetch].
 * @param scope Coroutine scope for all background jobs. Required — no default.
 * @param quilterConfig Tuning for the internal manifest [Quilter]. In tests pass a
 *   short-cadence config with [QuilterConfig.expectVirtualTime] `= true`.
 *
 * @see Creel
 * @see BobbinHash
 */
public class BobbinExchange(
    private val seam: Seam,
    public val creel: Creel,
    private val scope: CoroutineScope,
    private val quilterConfig: QuilterConfig = QuilterConfig(),
) {
    private val replica = ReplicaId(seam.selfId.value)

    // MuxSeam takes sole ownership of seam.incoming (ADR-034 single-collection).
    private val mux = MuxSeam(seam, scope)
    private val manifestSeam = mux.channel(CHANNEL_MANIFEST)
    private val fetchSeam = mux.channel(CHANNEL_FETCH)

    /**
     * Quilter replicating the `GSet<BobbinHash>` manifest.
     *
     * Each local [put] adds a hash to this set and the delta is broadcast immediately.
     * Anti-entropy ensures eventual convergence even across late joiners or dropped frames.
     */
    private val manifestQuilter: Quilter<GSet<BobbinHash>> = Quilter(
        seam = manifestSeam,
        initial = GSet.empty(),
        valueSerializer = GSet.serializer(serializer<BobbinHash>()),
        scope = scope,
        replica = replica,
        config = quilterConfig,
        random = kotlin.random.Random(seam.selfId.value.hashCode().toLong()),
    )

    private val _manifest = MutableStateFlow<Set<BobbinHash>>(emptySet())

    /**
     * The converged set of [BobbinHash]es known across the mesh — the union of every
     * peer's advertised bobbins. A hash appears here as soon as the manifest GSet delta
     * propagates; the kernel bytes may not yet be cached locally (that is the lazy part).
     *
     * Collect or snapshot this flow to discover which bobbins exist before calling [fetch].
     */
    public val manifest: StateFlow<Set<BobbinHash>> = _manifest.asStateFlow()

    // In-flight fetches: hash → deferred that completes with the raw response bytes.
    // Multiple concurrent callers for the same hash share one deferred and one Request.
    // Guarded by [lock]; suspend calls never happen inside the locked section.
    private val lock = reentrantLock()
    private val inFlight = mutableMapOf<BobbinHash, CompletableDeferred<ByteArray>>()

    init {
        // Keep _manifest in sync with the Quilter's converged GSet.
        manifestQuilter.state
            .map { gset -> gset.elements }
            .onEach { keys -> _manifest.value = keys }
            .launchIn(scope)

        // Serve loop — single collection of fetchSeam.incoming (ADR-034).
        scope.launch {
            fetchSeam.incoming.collect { swatch ->
                val msg = runCatching {
                    swatch.decode(Cbor, serializer<FetchMessage>())
                }.getOrNull() ?: return@collect
                when (msg) {
                    is FetchMessage.Request -> serveRequest(msg.hash, swatch.sender ?: return@collect)
                    is FetchMessage.Response -> completeWaiter(msg.hash, msg.bytes)
                }
            }
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Stores [bytes] in the local [creel] and adds the resulting [BobbinHash] to the
     * gossiped manifest so all peers learn the hash immediately.
     *
     * @return The [BobbinHash] under which [bytes] are stored (SHA-256 hex).
     */
    public fun put(bytes: ByteArray): BobbinHash {
        val hash = creel.put(bytes)
        manifestQuilter.mutate { it.add(hash) }
        return hash
    }

    /**
     * Returns the bytes for [hash].
     *
     * Returns from the local [creel] immediately if already cached. Otherwise broadcasts
     * a [FetchMessage.Request] and suspends until a [FetchMessage.Response] arrives from
     * a peer. The response bytes are verified via [Creel.putVerified] before being cached
     * and returned.
     *
     * @throws IllegalArgumentException if a received response's bytes do not hash to
     *   [hash] — indicating tampered or corrupt data.
     */
    public suspend fun fetch(hash: BobbinHash): ByteArray {
        creel.get(hash)?.let { return it }

        val (deferred, isNew) = claimInFlight(hash)
        if (isNew) sendRequest(hash)

        val bytes = deferred.await()
        creel.putVerified(hash, bytes)
        return bytes
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Registers an in-flight fetch for [hash].
     *
     * Returns the (possibly pre-existing) [CompletableDeferred] and whether it was
     * newly created. Callers send a [FetchMessage.Request] only when the deferred is new,
     * preventing duplicate requests when multiple callers race on the same hash.
     */
    private fun claimInFlight(hash: BobbinHash): Pair<CompletableDeferred<ByteArray>, Boolean> =
        lock.withLock {
            val existing = inFlight[hash]
            if (existing != null) {
                existing to false
            } else {
                val deferred = CompletableDeferred<ByteArray>()
                inFlight[hash] = deferred
                deferred to true
            }
        }

    private suspend fun sendRequest(hash: BobbinHash) {
        val payload = Cbor.encodeToByteArray(serializer<FetchMessage>(), FetchMessage.Request(hash))
        runCatchingCancellable { fetchSeam.broadcast(payload) }
            .onFailure { logger.debug { "Failed to broadcast fetch request for ${hash.value}: $it" } }
    }

    private suspend fun serveRequest(hash: BobbinHash, requester: PeerId) {
        val bytes = creel.get(hash) ?: return
        val payload = Cbor.encodeToByteArray(serializer<FetchMessage>(), FetchMessage.Response(hash, bytes))
        runCatchingCancellable { fetchSeam.sendTo(requester, payload) }
            .onFailure { logger.debug { "Failed to send fetch response for ${hash.value} to $requester: $it" } }
    }

    /**
     * Completes any pending [fetch] waiters for [hash] with the raw response [bytes].
     *
     * The deferred is removed from [inFlight] before completing it so subsequent [fetch]
     * calls after the bytes are cached hit [Creel.get] directly rather than re-joining
     * a stale in-flight entry.
     */
    private fun completeWaiter(hash: BobbinHash, bytes: ByteArray) {
        val deferred = lock.withLock { inFlight.remove(hash) } ?: return
        deferred.complete(bytes)
    }
}
