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
 * Which learners to keep in the config: every [connected] one, and every one the config holds that has
 * been disconnected for less than [grace]. Keyed on [NodeId], not on a connection, so a learner that reconnects
 * under the same id within [grace] (a fresh room after a tear, or another relay after failover) is
 * never removed, and each disconnect gets a full grace of its own.
 *
 * Graces start in exactly one place, [retain], which the leader's reconciler calls with the config's
 * learners immediately before it builds a target. So a learner is graced on the first read that finds
 * it in the config but not connected, whatever the reason: it disconnected, it was admitted before a
 * voter restarted from durable storage, or another writer added it. A learner that connects and leaves
 * before it is ever admitted leaves nothing behind.
 *
 * Shared by every voter's reconciler, so a leadership change keeps the pending removals.
 *
 * Thread-safety: the pending map, the released set and every publish of [retained] are guarded by one
 * lock, and nothing suspends under it. Every publish reads [connected] live, so a grace that expires
 * concurrently with a reconnect leaves the learner retained: the reconnect wins.
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
        // A reconnect ends the learner's grace (its next disconnect starts a new one), and every
        // change republishes so the reconciler's gate sees an arrival or a departure at once.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            connected.collect { now ->
                lock.withLock {
                    now.forEach { pending.remove(it)?.cancel() }
                    released.removeAll(now)
                    publish()
                }
            }
        }
    }

    /**
     * Start a grace for every id in [ids] that is neither connected, already pending nor released, and
     * return the learner set to hold, read under the same lock: the one consistent view the caller
     * builds its target from. Called with the config's learners before every target is built.
     */
    fun retain(ids: Set<NodeId>): Set<NodeId> = lock.withLock {
        released.retainAll(ids)
        val live = connected.value
        ids.filter { it !in live && it !in pending && it !in released }.forEach { startGrace(it) }
        publish()
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

    /** Under [lock]. Returns what it published. */
    private fun publish(): Set<NodeId> {
        val now = connected.value + pending.keys
        _retained.value = now
        return now
    }
}
