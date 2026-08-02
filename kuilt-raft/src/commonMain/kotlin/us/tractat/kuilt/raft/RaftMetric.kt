package us.tractat.kuilt.raft

import kotlin.time.Duration

/**
 * A structured metric event emitted by a [RaftNode] at key state-machine transitions.
 *
 * Delivered via the `onMetric` callback on [CoroutineScope.raftNode]. The hook is invoked
 * on the engine's coroutine — the consumer **must not block** inside the callback.
 *
 * Use-cases:
 * - Route to a metrics back-end (Prometheus, StatsD, OpenTelemetry) without parsing logs.
 * - Assert sequencing in tests (e.g. verify `Accepted → Committed → Applied` for a propose).
 * - Surface where wall-time went in a slow propose: election vs replication vs commit-apply.
 *
 * **Threading contract.** The callback is always invoked on the Raft engine's internal
 * coroutine. It must return promptly — blocking inside the hook will stall the engine
 * and delay replication for the entire cluster.
 *
 * @see CoroutineScope.raftNode
 */
public sealed interface RaftMetric {

    // ── Propose lifecycle ──────────────────────────────────────────────────────

    /**
     * A [RaftNode.propose] call was accepted by the leader and appended to its log at [logIndex].
     *
     * Emitted immediately after the entry is appended. This is the reference marker; subsequent
     * propose events carry elapsed time from this baseline.
     */
    public data class ProposeAccepted(val logIndex: Long, val term: Long) : RaftMetric

    /**
     * The proposed entry at [logIndex] has been replicated to a quorum and committed.
     *
     * [elapsed] is the wall-time from [ProposeAccepted] to this event.
     */
    public data class ProposeCommitted(val logIndex: Long, val elapsed: Duration) : RaftMetric

    /**
     * The committed entry at [logIndex] was emitted to [RaftNode.committed] (applied).
     *
     * [elapsed] is the wall-time from [ProposeAccepted] to this event. No-op entries
     * (§5.4.2 leadership barriers) are never emitted here.
     */
    public data class ProposeApplied(val logIndex: Long, val elapsed: Duration) : RaftMetric

    // ── Election lifecycle ─────────────────────────────────────────────────────

    /**
     * This node started an election for [term] (election timeout fired).
     */
    public data class ElectionStarted(val term: Long) : RaftMetric

    /**
     * This node won the election and became leader for [term].
     *
     * [elapsed] is the wall-time from [ElectionStarted] for the same [term].
     */
    public data class ElectionWon(val term: Long, val elapsed: Duration) : RaftMetric

    /**
     * An election timed out without this node winning leadership for [term].
     *
     * Emitted when a new election starts (i.e. a new [ElectionStarted] fires for `term + 1`),
     * indicating the prior term's election failed.
     */
    public data class ElectionTimedOut(val term: Long) : RaftMetric

    /**
     * No election was started, because this node's [term] has reached the engine's term
     * plausibility [ceiling] and the `term + 1` an election must propose would sit above it (#1886).
     *
     * **What "above the ceiling" costs, after #1897.** Term adoption is bounded by the *jump* now
     * (`RaftConfig.maxTermJump`), not by this constant, so whether a peer would refuse the `term + 1`
     * this node wanted to propose depends on where that peer sits: a peer at the same term sees a step
     * of one and admits it; a peer far below sees a leap of nearly `2^60` and drops it. The ceiling's
     * remaining jobs are local to this node: `currentTerm + 1` must stay clear of `Long` overflow, and
     * the node must not *write* a durable term its own restore guard will refuse on the next start —
     * the write itself is unbounded, so suppressing the election is what prevents it.
     *
     * So the suppression is stricter than the cluster requires **only when this node's peers are at the
     * ceiling too** — only then would they admit the `term + 1` it wanted to propose. Note that neither
     * origin below puts them there: both describe how *this* node reached the ceiling, and say nothing
     * about where anyone else sits. Whenever the peers are at an ordinary term they refuse this node's
     * frames on their own jump bound and it could not have won anyway, so the suppression costs no
     * liveness at all and buys the bootability outright. See `RaftEngine.termPinnedAtCeiling`.
     *
     * **This node cannot become leader again, and this metric does not mean it will recover.** Honest
     * terms advance once per election and stay some 18 orders of magnitude below the ceiling, and terms
     * never decrease, so the condition is permanent for the lifetime of this node's durable state.
     *
     * **Emitted on every subsequent election timeout** — it is a level to sample, not an edge to count.
     * (The engine's matching `warn` log is deliberately latched to fire *once*; a permanent condition
     * re-logged a few times a second would bury every other diagnostic. Read this metric, not the log,
     * to tell whether the node is still pinned.)
     *
     * Two origins are possible, and the cheaper one to check is the likelier:
     * 1. **This node's own durable storage** — a `RaftStorage` adapter that returned a corrupt term (a
     *    truncated column, a sign-extended `Int`, a torn read). No attacker and no malformed frame
     *    required; kuilt ships no durable `RaftStorage`, so this surface is always consumer code.
     * 2. **A malformed or hostile frame** from a peer, carrying a term at the ceiling — but since #1897
     *    only if this node was within `RaftConfig.maxTermJump` of the ceiling *already*. Such a frame
     *    aimed at a node at an ordinary term is a jump of nearly `2^60`, and the same bound refuses it.
     *    So this route can no longer carry a node here from an ordinary term, which leaves origin 1
     *    effectively the only one that can.
     *
     * What this buys is a *name* for a failure that would otherwise stay invisible until a restart. The
     * durable half is unconditional and is the point: absent the suppression, `term + 1` is written to
     * durable storage — nothing bounds that write — and this node is permanently unbootable the moment
     * it next restarts. What varies is how visible that is beforehand, and it turns on where the *peers*
     * sit rather than on how this node got here. Peers at the ceiling too see a jump of one, admit it,
     * and this node campaigns and **wins normally** — nothing looks wrong at all until the restart.
     * Peers at an ordinary term refuse its frames on their own jump bound, so it simply never wins.
     * Treat one of these as an operational incident — inspect this node's persisted term first, then
     * the peer that last raised it, then re-provision from empty state.
     *
     * @see ElectionStarted for the ordinary path this replaces when the ceiling is reached.
     */
    public data class ElectionSuppressedTermCeiling(val term: Long, val ceiling: Long) : RaftMetric

    // ── Snapshot install ───────────────────────────────────────────────────────

    /**
     * An inbound snapshot chunk would have pushed this follower's reassembly to [attemptedTotal]
     * bytes, above the configured [RaftConfig.snapshotTotalCeiling] of [ceiling], so the whole
     * in-flight reassembly was discarded and offset `0` re-advertised to the sender (#1881, #1926).
     *
     * **Emitted on every rejection** — it is a level to sample, not an edge to count, because the two
     * things it can mean are told apart by whether it *keeps* firing:
     *
     * 1. **A misconfiguration**, if it repeats. An honest leader whose snapshot is genuinely larger
     *    than this follower's ceiling restarts from `0`, refills to the ceiling, is discarded again,
     *    and repeats — the follower never catches up. Nothing recovers on its own. The fix is to raise
     *    [RaftConfig.snapshotTotalCeiling] above the leader's snapshot size; the ceiling is read once
     *    when the node is built, so the new value takes effect on restart.
     * 2. **A defeated resource attack**, if it does not. The sender chooses `done`, so a peer can hold
     *    a follower in reassembly forever — every chunk well-formed, every offset advancing,
     *    `done = false` each time — and grow the buffer until the process dies. The ceiling is what
     *    stops that, and a one-off rejection is the guard working rather than a fault. The §5.2
     *    leader-authority gate means such a sender is a current voter (or this node has not yet
     *    learned its voter set), so this is the Byzantine-voter model, not an open door for a stranger.
     *
     * (The engine's matching `warn` log fires **once per node**, and only on a *repeat* for the same
     * peer and snapshot position — the signature of case 1. Read this metric, not the log, to tell
     * whether the follower is still stuck.)
     *
     * [attemptedTotal] is a `Long` because it is the sum of two attacker-influenced `Int`s: an `Int`
     * sum can overflow negative and sail under the very ceiling this reports.
     */
    public data class SnapshotRejectedSizeCeiling(val attemptedTotal: Long, val ceiling: Int) : RaftMetric

    // ── Wedge detection ────────────────────────────────────────────────────────

    /**
     * This node has been refusing leader→peer frames for a sustained run while its own commit index
     * stood still — the local signature of a node that has argued itself out of the conversation
     * (#1898, and the honest-jump case of #1897).
     *
     * Design: [`docs/raft-wedge-diagnosis-and-recovery.md`](https://github.com/tractat-us/kuilt/blob/main/docs/raft-wedge-diagnosis-and-recovery.md).
     *
     * **This changes no decision.** Both gates that can feed it are unchanged and still drop the
     * frame; the emission is the only thing added. That is deliberate and is the reason detection is
     * safe: an honest long absence and a hostile peer produce the *same* local symptom, so a gate
     * relaxed on this predicate would relax for both. Read this as "I am jammed", never as "the
     * sender is hostile" — the cause is **not** locally determinable.
     *
     * ### The predicate
     *
     * A run of [Gate]-dropped frames of a leader→peer type (`AppendEntries` / `InstallSnapshot` /
     * `TimeoutNow`) from a sender claiming a term **at least as high as ours**, during which
     * [RaftNode.commitIndex] does not advance. Neither half alone is interesting: dropping frames is
     * normal, and a stalled commit index is normal. The run is reset by our own commit index moving,
     * and by any leader→peer frame that *passes* both gates — a node being fed by a leader it accepts
     * is not jammed, whatever else is being refused alongside.
     *
     * ### Latched once per node, per voter-set epoch
     *
     * Unlike [SnapshotRejectedSizeCeiling], this is an **edge, not a level**: it is emitted once for
     * as long as [ourVoters] stays the same, and re-arms when this node's voter set changes. Two
     * reasons, and the second is the load-bearing one:
     *
     * 1. Everything it reports is a function of the voter set, so a second emission under the same
     *    one carries no new information.
     * 2. A finer latch — per sender, say — is a log-amplification lever. A peer alternating between
     *    two identities would mint a fresh emission per alternation, handing unbounded volume to the
     *    exact sender the §5.2 gate exists to contain. It cannot change our voter set, so it cannot
     *    re-arm this.
     *
     * A consumer that wants a *level* has one already: this node's [RaftNode.commitIndex] is not
     * advancing, which is what "still jammed" means and is observable without any help from here.
     *
     * ### What to do about one
     *
     * Bring the node back as a **genuinely new member — a fresh [NodeId] and empty storage — admitted
     * by an ordinary single-server membership change.** Nothing in the library self-heals; this is an
     * operator (or supervisor) action, and the existing membership machinery does the rest.
     *
     * ⚠ **Never wipe storage under the same [NodeId].** It looks like the same fix and it is the
     * opposite of it: the node returns with no memory of a term it already voted in and votes again,
     * which is a §5.2 Election Safety violation — two leaders in one term. The identity change is not
     * cosmetic; it is the entire reason the recovery is safe.
     *
     * The residual, stated plainly: admitting a new member needs a quorum of the existing ones, so
     * this recovers nodes one at a time against a cluster that still works. A *majority* jammed at
     * once has no route back but re-bootstrapping the cluster.
     *
     * @property sender the peer whose frame was dropped — the true origin, already unwrapped from any
     *   relay envelope. Under [Gate.LeaderAuthority] on an honest wedge this is the **current
     *   leader**, which is the single most useful identity in the report: it is a node [ourVoters]
     *   does not contain and therefore names the rotation this node slept through.
     * @property senderTerm the term that frame claimed.
     * @property ourTerm this node's own term when the run was reported.
     * @property ourVoters the voter set this node is holding — the possibly-stale state that, under
     *   [Gate.LeaderAuthority], is doing the refusing. Also the latch key.
     * @property gate which gate dropped the frame.
     */
    public data class WedgeSuspected(
        val sender: NodeId,
        val senderTerm: Long,
        val ourTerm: Long,
        val ourVoters: Set<NodeId>,
        val gate: Gate,
    ) : RaftMetric {

        /** Which of `RaftEngine.onMessage`'s two dispatch-boundary gates dropped the frame. */
        public enum class Gate {
            /**
             * The §5.2/§8 leader-authority gate (#1383, #1889): the sender is not in [ourVoters], and
             * only a voter can be leader, so a leader→peer RPC from it is treated as a forgery.
             *
             * On an honest wedge (#1898) [ourVoters] is simply **stale** — this node was absent across
             * a voter-set rotation, and the frames that would teach it the new set are precisely the
             * ones it refuses, because they come from senders the old set does not contain. The stale
             * set is the thing preventing its own repair, and it is persisted, so restarting does not
             * clear it.
             */
            LeaderAuthority,

            /**
             * The relative term-jump bound (#1897): the frame's term is more than
             * [RaftConfig.maxTermJump] above ours.
             *
             * On an honest wedge this is a node that was away for more than that many elections, so
             * the cluster's term legitimately ran beyond what a jump bound will admit in one step.
             * (The bound's other job — refusing a fabricated term near [Long.MAX_VALUE] — produces
             * this same gate, which is exactly why the cause is not locally determinable.)
             */
            TermJump,
        }
    }
}
