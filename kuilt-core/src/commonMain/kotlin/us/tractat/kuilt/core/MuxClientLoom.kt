package us.tractat.kuilt.core

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A client [Loom] that weaves **one** base fabric and serves many logical sessions as
 * named channels over a single [NamedMux] — the fix for "every join opens a new socket."
 *
 * In plain terms: a client that joins a lobby *and* a table normally opens two connections.
 * [MuxClientLoom] opens **one** connection and splits it into independent named channels, so
 * `join("lobby")` and `join("table-7")` share a single underlying link.
 *
 * ## How it works
 *
 * The first [weave] lazily weaves [base] once (using [baseRendezvous]) and wraps the resulting
 * [Seam] in a [NamedMux]. Every [weave] thereafter returns `namedMux.channel(nameOf(rendezvous))`
 * — so [host] and [join] for the same logical tag (mapped through [nameOf]) land on the **same**
 * channel name. Concurrent first-weaves are serialised by an internal [Mutex]; the base weaves
 * exactly once.
 *
 * Each returned channel is a **stable, resumable handle**: weaving the same name twice returns
 * the same [Seam], and closing it ([Seam.close]) tears down only that channel — the base stays
 * live for the others (per-channel close).
 *
 * ## Resume — heal every channel over one re-established base
 *
 * If the base fabric tears (the socket drops), the next [weave] re-weaves the base **once** and
 * re-keys every previously-woven channel name onto the new base. Each prior handle heals
 * transparently — callers keep the same [Seam] instances and the same stable [Seam.selfId], so a
 * server can re-associate each per-channel membership by [PeerId]. One re-established base heals
 * all channels; no fan-out of N reconnections.
 *
 * @param base the underlying transport [Loom] (any fabric — WebSocket, TCP, in-memory).
 * @param baseRendezvous how to weave the single base fabric (host a new session or join one).
 * @param scope a [CoroutineScope] owning the per-generation [NamedMux] collectors. **Required** —
 *   no real-dispatcher default, so tests drive the mux under virtual time.
 * @param nameOf maps a [Rendezvous] to the channel name a [weave] resolves to. `host` and `join`
 *   for one logical session must map to the same name.
 * @sample us.tractat.kuilt.core.sampleMuxClientLoom
 */
public class MuxClientLoom(
    private val base: Loom,
    private val baseRendezvous: Rendezvous,
    private val scope: CoroutineScope,
    private val nameOf: (Rendezvous) -> String,
) : Loom {

    /** Serialises base (re-)weaving and channel-handle bookkeeping. */
    private val lock = Mutex()

    /** The active [NamedMux] generation over the current base, or `null` before the first weave. */
    private var generation: NamedMux? = null

    /** Stable per-name handles. Survive base re-weaves — each re-points to the current generation. */
    private val handles = mutableMapOf<String, ResumableChannel>()

    override suspend fun weave(rendezvous: Rendezvous): Seam {
        val name = nameOf(rendezvous)
        return lock.withLock {
            val mux = ensureGeneration()
            handles.getOrPut(name) { ResumableChannel(name) }.also { it.repointTo(mux.channel(name)) }
        }
    }

    /**
     * Closes the current base fabric, tearing down the single shared socket. The next [weave]
     * re-weaves the base and heals every channel handle onto it.
     */
    public suspend fun closeBase(reason: CloseReason = CloseReason.Normal): Unit =
        lock.withLock { generation?.closeBase(reason) }

    /** Returns the live generation, re-weaving the base if absent or torn. Caller holds [lock]. */
    private suspend fun ensureGeneration(): NamedMux {
        val live = generation
        if (live != null && live.baseState.value !is SeamState.Torn) return live
        val freshBase = base.weave(baseRendezvous)
        val fresh = NamedMux(freshBase, scope)
        handles.forEach { (name, handle) -> handle.repointTo(fresh.channel(name)) }
        generation = fresh
        return fresh
    }

    /**
     * A name-keyed [Seam] handle whose underlying channel is swapped out on every base re-weave.
     * Presents a [selfId] frozen at first resolution so the peer identity is stable across
     * resumes; delegates everything else to the current channel.
     */
    private inner class ResumableChannel(private val name: String) : Seam {
        private val delegate = atomic<Seam?>(null)
        private val frozenSelfId = atomic<PeerId?>(null)

        fun repointTo(channel: Seam) {
            delegate.value = channel
            frozenSelfId.compareAndSet(null, channel.selfId)
        }

        private fun current(): Seam =
            delegate.value ?: error("MuxClientLoom channel \"$name\" used before it was woven")

        override val selfId: PeerId get() = frozenSelfId.value ?: current().selfId
        override val peers: StateFlow<Set<PeerId>> get() = current().peers
        override val state: StateFlow<SeamState> get() = current().state

        /** Reads the current channel at collection time so a resumed handle picks up the new base. */
        override val incoming: Flow<Swatch> = flow { emitAll(current().incoming) }

        override suspend fun broadcast(payload: ByteArray): Unit = current().broadcast(payload)

        override suspend fun sendTo(peer: PeerId, payload: ByteArray): Unit = current().sendTo(peer, payload)

        override suspend fun close(reason: CloseReason): Unit = current().close(reason)
    }
}
