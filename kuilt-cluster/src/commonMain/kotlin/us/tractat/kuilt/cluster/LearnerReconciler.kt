package us.tractat.kuilt.cluster

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import kotlin.time.Duration.Companion.milliseconds

private val log = KotlinLogging.logger("us.tractat.kuilt.cluster.LearnerReconciler")

/**
 * Backoff after a refused or failed membership change, so a leader whose role flow still reads
 * Leader while its engine already refuses cannot hot-loop. The same cadence as
 * `CORE_ADMISSION_RETRY_BACKOFF`.
 */
private val LEARNER_RECONCILE_RETRY_BACKOFF = 200.milliseconds

/**
 * Keep [node]'s learner set a superset of [desired] for as long as [node] leads: whenever a desired
 * id is neither a voter nor a learner, commit one membership change adding **every** such id at once.
 *
 * Launched on every voter. The role gate means only the current leader acts, so a leadership change
 * hands the work to the new leader with nothing to coordinate. On each node the loop is the only
 * writer, and it serialises its own changes.
 *
 * ## Why every attempt re-reads the membership
 *
 * `changeMembership` takes an absolute config and compares it with nothing, so a target built from a
 * stale read silently undoes whatever committed since. #3120 was exactly that: each admission sent
 * `voters + {itself}`, and a retry after `MembershipChangeInProgressException` re-sent it once the
 * previous admission had committed, evicting that learner. This loop builds every target from
 * `node.membership.value` read immediately before the call, and is level-triggered: a refused or
 * superseded attempt is followed by a fresh read, never a resend. Admissions that arrive while a
 * change is in flight are batched into the next one, so an admission storm costs a few config
 * entries rather than one each.
 *
 * Additive only: an id leaving [desired] is never removed here.
 *
 * The generalisation of [launchCoreLearnerAdmission], whose desired set is a seam's roster and which
 * admits one id per change. Correct only while this loop is the sole membership writer for [node];
 * a caller changing membership directly can still race it, though the next iteration re-adds any
 * desired learner such a change drops.
 */
internal fun CoroutineScope.launchLearnerReconciler(
    node: RaftNode,
    desired: StateFlow<Set<NodeId>>,
): Job = launch {
    while (true) {
        // Suspend until this node leads and something desired is missing from its membership.
        combine(desired, node.role, node.membership) { want, role, membership ->
            role is RaftRole.Leader && missingLearners(want, membership).isNotEmpty()
        }.first { it }

        val current = node.membership.value
        val missing = missingLearners(desired.value, current)
        if (missing.isEmpty()) continue
        try {
            node.changeMembership(ClusterConfig(voters = current.voters, learners = current.learners + missing))
        } catch (failure: Throwable) {
            // Unshielded, and the loop's next iteration follows: only THIS job's cancellation ends it.
            // NotLeader, LeadershipLost and MembershipChangeInProgress all mean "read again, later".
            currentCoroutineContext().ensureActive()
            log.debug { "learner-reconciler: adding $missing failed, re-reading: $failure" }
            delay(LEARNER_RECONCILE_RETRY_BACKOFF)
        }
    }
}

private fun missingLearners(desired: Set<NodeId>, membership: ClusterConfig): Set<NodeId> =
    desired - membership.voters - membership.learners
