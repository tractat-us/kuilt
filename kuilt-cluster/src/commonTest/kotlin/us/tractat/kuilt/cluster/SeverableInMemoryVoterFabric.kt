package us.tractat.kuilt.cluster

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.time.Duration

/**
 * A severable [InMemoryVoterFabric] — the reconnection harness's fabric. It models a **half-open**
 * voter-to-voter link under virtual time, so the deterministic reconnection tests drive the *real*
 * redial supervisors and ping-reap eviction path with no real sockets.
 *
 * ## The half-open model (why this is faithful, not a clean-close shortcut)
 *
 * A real half-open link is a silently-dead TCP corpse: bytes are black-holed (the proxy reads and
 * discards them), **no** FIN/RST is emitted, so both peers still believe the link is up until the
 * WebSocket ping's pong-timeout reaps it. This fabric reproduces exactly that shape:
 *
 * - [sever] flips the edge to *byte-dead immediately* but *roster-present*: every wrapped [send]
 *   silently discards while severed (AMENDMENT 3a — it must NEVER throw, or [Mesh.broadcast]'s
 *   `onFailure` would evict the peer EARLY via the send path, a clean drop that defeats the
 *   timeout-driven fidelity guard). The link's `incoming` stays OPEN, so both read loops keep
 *   running and both rosters keep the peer — precisely a half-open link that "looks alive".
 * - Only after [livenessTimeout] — the virtual analog of the ping+pong reap delay — does the armed
 *   reaper close BOTH ends of the physical link. Closing both ends completes both peers'
 *   `Connection.incoming`, driving each `MeshSeam.readLoop`'s `finally → removePeer`: the REAL
 *   eviction path, on both ends, never a test backdoor poking the roster.
 * - [restore] clears the flag; the supervisor's next redial opens a fresh live relay that the
 *   accept-pump admits, and the peer re-enters both rosters.
 *
 * ## AMENDMENT 1 — a redial issued while severed SUSPENDS
 *
 * While an edge is severed, [openLink] does not hand back a dead conn — it [awaitCancellation]s
 * (suspends forever). This is the faithful physics (connect succeeds, upgrade bytes are discarded,
 * negotiation never reaches `101`) AND it avoids a real wedge: a returned-but-dead conn would sail
 * past `dialTimeout` and then hang in `mesh.addLink`'s `MeshHello` handshake (`firstFrame` awaiting a
 * reply that never comes) — an UNBOUNDED hang the supervisor's `withTimeoutOrNull(dialTimeout)` does
 * NOT cover (it wraps only `dial`, not `addLink`). By suspending inside the dial, the supervisor's
 * timeout fires, returns `null`, and the backoff loop retries — mirroring the merged
 * `VoterReconnectionSupervisorTest`'s `delay(1.hours)`-inside-dial idiom.
 *
 * ## AMENDMENT 3b — per-edge generation counter
 *
 * A `sever → restore → sever` within one [livenessTimeout] window arms two reapers; the first is still
 * sleeping when the second (live) generation exists. Each [sever] bumps a per-edge generation and its
 * reaper reaps **only if the edge is still severed at the same generation it was armed for** — so a
 * stale reaper can never reap a later, live generation.
 *
 * All shared state is guarded by an explicit [reentrantLock] (not single-thread confinement), so the
 * fabric is correct under a multi-threaded dispatcher; the suspending `close()`s the reaper performs
 * are done OUTSIDE the lock.
 *
 * @param voters the voter ids (passed through to [InMemoryVoterFabric]).
 * @param scope the scope the virtual reaper is armed on — pass the test [kotlinx.coroutines.test.TestScope.backgroundScope]
 *   so it advances on the one virtual clock and cancels cleanly at teardown.
 * @param livenessTimeout the virtual delay between [sever] and the reap — the ping+pong reap analog.
 */
internal class SeverableInMemoryVoterFabric(
    voters: List<NodeId>,
    private val scope: CoroutineScope,
    private val livenessTimeout: Duration,
) : InMemoryVoterFabric(voters) {

    /** Per-edge severed flag, current generation, and the raw ends of the live link (to reap on timeout). */
    private class EdgeState {
        var severed: Boolean = false
        var generation: Int = 0
        var live: Pair<Connection, Connection>? = null
    }

    private val lock = reentrantLock()
    private val states = mutableMapOf<VoterEdge, EdgeState>()

    override suspend fun openLink(edge: VoterEdge): Pair<Connection, Connection> {
        // Decide severed-vs-live and record the live ends in ONE critical section (no suspension point
        // inside — connectionPair() is non-suspending), so a concurrent sever can never slip between the
        // check and the record and leave an un-reapable severed link.
        val fresh: Pair<Connection, Connection>? = lock.withLock {
            val st = states.getOrPut(edge) { EdgeState() }
            if (st.severed) {
                null
            } else {
                connectionPair().also { st.live = it }
            }
        }
        // AMENDMENT 1: severed ⇒ suspend forever (outside the lock). The supervisor's dialTimeout fires
        // and it retries; a fresh live dial only succeeds once restore() clears the flag.
        if (fresh == null) awaitCancellation()
        val (dialerRaw, acceptorRaw) = fresh
        return SeverableConnection(dialerRaw, edge) to SeverableConnection(acceptorRaw, edge)
    }

    /**
     * Half-open the link between [a] and [b]: mark it severed (sends now discard, but the link stays
     * roster-present) and arm the virtual reaper that, [livenessTimeout] later, closes both ends and
     * drives the real per-end `removePeer` eviction. Bidirectional by construction — closing both ends
     * of the single physical link reaps BOTH rosters, matching real half-open on both peers.
     */
    fun sever(a: NodeId, b: NodeId) {
        val edge = edgeOf(a, b)
        // AMENDMENT 3b: bump the generation; the reaper below reaps only at THIS generation.
        val gen = lock.withLock {
            val st = states.getOrPut(edge) { EdgeState() }
            st.severed = true
            ++st.generation
        }
        scope.launch {
            delay(livenessTimeout)
            val toReap = lock.withLock {
                val st = states.getValue(edge)
                // Reap only if still severed AT the generation we armed for — a later restore (or a
                // restore→sever that advanced the generation) leaves this stale reaper inert.
                if (st.severed && st.generation == gen) {
                    st.live.also { st.live = null }
                } else {
                    null
                }
            }
            // Close BOTH ends OUTSIDE the lock: each completes the OTHER peer's Connection.incoming, so
            // both MeshSeam.readLoops hit finally → removePeer — the real, two-sided eviction path.
            toReap?.let { (dialerRaw, acceptorRaw) ->
                runCatchingCancellable { dialerRaw.close() }
                runCatchingCancellable { acceptorRaw.close() }
            }
        }
    }

    /** Heal the link between [a] and [b]: the next redial's [openLink] returns a fresh live relay. */
    fun restore(a: NodeId, b: NodeId) {
        lock.withLock { states.getOrPut(edgeOf(a, b)) { EdgeState() }.severed = false }
    }

    private fun isSevered(edge: VoterEdge): Boolean = lock.withLock { states[edge]?.severed ?: false }

    /**
     * The single directed edge that actually exists for the pair — the lower id dials the higher
     * (`assembleVoterMesh`'s rule), so `openLink` is only ever keyed `VoterEdge(lower, higher)`.
     * Normalising here makes [sever]/[restore] order-independent in their arguments.
     */
    private fun edgeOf(a: NodeId, b: NodeId): VoterEdge {
        val sorted = listOf(a, b).sortedBy { it.value }
        return VoterEdge(sorted[0], sorted[1])
    }

    /**
     * Wraps one raw [connectionPair] end. While the edge is severed [send] DISCARDS silently
     * (AMENDMENT 3a — never throws); `incoming` is left untouched (open) so the read loop keeps
     * running until the reaper closes the underlying spool.
     */
    private inner class SeverableConnection(
        private val raw: Connection,
        private val edge: VoterEdge,
    ) : Connection {
        override suspend fun send(frame: ByteArray) {
            // AMENDMENT 3a: a severed (or reaped) send must NEVER throw. While severed we discard here
            // (the proxy read-and-discard of a half-open link). After a reap the underlying Spool is
            // closed and deliver() drops on a closed channel without throwing — throw-free in every state.
            if (isSevered(edge)) return
            raw.send(frame)
        }

        override val incoming: Flow<ByteArray> get() = raw.incoming

        override suspend fun close() {
            raw.close()
        }
    }
}
