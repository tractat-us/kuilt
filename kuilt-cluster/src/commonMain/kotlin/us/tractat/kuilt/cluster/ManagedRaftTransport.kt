package us.tractat.kuilt.cluster

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftEnvelope
import us.tractat.kuilt.raft.RaftTransport
import us.tractat.kuilt.raft.SeamRaftTransport

/**
 * A [RaftTransport] whose backing [Seam] can be replaced on transport tear without
 * recreating the [us.tractat.kuilt.raft.RaftNode].
 *
 * The [RaftNode] keeps its identity and state across reconnects; only the underlying
 * [Seam] is swapped when the entry server changes. This is the core primitive that
 * makes [us.tractat.kuilt.cluster.clusterClient] possible.
 *
 * ## Lifecycle
 *
 * 1. Construct with the node's stable [NodeId]. The transport starts with no backing Seam —
 *    [peers] is empty and [sendTo] drops frames silently until [swapSeam] is first called.
 * 2. Pass this transport to `scope.raftNode(...)` — the [RaftNode] lifetime is
 *    bound to [scope]; this transport outlives individual Seam instances.
 * 3. Call [swapSeam] with the first (and each subsequent) [Seam] — the [RaftNode]
 *    begins or resumes operation.
 * 4. On transport tear: call [swapSeam] again with the freshly-joined [Seam].
 *
 * ## Thread safety
 *
 * The current [SeamRaftTransport] pointer is guarded by an atomicfu reentrant lock.
 * [incoming] is a hot [MutableSharedFlow] relayed from each seam's incoming flow;
 * each swap launches a new relay coroutine in [scope] and cancels the previous one.
 * The lock is **never** held across a suspend call.
 *
 * ## Reachable peers across the swap
 *
 * [peers] is a [MutableStateFlow] updated to reflect the newly installed seam's
 * peer set on each [swapSeam]. Between a tear and a swap, [peers] holds the last
 * known set (possibly stale).
 *
 * @param scope The [CoroutineScope] for relay coroutines. Must outlive all [swapSeam] calls.
 * @param selfId This node's stable [NodeId] — does not change across reconnects.
 */
public class ManagedRaftTransport(
    private val scope: CoroutineScope,
    public override val selfId: NodeId,
) : RaftTransport {

    private val lock = reentrantLock()

    // Null until the first swapSeam call. Sends before that are silently dropped.
    private var currentTransport: SeamRaftTransport? = null

    private val _peers: MutableStateFlow<Set<NodeId>> = MutableStateFlow(emptySet())
    override val peers: StateFlow<Set<NodeId>> = _peers.asStateFlow()

    private val _incoming: MutableSharedFlow<RaftEnvelope> =
        MutableSharedFlow(extraBufferCapacity = Int.MAX_VALUE)
    override val incoming: Flow<RaftEnvelope> = _incoming

    // The active relay job — one per Seam. Cancelled on each swap.
    private val relayJob = atomic<Job?>(null)

    override suspend fun sendTo(peer: NodeId, message: ByteArray) {
        val transport = lock.withLock { currentTransport } ?: return
        // Learner-over-relay addressing (#544): the backing seam is strictly 2-peer
        // (this learner + one relay server). The Raft engine forwards a proposal to the
        // *real* leader NodeId read from the AppendEntries body, which generally is not
        // this relay's PeerId; addressing it directly would hit an absent peer and drop.
        // The relay's LearnerRouter already routes each inbound frame to the current
        // leader voter, so we always send to the single relay peer (peers − selfId) —
        // letting the learner keep committing through any relay endpoint regardless of
        // which voter leads, the precondition for failover without moving leadership.
        val relayPeer = transport.peers.value.singleOrNull { it != selfId } ?: peer
        runCatchingCancellable { transport.sendTo(relayPeer, message) }
            .onFailure { /* fire-and-forget: drop silently on tear */ }
    }

    /**
     * Replace the backing [Seam] with [newSeam].
     *
     * Cancels the current relay coroutine, installs [newSeam] as the active transport,
     * refreshes [peers] from the new seam, and starts a new relay coroutine. The [RaftNode]
     * observing [incoming] and [peers] continues without interruption — it sees the updated
     * peer set as network connectivity was restored.
     *
     * Must not be called concurrently with another [swapSeam] call.
     */
    public fun swapSeam(newSeam: Seam) {
        val newTransport = SeamRaftTransport(newSeam)
        val newPeers = newSeam.peers.value.toNodeIds()
        lock.withLock {
            currentTransport = newTransport
            _peers.value = newPeers
        }
        cancelCurrentRelay()
        startRelay(newSeam)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun startRelay(seam: Seam) {
        val job = scope.launch {
            runCatchingCancellable {
                seam.incoming.collect { swatch ->
                    val sender = swatch.sender ?: return@collect
                    _incoming.emit(RaftEnvelope(NodeId(sender.value), swatch.payload))
                }
            }
        }
        relayJob.value = job
    }

    private fun cancelCurrentRelay() {
        relayJob.value?.cancel()
    }

    private fun Set<PeerId>.toNodeIds(): Set<NodeId> =
        mapTo(mutableSetOf()) { NodeId(it.value) }
}
