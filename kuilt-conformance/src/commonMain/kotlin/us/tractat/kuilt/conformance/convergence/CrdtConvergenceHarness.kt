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
 * *within* a target and nothing more. It does **not** prove two targets agree on the bytes; that
 * dimension is pinned separately by the golden vectors in `:kuilt-crdt`'s `commonTest`.
 *
 * More sharply: on JVM and Android this assertion has **near-zero discriminating power**. The map
 * merges underneath most CRDTs return a `HashMap`, and `java.util.HashMap` iterates in bucket
 * order — a function of the key set and table capacity, both invariant under the fold order — so
 * every permutation emits identically whether or not the type is canonical. Kotlin/Native and
 * Kotlin/Wasm preserve insertion order, which *is* the fold order, so only they see the defect.
 * When you bind a new CRDT to this suite, **verify on `macosArm64Test` or `wasmJsTest`** — a green
 * `jvmTest` is not evidence of canonicality. (Types whose merge yields a `LinkedHashSet`, e.g. via
 * `Set.plus`, are insertion-ordered on the JVM too and do fail there — so a JVM red is meaningful
 * even though a JVM green is not.)
 */
@OptIn(ExperimentalSerializationApi::class, ExperimentalStdlibApi::class)
public class CrdtConvergenceHarness<S : Quilted<S>>(
    public val initial: S,
    public val gen: OperationGenerator<S>,
    public val serializer: KSerializer<S>,
    public val replicaCount: Int = 3,
    public val opsPerReplica: Int = 8,
) {
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
}
