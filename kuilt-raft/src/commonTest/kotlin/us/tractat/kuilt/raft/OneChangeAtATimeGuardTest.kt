@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * §4.1 one-change-at-a-time, against the **real** `RaftEngine.onChangeMembership` — the two guards
 * that refuse a membership change while another is still converging:
 *
 * - **`pendingConfigChange != null`** — an in-memory flag set by a *local* `changeMembership` caller
 *   and cleared when the resulting `Simple` entry commits.
 * - **`lastConfigIndex > currentCommitIndex`** — log-grounded, and the only thing covering the
 *   *inherited* paths where `pendingConfigChange` is null because no local caller exists: the
 *   `Simple(C_new)` that `finalizeInheritedCommittedJoint` appends on election, and the one
 *   `onConfigCommitted` appends when a leader inherits an in-flight Joint.
 *
 * ## Why the whole module stayed green without either of them
 *
 * #1980's discrimination audit deleted the log-grounded guard (verdict **C19**) and then deleted
 * **both** (verdict **P4**): `:kuilt-raft` was green each time. Not a disjunction — with both gone
 * the region had *no* coverage to redistribute.
 *
 * The reason it read as covered is a shape worth naming: a **stubbed-out guard**.
 * [MembershipTest.changeMembershipWithRetry_retriesWhileInProgressThenSucceeds] is named for this
 * refusal and asserts on [MembershipChangeInProgressException] — but a `ScriptedMembershipNode`
 * *double* throws it, so `onChangeMembership` is never entered. The test name is right, the exception
 * type is real, the assertion is real, and the production guard does not run. Grepping the exception
 * name finds a passing test and the guard reads as covered. (That test is legitimate for what it does
 * test — the `changeMembershipWithRetry` control flow — so it stays; what was missing is a sibling
 * that drives the engine.)
 *
 * ## Holding the pre-commit window open
 *
 * Both guards only bite between a config entry being *appended* and that entry *committing*. With
 * zero-latency links the whole append → replicate → ack → commit cascade runs at a single virtual
 * instant, so a test coroutine can never observe the window. Both tests therefore give every directed
 * link a one-way [LINK_LATENCY], which makes the window exactly one round trip of virtual time wide —
 * and no wall-clock ceiling is load-bearing, since the width is measured in virtual time.
 *
 * The election timings are stretched to match (heartbeat and election timeout both well above the
 * round trip) so the latency buys a window without provoking election thrash.
 *
 * ## Cross-certification (#2001 / #2004), and its honest limit
 *
 * [inheritedUncommittedConfigEntry_refusesTheNextChange] runs where `pendingConfigChange` is
 * **structurally null** — the trailing config entry was minted by the engine's own election-time
 * completion path and the refused call is the test's *first* `changeMembership` on the cluster, and
 * `pendingConfigChange` is only ever assigned inside `onChangeMembership`. Asserted as a premise via
 * the entry's index. So that test pins the log-grounded guard **alone**.
 *
 * [inFlightLocalChange_refusesASecondChange] pins the in-memory guard, and could not until #2032. On
 * the leader, `pendingConfigChange != null` *implies* the last config entry is uncommitted, so no
 * trajectory distinguishes the two guards **by their state effects**:
 *
 *  - it is set at `onChangeMembership`'s tail, immediately before `appendConfigEntry` puts a config
 *    entry at `lastLogIndex + 1`, which is above `currentCommitIndex`;
 *  - the only way that stops holding is `currentCommitIndex` reaching the entry, which happens inside
 *    `advanceCommit` — which then runs `onConfigCommitted` on that same entry before returning, and
 *    that either clears `pendingConfigChange` (Simple) or appends the trailing `Simple(C_new)` above
 *    the new commit index (Joint), restoring the relation. The Joint branch's skip arm needs a *later*
 *    config entry, which cannot exist while this pending change owns the last one;
 *  - the other two `currentCommitIndex` writes are the restore path (engine `init`) and
 *    `onInstallSnapshot`'s finalize, and the latter runs `demoteToFollowerOnLeaderContact` →
 *    `relinquishToFollower` → `failPendingConfigChange` **before** it touches the commit index.
 *
 * So the in-memory guard is subsumed by the log-grounded one, and deleting it alone is
 * behaviour-preserving in every sense a state-effect assertion can reach: the call is still refused,
 * with the same exception type, at the same instant. Until #2032 that made it **unmeasurable by
 * construction** — this file's left column was all-green, and that was the subsumption argument's
 * receipt rather than a gap.
 *
 * What closed it is attribution, not a new trajectory. [MembershipChangeInProgressException.reason]
 * names the guard that refused as a typed [MembershipRefusal], so the two guards — which refuse the
 * *same* call at the *same* instant and are therefore separable only by what they say — now say
 * different things, and the deletion slides the refusal from [MembershipRefusal.PendingLocalChange]
 * down to [MembershipRefusal.UncommittedConfigEntry] where an assertion can see it. The subsumption
 * argument above is unchanged; what changed is that breaking it is now visible.
 *
 * Mutation-verified on this branch, `:kuilt-raft:jvmTest` in full, `:kuilt-raft:compileKotlinJvm`
 * EXECUTED (never `FROM-CACHE`) and the gradle exit code checked before reading any results XML; each
 * deletion confirmed absent from the source before the run. Every cell is `tests=544 skipped=0`:
 *
 * | | drop `pendingConfigChange` guard | drop uncommitted-config guard (C19) | drop **both** (P4) |
 * |---|---|---|---|
 * | [inheritedUncommittedConfigEntry_refusesTheNextChange] | GREEN | **RED** | **RED** |
 * | [inFlightLocalChange_refusesASecondChange] | **RED** | GREEN | **RED** |
 *
 * Read as failure *sets*, not counts: `failures=1`, `failures=1`, `failures=2`, and in each column the
 * failing tests are exactly the ones marked — no collateral anywhere, and in particular no
 * `LeadershipLostException` fallout. The left column's red is
 * `expected:<PendingLocalChange> but was:<UncommittedConfigEntry>`, which is the deletion described
 * precisely. The same left-column mutation applied to `origin/main` is `failures=0` over the identical
 * 544 tests — that pair is the whole receipt for #2032.
 */
class OneChangeAtATimeGuardTest {

    private val v1 = NodeId("v1")
    private val v2 = NodeId("v2")
    private val v3 = NodeId("v3")

    /** Added by the inherited Joint; never instantiated — irrelevant to every quorum used here. */
    private val v4 = NodeId("v4")

    private val l1 = NodeId("L1")
    private val l2 = NodeId("L2")

    private val presentVoters = listOf(v1, v2, v3)
    private val oldConfig = ClusterConfig(voters = presentVoters.toSet())
    private val newConfig = ClusterConfig(voters = presentVoters.toSet() + v4)
    private val joint = ConfigPayload(old = oldConfig, new = newConfig)

    /** The snapshot is cut exactly at the Joint entry's index, carrying the Joint config, no Simple. */
    private val jointIndex = 10L
    private val jointTerm = 1L

    /**
     * The **inherited** lane, where `pendingConfigChange` is null: a leader elected holding an
     * already-committed Joint appends `Simple(C_new)` itself (§4.1 orphaned-Joint completion, #1247).
     * Until that entry commits the cluster is mid-transition — and adopt-on-append has *already*
     * flipped `membershipState` to `Simple(C_new)`, so the settled-Simple guard below it is inert and
     * a change arriving now passes every other check. The log-grounded guard is the only thing
     * refusing it; without it the change appends a Joint above an uncommitted Simple and the caller is
     * handed a config that was never the committed one.
     *
     * Premises asserted before the probe, each making one sibling guard inert:
     *  - the trailing config entry sits at `jointIndex + 2` — the no-op is at `+1` and the engine put
     *    the `Simple(C_new)` above it, so it was minted by `finalizeInheritedCommittedJoint`, not by a
     *    caller. Combined with this being the test's first `changeMembership` — and
     *    `pendingConfigChange` being assigned nowhere but `onChangeMembership` — the in-memory guard
     *    cannot fire;
     *  - that entry's payload has `old == null`, i.e. it is a `Simple`, so `membershipState` is
     *    `Simple` and the settled-Simple guard cannot fire;
     *  - it is above the commit index, i.e. the log-grounded guard's own predicate holds.
     *
     * The tail asserts the guard only ever *withholds*: once the inherited entry commits, the very
     * same change is accepted and commits.
     */
    @Test
    fun inheritedUncommittedConfigEntry_refusesTheNextChange() = raftRunTest {
        val cfg = slowLinkRaftConfig()
        val sim = RaftSimulation(
            nodeIds = presentVoters,
            scope = this,
            nodeScope = backgroundScope,
            nodeFactory = { _, transport, storage, nodeScope ->
                nodeScope.raftNode(oldConfig, transport, storage, cfg)
            },
        )
        sim.delayEveryLink()
        // Model each present voter recovering from a durable snapshot cut at the Joint index — the
        // engine `init` restore path, which seeds commitIndex from the snapshot, so the Joint is
        // committed with no commit window ever containing it.
        presentVoters.forEach { sim.crash(it) }
        presentVoters.forEach { id ->
            val storage = sim.storages.getValue(id)
            storage.saveTermAndVotedFor(jointTerm, null)
            storage.saveSnapshot(
                SnapshotMeta(lastIncludedIndex = jointIndex, lastIncludedTerm = jointTerm, config = joint),
                byteArrayOf(1),
            )
        }
        presentVoters.forEach { sim.restart(it) }

        val leader = sim.awaitLeader()
        val leaderId = presentVoters.first { sim.nodes[it] === leader }
        val trailingConfig = sim.storages.getValue(leaderId).entries(0L).last { it.config != null }

        assertAll(
            {
                assertEquals(
                    jointIndex + 2L,
                    trailingConfig.index,
                    "premise: the trailing config entry was minted by the engine above its own no-op " +
                        "(finalizeInheritedCommittedJoint), not by a caller — no changeMembership has run, " +
                        "so pendingConfigChange is null and its guard is inert",
                )
            },
            {
                assertNull(
                    trailingConfig.config?.old,
                    "premise: the trailing config entry is a Simple, so membershipState is Simple and the " +
                        "settled-Simple guard is inert",
                )
            },
            {
                assertTrue(
                    trailingConfig.index > leader.commitIndex.value,
                    "premise: the trailing config entry is uncommitted (commit=${leader.commitIndex.value}) — " +
                        "the log-grounded guard's own predicate",
                )
            },
        )

        val furtherTarget = ClusterConfig(voters = newConfig.voters, learners = setOf(l1))
        val refusal = assertFailsWith<MembershipChangeInProgressException>(
            "a change arriving above an uncommitted inherited config entry must be refused",
        ) { leader.changeMembership(furtherTarget) }
        assertEquals(
            MembershipRefusal.UncommittedConfigEntry,
            refusal.reason,
            "the log-grounded guard is the only one whose predicate holds here — the refusal must say so",
        )

        // The guard withholds; it does not wedge. Once the inherited entry commits, the same change lands.
        val committed = sim.changeMembershipOnLeader(furtherTarget)
        assertAll(
            { assertEquals(newConfig.voters, committed.voters, "settled onto C_new before the change committed") },
            { assertEquals(setOf(l1), committed.learners, "the refused change succeeds once the window closes") },
        )
    }

    /**
     * The **mainline** lane: a local `changeMembership` is still in flight when a second one arrives.
     * This is the trajectory the `ScriptedMembershipNode` test stands in for, driven against the real
     * engine.
     *
     * It pins the **in-memory guard alone**, and does so only because the refusal is attributed. Both
     * guards' predicates hold at the probing instant — the premises below assert exactly that, since
     * `pendingConfigChange != null` implies an uncommitted trailing config entry on the leader (class
     * KDoc) — so every *state effect* of this refusal is identical either way, and the assertion that
     * discriminates them is the [MembershipChangeInProgressException.reason] one after the probe.
     *
     * **That single `assertEquals` is the whole pin.** Delete the line and this test silently reverts
     * to pinning the pair — the suite stays green, `assertFailsWith` still passes, and nothing marks
     * the loss. This paragraph is the only mitigation, and prose is not a mechanism.
     *
     * The premises are therefore stated as what they are: the in-memory guard's predicate holds (the
     * first call has not returned), the log-grounded guard's predicate *also* holds, and the
     * settled-Simple guard is inert.
     *
     * The in-flight change is **learner-only** on purpose. A voter-set change appends a `Joint`, which
     * adopt-on-append makes `membershipState`, and the settled-Simple guard would then refuse the second
     * change no matter what these two do — the test would stay green under the pair deletion and pin
     * nothing.
     */
    @Test
    fun inFlightLocalChange_refusesASecondChange() = raftRunTest {
        val cfg = slowLinkRaftConfig()
        val sim = RaftSimulation(
            nodeIds = presentVoters,
            scope = this,
            nodeScope = backgroundScope,
            nodeFactory = { _, transport, storage, nodeScope ->
                nodeScope.raftNode(oldConfig, transport, storage, cfg)
            },
        )
        sim.delayEveryLink()

        val leader = sim.awaitLeader()
        val leaderId = presentVoters.first { sim.nodes[it] === leader }

        // First change: learner-only, so the entry it appends is a Simple and membershipState stays Simple.
        val inFlight = backgroundScope.async {
            leader.changeMembership(ClusterConfig(voters = oldConfig.voters, learners = setOf(l1)))
        }
        delay(1) // the engine appends the entry at this instant; its acks are a round trip away

        val trailingConfig = sim.storages.getValue(leaderId).entries(0L).last { it.config != null }
        assertAll(
            {
                assertFalse(
                    inFlight.isCompleted,
                    "premise: the first change has not returned, so pendingConfigChange is non-null",
                )
            },
            {
                assertNull(
                    trailingConfig.config?.old,
                    "premise: the in-flight entry is a Simple, so membershipState is Simple and the " +
                        "settled-Simple guard is inert",
                )
            },
            {
                assertTrue(
                    trailingConfig.index > leader.commitIndex.value,
                    "premise: the in-flight entry is uncommitted (commit=${leader.commitIndex.value}) — the " +
                        "log-grounded guard's predicate holds here too, which is exactly why this test pins " +
                        "the pair rather than either conjunct",
                )
            },
        )

        val refusal = assertFailsWith<MembershipChangeInProgressException>(
            "a second membership change while one is still converging must be refused",
        ) { leader.changeMembership(ClusterConfig(voters = oldConfig.voters, learners = setOf(l2))) }
        // The discriminator, and the only thing that separates the two guards here (#2032). Both
        // predicates hold at this instant — the premises above assert exactly that — so a refusal that
        // says only "in progress" is compatible with either guard having fired, and deleting the
        // in-memory one is invisible. Naming the reason makes the deletion observable: with guard 3
        // gone the same call slides down to guard 4 and this assertion reads UncommittedConfigEntry.
        assertEquals(
            MembershipRefusal.PendingLocalChange,
            refusal.reason,
            "the in-memory guard is evaluated first and must be the one that refused",
        )

        // The first change still commits — the refusal cost the cluster nothing.
        assertEquals(setOf(l1), inFlight.await().learners, "the in-flight change committed unharmed")
    }

    /** Give every directed link the same one-way latency, so append → ack spans virtual time. */
    private fun RaftSimulation.delayEveryLink() {
        presentVoters.forEach { from ->
            presentVoters.forEach { to ->
                if (from != to) network.setLinkLatency(from, to, LINK_LATENCY)
            }
        }
    }

    /**
     * Timings stretched around [LINK_LATENCY]: a vote or replication round trip is `2 * LINK_LATENCY`,
     * comfortably inside `electionTimeoutMin`, and heartbeats still arrive several times per election
     * window, so the latency opens the pre-commit window without provoking election thrash.
     *
     * A function, not a `val`, and seeded — see [fastRaftConfig] / [RAFT_TEST_SEED]. Called once per
     * simulation and shared across its nodes, so the nodes break timeout symmetry on successive draws.
     */
    private fun slowLinkRaftConfig(): RaftConfig = RaftConfig(
        electionTimeoutMin = 100.milliseconds,
        electionTimeoutMax = 200.milliseconds,
        heartbeatInterval = 30.milliseconds,
        expectVirtualTime = true,
        random = Random(RAFT_TEST_SEED),
    )

    private companion object {
        val LINK_LATENCY = 20.milliseconds
    }
}
