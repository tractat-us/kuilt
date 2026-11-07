package us.tractat.kuilt.cluster

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import us.tractat.kuilt.raft.NodeId
import kotlin.time.Duration

/**
 * Which learners to keep in the config: every [connected] one, and every one that left less than
 * [grace] ago. Keyed on [NodeId], not on a connection, so a learner that reconnects under the same id
 * within [grace] (a fresh room after a tear, or another relay after failover) is never removed.
 *
 * A learner the config holds but this server has never seen connected (one admitted before a voter
 * restarted from durable storage, or added by another writer) is handed a grace the first time the
 * reconciler reads it, through [retain], rather than being removed at once.
 *
 * Shared by every voter's reconciler, so a leadership change keeps the pending removals.
 *
 * Thread-safety: the pending map and every publish of [retained] are guarded by one lock, and nothing
 * suspends under it. Every publish reads [connected] live, so a grace that expires concurrently with a
 * reconnect leaves the learner retained: the reconnect wins.
 *
 * @param connected the learners with a live connection, `RaftRelayHub.learnersFlow`.
 * @param grace how long a learner may stay disconnected before it leaves [retained]; non-negative.
 * @param scope parents the collector and the grace timers.
 */
internal class LearnerGrace(
    private val connected: StateFlow<Set<NodeId>>,
    private val grace: Duration,
    private val scope: CoroutineScope,
) {
    init {
        require(!grace.isNegative()) { "learner grace must not be negative, was $grace" }
    }

    private val lock = reentrantLock()

    /** Learners not connected whose grace is running, each with the timer that will remove it. */
    private val pending: MutableMap<NodeId, Job> = mutableMapOf()

    /**
     * Learners whose grace ran out while disconnected and that the config may still hold until the
     * removal commits. [retain] must not hand them a second grace, or none would ever be removed.
     * Pruned to the ids each [retain] is given, so it is bounded by the config's learner set.
     */
    private val released: MutableSet<NodeId> = mutableSetOf()

    private val _retained = MutableStateFlow(connected.value)

    /** Connected learners plus those still inside their grace: the learner set to hold. */
    val retained: StateFlow<Set<NodeId>> = _retained.asStateFlow()

    init {
        // Undispatched so a disconnect that lands before this collector is first scheduled is still
        // seen as a change from the value retained started from.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            connected.collect { now -> onConnectedChanged(now) }
        }
    }

    /**
     * Start a grace for every id in [ids] that is neither connected nor already pending. Called with
     * the membership's learners before a target is built, so no learner is dropped without one.
     */
    fun retain(ids: Set<NodeId>) {
        lock.withLock {
            released.retainAll(ids)
            val live = connected.value
            val fresh = ids.filter { it !in live && it !in pending && it !in released }
            if (fresh.isEmpty()) return
            fresh.forEach { startGrace(it) }
            publish()
        }
    }

    private fun onConnectedChanged(now: Set<NodeId>) {
        lock.withLock {
            now.forEach { pending.remove(it)?.cancel() }
            released.removeAll(now)
            (_retained.value - now - pending.keys).forEach { startGrace(it) }
            publish()
        }
    }

    /** Under [lock]. */
    private fun startGrace(id: NodeId) {
        val timer = scope.launch(start = CoroutineStart.LAZY) {
            delay(grace)
            expire(id, checkNotNull(coroutineContext[Job]))
        }
        pending[id] = timer
        timer.start()
    }

    private fun expire(id: NodeId, timer: Job) {
        lock.withLock {
            if (pending[id] !== timer) return
            // Always clear the entry, even when a reconnect landed between the timer firing and this
            // lock: the reconnect keeps the learner retained through `connected`, and a finished timer
            // left here would stop a later disconnect from ever starting a new grace.
            pending.remove(id)
            if (id !in connected.value) released += id
            publish()
        }
    }

    /** Under [lock]. */
    private fun publish() {
        _retained.value = connected.value + pending.keys
    }
}
