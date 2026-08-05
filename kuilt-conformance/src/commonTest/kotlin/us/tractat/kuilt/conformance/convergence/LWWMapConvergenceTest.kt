package us.tractat.kuilt.conformance.convergence

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.LWWMap
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

/** The key the retire-and-re-assert shape is built on; also one of the three the roaming ops draw. */
private const val FOCUS_KEY = "k-0"

/**
 * Prefix of the reserved cells — one per replica — that the generator writes on every step and
 * **never removes**.
 *
 * They exist because [LWWMap] hides the one number this generator needs. Every mutator here is a
 * *join*, so a write takes effect only when its `(timestamp, replica)` tag beats the key's current
 * one — and after a [LWWMap.remove] that tag lives in a tombstone cell which `get` and `entries`
 * both report as absent. A generator that reads its next timestamp off the values it can see is
 * therefore blind exactly where the critical shape needs it: in `set · remove · set` the re-assert
 * would compute a timestamp at or below the tombstone's, the join would drop it, and the harness
 * would fail the shape as decoration. Ascending fixed bands are not a way out — they make the shape
 * land, but the same ops then no-op on every later draw, which is worse than the 24.2% this binding
 * started at.
 *
 * So each op stamps its timestamp into its own replica's cell and takes the next one from the
 * highest cell it can see. A cell's value **is** its timestamp, so the tag fixes the content there
 * as it does everywhere else; and because every write in a step shares that step's timestamp, no
 * key's tag can exceed the visible maximum, so `max + 1` outranks the whole map. Every op is
 * effective, on every seed, including after a removal.
 *
 * **One cell per replica rather than one shared cell, and the difference is measurable.** A single
 * shared clock is enough to keep every op effective, and it was what this generator did first — but
 * a cell every replica writes is a cell on which any two states are *comparable*, and that dragged
 * the pool's concurrent-pair rate from 25.9% down to **14.1%**, under the 15% floor Task 5 asserts.
 * Per-replica cells restore it: a state carrying `@clock-R0` and one carrying `@clock-R1` are
 * incomparable on those cells alone, which is the honest shape — two peers that have not heard from
 * each other.
 */
private const val CLOCK_PREFIX = "@clock-"

/**
 * The value a write carries, derived from `(replica, timestamp, key)` — [LWWMap.set]'s documented
 * tag-uniqueness precondition, honoured the way `LWWMapLawsPropertyTest` honours it.
 *
 * `set` requires `(replica, timestamp)` to identify a write on a key uniquely, because
 * [us.tractat.kuilt.crdt.LWWRegister.piece]'s `else -> this` reads equal tags as equal values: two
 * states sharing a tag and disagreeing on the value converge to whichever operand is on the left.
 * The monotone clock above already makes a duplicate tag unreachable — a replica's timestamps
 * strictly ascend along its own history, so a `set` and a `remove` cannot collide either — and this
 * derivation is the second, independent guard: if a future generator drew timestamps again, two
 * writes sharing a tag would still agree.
 *
 * **The measured violation count here was 0 in 12,950 pairs over seeds `0..63`, and not because the
 * precondition was honoured.** It was not — the shipped `set` drew its value at random. The pool
 * could not *reach* the violation: these mutators join, so a second write at an already-used tag is
 * dropped rather than kept and the losing value never enters a pool state. The single-cell sibling
 * [LWWRegisterConvergenceTest] assigns instead of joining, reaches the same defect, and measured
 * **226**. Fixing it here is preventive — and worth doing precisely because the green was a
 * property of the pool builder rather than of the binding.
 */
private fun mapValue(replica: ReplicaId, timestamp: Long, key: String): String =
    "v-${replica.value}-$timestamp-$key"

/** The highest timestamp any visible replica has stamped — never below any key's tag. */
private fun clockOf(state: LWWMap<String, String>): Long =
    state.entries.filterKeys { it.startsWith(CLOCK_PREFIX) }.values.maxOfOrNull(String::toLong) ?: -1L

/** Advance this replica's clock, then either write [key] or tombstone it, both at the new tag. */
private fun write(
    state: LWWMap<String, String>,
    replicaIndex: Int,
    key: String,
    retire: Boolean,
): LWWMap<String, String> {
    val replica = ReplicaId("R$replicaIndex")
    val timestamp = clockOf(state) + 1L
    val ticked = state.piece { it.set(replica, timestamp, "$CLOCK_PREFIX$replicaIndex", timestamp.toString()) }
    return if (retire) {
        ticked.piece { it.remove(replica, timestamp, key) }
    } else {
        ticked.piece { it.set(replica, timestamp, key, mapValue(replica, timestamp, key)) }
    }
}

internal class LWWMapConvergenceTest : CrdtConvergenceSuite<LWWMap<String, String>>() {
    override fun newHarness(): CrdtConvergenceHarness<LWWMap<String, String>> = CrdtConvergenceHarness(
        initial = LWWMap.empty(),
        // `LWWMap.remove` has always existed and this generator had never called it: 0.0% retiring
        // steps, the #2100 vacuity shape on a live binding. It is a last-writer-wins tombstone that
        // competes under `piece` exactly like a set, so it retires in the sense OpKind names — the
        // key stops reading back — while the encoded state only grows.
        //
        // Replicas still collide on a timestamp often: all three clocks start at 0, and a gossip
        // absorb re-aligns them to the max, so concurrent writes to one key at one timestamp are
        // common and `piece`'s replicaId tie-break decides them. The tie stays observable, because
        // `mapValue` differs by replica at the same timestamp.
        alphabet = listOf(
            LatticeOp("set-focus", OpKind.ASSERT) { state, replicaIndex, _ ->
                write(state, replicaIndex, FOCUS_KEY, retire = false)
            },
            LatticeOp("remove-focus", OpKind.RETIRE) { state, replicaIndex, _ ->
                write(state, replicaIndex, FOCUS_KEY, retire = true)
            },
            LatticeOp("set-roam", OpKind.ASSERT) { state, replicaIndex, random ->
                write(state, replicaIndex, "k-${random.nextInt(0, 3)}", retire = false)
            },
            LatticeOp("remove-roam", OpKind.RETIRE) { state, replicaIndex, random ->
                write(state, replicaIndex, "k-${random.nextInt(0, 3)}", retire = true)
            },
        ),
        // Named rather than defaulted: `defaultCriticalShapes` takes the first two ASSERT ops in
        // declaration order, which would make `set-roam` the re-assert — and a re-assert that
        // wanders onto another key says nothing about the key that was retired.
        //
        // One op serves both ends of the word and the shape still discriminates, because the clock
        // has moved between them: `set-focus` at timestamp 0, `remove-focus` at 1, `set-focus` at
        // 2. The re-assert outranks the tombstone and carries a value the first assert did not, so
        // a join that kept the retirement reads back null and one that dropped the re-assert reads
        // back the first value. Neither is the right answer, so neither can hide.
        criticalShapes = listOf(listOf("set-focus", "remove-focus", "set-focus")),
        serializer = LWWMap.serializer(String.serializer(), String.serializer()),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
