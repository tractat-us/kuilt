package us.tractat.kuilt.demo

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.crdt.LWWMap
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig

/**
 * One peer's view of a shared Patchwork quilt — the collaborative canvas at the
 * heart of the kuilt demo app.
 *
 * A group of people, each on their own device, stitch coloured patches onto one
 * shared quilt. Everyone sees everyone else's patches appear live; a peer that
 * goes offline keeps stitching, and its patches merge in when it reconnects —
 * nothing lost, nothing doubled.
 *
 * The canvas is an [LWWMap] of [Cell] → [Colour] (per-cell last-writer-wins),
 * replicated live over a [Seam] by a [Quilter]. Convergence under partition
 * falls out of the CRDT: while offline, stitches land on the local board; on
 * reconnect the [Quilter] full-state exchange join-merges the local board with
 * every peer's, in both directions.
 *
 * Lifecycle: [host] or [join] (or [connect] with an explicit [Rendezvous]) goes
 * online; [disconnect] goes offline but keeps the local board (tunnel mode);
 * connecting again merges it back. A transport that tears on its own (relay
 * crash, socket drop — the seam latches [SeamState.Torn]) is treated exactly
 * like [disconnect]: the session goes offline, keeps the board, and can
 * reconnect. Cancelling [scope] tears everything down.
 *
 * @param loom the fabric to weave sessions from ([us.tractat.kuilt.core.InMemoryLoom]
 *   in tests; WebSocket/TCP/WebRTC looms in the real demo).
 * @param stitcher the stable identity stitches are tagged with in the LWW map.
 *   Distinct per participant — two sessions must never share a [stitcher].
 *   (The [Quilter]'s own delta-sequence replica id is deliberately *not* this
 *   id: it is minted fresh per connection, because a rebooted delta author
 *   restarting at seq 1 would look stale to peers. The two identity domains
 *   are independent.)
 * @param scope owns all background work (the [Quilter]'s collectors and the
 *   board mirror). Inject a test scope's `backgroundScope` in tests.
 * @param clock wall-clock source for LWW stitch timestamps; see [StitchClock].
 * @param quilterConfig replication tuning, passed through to each [Quilter].
 *   Tests set `expectVirtualTime = true`.
 */
class PatchworkSession(
    private val loom: Loom,
    val stitcher: ReplicaId,
    private val scope: CoroutineScope,
    private val clock: StitchClock,
    private val quilterConfig: QuilterConfig = QuilterConfig(),
) {
    /**
     * Guards [board], [quilter], [seam], [mirrorJob], [watcherJob],
     * [connecting], and [lastTimestamp]. Sessions must be correct under a
     * multi-threaded dispatcher; suspending calls ([Loom.weave], [Seam.close])
     * stay outside the locked sections.
     */
    private val lock = reentrantLock()

    /** The local board — the single source of truth while offline. */
    private var board: LWWMap<Cell, Colour> = LWWMap.empty()
    private var quilter: Quilter<LWWMap<Cell, Colour>>? = null
    private var seam: Seam? = null
    private var mirrorJob: Job? = null
    private var watcherJob: Job? = null
    private var connecting = false
    private var lastTimestamp = 0L

    private val _quilt = MutableStateFlow<Map<Cell, Colour>>(emptyMap())

    /** The merged canvas as everyone should render it: cell → colour, live. */
    val quilt: StateFlow<Map<Cell, Colour>> = _quilt.asStateFlow()

    private val _connected = MutableStateFlow(false)

    /** Whether this peer is currently online (woven into a session). */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** Hosts a new quilt session. */
    suspend fun host(pattern: Pattern): Unit = connect(Rendezvous.New(pattern))

    /** Joins an existing quilt session. */
    suspend fun join(tag: Tag): Unit = connect(Rendezvous.Existing(tag))

    /**
     * Goes online: weaves a [Seam] from [loom] and starts replicating the local
     * board over it. The current board (including any patches stitched while
     * offline) seeds the replicator, so reconnecting merges offline work into
     * every peer — and their work into this one — via the full-state exchange.
     *
     * @throws IllegalStateException if already connected (or connecting).
     */
    suspend fun connect(rendezvous: Rendezvous) {
        lock.withLock {
            check(!connecting && seam == null) { "already connected — disconnect() first" }
            connecting = true
        }
        val newSeam = try {
            loom.weave(rendezvous)
        } catch (e: Throwable) {
            lock.withLock { connecting = false }
            throw e
        }
        val newQuilter = lock.withLock {
            Quilter(
                seam = newSeam,
                initial = board,
                valueSerializer = BOARD_SERIALIZER,
                scope = scope,
                config = quilterConfig,
            ).also {
                seam = newSeam
                quilter = it
                connecting = false
            }
        }
        // Mirror the replicator's merged state into the session-owned flow so
        // `quilt` stays live across connect/disconnect cycles.
        mirrorJob = newQuilter.state
            .onEach { merged ->
                lock.withLock { board = merged }
                _quilt.value = merged.entries
            }
            .launchIn(scope)
        // Watch for the transport tearing on its own (relay crash, socket
        // drop): free the slot so the session goes offline and a fresh
        // connect works without an explicit disconnect().
        watcherJob = scope.launch {
            newSeam.state.first { it is SeamState.Torn }
            releaseAfterTear(newSeam)
        }
        _connected.value = true
    }

    /**
     * Frees the single-seam slot after [torn] latched [SeamState.Torn] on its
     * own — the self-driven counterpart of [disconnect]. Identity-guarded so a
     * stale watcher from an already-replaced connection is a no-op, and
     * idempotent with [disconnect] (which clears [seam] first). The local
     * board survives, exactly like tunnel mode.
     */
    private suspend fun releaseAfterTear(torn: Seam) {
        val parted = lock.withLock {
            if (seam !== torn) return
            val liveQuilter = quilter
            board = liveQuilter?.state?.value ?: board
            quilter = null
            seam = null
            val job = mirrorJob
            mirrorJob = null
            // Not cancelled — this runs *inside* the watcher; it completes naturally.
            watcherJob = null
            liveQuilter to job
        }
        parted.second?.cancel()
        parted.first?.close()
        _connected.value = false
        torn.close() // release any lingering transport resources; suspends — outside the lock
    }

    /**
     * Stitches a patch: sets [cell] to [colour] on the shared quilt.
     *
     * Online, the patch is broadcast to all peers as a single-cell delta.
     * Offline, it lands on the local board and merges on the next [connect].
     * Concurrent stitches to the same cell resolve per-cell last-writer-wins
     * (timestamp, then [stitcher] tie-break) — identically on every peer.
     */
    fun stitch(cell: Cell, colour: Colour): Unit = lock.withLock {
        lastTimestamp = maxOf(lastTimestamp + 1, clock.nowMillis())
        val timestamp = lastTimestamp
        val live = quilter
        board = if (live != null) {
            // A one-cell map is a proper delta: joining it into any board sets
            // exactly this cell (or loses the LWW race, identically everywhere).
            live.mutate { it.set(stitcher, timestamp, cell, colour) }
            live.state.value
        } else {
            board.piece(board.set(stitcher, timestamp, cell, colour))
        }
        _quilt.value = board.entries
    }

    /**
     * Goes offline (tunnel mode): tears the seam but keeps the local board.
     * Stitches keep landing locally; [connect] again to merge back in.
     * No-op when already offline.
     */
    suspend fun disconnect() {
        val parted = lock.withLock {
            val liveQuilter = quilter ?: return
            val liveSeam = checkNotNull(seam) { "quilter without a seam" }
            board = liveQuilter.state.value
            quilter = null
            seam = null
            val jobs = listOf(mirrorJob, watcherJob)
            mirrorJob = null
            watcherJob = null
            Triple(liveQuilter, liveSeam, jobs)
        }
        parted.third.forEach { it?.cancel() }
        parted.first.close()
        parted.second.close() // suspends — outside the lock
        _connected.value = false
    }

    private companion object {
        private val BOARD_SERIALIZER = LWWMap.serializer(Cell.serializer(), Colour.serializer())
    }
}
