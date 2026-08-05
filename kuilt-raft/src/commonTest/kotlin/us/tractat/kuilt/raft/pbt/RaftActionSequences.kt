package us.tractat.kuilt.raft.pbt

import kotlin.random.Random

// ---------------------------------------------------------------------------
// Seeded generation + shrinking for the pure Raft model (issue #2107).
//
// Replaces the jqwik `@Provide`/`Arbitrary`/`@ForAll` layer that used to drive
// [PureRaftModelTest]. jqwik is JVM-only, which pinned this — the repo's most
// spec-critical model check — to a single target. Everything here is plain
// Kotlin, so the properties now run on every target the module builds.
//
// Two things the replacement has to carry that a naive "loop N times with a
// Random" does not:
//
//   1. **Reproducibility.** Every trajectory is derived from an explicit seed
//      ([SEED] + try index). A failure prints the seed it came from, so it
//      replays exactly. Nothing here ever touches an unseeded `Random`.
//   2. **Shrinking.** jqwik reduces a failing input to a minimal counterexample;
//      without that you get "try 4,812 failed" plus a 60-element action list.
//      [shrink] reproduces it by repeatedly deleting actions and simplifying
//      their indices, keeping any smaller sequence that still fails. The model
//      is pure and synchronous — a whole trajectory costs microseconds — so the
//      naive greedy search is both cheap and obviously correct.
// ---------------------------------------------------------------------------

/** Actions that can be applied to a [Cluster] snapshot. */
public sealed interface RaftAction {
    /** Drive node[nodeIdx % clusterSize] to start an election. */
    public data class Timeout(val nodeIdx: Int) : RaftAction

    /** Deliver inFlight[msgIdx % queue.size] (or no-op if queue is empty). */
    public data class Deliver(val msgIdx: Int) : RaftAction

    /** Propose next command byte to the current leader (no-op if no leader). */
    public data object Propose : RaftAction

    /** Crash node[nodeIdx % clusterSize]. */
    public data class Crash(val nodeIdx: Int) : RaftAction

    /** Restart (dead) node[nodeIdx % clusterSize]. */
    public data class Restart(val nodeIdx: Int) : RaftAction

    /** Partition node[aIdx] from node[bIdx]. */
    public data class Partition(val aIdx: Int, val bIdx: Int) : RaftAction

    /** Heal all partitions. */
    public data object Heal : RaftAction

    /** Compact node[nodeIdx % clusterSize] through the cluster-wide replicated floor. */
    public data class Compact(val nodeIdx: Int) : RaftAction
}

/**
 * Root seed for every property in this package. Fixed, so CI explores the same
 * trajectories on every run and on every target — a property surface that draws
 * fresh randomness each run trades a real defect signal for a flake generator.
 * Change it deliberately (and re-run the mutation table in the PR that does).
 *
 * One mutation in that table is **structurally unkillable here, and no seed or budget will change
 * that**: deleting the `entry.term == r.term` conjunct at the commit-advance site (Raft §5.4.2,
 * Figure 8). [Cluster.becomeLeader] appends a current-term no-op as the leader's last entry and
 * `appendEntriesMsgs` ships the *entire* unbounded suffix, so any follower that accepts replies
 * with a `matchIndex` at or above that no-op — leaving the quorum-th `matchIndex` either 0 (no
 * commit) or in the all-current-term region. A prior-term majority commit is therefore
 * unreachable. Killing it needs bounded AppendEntries batches, so a follower can acknowledge a
 * prefix stopping short of the no-op; that is tracked in #2114. Don't spend a budget increase on
 * it — 450,000 trajectories over 100 seeds produced zero prior-term majority-commit states.
 */
internal const val SEED: Long = 0x5AFE_7A17L

/** Exclusive upper bound on a generated node index, mirroring the jqwik `between(0, 10)`. */
private const val NODE_INDEX_BOUND = 11

/** Exclusive upper bound on a generated message index, mirroring the jqwik `between(0, 100)`. */
private const val MESSAGE_INDEX_BOUND = 101

/**
 * Relative weights over the eight action constructors, preserving the original
 * arbitrary's shape: `Deliver` is weighted 3× because messages have to be
 * processed for the cluster to make any progress at all.
 */
private const val DELIVER_WEIGHT = 3
private const val ACTION_WEIGHT_TOTAL = 7 + DELIVER_WEIGHT

/** Generates one action. Index bounds match the jqwik arbitraries this replaced. */
private fun randomAction(random: Random): RaftAction {
    val node = { random.nextInt(NODE_INDEX_BOUND) }
    return when (random.nextInt(ACTION_WEIGHT_TOTAL)) {
        0 -> RaftAction.Timeout(node())
        1, 2, 3 -> RaftAction.Deliver(random.nextInt(MESSAGE_INDEX_BOUND))
        4 -> RaftAction.Propose
        5 -> RaftAction.Crash(node())
        6 -> RaftAction.Restart(node())
        7 -> RaftAction.Partition(node(), node())
        8 -> RaftAction.Heal
        else -> RaftAction.Compact(node())
    }
}

/**
 * Generates one trajectory of 1..[maxActions] actions.
 *
 * The length is drawn uniformly. That is a deliberate departure from jqwik, whose
 * default list-size distribution is biased hard toward short lists — and short
 * trajectories cannot reach a safety violation, which takes an election, a
 * replication round and a second election before anything can go wrong.
 */
internal fun generateActions(random: Random, maxActions: Int): List<RaftAction> =
    List(1 + random.nextInt(maxActions)) { randomAction(random) }

// ── Shrinking ───────────────────────────────────────────────────────────────

/** Candidate simplifications of a single action, ordered simplest-first. */
private fun simplifications(action: RaftAction): List<RaftAction> = when (action) {
    is RaftAction.Timeout -> shrinkInts(action.nodeIdx).map { RaftAction.Timeout(it) }
    is RaftAction.Deliver -> shrinkInts(action.msgIdx).map { RaftAction.Deliver(it) }
    is RaftAction.Crash -> shrinkInts(action.nodeIdx).map { RaftAction.Crash(it) }
    is RaftAction.Restart -> shrinkInts(action.nodeIdx).map { RaftAction.Restart(it) }
    is RaftAction.Compact -> shrinkInts(action.nodeIdx).map { RaftAction.Compact(it) }
    is RaftAction.Partition ->
        shrinkInts(action.aIdx).map { RaftAction.Partition(it, action.bIdx) } +
            shrinkInts(action.bIdx).map { RaftAction.Partition(action.aIdx, it) }
    RaftAction.Propose, RaftAction.Heal -> emptyList()
}

/** Smaller values to try for an integer payload: 0 first, then a halving step. */
private fun shrinkInts(value: Int): List<Int> = when {
    value <= 0 -> emptyList()
    value == 1 -> listOf(0)
    else -> listOf(0, value / 2, value - 1)
}

/** Total candidate replays [shrink] may spend. Each is microseconds; this bounds a pathological search. */
private const val SHRINK_BUDGET = 20_000

/**
 * Reduces [initial] to a locally minimal sequence that still satisfies [fails].
 *
 * Two alternating passes run to a fixpoint: delete a contiguous run of actions
 * (halving the run length, ddmin-style), then simplify each surviving action's
 * integer payload toward zero. Every candidate is re-checked, so the returned
 * sequence is guaranteed to still reproduce the failure.
 */
@Suppress("NestedBlockDepth")
internal fun shrink(initial: List<RaftAction>, fails: (List<RaftAction>) -> Boolean): ShrinkResult {
    var best = initial
    var spent = 0
    var progress = true
    while (progress && spent < SHRINK_BUDGET) {
        progress = false
        var run = maxOf(1, best.size / 2)
        while (run >= 1 && spent < SHRINK_BUDGET) {
            var at = 0
            while (at < best.size && spent < SHRINK_BUDGET) {
                val candidate = best.take(at) + best.drop(at + run)
                if (candidate.isEmpty()) {
                    at += run
                    continue
                }
                spent++
                if (fails(candidate)) {
                    best = candidate
                    progress = true
                } else {
                    at += run
                }
            }
            run /= 2
        }
        for (index in best.indices) {
            for (simpler in simplifications(best[index])) {
                if (spent >= SHRINK_BUDGET) break
                spent++
                val candidate = best.toMutableList().also { it[index] = simpler }
                if (fails(candidate)) {
                    best = candidate
                    progress = true
                    break
                }
            }
        }
    }
    return ShrinkResult(actions = best, replays = spent)
}

/** The outcome of [shrink]: the minimal failing sequence and how many replays it cost. */
internal data class ShrinkResult(val actions: List<RaftAction>, val replays: Int)

// ── Property runner ─────────────────────────────────────────────────────────

/**
 * Replays [actions] through [body], returning whatever it threw, or null if it passed.
 *
 * Catches [Throwable], not only [AssertionError]. A model edit that breaks an internal premise
 * throws something else entirely — `NoSuchElementException` from the conflict-term lookup in
 * `onAppendEntries` is the live example — and letting that escape [forAllActionSequences] would
 * lose the seed, the try index and the shrinking, i.e. exactly the "try 4,812 failed, here is a
 * 60-action mystery" report this file exists to prevent. jqwik shrank on any `Throwable` too, so
 * narrowing to `AssertionError` would have been a diagnostics regression against what it replaced.
 *
 * Nothing is swallowed: the caller either rethrows it inside a reported [AssertionError] or is
 * replaying a pure, non-suspending function while shrinking.
 */
@Suppress("TooGenericExceptionCaught") // deliberate: any throw is a counterexample worth reporting
private fun outcomeOf(actions: List<RaftAction>, body: (List<RaftAction>) -> Unit): Throwable? =
    try {
        body(actions)
        null
    } catch (failure: Throwable) {
        failure
    }

/** Renders a replayed failure, naming the type when it is not a plain assertion. */
private fun describe(failure: Throwable?): String = when (failure) {
    null -> "<not reproduced — the shrunk sequence passed>"
    is AssertionError -> failure.message ?: failure.toString()
    else -> "${failure::class.simpleName}: ${failure.message ?: failure.toString()}"
}

/**
 * Runs [body] over [tries] seeded trajectories of up to [maxActions] actions.
 *
 * On the first failing trajectory, shrinks it to a minimal counterexample and
 * throws an [AssertionError] carrying the seed, the try index, the shrunk
 * sequence and the failure it reproduces — everything needed to replay it by
 * hand, and nothing that requires re-running the search.
 */
internal fun forAllActionSequences(
    property: String,
    tries: Int,
    maxActions: Int,
    seed: Long = SEED,
    body: (List<RaftAction>) -> Unit,
) {
    for (tryIndex in 0 until tries) {
        val trySeed = seed + tryIndex
        val actions = generateActions(Random(trySeed), maxActions)
        val failure = outcomeOf(actions, body) ?: continue
        // Shrink toward the SAME kind of failure. A predicate that accepted any throwable would let
        // the search minimise a safety violation into an unrelated model crash and report that one.
        val stillFails = { candidate: List<RaftAction> ->
            outcomeOf(candidate, body)?.let { it::class == failure::class } == true
        }
        val shrunk = shrink(actions, stillFails)
        val reproduced = outcomeOf(shrunk.actions, body)
        throw AssertionError(
            buildString {
                appendLine("Property \"$property\" FAILED.")
                appendLine("  seed=$seed  try=$tryIndex/$tries  trySeed=$trySeed")
                appendLine("  counterexample: ${actions.size} actions, shrunk to ${shrunk.actions.size}")
                appendLine("  (${shrunk.replays} shrink replays)")
                appendLine("  minimal counterexample:")
                shrunk.actions.forEachIndexed { i, action -> appendLine("    [$i] $action") }
                appendLine("  reproduces: ${describe(reproduced)}")
            },
        )
    }
}
