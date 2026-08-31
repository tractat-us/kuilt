package us.tractat.kuilt.conformance.lattice

import kotlin.math.round
import kotlin.random.Random
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.Quilted
import us.tractat.kuilt.crdt.VersionVector

/** Strategy for generating an operation against a model state, producing the next state. */
public fun interface OperationGenerator<S> {
    /** Pick and apply an op against [state] using [random] for choices. Returns the new state (post-mutation). */
    public fun applyRandomOp(state: S, replicaIndex: Int, random: Random): S
}

/**
 * What one binding's generator actually searched, measured by
 * [LatticeLawHarness.measureVacuity] and checked against [VacuityFloors].
 *
 * The counts are carried alongside the rates on purpose. A rate on its own is unreadable when the
 * denominator is small — "0.0% retiring" over 6 steps and over 600 are very different claims — and
 * the raw counts are what makes a drift diagnosable rather than merely visible.
 *
 * @param pairs ordered pairs of distinct pool positions, summed over the seeds measured.
 * @param strictAncestorPairs pairs `(a, b)` with `a` strictly below `b`. See [VacuityFloors] — only
 *   one direction of a comparable pair counts, so this tops out at half of [pairs].
 * @param concurrentPairs pairs where neither state is below the other. Both directions count.
 * @param steps ops applied by the pool builder. Absorbing a peer's state is not one.
 * @param effectiveRetireSteps steps whose op is [OpKind.RETIRE] **and** which changed the state.
 * @param noOpSteps steps of any kind that left the state unchanged.
 * @param floors the bounds these were measured against, so a printed report is self-contained.
 */
public class VacuityReport(
    public val pairs: Long,
    public val strictAncestorPairs: Long,
    public val concurrentPairs: Long,
    public val steps: Int,
    public val effectiveRetireSteps: Int,
    public val noOpSteps: Int,
    public val floors: VacuityFloors,
) {
    /** Fraction of [pairs] that are strict-ancestor pairs. A total order reads `0.5`. */
    public val strictAncestorRate: Double get() = ratio(strictAncestorPairs, pairs)

    /** Fraction of [pairs] that are concurrent. */
    public val concurrentRate: Double get() = ratio(concurrentPairs, pairs)

    /** Fraction of [steps] that retired something *and* changed the state. */
    public val effectiveRetireRate: Double get() = ratio(effectiveRetireSteps.toLong(), steps.toLong())

    /** Fraction of [steps] that changed nothing. */
    public val noOpRate: Double get() = ratio(noOpSteps.toLong(), steps.toLong())

    override fun toString(): String =
        "  strict-ancestor pairs  ${percent(strictAncestorRate)}  ($strictAncestorPairs / $pairs)" +
            "  floor ≥ ${percent(floors.strictAncestorPairs)}\n" +
            "  concurrent pairs       ${percent(concurrentRate)}  ($concurrentPairs / $pairs)" +
            "  floor ≥ ${percent(floors.concurrentPairs)}${if (floors.totalOrder) " — WAIVED, totalOrder" else ""}\n" +
            "  effective RETIRE steps ${percent(effectiveRetireRate)}  ($effectiveRetireSteps / $steps)" +
            "  floor ≥ ${percent(floors.effectiveRetireSteps)}\n" +
            "  no-op steps            ${percent(noOpRate)}  ($noOpSteps / $steps)" +
            "  ceiling ≤ ${percent(floors.maxNoOpSteps)}"

    private fun ratio(numerator: Long, denominator: Long): Double =
        if (denominator == 0L) 0.0 else numerator.toDouble() / denominator.toDouble()
}

/** One decimal place, without `String.format` — which `commonMain` does not have. */
internal fun percent(rate: Double): String {
    val tenths = round(rate * 1000.0).toLong()
    return "${tenths / 10}.${tenths % 10}%"
}

/**
 * What one binding's codec pass actually put through the wire, measured by
 * [LatticeLawHarness.runCodecLaws].
 *
 * The counts are the pass's own **rig receipt**. Every law the pass asserts is a statement about a
 * codec, and every one of them holds vacuously over a pool that degenerated to a single value: a
 * round-trip of `initial` against itself is green on a serializer that drops every field it has.
 * So the pass reports what it searched, and [LatticeLawHarness.runCodecLaws] refuses to pass on a
 * pool that searched nothing. The rates are absent on purpose — there is no denominator here worth
 * dividing by, and a count of one is exactly the number a reader needs to see.
 *
 * @param seeds how many pools were walked.
 * @param states pool states round-tripped, summed over [seeds].
 * @param distinctStates states no other pool state equalled, summed within each seed. Counted by
 *   `==` rather than by set membership: `hashCode` is not part of the
 *   [us.tractat.kuilt.crdt.Quilted] contract, and a type with equality and no hash would silently
 *   read every state as distinct.
 * @param distinctEncodings the same count over the encoded forms, by `contentEquals`. Below
 *   [distinctStates] the codec cannot tell two reachable states apart; above it, two equal states
 *   encode differently.
 * @param joinPairs ordered pairs of pool positions joined through the codec. Includes `i == j`,
 *   which is the idempotent case and the cheapest place a decoded operand can go wrong.
 * @param absorbingJoinPairs pairs where `a ⊔ b != a` — the join had to take something from the
 *   decoded operand. A pass whose every join already dominated its second operand never read the
 *   decoded state at all.
 */
public class CodecReport(
    public val seeds: Int,
    public val states: Long,
    public val distinctStates: Long,
    public val distinctEncodings: Long,
    public val joinPairs: Long,
    public val absorbingJoinPairs: Long,
) {
    /** Sum two per-seed reports. Associative and commutative, so a seed range folds in any order. */
    public operator fun plus(other: CodecReport): CodecReport = CodecReport(
        seeds = seeds + other.seeds,
        states = states + other.states,
        distinctStates = distinctStates + other.distinctStates,
        distinctEncodings = distinctEncodings + other.distinctEncodings,
        joinPairs = joinPairs + other.joinPairs,
        absorbingJoinPairs = absorbingJoinPairs + other.absorbingJoinPairs,
    )

    override fun toString(): String =
        "  seeds walked           $seeds\n" +
            "  states round-tripped   $states  ($distinctStates distinct, " +
            "$distinctEncodings distinct encodings)\n" +
            "  joins through the wire $joinPairs  ($absorbingJoinPairs absorbing)"

    internal companion object {
        val EMPTY = CodecReport(0, 0L, 0L, 0L, 0L, 0L)
    }
}

/**
 * Drives [replicaCount] replicas (default 3) through [opsPerReplica] random operations distributed
 * across them, then merges in every possible pairwise order and asserts all replicas converge to
 * the same value.
 *
 * Two-pass approach:
 *  1. From [initial], each replica builds its own independent history of [opsPerReplica] local ops.
 *  2. Every permutation of those replica states is folded into a fresh merge from [initial]; all
 *     must equal the canonical merge (fold in natural order). This exercises commutativity and
 *     associativity under random delivery orderings.
 *
 * Multiplatform: uses [Random] (seed constructor) for determinism — the same seed produces the
 * same outcome on JVM, wasmJs, and native.
 *
 * Every permutation is additionally asserted to encode to the *same bytes* under [serializer]
 * (#1957) — see `assertAllPermutationsConverge`.
 *
 * **Scope of the byte assertion — read before trusting a green run.** Every comparison it makes is
 * between two encodings produced in one process on one target, so it proves order-independence
 * *within* a target and nothing more. It does **not** prove two targets agree on the bytes — that
 * dimension is pinned separately, by the cross-target golden vectors in `CanonicalGoldenVectorTest`
 * (`:kuilt-crdt`'s `commonTest`).
 *
 * More sharply: on JVM and Android this assertion has **near-zero discriminating power**. The map
 * merges underneath most CRDTs return a `HashMap`, and `java.util.HashMap` iterates in bucket
 * order — largely a function of the key set and table capacity, both invariant under the fold
 * order — so a permutation almost always emits identically whether or not the type is canonical.
 * Only *largely*: `putVal` appends to the tail of a bin and iteration walks each bin head→tail, so
 * keys that collide into one bucket iterate in **insertion** order, and there the fold order does
 * show through. A few short `ReplicaId` keys in a 16-slot table collide rarely enough that this is
 * not something to count on in either direction. Kotlin/Native and Kotlin/Wasm preserve insertion
 * order throughout, which *is* the fold order, so they see the defect reliably.
 * When you bind a new CRDT to this suite, **verify on `macosArm64Test` or `wasmJsTest`** — a green
 * `jvmTest` is not evidence of canonicality. (Types whose merge yields a `LinkedHashSet`, e.g. via
 * `Set.plus`, are insertion-ordered on the JVM too and do fail there — so a JVM red is meaningful
 * even though a JVM green is not.)
 */
@OptIn(ExperimentalSerializationApi::class, ExperimentalStdlibApi::class)
public class LatticeLawHarness<S : Quilted<S>>(
    public val initial: S,
    public val alphabet: List<LatticeOp<S>>,
    public val serializer: KSerializer<S>,
    public val criticalShapes: List<List<String>> = defaultCriticalShapes(alphabet),
    public val floors: VacuityFloors = VacuityFloors.DEFAULT,
    public val replicaCount: Int = 3,
    public val opsPerReplica: Int = 8,
    public val compactor: CrdtCompactor<S>? = null,
) {
    /**
     * Bind a type that has not declared an alphabet yet.
     *
     * The generator becomes a one-op alphabet named `undeclared`, and the binding gets **no**
     * critical shapes — there are no op names for a shape to be a word over. That is the honest
     * reading, not a degradation to route around: an undeclared binding also declares no
     * [OpKind.RETIRE] op, so it reads as non-retiring to anything that measures retirement, which
     * is what an undeclared binding should read as. Prefer the primary constructor.
     */
    public constructor(
        initial: S,
        gen: OperationGenerator<S>,
        serializer: KSerializer<S>,
        replicaCount: Int = 3,
        opsPerReplica: Int = 8,
    ) : this(
        initial = initial,
        alphabet = listOf(LatticeOp(UNDECLARED_OP, OpKind.ASSERT, gen::applyRandomOp)),
        serializer = serializer,
        criticalShapes = emptyList(),
        floors = VacuityFloors.DEFAULT,
        replicaCount = replicaCount,
        opsPerReplica = opsPerReplica,
    )

    /**
     * The alphabet as a uniform random draw — the shape the pool builder consumes.
     *
     * Derived rather than stored so a binding cannot declare an alphabet the random pass then
     * ignores. A single-op alphabet draws nothing, so wrapping one generator in [LatticeOp] leaves
     * that binding's per-seed trajectory byte-for-byte what it was.
     */
    public val gen: OperationGenerator<S> = OperationGenerator { state, replicaIndex, random ->
        pick(random).apply(state, replicaIndex, random)
    }

    init {
        require(alphabet.isNotEmpty()) { "alphabet must not be empty — the pool builder has nothing to draw from" }
        val names = alphabet.map { it.name }
        require(names.distinct().size == names.size) { "alphabet op names must be unique, got $names" }
    }

    private val cbor = Cbor {}

    private fun encoded(state: S): ByteArray = cbor.encodeToByteArray(serializer, state)

    /**
     * The other half of [encoded] — and, until #2317, the half this harness never called.
     *
     * Every replica exchange the rest of this file performs hands one in-process object to another.
     * `Quilter` does not: it encodes a delta, puts the bytes on a `Seam`, and the receiver joins
     * whatever comes back out here. A serializer that is **lossy but deterministic** — drops a
     * field, collapses a dot context, omits tombstones — satisfies every other law in this file,
     * including the byte laws, because both sides of every comparison go through the same lossy
     * path. It fails only against a decode.
     */
    private fun decoded(bytes: ByteArray): S = cbor.decodeFromByteArray(serializer, bytes)

    /**
     * Run with a single [seed]; assert convergence. Returns the **pre-compaction** converged state.
     *
     * Three phases when a [compactor] is bound, and only the first when one is not.
     *
     * **Phase 0 — fold every permutation, assert equal and byte-identical.** Exactly what this
     * method has always done, preserved character for character. That is not politeness: a new gate
     * placed ahead of an older one is how an older guard's coverage silently drops to zero, and
     * phase 0's byte assertion is #1957's coverage for every non-compaction field in the zoo. The
     * phases below *add* assertions over *additional* states and never replace it.
     *
     * **Phase A — fold every permutation, then compact to stable.** The tombstone set the
     * compaction predicate walks was built by `Set.plus` in fold order; `gcIds` inherits that order
     * and the minted `Compact` op's `positions` map inherits it from `gcIds`. So one `Compact` op's
     * map order depends on the merge order, and a plain `MapSerializer` there writes two
     * fold-equal states to different bytes (#1978).
     *
     * **Phase B — compact each replica alone, then fold every permutation.** Each replica mints its
     * `Compact` from its own single-author history, in an order fixed at mint time and identical
     * under every later fold — so phase A's axis is *gone* here, and what varies instead is the
     * **merge of already-compacted states**: `compactedDots + other.compactedDots`, and the position
     * of each `Compact` op within the unioned op set. Those are the #1957 and #713 axes.
     *
     * **Neither phase is redundant, and this is measured rather than argued.** Phase A cannot reach
     * the merge-of-compacted-states path, because after it runs there is nothing left to merge;
     * phase B cannot reach the fold-dependent-tombstone-set path, because each replica compacts a
     * history only it authored. Reverting each mechanism in turn on `main`:
     *
     * | mechanism | phase A | phase B |
     * |---|---|---|
     * | `Rga`/`Fugue` `Compact.positions` map order (#1978) | **RED** | green |
     * | `MovableTree.compactedDots` set order (#1957) | green | **RED** |
     * | order *between* several `Compact` ops (#713) | green | **RED** |
     *
     * `MovableTree` is the case worth naming, because the shape recurs: its `compact` selects
     * droppable ops by filtering a `log` kept sorted by `(ts, replicaId)`, so the freshly-minted
     * `droppedDots` is *already* canonical and phase A's serializer has nothing to fix. Measured
     * over seeds `0..31`, its minted-`Compact` iteration order varies across the six folds on
     * **0** of 32 seeds — against `Rga`'s 32 and `Fugue`'s 13. **Reaching the code that writes a
     * field is not the same as reaching the disagreement**, which is why a post-merge hook alone —
     * what #2019 originally proposed — would report compaction reached on 24 of 32 seeds while
     * leaving `compactedDots` exactly as unpinned as it was.
     *
     * **Phase B's soundness rests on the replicas' histories being disjoint**, and that is
     * asserted, not assumed — see [assertReplicaHistoriesAreDisjoint]. Replica `Rᵢ`'s history holds
     * only `Rᵢ`'s ops, so no peer can hold a concurrent op referencing one of them, and `Rga`'s
     * "no surviving successor" condition (and `Fugue`'s "no surviving tree anchor") is evaluated
     * over a set nobody can add to behind the compactor's back. A future generator that gave two
     * replicas one author id would break that premise, so the harness reds instead.
     */
    public fun run(seed: Long): S {
        val random = Random(seed)
        val replicas = buildReplicas(random)
        val compactor = this.compactor
        if (compactor != null) assertReplicaHistoriesAreDisjoint(replicas, seed)
        val canonical = mergeAll(replicas)
        assertAllPermutationsConverge(replicas, canonical)
        if (compactor != null) {
            runPostMergePhase(compactor, replicas, seed)
            runPreMergePhase(compactor, replicas, seed)
            runs++
        }
        return canonical
    }

    /** Run over every seed in [seeds]; returns the converged state for each. */
    public fun runSeeds(seeds: LongRange): List<S> = seeds.map(::run)

    /**
     * Assert **both** laws that relate the two bracketings of a join, in one pass over
     * [causalPool]: for every ordered triple, `(a ⊔ b) ⊔ c` and `a ⊔ (b ⊔ c)` must be equal
     * (**associativity**), and — only once they are equal — must encode to the same bytes
     * (**canonicality**).
     *
     * **Associativity is a different property from [run], and the difference is the whole point.**
     * [run] folds a fixed set of replica states in every *order* — `((i⊔a)⊔b)⊔c` versus
     * `((i⊔b)⊔a)⊔c` — which is commutativity plus one fixed left-nested bracketing. It never forms
     * `a ⊔ (b ⊔ c)`, so a join that is order-sensitive but self-healing passes it: the states it
     * compares have all absorbed the same three operands, and a lost contribution comes back the
     * moment the missing operand is merged in again. Real deployments do not enjoy that. A peer
     * that receives `b ⊔ c` as one anti-entropy digest, and a peer that receives `b` then `c`, must
     * land on the same state — and until the next round they are what a user reads and what
     * `Quilter.stateRoot()` hashes.
     *
     * The second thing [run] cannot see is *causal relation*. Its replicas each fork from
     * `initial` under their own replica id, so no replica's context ever witnesses another's dots.
     * Bugs that need one operand to **retire a tag a second operand still carries** are structurally
     * unreachable there. [causalPool] restores that dimension by snapshotting each replica's own
     * running history, so `s`, `s.remove(k)` and `s.remove(k).put(k, v)` all sit in the pool.
     *
     * **Canonicality is checked second, and only on equal values, so the two failures never blur.**
     * An inequality is an associativity defect; differing bytes over states that already compare
     * equal is a *canonicality* defect, and the messages say so in different words on purpose. The
     * byte law matters because `Quilter`'s root-hash gate (#1955) compares digests, not values: a
     * pair of peers that agree on the state but disagree on its bytes reads as diverged and skips
     * the fast path.
     *
     * **Why one pass and not two.** The two laws were once two methods, each rebuilding the pool
     * from the same seed and each recomputing *both* bracketings for every one of ~45,797 triples;
     * only the second went on to encode. Splitting them cost a full duplicate set of joins — 18% of
     * this module's Kotlin/Native test budget — and bought nothing, because the encoding pass
     * already had to compare values to know which triples it could speak about.
     */
    public fun runAssociativeLaws(seed: Long) {
        checkAssociativeLaws(causalPool(Random(seed)).states, where = { "at seed $seed" })
    }

    /** Run [runAssociativeLaws] over every seed in [seeds]. */
    public fun runAssociativeLawsSeeds(seeds: LongRange): Unit = seeds.forEach(::runAssociativeLaws)

    /**
     * The three join laws that are **not** about bracketing — **commutativity**, **idempotence**
     * and **least-upper-bound** — over [causalPool], plus the byte law on the commutativity pair.
     *
     * These are breadth, not depth, and the honest framing is worth keeping in front of the next
     * reader. Over the causal pool of a lattice provably broken in the way #2086 was broken, the
     * four laws read `assoc = 500, comm = 0, idem = 0, lub = 0`: **associativity is the only one
     * that sees it.** Nobody should add these expecting a second detector for that class, and
     * nobody should read a green here as covering what
     * [associativeJoinLawsHoldOverLowerSeeds][LatticeLawSuite.associativeJoinLawsHoldOverLowerSeeds]
     * covers. What they do buy is the rest of the semilattice contract, on a pool that carries
     * causal ancestry, on every target — which is what the JVM-only surface they replace asserted
     * over operands drawn from *disjoint* replicas, where no operand could be another's ancestor.
     *
     * **Commutativity is asserted unconditionally, with no per-binding waiver, and that was a
     * decision.** It reads 0 everywhere today only because two generators were fixed first:
     * `LWWRegisterConvergenceTest` minted one `(replica, timestamp)` tag for two different values —
     * outside `LWWRegister.set`'s documented precondition — and the pool held **226 non-commuting
     * pairs in 12,979**. The fix belongs in the generator, because the interesting behaviour at an
     * equal tag is real and stays pinned by name in `LWWMapTest`
     * (`oneTagCarryingTwoValuesCostsCommutativityNotAssociativity`) and `MVRegisterTest`
     * (`forkingOneReplicaBreaksCommutativityButNotAssociativity`). A `tagUniqueness = false`
     * escape hatch here would be a permanent green-by-declaration and would leave that behaviour
     * asserted nowhere.
     *
     * **Least-upper-bound is the absorption pair**, `a ⊔ b ⊒ a` and `a ⊔ b ⊒ b`, checked as
     * `(a ⊔ b) ⊔ a == a ⊔ b`. That is the form the deleted jqwik surface asserted, re-homed
     * verbatim rather than strengthened, so a red here means the same thing it meant there.
     *
     * The byte law extends to the commutativity pair for the same reason it covers the bracketing
     * pair: `Quilter`'s root-hash gate compares digests, so two peers that agree on the value and
     * disagree on the bytes read as diverged. Measured free — 0 differences in 3,113–3,261
     * equal-valued pairs per binding.
     */
    public fun runOtherJoinLaws(seed: Long) {
        val pool = causalPool(Random(seed)).states
        for (a in pool) {
            val selfJoin = a.state.piece(a.state)
            check(selfJoin == a.state) { idempotenceFailure(seed, a, selfJoin) }
            for (b in pool) {
                val ab = a.state.piece(b.state)
                val ba = b.state.piece(a.state)
                check(ab == ba) { commutativityFailure(seed, a, b, ab, ba) }
                val abBytes = encoded(ab)
                val baBytes = encoded(ba)
                check(abBytes.contentEquals(baBytes)) { commutativityCanonicalityFailure(seed, a, b, abBytes, baBytes, ab) }
                check(ab.piece(a.state) == ab) { absorptionFailure(seed, "a", a, b, ab, ab.piece(a.state)) }
                check(ab.piece(b.state) == ab) { absorptionFailure(seed, "b", a, b, ab, ab.piece(b.state)) }
            }
        }
    }

    /** Run [runOtherJoinLaws] over every seed in [seeds]. */
    public fun runOtherJoinLawsSeeds(seeds: LongRange): Unit = seeds.forEach(::runOtherJoinLaws)

    /**
     * The **codec** laws: a state that has been through `encode`/`decode` is interchangeable with
     * the one that has not — over [causalPool], `O(pool²)`.
     *
     * This is the seam every other law in this file skips. Replicas here hand each other in-process
     * objects; `Quilter` encodes a delta, sends the bytes over a `Seam`, and the receiver joins what
     * it decodes. So a `Quilted` whose serializer is **lossy but deterministic** passes
     * associativity, commutativity, idempotence, least-upper-bound *and* both byte laws — every
     * comparison those make is between two encodings produced by the same lossy path, so the loss
     * cancels. On the wire the receiver holds a state missing the omitted part, and a removed
     * element resurrects on the next merge.
     *
     * **Three arms, in the order they are checked, because each one catches what the one before it
     * cannot.** They are not three spellings of one property:
     *
     * 1. **`decode(encode(s)) == s`** — the codec preserves the *value*. The strongest arm and the
     *    one that reds on ordinary field loss. It is strong enough that the two below are, on a
     *    healthy type, implied by it; they exist for the two ways a type can be unhealthy that it
     *    is structurally blind to.
     * 2. **`encode(decode(encode(s)))` is byte-identical to `encode(s)`** — the codec is *stable*.
     *    Arm 1 is blind here whenever the loss is invisible to `equals` (a field equality ignores),
     *    and so is every existing byte law, which only ever compares two *built* states. This is
     *    the arm that sees a state whose encoding depends on how the object was **constructed**
     *    rather than on what it holds — parsed versus built — which is a live hazard for any type
     *    whose merge yields an insertion-ordered collection. It matters because #1955's root-hash
     *    gate compares digests: a receiver whose decoded state hashes differently from the sender's
     *    reads as diverged and skips the fast path, while comparing perfectly equal.
     * 3. **`a ⊔ decode(encode(b))` equals `a ⊔ b`, and encodes to the same bytes** — equality is a
     *    congruence for the join *through the codec*. Arms 1 and 2 are both blind to a field that
     *    `equals` ignores and `encode` also drops, but that `piece` reads: round-trip and re-encode
     *    are then both green, and the join lands somewhere else. `LWWRegister`'s
     *    `(timestamp, replica)` tag is the shape — drop it, and every join afterwards picks a
     *    different winner while every state still compares equal to itself.
     *
     * **The counts in [CodecReport] are the rig receipt, and two of them are asserted.** All three
     * laws hold vacuously over a pool that degenerated to one value; arm 1 would stay green on a
     * serializer that encodes nothing at all. So the pass refuses a pool with fewer than two
     * distinct states, refuses a codec that emitted fewer than two distinct encodings over it, and
     * refuses an arm-3 loop in which no join ever had to absorb its decoded operand. On a healthy
     * codec every one of those is implied by arm 1 — they are there to red when the **pool**
     * degenerates, which arm 1 cannot notice.
     *
     * `O(pool²)` joins and encodes, against the associativity pass's `O(pool³)`: this costs a
     * rounding error beside it, and roughly what [runOtherJoinLaws] costs.
     */
    public fun runCodecLaws(seed: Long): CodecReport {
        val pool = causalPool(Random(seed)).states
        val encodings = pool.map { encoded(it.state) }
        val distinctStates = distinctStateCount(pool)
        val distinctEncodings = distinctEncodingCount(encodings)
        check(distinctStates >= 2) { poolTooThin(seed, pool, distinctStates) }
        check(distinctEncodings >= 2) { encodingsTooThin(seed, pool, distinctStates, distinctEncodings) }
        val decodedPool = pool.indices.map { decoded(encodings[it]) }
        for (i in pool.indices) {
            val original = pool[i]
            val roundTripped = decodedPool[i]
            check(roundTripped == original.state) { roundTripValueFailure(seed, original, roundTripped) }
            val reEncoded = encoded(roundTripped)
            check(reEncoded.contentEquals(encodings[i])) {
                roundTripByteFailure(seed, original, encodings[i], reEncoded)
            }
        }
        var absorbing = 0L
        for (i in pool.indices) {
            for (j in pool.indices) {
                val a = pool[i]
                val b = pool[j]
                val direct = a.state.piece(b.state)
                if (direct != a.state) absorbing++
                val throughWire = a.state.piece(decodedPool[j])
                check(throughWire == direct) { wireJoinValueFailure(seed, a, b, direct, throughWire) }
                val directBytes = encoded(direct)
                val wireBytes = encoded(throughWire)
                check(wireBytes.contentEquals(directBytes)) {
                    wireJoinByteFailure(seed, a, b, directBytes, wireBytes, direct)
                }
            }
        }
        val pairs = pool.size.toLong() * pool.size.toLong()
        check(absorbing > 0L) { noAbsorbingJoin(seed, pool, pairs) }
        return CodecReport(
            seeds = 1,
            states = pool.size.toLong(),
            distinctStates = distinctStates.toLong(),
            distinctEncodings = distinctEncodings.toLong(),
            joinPairs = pairs,
            absorbingJoinPairs = absorbing,
        )
    }

    /** Run [runCodecLaws] over every seed in [seeds], summing what each pool searched. */
    public fun runCodecLawsSeeds(seeds: LongRange): CodecReport =
        seeds.fold(CodecReport.EMPTY) { acc, seed -> acc + runCodecLaws(seed) }

    /**
     * Distinct pool states by `==`, in `O(pool²)` — deliberately not a `Set`.
     *
     * `hashCode` is not part of the [Quilted] contract, and a type that overrides equality without
     * it would read every state as distinct here, turning the thinness guard into decoration.
     */
    private fun distinctStateCount(pool: List<Tracked<S>>): Int {
        val seen = ArrayList<S>(pool.size)
        for (entry in pool) if (seen.none { it == entry.state }) seen += entry.state
        return seen.size
    }

    /** Distinct encodings by `contentEquals` — `ByteArray` has reference equality, so no `Set`. */
    private fun distinctEncodingCount(encodings: List<ByteArray>): Int {
        val seen = ArrayList<ByteArray>(encodings.size)
        for (bytes in encodings) if (seen.none { it.contentEquals(bytes) }) seen += bytes
        return seen.size
    }

    /**
     * Both bracketing laws over **every word of length `1..L`** the alphabet can spell, on one
     * replica — and on failure, the **shortest** word that breaks them. Returns the number of words
     * searched on a green run.
     *
     * **This is the shrinking replacement, and it is a better artefact than a shrinker's.** Words
     * are enumerated breadth-first by length, so the first one that fails is the shortest one that
     * can: every shorter word was already tried and passed. A property-based shrinker reports a
     * *locally* minimal synthetic operand list — three states it narrowed by re-running a
     * generator; this reports the globally shortest **reachable trajectory**, named in the
     * binding's own vocabulary, with the exact prefixes that produced `a`, `b` and `c`. Re-running
     * the reported word from [initial] reproduces it exactly, because each word gets a fresh
     * [Random] seeded identically, so a word — not a seed plus a trial number — is the whole repro.
     *
     * Measured against a lattice that wrongly keeps a retired contribution (the pre-#2099 `ORMap`),
     * this pass reports `[put-high, remove, put-low]` — **length 3**, the shape #2086 needs. How
     * many words it took to get there is a property of the *alphabet*, not of the search: 28 over
     * `[put-low, put-high, remove]`, 20 over `[put-high, put-low, remove]`, 47 over the five ops
     * `ORMapConvergenceTest` actually declares. **The length is the claim; the count is bookkeeping.**
     *
     * **One replica, so the pool is a chain, and that is the point.** The randomised pass explores
     * *width*: three replicas, gossip, `POOL_LIMIT` states, sixteen seeds. This explores *depth
     * exhaustively at tiny sizes*, where "exhaustively" is what buys the minimality claim. A chain
     * is not a weaker pool than a forked one for this purpose — a broken join is exactly one that
     * fails to be a join, and a chain `i ≤ s₁ ≤ … ≤ sₙ` is where that shows most legibly, because
     * a correct lattice makes every join on it trivially the later operand.
     *
     * **The bound is `EXHAUSTIVE_WORD_LENGTH`, capped by `EXHAUSTIVE_TRIPLE_BUDGET`** — read both
     * before changing either.
     */
    public fun runExhaustiveSmall(): Int {
        var searched = 0
        for (length in 1..exhaustiveWordLength) {
            forEachWordOfLength(length) { word ->
                searched++
                val at = searched
                checkAssociativeLaws(
                    pool = walk(word),
                    where = { "in the exhaustive-small search" },
                    footer = { minimalCounterexample(word, at) },
                )
            }
        }
        return searched
    }

    /**
     * Assert associativity, and then canonicality on the pairs that were equal, over every ordered
     * triple from [pool].
     *
     * [where] names the trajectory family in the failure's first line and [footer] closes it; both
     * are lambdas so a green run — every run but one — builds neither.
     */
    private fun checkAssociativeLaws(pool: List<Tracked<S>>, where: () -> String, footer: () -> String = { "" }) {
        for (a in pool) for (b in pool) for (c in pool) {
            val leftNested = a.state.piece(b.state).piece(c.state)
            val rightNested = a.state.piece(b.state.piece(c.state))
            check(leftNested == rightNested) {
                associativityFailure(where(), a, b, c, leftNested, rightNested) + footer()
            }
            val leftBytes = encoded(leftNested)
            val rightBytes = encoded(rightNested)
            check(leftBytes.contentEquals(rightBytes)) {
                canonicalityFailure(where(), a, b, c, leftBytes, rightBytes, leftNested) + footer()
            }
        }
    }

    // The encodings are printed because a CRDT's `toString` shows the *observable* value and hides
    // the causal bookkeeping — an ORMap prints its values but not its tags or context. When two
    // states differ only there, the rendered forms look identical and the hex is the only readable
    // evidence.
    //
    // Each operand also prints the WORD that built it. A `toString` says where a state landed; the
    // word says how it got there, which is the half a reader has to reconstruct by hand otherwise —
    // and reconstructing it from a seed means re-deriving the pool builder's draw order in their
    // head. This is the provenance half of what replaces jqwik's shrinking; `runExhaustiveSmall` is
    // the minimality half.
    @Suppress("LongParameterList")
    private fun associativityFailure(
        where: String,
        a: Tracked<S>,
        b: Tracked<S>,
        c: Tracked<S>,
        left: S,
        right: S,
    ): String =
        "Associativity failure $where — the two bracketings are NOT EQUAL:\n" +
            operandLog(a, b, c) +
            "  (a⊔b)⊔c     = $left\n" +
            "  a⊔(b⊔c)     = $right\n" +
            "  (a⊔b)⊔c bytes = ${encoded(left).toHexString()}\n" +
            "  a⊔(b⊔c) bytes = ${encoded(right).toHexString()}"

    @Suppress("LongParameterList")
    private fun canonicalityFailure(
        where: String,
        a: Tracked<S>,
        b: Tracked<S>,
        c: Tracked<S>,
        leftBytes: ByteArray,
        rightBytes: ByteArray,
        state: S,
    ): String =
        "Canonical-encoding failure $where — the two bracketings are EQUAL but encode to " +
            "DIFFERENT bytes. This is not an associativity defect; the join landed in the right " +
            "place and the serializer is history-dependent:\n" +
            operandLog(a, b, c) +
            "  (a⊔b)⊔c bytes ${leftBytes.toHexString()}\n" +
            "  a⊔(b⊔c) bytes ${rightBytes.toHexString()}\n" +
            "  state         $state"

    private fun commutativityFailure(seed: Long, a: Tracked<S>, b: Tracked<S>, ab: S, ba: S): String =
        "Commutativity failure at seed $seed — a⊔b and b⊔a are NOT EQUAL:\n" +
            pairLog(a, b) +
            "  a⊔b         = $ab\n" +
            "  b⊔a         = $ba\n" +
            "  Before reading this as a defect in the type, check the generator against the type's " +
            "documented preconditions: a generator that mints one tag for two different writes " +
            "steps outside them, and the resulting asymmetry is the test's, not the lattice's."

    private fun commutativityCanonicalityFailure(
        seed: Long,
        a: Tracked<S>,
        b: Tracked<S>,
        abBytes: ByteArray,
        baBytes: ByteArray,
        state: S,
    ): String =
        "Canonical-encoding failure at seed $seed — a⊔b and b⊔a are EQUAL but encode to DIFFERENT " +
            "bytes. The join commutes and the serializer does not; it is history-dependent:\n" +
            pairLog(a, b) +
            "  a⊔b bytes   ${abBytes.toHexString()}\n" +
            "  b⊔a bytes   ${baBytes.toHexString()}\n" +
            "  state       $state"

    private fun idempotenceFailure(seed: Long, a: Tracked<S>, selfJoin: S): String =
        "Idempotence failure at seed $seed — a⊔a is not a:\n" +
            "  a           = ${a.state}\n" +
            "    built by  ${a.provenance}\n" +
            "  a⊔a         = $selfJoin"

    @Suppress("LongParameterList")
    private fun absorptionFailure(seed: Long, side: String, a: Tracked<S>, b: Tracked<S>, ab: S, absorbed: S): String =
        "Least-upper-bound failure at seed $seed — a⊔b is not an upper bound of $side:\n" +
            pairLog(a, b) +
            "  a⊔b         = $ab\n" +
            "  (a⊔b)⊔$side      = $absorbed\n" +
            "  A join that is not above its own operands is not a join. Expect this to come with " +
            "an associativity failure; if it does not, the defect is in `piece` itself rather than " +
            "in how contributions are combined."

    private fun roundTripValueFailure(seed: Long, a: Tracked<S>, roundTripped: S): String =
        "Codec round-trip failure at seed $seed — decode(encode(s)) is NOT EQUAL to s. The " +
            "serializer is lossy; every other law in this suite is blind to it, because both sides " +
            "of each of their comparisons go through the same lossy path:\n" +
            "  s           = ${a.state}\n" +
            "    built by  ${a.provenance}\n" +
            "  decoded     = $roundTripped\n" +
            "  bytes       ${encoded(a.state).toHexString()}\n" +
            "  A peer that receives this state over a `Seam` holds the decoded value, not `s`."

    private fun roundTripByteFailure(seed: Long, a: Tracked<S>, first: ByteArray, second: ByteArray): String =
        "Codec stability failure at seed $seed — decode(encode(s)) is EQUAL to s but does not " +
            "RE-ENCODE to the same bytes. This is not a lossy serializer; the encoding depends on " +
            "how the object was constructed (parsed rather than built) rather than on what it " +
            "holds:\n" +
            "  s           = ${a.state}\n" +
            "    built by  ${a.provenance}\n" +
            "  encode(s)               ${first.toHexString()}\n" +
            "  encode(decode(encode(s))) ${second.toHexString()}\n" +
            "  #1955's root-hash gate compares digests, so a receiver in this state reads as " +
            "diverged from the sender it agrees with. An insertion-ordered collection whose merge " +
            "and whose decode disagree on order is the usual cause."

    @Suppress("LongParameterList")
    private fun wireJoinValueFailure(seed: Long, a: Tracked<S>, b: Tracked<S>, direct: S, throughWire: S): String =
        "Codec join failure at seed $seed — a ⊔ b and a ⊔ decode(encode(b)) are NOT EQUAL, though " +
            "b round-tripped to something that compares equal to itself. Equality is not a " +
            "congruence for the join through this codec: the serializer drops a field `equals` " +
            "ignores and `piece` reads — a last-writer-wins tag is the usual one:\n" +
            pairLog(a, b) +
            "  a⊔b                  = $direct\n" +
            "  a⊔decode(encode(b))  = $throughWire"

    @Suppress("LongParameterList")
    private fun wireJoinByteFailure(
        seed: Long,
        a: Tracked<S>,
        b: Tracked<S>,
        directBytes: ByteArray,
        wireBytes: ByteArray,
        state: S,
    ): String =
        "Codec join canonicality failure at seed $seed — a ⊔ b and a ⊔ decode(encode(b)) are EQUAL " +
            "but encode to DIFFERENT bytes. The value survived the wire and its digest did not:\n" +
            pairLog(a, b) +
            "  a⊔b bytes                 ${directBytes.toHexString()}\n" +
            "  a⊔decode(encode(b)) bytes ${wireBytes.toHexString()}\n" +
            "  state                     $state"

    private fun poolTooThin(seed: Long, pool: List<Tracked<S>>, distinctStates: Int): String =
        "Codec-law pool is vacuous at seed $seed — ${pool.size} states hold only $distinctStates " +
            "distinct value(s), so the round-trip arm compared one value with itself and would " +
            "stay green on a serializer that encodes nothing at all.\n" +
            "  This is a defect in the generator, not in the codec. Widen the alphabet or point " +
            "its roaming ops at what the state actually holds; `generatorIsNotVacuous` measures " +
            "the same pool and will say which way it collapsed.\n" +
            "  pool ${pool.joinToString("\n       ") { it.provenance }}"

    private fun encodingsTooThin(seed: Long, pool: List<Tracked<S>>, states: Int, encodings: Int): String =
        "Codec-law pool is vacuous at seed $seed — its $states distinct states produced only " +
            "$encodings distinct encoding(s) over ${pool.size} pool entries.\n" +
            "  Below the state count the serializer cannot tell two reachable states apart, which " +
            "is the lossy-codec defect itself arriving one arm early; at 1 it emits a constant and " +
            "there is nothing for a decode to get wrong. Either way the arms below prove nothing " +
            "until it is fixed."

    private fun noAbsorbingJoin(seed: Long, pool: List<Tracked<S>>, pairs: Long): String =
        "Codec join arm is vacuous at seed $seed — none of $pairs ordered pairs over " +
            "${pool.size} states had `a ⊔ b != a`, so no join ever read anything out of the " +
            "decoded operand and the arm holds for any codec whatsoever.\n" +
            "  A pool in which every join already dominates its second operand is a pool of one " +
            "value under a different name; see the round-trip guard's message."

    private fun pairLog(a: Tracked<S>, b: Tracked<S>): String =
        "  a           = ${a.state}\n" +
            "    built by  ${a.provenance}\n" +
            "  b           = ${b.state}\n" +
            "    built by  ${b.provenance}\n"

    private fun operandLog(a: Tracked<S>, b: Tracked<S>, c: Tracked<S>): String =
        "  a           = ${a.state}\n" +
            "    built by  ${a.provenance}\n" +
            "  b           = ${b.state}\n" +
            "    built by  ${b.provenance}\n" +
            "  c           = ${c.state}\n" +
            "    built by  ${c.provenance}\n"

    private fun minimalCounterexample(word: IntArray, searched: Int): String {
        val spelled = word.joinToString(", ") { alphabet[it].name }
        return "\n\n  MINIMAL COUNTEREXAMPLE\n" +
            "  word            [$spelled]\n" +
            "  length          ${word.size}\n" +
            "  words searched  $searched of ${wordsUpTo(exhaustiveWordLength)} " +
            "(every word of length 1..$exhaustiveWordLength over ${alphabet.size} ops)\n" +
            "  minimal because words are enumerated breadth-first by length: every shorter word " +
            "over this alphabet was tried first and held.\n" +
            "  repro           apply the word above to `initial` on replica 0 with " +
            "Random($EXHAUSTIVE_SEED)."
    }

    /**
     * Reachable states that are **causally related to one another**, not merely siblings.
     *
     * Each of [replicaCount] replicas keeps one linear local history — it only ever extends its own
     * latest state — and every intermediate state is kept. A linear history is what makes the pool
     * safe to enumerate: a replica that branched would mint the same dot (or the same
     * `(replica, timestamp)`) twice, which no CRDT promises anything about, and the resulting
     * "failure" would be the generator's fault rather than the type's. Occasionally a replica
     * absorbs a peer's current state before its next op, so the pool also holds states whose
     * contexts overlap.
     *
     * **[criticalShapes] run first, on replica 0, as a prefix.** Random exploration reaches the
     * interesting shape on *some* seeds; a constructed prefix reaches it on every seed, which is
     * the difference between a law and a probabilistic pin. Against a lattice that wrongly keeps a
     * retired contribution, the seeds-only pool is red on **8 of 16** seeds — first at seed 2, so a
     * range of `0..1` is green on a provably broken type — and the same pool with a
     * `put(k,4) · remove(k) · put(k,1)` prefix is red on **64 of 64**. The control matters as much:
     * the corrected lattice is green on 64 of 64 either way, so the prefix is not manufacturing the
     * red.
     *
     * **Then every other replica gets one leading assert** — see [leadEveryReplicaWithAnAssert],
     * which is the whole of #2145: a shape runs on replica 0 only, so without this the other
     * replicas entered the loop below still holding [initial], where a retiring draw *cannot* be
     * effective.
     *
     * Trimmed to [POOL_LIMIT] entries — the triple loop is cubic, and the interesting shapes all
     * appear within a handful of ops. **The prefix is counted inside that cap, not added to it**, so
     * constructed shapes cost random exploration rather than wall clock. That is the intended trade:
     * a constructed step is informative on every seed, and the random step it displaces was
     * informative on roughly half. The leading asserts are counted inside it on the same terms.
     */
    private fun causalPool(random: Random): PoolRun<S> {
        val latest = MutableList(replicaCount) { initial }
        val words = MutableList(replicaCount) { emptyList<String>() }
        val pool = mutableListOf(Tracked(initial, "initial"))
        val steps = mutableListOf<Step>()
        applyCriticalShapes(latest, words, pool, steps, random)
        leadEveryReplicaWithAnAssert(latest, words, pool, steps, random)
        outer@ for (op in 0 until opsPerReplica) {
            for (r in 0 until replicaCount) {
                if (random.nextInt(GOSSIP_ONE_IN) == 0) {
                    val peer = random.nextInt(replicaCount)
                    latest[r] = latest[r].piece(latest[peer])
                    words[r] = words[r] + "⊔R$peer"
                    pool += Tracked(latest[r], provenance(r, words[r]))
                }
                val chosen = pick(random)
                val before = latest[r]
                latest[r] = chosen.apply(before, r, random)
                steps += Step(chosen.kind, changed = latest[r] != before)
                words[r] = words[r] + chosen.name
                pool += Tracked(latest[r], provenance(r, words[r]))
                if (pool.size >= POOL_LIMIT) break@outer
            }
        }
        return PoolRun(pool, steps)
    }

    private fun provenance(replica: Int, word: List<String>): String =
        "R$replica ← ${word.joinToString(" · ")}"

    /**
     * The states one exhaustive [word] passes through, `initial` first — the pool
     * [runExhaustiveSmall] checks the laws over.
     *
     * The [Random] is fresh and identically seeded for every word, so a roaming op (one drawing its
     * key or value from the stream) still lands the same way each time the same word is walked.
     * That is what makes a reported word a complete repro on its own.
     */
    private fun walk(word: IntArray): List<Tracked<S>> {
        val random = Random(EXHAUSTIVE_SEED)
        val pool = ArrayList<Tracked<S>>(word.size + 1)
        pool += Tracked(initial, "⊥")
        var state = initial
        for (i in word.indices) {
            state = alphabet[word[i]].apply(state, 0, random)
            pool += Tracked(state, (0..i).joinToString(" · ") { alphabet[word[it]].name })
        }
        return pool
    }

    /**
     * Walk each word of [criticalShapes] on replica 0, keeping every intermediate state.
     *
     * Every step is asserted to have **changed the state**, because a shape that no-ops is
     * decoration that reads like coverage. This is not hypothetical: `ORMapConvergenceTest`'s
     * generator once burned **10 of 29** steps removing a key the state did not hold, so a third of
     * its budget bought nothing by accident. A constructed shape that did the same would be worse,
     * because someone wrote it down on purpose and the next reader would trust it.
     * ([leadEveryReplicaWithAnAssert] and the binding's own roaming ops have since taken that rate
     * to 5.7% over seeds `0..63`; the point the check makes is unchanged.)
     */
    private fun applyCriticalShapes(
        latest: MutableList<S>,
        words: MutableList<List<String>>,
        pool: MutableList<Tracked<S>>,
        steps: MutableList<Step>,
        random: Random,
    ) {
        for (shape in criticalShapes) {
            for (opName in shape) {
                val op = alphabet.firstOrNull { it.name == opName }
                    ?: error(
                        "critical shape $shape names op '$opName', which is not in the alphabet " +
                            "${alphabet.map { it.name }}",
                    )
                val before = latest[0]
                val after = op.apply(before, 0, random)
                check(after != before) { criticalShapeNoOpFailure(shape, op, before) }
                latest[0] = after
                steps += Step(op.kind, changed = true)
                words[0] = words[0] + op.name
                pool += Tracked(after, provenance(0, words[0]))
            }
        }
    }

    /**
     * Give every replica the leading assert [applyCriticalShapes] has already given replica 0, so
     * **no replica enters the exploration loop at the lattice bottom** (#2145, #2158).
     *
     * A shape runs on replica 0 only. The others therefore used to take their first exploration draw
     * from [initial] — and on a retiring alphabet roughly half that alphabet is retiring, so their
     * first draw was a coin flip on an op that *cannot* be effective, because there is nothing yet to
     * retire. Measured over seeds `0..63` before this existed, **every single no-op taken from the
     * bottom state was a RETIRE and no ASSERT ever no-opped there** — 85 of `ORSet`'s 139 no-ops,
     * 94 of `CausalDotSet`'s 143, 92 of `MovableTree`'s 120. Those are gone: all twelve retiring
     * bindings now read **0** bottom-state no-ops, and `ORSet`'s no-op rate falls 19.5% → 7.7%.
     *
     * **Only on an alphabet that has something to retire.** A grow-only binding cannot draw an op
     * that is vacuous by construction, so the leading assert would buy it nothing and cost it pool
     * budget — `GSet` measured 23.2% → 32.8% exploration no-ops when it was included. This is the
     * same precondition [defaultCriticalShapes] applies when it declines to construct a prefix at
     * all, and the seven grow-only bindings' trajectories are byte-for-byte what they were.
     *
     * **The op is drawn, not fixed, and that is a measurement rather than a taste.** Leading every
     * replica with `alphabet.first { it.kind == ASSERT }` is the obvious spelling and it collapses
     * the pool on any binding whose asserting op ignores `replicaIndex`: `TwoPhaseSet`'s `add` puts
     * the *identical* state on all three replicas, which took its concurrent-pair rate from 22.0% to
     * **7.6%** — through the 15% floor — while its equal-pair rate doubled to 14.4%. Drawing
     * uniformly from the asserting ops keeps the replicas apart (concurrency 20.0%, equal pairs
     * 9.4%) and costs nothing elsewhere.
     *
     * Every step is asserted to have **changed the state**, for the reason [applyCriticalShapes]
     * gives: a leading assert that no-ops would silently reinstate the bottom-state vacuity this
     * exists to remove. No live binding trips it — the bottom is where an assert is least likely to
     * be absorbed.
     */
    private fun leadEveryReplicaWithAnAssert(
        latest: MutableList<S>,
        words: MutableList<List<String>>,
        pool: MutableList<Tracked<S>>,
        steps: MutableList<Step>,
        random: Random,
    ) {
        if (alphabet.none { it.kind == OpKind.RETIRE }) return
        val asserts = alphabet.filter { it.kind == OpKind.ASSERT }
        if (asserts.isEmpty()) return
        for (r in 0 until replicaCount) {
            if (words[r].isNotEmpty()) continue
            val op = if (asserts.size == 1) asserts[0] else asserts[random.nextInt(asserts.size)]
            val before = latest[r]
            val after = op.apply(before, r, random)
            check(after != before) { leadingAssertNoOpFailure(op, before) }
            latest[r] = after
            steps += Step(op.kind, changed = true)
            words[r] = words[r] + op.name
            pool += Tracked(after, provenance(r, words[r]))
        }
    }

    private fun leadingAssertNoOpFailure(op: LatticeOp<S>, before: S): String =
        "Leading assert '${op.name}' left the state unchanged at the lattice bottom.\n" +
            "  state $before\n" +
            "  Every replica is led with one asserting op so that a retiring draw can be effective " +
            "(#2145). An ASSERT that is absorbed by `initial` puts the replica straight back where " +
            "that is impossible, so the alphabet is declaring as ASSERT something that adds no " +
            "observation — check the op's kind against `OpKind`, and check its precondition holds " +
            "on `initial`."

    private fun criticalShapeNoOpFailure(shape: List<String>, op: LatticeOp<S>, before: S): String =
        "Critical shape $shape is decoration: step '${op.name}' left the state unchanged.\n" +
            "  state $before\n" +
            "  A constructed shape only pins what it actually reaches. Either the op is not " +
            "target-deterministic (its key/element is drawn from `random`, so it wandered off what " +
            "the previous step touched), or its precondition is unmet at this point in the word."

    private fun pick(random: Random): LatticeOp<S> =
        if (alphabet.size == 1) alphabet[0] else alphabet[random.nextInt(alphabet.size)]

    /**
     * Every word of [length] over the alphabet's indices, in lexicographic order, reusing one
     * `IntArray` — an odometer. Lexicographic order is not an aesthetic choice: with
     * [runExhaustiveSmall]'s outer loop over lengths it makes the enumeration breadth-first, and
     * breadth-first is the entire basis of the minimality claim.
     */
    private fun forEachWordOfLength(length: Int, action: (IntArray) -> Unit) {
        val word = IntArray(length)
        while (true) {
            action(word)
            var i = length - 1
            while (i >= 0) {
                word[i]++
                if (word[i] < alphabet.size) break
                word[i] = 0
                i--
            }
            if (i < 0) return
        }
    }

    /** Words of length `1..length` over this alphabet: `Σ |A|ⁿ`. */
    private fun wordsUpTo(length: Int): Int =
        (1..length).sumOf { n -> intPow(alphabet.size, n) }

    /**
     * Ordered triples the exhaustive pass would check at bound [length] — the real cost unit, since
     * a word of length `n` yields a pool of `n + 1` states and therefore `(n + 1)³` triples.
     */
    private fun triplesUpTo(length: Int): Long =
        (1..length).sumOf { n -> intPow(alphabet.size, n).toLong() * (n + 1) * (n + 1) * (n + 1) }

    private fun intPow(base: Int, exponent: Int): Int {
        var result = 1
        repeat(exponent) { result *= base }
        return result
    }

    /**
     * The bound [runExhaustiveSmall] actually uses: [EXHAUSTIVE_WORD_LENGTH], reduced to the
     * largest **complete** length this alphabet can afford under [EXHAUSTIVE_TRIPLE_BUDGET].
     *
     * Reduced by whole lengths, never truncated part-way through one. A partial length would
     * silently weaken the minimality claim to "shortest among the words we happened to reach",
     * which is the kind of caveat nobody carries forward; a complete length keeps it exact —
     * *shortest, full stop, up to this length*.
     */
    private val exhaustiveWordLength: Int =
        (EXHAUSTIVE_WORD_LENGTH downTo 2).firstOrNull { triplesUpTo(it) <= EXHAUSTIVE_TRIPLE_BUDGET } ?: 1

    /** One pool state, plus the word that built it. */
    private class Tracked<S>(val state: S, val provenance: String)

    /** One op the pool builder applied: what it was for, and whether it did anything. */
    private class Step(val kind: OpKind, val changed: Boolean)


    /** A built pool and the steps that built it — see [VacuityFloors] for what counts as a step. */
    private class PoolRun<S>(val states: List<Tracked<S>>, val steps: List<Step>)

    /**
     * Measure how much the generator actually searched, over the pools of [seeds]. Asserts nothing.
     *
     * See [VacuityFloors] for the exact pair and step definitions — in particular that a pair is an
     * **ordered** pair of distinct pool positions, which is what makes a total order read 50%
     * ancestry rather than 100%.
     *
     * `O(pool²)` joins per seed against the associativity pass's `O(pool³)`, so this is free at any
     * pool size that pass can afford.
     */
    public fun measureVacuity(seeds: LongRange): VacuityReport {
        var pairs = 0L
        var strictAncestor = 0L
        var concurrent = 0L
        var steps = 0
        var effectiveRetires = 0
        var noOps = 0
        for (seed in seeds) {
            val run = causalPool(Random(seed))
            val states = run.states
            for (i in states.indices) {
                for (j in states.indices) {
                    if (i == j) continue
                    pairs++
                    val a = states[i].state
                    val b = states[j].state
                    val aBelowB = a.piece(b) == b
                    val bBelowA = b.piece(a) == a
                    if (aBelowB && !bBelowA) strictAncestor++
                    if (!aBelowB && !bBelowA) concurrent++
                }
            }
            for (step in run.steps) {
                steps++
                if (step.kind == OpKind.RETIRE && step.changed) effectiveRetires++
                if (!step.changed) noOps++
            }
        }
        return VacuityReport(pairs, strictAncestor, concurrent, steps, effectiveRetires, noOps, floors)
    }

    /**
     * [measureVacuity] over [seeds], with every floor in [floors] asserted — and the measured
     * values returned either way, so a caller can print them on a green run.
     *
     * Each floor fails on its own with its own message. That separation is the point of the whole
     * task: deleting a binding's retiring op must red the **retirement** floor and leave ancestry
     * and concurrency green, because ancestry and concurrency are exactly the metrics that stay
     * healthy while a generator stops searching: on an arm that found 0 violations in 47,059
     * triples, ancestry read 28.4% and concurrency 43.2% — the latter *higher* than the 39.7% of the
     * arm that found 500, and both far above their floors. `VacuityFloorSelfTest` holds that
     * asymmetry as a standing assertion. A single aggregate assertion would report "the generator is
     * vacuous" and lose the one bit of information worth having, which is *how*.
     *
     * **The floors are checked in declaration order and the first breach raises**, so a failure
     * naming one floor says nothing about the ones below it — the no-op ceiling is checked last.
     * Call [measureVacuity] instead when you need every rate regardless of which one broke.
     */
    public fun checkVacuityFloors(seeds: LongRange): VacuityReport {
        val report = measureVacuity(seeds)
        check(report.strictAncestorRate >= floors.strictAncestorPairs) {
            floorFailure(
                "strict-ancestor pairs", report.strictAncestorRate, floors.strictAncestorPairs, report,
                "The pool has stopped being a causal chain — its states are siblings, so no operand " +
                    "can retire a tag another still carries, and the whole #2086 bug class is out of reach.",
            )
        }
        check(floors.totalOrder || report.concurrentRate >= floors.concurrentPairs) {
            floorFailure(
                "concurrent pairs", report.concurrentRate, floors.concurrentPairs, report,
                "Every join in this pool is trivial — one operand already dominates — so the law " +
                    "holds for free. If the type genuinely cannot fork (a chain, like `IntMax`), " +
                    "declare `VacuityFloors(totalOrder = true)`; if it can, the generator has stopped " +
                    "letting it. A shared cell every replica writes is the usual cause: it makes any " +
                    "two states comparable, and on `LWWMap` it cost 12 points (25.9% → 14.1%).",
            )
        }
        check(report.effectiveRetireRate >= floors.effectiveRetireSteps) {
            floorFailure(
                "effective RETIRE steps", report.effectiveRetireRate, floors.effectiveRetireSteps, report,
                "This is the floor the suite exists for, and the only one no `Quilted` expression " +
                    "can compute — see `OpKind`. Either the alphabet declares no RETIRE op, or the " +
                    "ones it declares are landing on nothing (removing what the state does not hold " +
                    "is the lattice identity). Both read as coverage and neither is.",
            )
        }
        check(report.noOpRate <= floors.maxNoOpSteps) {
            floorFailure(
                "no-op steps (ceiling)", report.noOpRate, floors.maxNoOpSteps, report,
                "Most of the generator's budget is being spent on ops the type discards. Point the " +
                    "roaming ops at what the state actually holds, or give a clock-gated op a " +
                    "state-derived clock; do not raise the ceiling, which buys the number without " +
                    "buying the search.",
            )
        }
        return report
    }

    private fun floorFailure(name: String, measured: Double, floor: Double, report: VacuityReport, why: String): String =
        "Vacuity floor breached — $name measured ${percent(measured)}, required " +
            "${if (name.endsWith("(ceiling)")) "at most" else "at least"} ${percent(floor)}.\n" +
            "  $why\n" +
            "  alphabet ${alphabet.joinToString(", ")}\n" +
            "  shapes   ${criticalShapes.ifEmpty { "none" }}\n" +
            report

    private fun buildReplicas(random: Random): List<S> =
        List(replicaCount) { r ->
            (0 until opsPerReplica).fold(initial) { acc, _ -> gen.applyRandomOp(acc, replicaIndex = r, random = random) }
        }

    private fun assertAllPermutationsConverge(replicas: List<S>, canonical: S) {
        val canonicalBytes = encoded(canonical)
        for (permutation in permutationsOf(replicas.indices.toList())) {
            val result = permutation.fold(initial) { acc, idx -> acc.piece(replicas[idx]) }
            check(result == canonical) {
                "Convergence failure under permutation $permutation:\n" +
                    "  expected $canonical\n" +
                    "  got      $result\n" +
                    "  replicas $replicas"
            }
            // Byte-level canonicality (#1957): converged replicas must ENCODE identically,
            // not merely compare equal. Set/Map equality is order-insensitive exactly where
            // the encoding is not, so `result == canonical` is structurally blind to a
            // history-dependent encoding.
            val resultBytes = encoded(result)
            check(resultBytes.contentEquals(canonicalBytes)) {
                "Canonical-encoding failure under permutation $permutation:\n" +
                    "  canonical bytes ${canonicalBytes.toHexString()}\n" +
                    "  permuted  bytes ${resultBytes.toHexString()}\n" +
                    "  state     $canonical"
            }
        }
    }

    // ── Compaction phases (#2019) ─────────────────────────────────────────────────────────────

    private var runs: Int = 0
    private var postMergeRunsWithCompaction: Int = 0
    private var postMergeMaxDroppedInOneStep: Int = 0
    private var preMergeRunsWithTwoOrMoreCompacting: Int = 0

    /**
     * What the compaction phases reached across every [run] so far — see [CompactionCoverage].
     *
     * Accumulated per harness instance, and [LatticeLawSuite.newHarness] is called once per test
     * method, so a caller reads the coverage of the seeds *it* ran and nothing else.
     */
    public val compactionCoverage: CompactionCoverage
        get() = CompactionCoverage(
            runs = runs,
            postMergeRunsWithCompaction = postMergeRunsWithCompaction,
            postMergeMaxDroppedInOneStep = postMergeMaxDroppedInOneStep,
            preMergeRunsWithTwoOrMoreCompacting = preMergeRunsWithTwoOrMoreCompacting,
        )

    /** Fold every permutation, compact each fold to stable, and assert the results agree. */
    private fun runPostMergePhase(compactor: CrdtCompactor<S>, replicas: List<S>, seed: Long) {
        var compactedSomething = false
        var reference: S? = null
        var referenceBytes: ByteArray? = null
        for (permutation in permutationsOf(replicas.indices.toList())) {
            val folded = permutation.fold(initial) { acc, idx -> acc.piece(replicas[idx]) }
            val stable = compactToStable(compactor, folded)
            if (stable.steps > 0) compactedSomething = true
            if (stable.maxDropped > postMergeMaxDroppedInOneStep) {
                postMergeMaxDroppedInOneStep = stable.maxDropped
            }
            val bytes = encoded(stable.state)
            val first = reference
            val firstBytes = referenceBytes
            if (first == null || firstBytes == null) {
                reference = stable.state
                referenceBytes = bytes
                continue
            }
            check(stable.state == first) {
                compactionConvergenceFailure("post-merge", seed, permutation, first, stable.state)
            }
            check(bytes.contentEquals(firstBytes)) {
                compactionCanonicalityFailure("post-merge", seed, permutation, firstBytes, bytes, first)
            }
        }
        if (compactedSomething) postMergeRunsWithCompaction++
    }

    /** Compact each replica alone to stable, then fold every permutation and assert they agree. */
    private fun runPreMergePhase(compactor: CrdtCompactor<S>, replicas: List<S>, seed: Long) {
        var replicasThatCompacted = 0
        val compacted = replicas.map { replica ->
            val stable = compactToStable(compactor, replica)
            if (stable.steps > 0) replicasThatCompacted++
            stable.state
        }
        if (replicasThatCompacted >= 2) preMergeRunsWithTwoOrMoreCompacting++
        var reference: S? = null
        var referenceBytes: ByteArray? = null
        for (permutation in permutationsOf(compacted.indices.toList())) {
            val folded = permutation.fold(initial) { acc, idx -> acc.piece(compacted[idx]) }
            val bytes = encoded(folded)
            val first = reference
            val firstBytes = referenceBytes
            if (first == null || firstBytes == null) {
                reference = folded
                referenceBytes = bytes
                continue
            }
            check(folded == first) {
                compactionConvergenceFailure("pre-merge", seed, permutation, first, folded)
            }
            check(bytes.contentEquals(firstBytes)) {
                compactionCanonicalityFailure("pre-merge", seed, permutation, firstBytes, bytes, first)
            }
        }
    }

    /** One compaction chain run to a fixpoint, and what it cost. */
    private class Stable<S>(val state: S, val steps: Int, val maxDropped: Int)

    /**
     * Compact [from] until [CrdtCompactor.compactOnce] declines — mirroring
     * `RgaGcCoordinator.compactUntilStable`, which loops because removing one tombstone can unblock
     * its structural predecessor. Looping is what makes a *chain* reachable rather than only its
     * tail.
     *
     * [COMPACT_STEP_CAP] bounds the loop so a compactor that never reaches a fixpoint fails as a
     * legible error rather than as a hang.
     */
    private fun compactToStable(compactor: CrdtCompactor<S>, from: S): Stable<S> {
        var state = from
        var steps = 0
        var maxDropped = 0
        while (true) {
            val cut = cutOf(state)
            val step = compactor.compactOnce(state, cut, cut, cut) ?: break
            state = step.state
            if (step.droppedCount > maxDropped) maxDropped = step.droppedCount
            steps++
            check(steps <= COMPACT_STEP_CAP) {
                "Compaction did not reach a fixpoint within $COMPACT_STEP_CAP steps.\n" +
                    "  state $state\n" +
                    "  A compactor must return null once nothing qualifies; one that keeps " +
                    "returning a step is either not applying the step it reports, or reporting a " +
                    "drop it did not make."
            }
        }
        return Stable(state, steps, maxDropped)
    }

    /**
     * The cut the phases compact at: `stableCut = frontierMax = delivered = contiguous frontier`.
     *
     * Derived by the harness from the state alone, never supplied by a binding — a binding free to
     * pass its own cut could pass one no execution reaches, which pins nothing, and that is the
     * exact failure #2019 names.
     *
     * Setting all three equal is not a convenient fiction. It is what `Quilter.recomputeCut`
     * computes in two reachable topologies, because its `rows` always contains `self`: a **fully
     * converged room**, where every peer has gossiped a `Delivered` equal to every other's so
     * `min == max == self` (phase A), and a **solo peer**, where `knownPeers` is empty so
     * `rows == [self]` (phase B). The first is the modal steady state of any quiet mesh; the second
     * is a device editing offline, which is what phase B's independent per-replica histories model.
     *
     * The derivation errs toward **under**-compaction, never over: [VersionVector.Companion.contiguous]
     * stops at the first gap, so a generator quirk that leaves a hole yields a lower cut and less
     * compaction. It cannot manufacture a cut authorising a drop a real execution would refuse — a
     * quirk costs coverage, which the floors catch, rather than soundness.
     */
    private fun cutOf(state: S): VersionVector =
        VersionVector.contiguous(state.causalDots(), state.causalFloor())

    /**
     * Assert no two replicas share an author id — the premise phase B's soundness rests on.
     *
     * A replica compacting alone uses its own delivered vector as the stable cut, which is only
     * sound because its history is single-author: no peer can hold a concurrent op referencing one
     * of its dots. Two replicas sharing an author id would break that quietly, and the resulting
     * "convergence failure" would be the generator's fault rather than the type's — so it is named
     * here instead.
     *
     * **It runs before phase 0, and that ordering was checked rather than chosen.** A gate placed
     * ahead of an older one is normally how the older one's coverage silently drops to zero — but
     * this gate can only fire on a generator whose replicas share an author id, and such a generator
     * mints the same dot twice, so phase 0 is *already* red on every one of those inputs (measured:
     * it reds with a canonical-encoding failure). No input moves from red to green; only the message
     * changes, from a byte diff to the name of the generator fault that produced it. Placing it
     * after phase 0 would mean the diagnosis never printed, because phase 0 raises first.
     */
    private fun assertReplicaHistoriesAreDisjoint(replicas: List<S>, seed: Long) {
        val authorsPerReplica = replicas.map { r -> r.causalDots().mapTo(mutableSetOf()) { it.replica } }
        for (i in authorsPerReplica.indices) {
            for (j in i + 1 until authorsPerReplica.size) {
                val shared = authorsPerReplica[i] intersect authorsPerReplica[j]
                check(shared.isEmpty()) {
                    "Replica histories are not disjoint at seed $seed — R$i and R$j both authored " +
                        "dots for ${shared.joinToString()}.\n" +
                        "  The pre-merge compaction phase compacts each replica against its own " +
                        "delivered vector, which is sound only while each history is single-author " +
                        "(no peer can hold a concurrent op referencing a dot this replica minted). " +
                        "Give each replica its own `ReplicaId` — the generator receives " +
                        "`replicaIndex` for exactly this."
                }
            }
        }
    }

    private fun compactionConvergenceFailure(
        phase: String,
        seed: Long,
        permutation: List<Int>,
        expected: S,
        actual: S,
    ): String =
        "Compaction convergence failure ($phase) at seed $seed under permutation $permutation — " +
            "two fold orders compact to states that are NOT EQUAL:\n" +
            "  expected $expected\n" +
            "  got      $actual\n" +
            "  Compaction must be a pure function of the state, so equal states must compact to " +
            "equal states. A predicate reading the state's iteration order rather than its value " +
            "is the usual cause."

    @Suppress("LongParameterList")
    private fun compactionCanonicalityFailure(
        phase: String,
        seed: Long,
        permutation: List<Int>,
        expectedBytes: ByteArray,
        actualBytes: ByteArray,
        state: S,
    ): String =
        "Compaction canonical-encoding failure ($phase) at seed $seed under permutation " +
            "$permutation — the compacted states are EQUAL but encode to DIFFERENT bytes.\n" +
            "  reference bytes ${expectedBytes.toHexString()}\n" +
            "  permuted  bytes ${actualBytes.toHexString()}\n" +
            "  state           $state\n" +
            "  A compaction record's own collections are built by `Set.plus` in merge order, so " +
            "they are insertion-ordered by a history the value does not carry. Encode them through " +
            "`CanonicalMapSerializer`/`CanonicalSetSerializer`, and order several `Compact` ops " +
            "against each other by a pure function of their contents."

    private fun mergeAll(states: List<S>): S = states.fold(initial) { acc, s -> acc.piece(s) }

    private fun <T> permutationsOf(items: List<T>): List<List<T>> {
        if (items.size <= 1) return listOf(items)
        return items.flatMapIndexed { i, head ->
            val rest = items.toMutableList().also { it.removeAt(i) }
            permutationsOf(rest).map { listOf(head) + it }
        }
    }

    private companion object {
        /**
         * Cap on [causalPool]'s size — the associativity loop over it is cubic.
         *
         * Measured on JVM: pool 14 → 45,797 triples / 107 ms; 20 → 133,044 / 305 ms; 28 → 356,106 /
         * 875 ms; 40 → 1,024,000 / 2.61 s. Multiply by 10–15 for Kotlin/Native, which is where this
         * module's test budget lands. **Do not raise this to buy redness** — [criticalShapes] buy
         * the same redness at zero cost, which is what they are for.
         */
        const val POOL_LIMIT = 14

        /** One op in this many is preceded by absorbing a peer's state. */
        const val GOSSIP_ONE_IN = 4

        /** Name of the synthetic op wrapping a raw [OperationGenerator]. */
        const val UNDECLARED_OP = "undeclared"

        /**
         * Longest word `runExhaustiveSmall` will spell. **Raising this is the obvious future
         * mistake**, so the curve is here rather than in a design doc nobody opens.
         *
         * A green run — the everyday case — must enumerate *everything*, so it pays the full
         * `Σ |A|ⁿ` words and `Σ |A|ⁿ(n+1)³` triples. Finding a counterexample is cheap because the
         * search exits at it; **proving absence is what costs, and that is what runs every day.**
         * Measured on the `ORMap` binding over a three-op alphabet:
         *
         * | L | words | JVM | wasmJs | macosArm64 |
         * |---|---|---|---|---|
         * | 3 | 39 | 25 ms | 9.9 ms | — |
         * | 4 | 120 | 45 ms | 59 ms | **364 ms** |
         * | 5 | 363 | 165 ms | 313 ms | — |
         * | 6 | 1,092 | — | — | **15.2 s** |
         * | 7 | 3,279 | — | — | **65 s** |
         * | 8 | 9,840 | — | — | **193 s** |
         *
         * 4 is the right number because the shape this suite exists for — assert, retire,
         * re-assert — is **three** ops long, so 4 carries one op of headroom over the deepest bug
         * anyone has needed to reach here. 6 would cost four minutes on Kotlin/Native alone, and
         * Kotlin/Native is where this module's test budget lands.
         *
         * **Read the shape of this curve, not its absolute native cells.** Two things move them.
         * *The box:* the `L = 4` native cell has been measured at **364 ms**, **694–703 ms** and
         * **1.01 s** on three occasions, and the whole track's native suite total has read anywhere
         * from 53 s to 149 s for identical code. *The type:* the column is `ORMap<String, GCounter>`,
         * whose join is nested — `LWWRegisterConvergenceTest` runs this **exact** configuration
         * (`|A| = 3`, `L = 4`, 120 words) for **1 ms**, because its join is a tag comparison. So the
         * row is not a per-binding constant and must not be multiplied out as one. Measured whole-
         * suite cost of this pass on Kotlin/Native, 2026-08-05, 19 bindings, load 1.5: **3.03 s
         * total**, of which `JsonCrdt` is 2.02 s and `ORMap` 1.01 s and the other 17 are ~0.
         */
        const val EXHAUSTIVE_WORD_LENGTH = 4

        /**
         * Ceiling on ordered triples one binding's exhaustive pass may check, which reduces
         * [EXHAUSTIVE_WORD_LENGTH] for a wide alphabet.
         *
         * **Word length is not the cost; `|A|ᴸ` is**, and the table above was measured at `|A| = 3`.
         * The live bindings run 1 to 6 ops, and at `L = 4` that spans 12,120 triples (`|A| = 3`) to
         * **176,844** (`JsonCrdt`, `|A| = 6`) — a 15× spread from a constant that looks fixed. Left
         * uncapped, one binding's tenth op would quietly cost more than the whole randomised suite.
         *
         * The value is the `|A| = 3, L = 4` cost — the configuration the table above was taken on —
         * rounded up: a three-op alphabet gets the full bound, and a wider one drops to the longest
         * length it can still enumerate *completely*. Every live alphabet keeps `L ≥ 3`, which is
         * the length the assert/retire/re-assert shape needs, so no binding loses the shape it
         * exists to catch.
         */
        const val EXHAUSTIVE_TRIPLE_BUDGET = 15_000L

        /**
         * The one seed every exhaustive word is walked under.
         *
         * Fixed rather than varied so that a word **is** the repro: an op that draws its key from
         * the stream lands identically each time the same word is walked, and a reader can replay
         * the reported counterexample without also being told a trial number.
         */
        const val EXHAUSTIVE_SEED = 0L

        /**
         * Ceiling on [compactToStable]'s fixpoint loop.
         *
         * Not a tuning knob: every live binding reaches its fixpoint in a handful of steps, and the
         * cap exists so a compactor that reports progress it never makes fails with a legible
         * message instead of spinning.
         */
        const val COMPACT_STEP_CAP = 64
    }
}
