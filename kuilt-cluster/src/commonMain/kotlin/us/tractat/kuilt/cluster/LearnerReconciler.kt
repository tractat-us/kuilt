package us.tractat.kuilt.cluster

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.LeadershipLostException
import us.tractat.kuilt.raft.MembershipChangeInProgressException
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.NotLeaderException
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
 * `node.membership.value` read immediately before the call, and is level-triggered: a refused
 * attempt (`MembershipChangeInProgressException`, `NotLeaderException`, `LeadershipLostException`)
 * is followed by a fresh read, never a resend. Any other failure escapes and fails the owning scope.
 * Admissions that arrive while a change is in flight are batched into the next one, so an admission
 * storm costs a few config entries rather than one each.
 *
 * Additive only: an id leaving [desired] is never removed here. The [LearnerGrace] overload removes.
 *
 * The generalisation of [launchCoreLearnerAdmission], whose desired set is a seam's roster and which
 * admits one id per change. Correct only while this loop is the sole membership writer for [node];
 * a caller changing membership directly can still race it, though the next iteration re-adds any
 * desired learner such a change drops.
 */
internal fun CoroutineScope.launchLearnerReconciler(
    node: RaftNode,
    desired: StateFlow<Set<NodeId>>,
): Job = reconcileLearners(node, desired, retain = {}) { current, want ->
    current.learners + (want - current.voters)
}

/**
 * Keep [node]'s learner set **equal** to [grace]'s retained set for as long as [node] leads: add every
 * learner that connects, and remove one only once it has been gone for the whole grace.
 *
 * Every attempt first hands the membership's learners to [LearnerGrace.retain], so a learner this
 * server has not seen connected (admitted before a restart, or by another writer) gets a full grace
 * rather than being removed by the first change built after it was read.
 *
 * Removing a learner is a learner-set-only `Simple` entry: the voter set and its quorum are
 * untouched. Everything else is as the additive overload above describes.
 */
internal fun CoroutineScope.launchLearnerReconciler(
    node: RaftNode,
    grace: LearnerGrace,
): Job = reconcileLearners(node, grace.retained, retain = grace::retain) { current, want ->
    want - current.voters
}

/**
 * The loop both overloads share. [target] maps the current membership and the wanted set to the
 * learner set to commit; [retain] sees the membership's learners before each [target] is built.
 */
private fun CoroutineScope.reconcileLearners(
    node: RaftNode,
    wanted: StateFlow<Set<NodeId>>,
    retain: (Set<NodeId>) -> Unit,
    target: (current: ClusterConfig, want: Set<NodeId>) -> Set<NodeId>,
): Job = launch {
    while (true) {
        // Suspend until this node leads and its learner set differs from the target.
        combine(wanted, node.role, node.membership) { want, role, membership ->
            role is RaftRole.Leader && target(membership, want) != membership.learners
        }.first { it }

        val current = node.membership.value
        retain(current.learners)
        val learners = target(current, wanted.value)
        // Retaining can close the gap by itself (a learner nobody had seen now has its grace), and
        // the gate re-evaluates against the updated wanted set.
        if (learners == current.learners) continue
        val refusal = try {
            node.changeMembership(ClusterConfig(voters = current.voters, learners = learners))
            null
        } catch (refused: MembershipChangeInProgressException) {
            refused
        } catch (refused: NotLeaderException) {
            refused
        } catch (refused: LeadershipLostException) {
            refused
        }
        // The three refusals all mean "read again, later". Anything else escapes and fails the owning
        // scope: a reconciler retrying an unexpected failure forever would leave learners that never
        // receive, and no line at the default log level saying why.
        if (refusal != null) {
            log.debug { "learner-reconciler: learners ${current.learners} -> $learners refused, re-reading: $refusal" }
            delay(LEARNER_RECONCILE_RETRY_BACKOFF)
        }
    }
}
