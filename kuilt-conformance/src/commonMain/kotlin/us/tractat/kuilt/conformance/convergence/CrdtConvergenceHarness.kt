package us.tractat.kuilt.conformance.convergence

import kotlin.random.Random
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.Quilted

/** Strategy for generating an operation against a model state, producing the next state. */
public fun interface OperationGenerator<S> {
    /** Pick and apply an op against [state] using [random] for choices. Returns the new state (post-mutation). */
    public fun applyRandomOp(state: S, replicaIndex: Int, random: Random): S
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
public class CrdtConvergenceHarness<S : Quilted<S>>(
    public val initial: S,
    public val alphabet: List<LatticeOp<S>>,
    public val serializer: KSerializer<S>,
    public val criticalShapes: List<List<String>> = defaultCriticalShapes(alphabet),
    public val replicaCount: Int = 3,
    public val opsPerReplica: Int = 8,
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

    /** Run with a single [seed]; assert convergence. Returns the converged state. */
    public fun run(seed: Long): S {
        val random = Random(seed)
        val replicas = buildReplicas(random)
        val canonical = mergeAll(replicas)
        assertAllPermutationsConverge(replicas, canonical)
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
        checkAssociativeLaws(causalPool(Random(seed)), where = { "at seed $seed" })
    }

    /** Run [runAssociativeLaws] over every seed in [seeds]. */
    public fun runAssociativeLawsSeeds(seeds: LongRange): Unit = seeds.forEach(::runAssociativeLaws)

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
     * Trimmed to [POOL_LIMIT] entries — the triple loop is cubic, and the interesting shapes all
     * appear within a handful of ops. **The prefix is counted inside that cap, not added to it**, so
     * constructed shapes cost random exploration rather than wall clock. That is the intended trade:
     * a constructed step is informative on every seed, and the random step it displaces was
     * informative on roughly half.
     */
    private fun causalPool(random: Random): List<Tracked<S>> {
        val latest = MutableList(replicaCount) { initial }
        val words = MutableList(replicaCount) { emptyList<String>() }
        val pool = mutableListOf(Tracked(initial, "initial"))
        applyCriticalShapes(latest, words, pool, random)
        outer@ for (op in 0 until opsPerReplica) {
            for (r in 0 until replicaCount) {
                if (random.nextInt(GOSSIP_ONE_IN) == 0) {
                    val peer = random.nextInt(replicaCount)
                    latest[r] = latest[r].piece(latest[peer])
                    words[r] = words[r] + "⊔R$peer"
                    pool += Tracked(latest[r], provenance(r, words[r]))
                }
                val chosen = pick(random)
                latest[r] = chosen.apply(latest[r], r, random)
                words[r] = words[r] + chosen.name
                pool += Tracked(latest[r], provenance(r, words[r]))
                if (pool.size >= POOL_LIMIT) break@outer
            }
        }
        return pool
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
     * generator burns **10 of 29** steps removing a key the state does not hold, so a third of its
     * budget already buys nothing by accident. A constructed shape that did the same would be worse,
     * because someone wrote it down on purpose and the next reader would trust it.
     */
    private fun applyCriticalShapes(
        latest: MutableList<S>,
        words: MutableList<List<String>>,
        pool: MutableList<Tracked<S>>,
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
                words[0] = words[0] + op.name
                pool += Tracked(after, provenance(0, words[0]))
            }
        }
    }

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
    }
}
