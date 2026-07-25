package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.ReplicaId

/**
 * The **log-known roster** — which replicas have enrolled as data-plane writers, derived as a
 * deterministic function of the committed control-log prefix
 * (`docs/heddle-ledger-relocation-design.md` §6.2 prerequisite; #1665 slice 2).
 *
 * ## Why this exists
 *
 * A barrier over "every peer that could author a slot on edge `s` has promised never to author
 * another" quantifies over a **set**, and the data-plane roster is *open*: it is derived from the
 * seam ([HeddleNode]'s `roster()` — peers currently visible, plus self, plus flagged-unreachable),
 * so it changes without any log record and two peers can legitimately disagree about it at the same
 * instant. Quantifying a fence over an open set makes the fence undefined. This type makes the
 * quantifier a **log-order fact**.
 *
 * ## The property: membership is a fold of the log prefix
 *
 * The roster is nothing but the ordered list of [Transition]s that committed enroll/depart acts
 * appended, each stamped with the log index it applied at. [enrolledAt] replays that list up to a
 * given index. Two peers that have applied the same prefix therefore hold **equal** rosters — not
 * merely convergent ones — and answer [enrolledAt] identically for every index in that prefix
 * (Raft §5.4.3 State Machine Safety). Nothing here reads gossip-merged state, a clock, or a seam;
 * this is the same log-purity discipline the entitlement projection follows (see
 * [HeddleControlPlane]) and the reason H5's BLOCKER-1 shape cannot recur here.
 *
 * Mutation is only ever through [advancedTo] (as the apply loop reaches an index) followed by
 * [enroll]/[depart], so a transition's index is, by construction, the index of the entry that
 * caused it — the structure cannot record a transition at an index it did not apply.
 *
 * ## Growth
 *
 * [transitions] grows with the number of *committed roster changes* — never with data-plane traffic,
 * and never on an idempotent act (those append nothing). For a low-frequency control plane that is
 * bounded in practice, the same framing as [HeddleControlPlane]'s dedup table; windowed pruning
 * below a fence watermark is a future refinement.
 *
 * @property appliedIndex the highest log index folded into this roster. [enrolledAt] refuses to
 *   answer beyond it: a peer must not pretend to know a prefix it has not applied.
 */
internal class EnrolledRoster private constructor(
    val appliedIndex: Long,
    private val transitions: List<Transition>,
) {
    /** One log-order roster change: at [index], [replica] became enrolled ([enrolled]) or departed. */
    data class Transition(val index: Long, val replica: ReplicaId, val enrolled: Boolean)

    /**
     * This roster advanced to [index] with no membership change — what the apply loop does for
     * *every* committed entry, so [appliedIndex] tracks the prefix actually applied.
     *
     * Requires [index] to advance **strictly**: `RaftNode.committedFrom` delivers "every subsequent
     * instruction exactly once, in index order", so a repeat or a regression is a broken contract
     * that would silently corrupt the fence's quantifier. Gaps are legal — the §5.4.2 election
     * no-op is withheld from the stream.
     */
    fun advancedTo(index: Long): EnrolledRoster {
        require(index > appliedIndex) {
            "roster index must advance strictly: got $index at appliedIndex $appliedIndex"
        }
        return EnrolledRoster(index, transitions)
    }

    /**
     * Enroll [replica] at [appliedIndex], or `null` if it is already enrolled (an idempotent act —
     * the house `Patch?` idiom: `null` means the caller's state is unchanged, not that it failed).
     */
    fun enroll(replica: ReplicaId): EnrolledRoster? = transition(replica, enrolled = true)

    /**
     * Depart [replica] at [appliedIndex], or `null` if it is not currently enrolled (idempotent).
     *
     * Departing removes the replica from the ack quantifier of every *later* barrier and **touches
     * no entitlement**: whatever the replica holds stays exactly where it is, stranded on the same
     * terms as a crashed peer's holdings (`heddle-design.md` §8.1 — v1 reclaims nothing). Who is
     * permitted to propose this is the caller's gate, not this type's; see [HeddleControlPlane].
     */
    fun depart(replica: ReplicaId): EnrolledRoster? = transition(replica, enrolled = false)

    private fun transition(replica: ReplicaId, enrolled: Boolean): EnrolledRoster? {
        if ((replica in this.enrolled) == enrolled) return null
        return EnrolledRoster(appliedIndex, transitions + Transition(appliedIndex, replica, enrolled))
    }

    /**
     * The set enrolled as of log [index] — **inclusive** of a transition committed at [index]
     * itself. This is the §6.2 quantifier: the ack set for a barrier committed at index `i` is
     * `enrolledAt(i)`, so a replica enrolling at any later index is excluded from that fence and a
     * replica that departed earlier is not waited on.
     *
     * @throws IllegalArgumentException if [index] is beyond [appliedIndex]. Answering from a short
     *   fold would silently hand back a roster from a prefix the caller did not ask about.
     */
    fun enrolledAt(index: Long): Set<ReplicaId> {
        require(index <= appliedIndex) {
            "enrolledAt($index) is beyond the applied prefix (appliedIndex=$appliedIndex)"
        }
        val members = LinkedHashSet<ReplicaId>()
        for (transition in transitions) {
            if (transition.index > index) break
            if (transition.enrolled) members += transition.replica else members -= transition.replica
        }
        return members
    }

    /** The set enrolled at [appliedIndex] — the whole applied prefix. */
    val enrolled: Set<ReplicaId> get() = enrolledAt(appliedIndex)

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is EnrolledRoster && appliedIndex == other.appliedIndex && transitions == other.transitions)

    override fun hashCode(): Int = 31 * appliedIndex.hashCode() + transitions.hashCode()

    override fun toString(): String = "EnrolledRoster(appliedIndex=$appliedIndex, enrolled=$enrolled)"

    companion object {
        /** The roster of a peer that has applied nothing — Raft log indices are 1-based. */
        val EMPTY: EnrolledRoster = EnrolledRoster(0L, emptyList())

        /**
         * An empty roster positioned just below [nextIndex], for a control plane replaying from
         * [nextIndex]. Note that a [nextIndex] above `1` means roster transitions below it were
         * never folded — the same caveat the projection's `initial` carries.
         */
        fun before(nextIndex: Long): EnrolledRoster = EnrolledRoster(maxOf(0L, nextIndex - 1L), emptyList())
    }
}
