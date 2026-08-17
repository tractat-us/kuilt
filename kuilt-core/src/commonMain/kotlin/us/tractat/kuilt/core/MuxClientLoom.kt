package us.tractat.kuilt.core

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
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
 * A handle's observable surfaces heal asymmetrically, by design: [Seam.selfId] is frozen across
 * resumes, [Seam.state] and [Seam.peers] **follow** the handle onto the fresh generation (a flow
 * captured before the resume keeps up), but [Seam.incoming] is **per-generation** — it completes at
 * its generation's [SeamState.Torn] and must be re-collected after a resume. Making `incoming`
 * follow would break its "completes on `Torn`" termination contract, which downstream `onCompletion`
 * cleanup relies on.
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
     * The [base] fabric's verdict, verbatim. Multiplexing changes how many logical sessions share a
     * link — not which medium carries it, nor whether that medium is usable on this runtime — so the
     * base's capability *is* this loom's capability, in both halves.
     *
     * Inheriting [Loom]'s default would be strictly worse than an un-established guess: it would
     * discard a verdict already established one layer down. A base that knows its dylib will not
     * load reports [FabricAvailability.Unavailable], and wrapping it must not launder that into a
     * confident `Available`. The [TransportCapability.roles] half matters just as much — it is read
     * pre-weave by `CompositeLoom.weave` and `CompositeSeam.attachDesiredPly` and captured onto the
     * ply for the composite's role rollup, so a muxed ply defaulting to `emptySet()` silently
     * under-reports what the composite can do (#1936).
     *
     * Deliberately **not** generation-aware: [Loom.capability] is the pre-connect surface, answered
     * before and independently of any [weave]. The live per-session view is [Seam.capability].
     */
    override fun capability(): TransportCapability = base.capability()

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
     *
     * ## Resume semantics — the surfaces split deliberately
     *
     * - [selfId] is **frozen** at first resolution (stable across every heal).
     * - [state] and [peers] **follow** the handle across heals: a flow captured before a resume
     *   switches to the fresh generation (see [FollowingStateFlow]).
     * - [incoming] is **per-generation**: it binds at collection start and completes at that
     *   generation's [SeamState.Torn]; it does **not** follow a heal — re-collect after a resume.
     *
     * The [state]/[peers] flows are [FollowingStateFlow]s: `.value` reads the current generation
     * live, and a **captured** flow's `collect` follows the swap — on heal the old generation's
     * collection is cancelled and the new generation's flow switched in, emitting its fresh
     * [SeamState.Woven]. A naive `get() = current().state` re-reads the delegate only at property
     * access, leaving a long-lived collector pinned at the pre-heal generation's terminal
     * [SeamState.Torn] (#1387). Following per-collector (no shared relay) means a `.first()` /
     * `filterIsInstance<Torn>().first()` consumer that terminates at/before the first heal behaves
     * exactly as before — only a collector that outlives a heal sees the difference. [incoming] is
     * intentionally *not* made following: its "completes on `Torn`" termination is load-bearing for
     * downstream `onCompletion` cleanup (a following `incoming` would break it).
     */
    private inner class ResumableChannel(private val name: String) : Seam {
        /** The current generation's channel, published so [state]/[peers] follow every re-point. */
        private val delegate = MutableStateFlow<Seam?>(null)
        private val frozenSelfId = atomic<PeerId?>(null)

        fun repointTo(channel: Seam) {
            frozenSelfId.compareAndSet(null, channel.selfId)
            delegate.value = channel
        }

        private fun current(): Seam =
            delegate.value ?: error("MuxClientLoom channel \"$name\" used before it was woven")

        override val selfId: PeerId get() = frozenSelfId.value ?: current().selfId

        override val peers: StateFlow<Set<PeerId>> = FollowingStateFlow { it.peers }
        override val state: StateFlow<SeamState> = FollowingStateFlow { it.state }

        /**
         * Binds to the current generation **at collection start** and completes when *that*
         * generation reaches [SeamState.Torn]. Unlike [state]/[peers], it does **not** follow a
         * heal — a collection spanning a resume ends on the pre-heal generation. Re-collect
         * `handle.incoming` after a resume. This asymmetry is deliberate (see [ResumableChannel]):
         * [Seam.incoming]'s "completes on Torn" termination is load-bearing for downstream
         * `onCompletion` cleanup, which a cross-generation following flow would break.
         */
        override val incoming: Flow<Swatch> = flow { emitAll(current().incoming) }

        /**
         * A scope-free [StateFlow] view over the current generation's `select`ed flow that follows
         * generation swaps. `.value` reads the live current generation; `collect` re-collects the
         * newest generation via [flatMapLatest] over [delegate], so a captured flow keeps up with a
         * heal instead of pinning to the torn pre-heal generation.
         *
         * [distinctUntilChanged] upholds the [StateFlow] contract that a collector never receives
         * two consecutive equal values *across a swap*: at heal time the pre-heal generation's
         * collapsed value and the fresh generation's initial value can coincide (e.g. `peers`
         * collapses to `{selfId}` on both sides) and [flatMapLatest] alone would deliver it twice.
         *
         * **Missed-`Torn` window.** A following collector is **not** guaranteed to observe the
         * pre-heal generation's terminal [SeamState.Torn]: if a heal completes before the collector
         * resumes, [flatMapLatest] cancels the old generation's collection at the swap and the
         * intermediate `Torn` is conflated away. Inherent to any conflated following design; benign
         * while heals are weave-driven (a wide window between tear and re-weave), but worth noting
         * for a future auto-reconnect that could heal within that window.
         */
        @OptIn(ExperimentalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
        private inner class FollowingStateFlow<T>(
            private val select: (Seam) -> StateFlow<T>,
        ) : StateFlow<T> {
            override val value: T get() = select(current()).value
            override val replayCache: List<T> get() = listOf(value)
            override suspend fun collect(collector: FlowCollector<T>): Nothing {
                delegate.filterNotNull().flatMapLatest { select(it) }.distinctUntilChanged().collect { collector.emit(it) }
                awaitCancellation()
            }
        }

        override suspend fun broadcast(payload: ByteArray): Unit = current().broadcast(payload)

        /**
         * Refused against **this view's** [selfId], not the current generation's.
         *
         * [selfId] is frozen at the first generation (see [frozenSelfId]) precisely so a heal cannot
         * change the identity a holder reads — so that frozen id is the one a caller can self-send
         * to, and delegating the check would hold the send against whatever id the *post-heal* seam
         * happens to publish. Those differ exactly when the freeze is doing its job (#2428).
         */
        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            require(peer != selfId) { "Cannot send to self — use broadcast if you intend to loop back" }
            current().sendTo(peer, payload)
        }

        override suspend fun close(reason: CloseReason): Unit = current().close(reason)
    }
}
