@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression for #2676: the restore must bound a durable [ConfigPayload] too, not just the
 * `lastIncludedIndex`/`lastIncludedTerm` of the snapshot it rides on.
 *
 * #1887 bounded every *numeric* field the init-restore reads. `config` was left adopted verbatim from
 * both places it can arrive — `SnapshotMeta.config` (into [RaftState.snapshotConfig]) and a restored
 * [LogEntry]'s own `config` — and `recomputeMembership` then adopts whichever of the two wins its
 * resolution order. A payload naming **no voters** on a currently-active side therefore boots the node
 * with an empty voter set, which is the state #2663 showed disarms `onMessage`'s §5.2 leader-authority
 * gate (it is conditioned on `membershipState.voters.isNotEmpty()`, a carve-out for the pre-bootstrap
 * learner seed). The node then accepts any peer's `AppendEntries` — including a peer in no
 * configuration anywhere — which truncates its committed log and installs the sender as leader.
 *
 * ## Reachability
 *
 * #2663's wire fix closes the two routes a live **peer** has. None of the three that remain is a peer:
 * a snapshot written by a pre-fix binary or an older build (`saveSnapshot` runs on the receive path, so
 * a poisoned payload reached disk before that fix existed), a torn or buggy [RaftStorage] —
 * `RaftStorageConformanceSuite` constrains none of these fields, and kuilt's own
 * [DurableStoreRaftStorage] validates none of them either, so a damaged medium reaches the engine
 * through it exactly as through a consumer's adapter — and the consumer's own `bootstrapConfig`, which
 * is out of scope here and stays under #2676.
 *
 * ## Two dispositions, because the two sources are not the same kind of thing
 *
 * - **`SnapshotMeta.config` — drop it and fall back.** The snapshot's config is *local metadata this
 *   engine alone owns*, and `recomputeMembership` already falls through to `bootstrapConfig` when a
 *   snapshot carries none — the documented meaning of `SnapshotMeta.config == null` ("the covered
 *   prefix carried no config change"). So nulling a payload no honest producer can emit lands the node
 *   in a state the engine reaches routinely, rather than one invented for the occasion. Refusing to
 *   start instead would strand every node whose snapshot predates the wire fix on data that was
 *   legitimate when written, with no in-place remedy.
 * - **A restored [LogEntry]'s `config` — refuse to start**, like the four numeric bounds beside it in
 *   [checkedRestoredEntries]. A log entry is *replicated content*, not local metadata: this node can
 *   become leader and serve that entry onward, so repairing the field in memory would make it publish a
 *   different config at a committed `(index, term)` than the rest of the cluster holds. Log Matching is
 *   keyed on `(index, term)` and would not notice. The stranding cost is also much lower — #2663
 *   measured the `AppendEntries` lane as self-healing, so a poisoned *entry* surviving to disk is far
 *   narrower a window than a poisoned snapshot, which has no fallback at all.
 *
 * ## Test discipline
 *
 * The snapshot arms are a **discriminator**, not a state read: the identical forged frame, from the
 * identical non-voter, is injected in every arm, and only the durable config differs. A bare assertion
 * on `membership.voters` would be much weaker — it says what the node believes, not whether the gate
 * that belief controls is actually armed. Each snapshot arm additionally asserts its own precondition
 * (`commitIndex >= poisonedIndex`), so an arm cannot pass because the snapshot was never restored at
 * all.
 */
internal class RestoredConfigValidationTest {

    /**
     * Comfortably past any log a test writes, and well inside `MAX_PLAUSIBLE_INDEX` (`1 shl 60`) so
     * [checkedRestoredSnapshotMeta]'s index bound is not what fires. High on purpose: it is the
     * position at which the bootstrap fallback is least obviously safe, since every honest entry is
     * below the baseline and the log-based supersession that heals the `AppendEntries` lane cannot fire.
     */
    private val poisonedIndex = 1L shl 40

    private val restoredTerm = 5L
    private val attacker = NodeId("attacker-not-a-voter")
    private val v3 = NodeId("v3")

    private val noVoters = ClusterConfig(voters = emptySet(), learners = emptySet())
    private val realVoters = ClusterConfig(voters = setOf(NodeId("v1"), NodeId("v2")))

    /**
     * Poison `v3`'s durable snapshot with [config], restart it, and report what the restored node
     * believes and whether its §5.2 gate is armed.
     *
     * "Armed" is measured, not inferred: a forged `AppendEntries` from a peer that is in no
     * configuration anywhere is injected at a higher term, and the witness is the node's own
     * **persisted** term — `persistTermAndVote` is storage-first, so a term that moved is a frame the
     * gate let through.
     */
    private suspend fun RaftSimulation.restoreWith(config: ConfigPayload?): RestoredNode {
        awaitLeader()
        val storage = storages.getValue(v3)
        crash(v3)
        storage.saveSnapshot(SnapshotMeta(poisonedIndex, 1L, config), byteArrayOf(1))
        val termBefore = storage.term()
        restart(v3)
        settle()
        val node = nodes.getValue(v3)
        val restoredMembership = node.membership.value
        val restoredCommitIndex = node.commitIndex.value
        deliverAppendEntries(to = v3, from = attacker, term = termBefore + 5L)
        settle()
        settle()
        return RestoredNode(
            membership = restoredMembership,
            commitIndex = restoredCommitIndex,
            gateArmed = storage.term() == termBefore,
            leader = node.leader.value,
        )
    }

    private data class RestoredNode(
        val membership: ClusterConfig,
        val commitIndex: Long,
        val gateArmed: Boolean,
        val leader: NodeId?,
    )

    // ── SnapshotMeta.config: drop and fall back ───────────────────────────────

    @Test
    fun poisonedSnapshotConfig_isDroppedAndTheNodeFallsBackToBootstrap() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val bootstrap = ClusterConfig(voters = setOf(NodeId("v1"), NodeId("v2"), v3))

        val restored = sim.restoreWith(ConfigPayload(old = null, new = noVoters))

        assertAll(
            {
                assertTrue(
                    restored.commitIndex >= poisonedIndex,
                    "precondition: the poisoned snapshot must actually have been restored — " +
                        "commitIndex=${restored.commitIndex}, expected >= $poisonedIndex",
                )
            },
            {
                assertEquals(
                    bootstrap, restored.membership,
                    "a snapshot config naming no voters must be dropped, leaving recomputeMembership " +
                        "to fall back to bootstrapConfig",
                )
            },
            {
                assertTrue(
                    restored.gateArmed,
                    "the §5.2 leader-authority gate must be armed after the restore — a forged " +
                        "AppendEntries from $attacker moved this node's persisted term",
                )
            },
            { assertNotEquals(attacker, restored.leader, "a non-voter must not become this node's leader") },
        )
    }

    /**
     * The `old` half. `MembershipState.Joint.voters` is the **union**, so an empty `old` leaves §5.2
     * armed and takes out quorum instead — commit and election need independent majorities of each
     * side, and a majority of the empty set is unreachable. Bounded for that reason rather than for the
     * gate's, which is why this arm asserts on the adopted membership and not on `gateArmed`.
     */
    @Test
    fun jointSnapshotConfigWithAnEmptyOldSide_isDropped() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val bootstrap = ClusterConfig(voters = setOf(NodeId("v1"), NodeId("v2"), v3))

        val restored = sim.restoreWith(ConfigPayload(old = noVoters, new = realVoters))

        assertAll(
            { assertTrue(restored.commitIndex >= poisonedIndex, "precondition: snapshot must have been restored") },
            {
                assertEquals(
                    bootstrap, restored.membership,
                    "a joint payload whose `old` side names no voters must be dropped too — the joint " +
                        "quorum takes independent majorities of each side",
                )
            },
        )
    }

    /**
     * The over-rejection guard, and the main risk this change carries: a node holding a snapshot whose
     * config is **legitimate** — the ordinary case for any cluster that ever changed membership, and
     * every such snapshot written before the wire fix landed — must still boot and must still adopt it.
     * Losing this would be the failure `MembershipTest.installSnapshot_adoptsConfigCompactedAwayFromTheLog`
     * exists to prevent: silently discarding a committed membership change on restart.
     */
    @Test
    fun legitimateSnapshotConfig_isStillAdoptedOnRestore() = raftRunTest {
        val sim = raftSim(this, backgroundScope)

        val restored = sim.restoreWith(ConfigPayload(old = null, new = realVoters))

        assertAll(
            { assertTrue(restored.commitIndex >= poisonedIndex, "precondition: snapshot must have been restored") },
            {
                assertEquals(
                    realVoters, restored.membership,
                    "a snapshot carrying a real config must still be adopted verbatim — this bound may " +
                        "only drop a payload no honest producer can emit",
                )
            },
            { assertTrue(restored.gateArmed, "and the gate that config arms must be armed") },
        )
    }

    /**
     * A snapshot carrying **no** config at all is the state the poisoned arm is repaired *into*, so it
     * is worth pinning that the two are the same: both fall back to `bootstrapConfig` with the gate
     * armed. This is what makes "drop it" a repair into an already-reachable state rather than an
     * invented one.
     */
    @Test
    fun snapshotWithNoConfig_alreadyFallsBackToBootstrapWithTheGateArmed() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val bootstrap = ClusterConfig(voters = setOf(NodeId("v1"), NodeId("v2"), v3))

        val restored = sim.restoreWith(config = null)

        assertAll(
            { assertTrue(restored.commitIndex >= poisonedIndex, "precondition: snapshot must have been restored") },
            { assertEquals(bootstrap, restored.membership) },
            { assertTrue(restored.gateArmed) },
        )
    }

    // ── A restored LogEntry's config: refuse to start ─────────────────────────

    @Test
    fun poisonedRestoredLogEntryConfig_refusesToStart() = raftRunTest {
        val storage = InMemoryRaftStorage()
        storage.saveTermAndVotedFor(restoredTerm, null)
        storage.appendEntries(
            listOf(
                LogEntry(index = 1L, term = restoredTerm, command = byteArrayOf(1)),
                LogEntry(
                    index = 2L,
                    term = restoredTerm,
                    command = byteArrayOf(),
                    config = ConfigPayload(old = null, new = noVoters),
                ),
            ),
        )

        val failure = awaitRestoreFailure(storage)

        assertAll(
            { assertTrue(failure is CorruptDurableStateException, "expected CorruptDurableStateException, got: $failure") },
            {
                assertTrue(
                    failure?.message.orEmpty().contains("no voters"),
                    "the diagnostic must name the degenerate voter set: ${failure?.message}",
                )
            },
            {
                assertTrue(
                    failure?.message.orEmpty().contains("index=2"),
                    "the diagnostic must name the offending entry: ${failure?.message}",
                )
            },
        )
    }

    /** The `old` half, on the entry lane. */
    @Test
    fun jointRestoredLogEntryConfigWithAnEmptyOldSide_refusesToStart() = raftRunTest {
        val storage = InMemoryRaftStorage()
        storage.saveTermAndVotedFor(restoredTerm, null)
        storage.appendEntries(
            listOf(
                LogEntry(
                    index = 1L,
                    term = restoredTerm,
                    command = byteArrayOf(),
                    config = ConfigPayload(old = noVoters, new = realVoters),
                ),
            ),
        )

        val failure = awaitRestoreFailure(storage)

        assertTrue(failure is CorruptDurableStateException, "expected CorruptDurableStateException, got: $failure")
    }

    /** The over-rejection guard for the entry lane: a real config entry must still restore. */
    @Test
    fun legitimateRestoredLogEntryConfig_startsNormally() = raftRunTest {
        val storage = InMemoryRaftStorage()
        storage.saveTermAndVotedFor(restoredTerm, null)
        storage.appendEntries(
            listOf(
                LogEntry(
                    index = 1L,
                    term = restoredTerm,
                    command = byteArrayOf(),
                    config = ConfigPayload(old = null, new = realVoters),
                ),
            ),
        )

        val failure = awaitRestoreFailure(storage)

        assertNull(failure, "a restored config entry naming real voters must restore without complaint")
    }
}
