@file:Suppress("ForbiddenImport") // deliberate local-only wall-clock benchmark: it measures REAL elapsed time (System.nanoTime) of the interpreter, so it uses Dispatchers.IO and runBlocking, never a virtual-time dispatcher — there are no delays to drive, only genuine CPU work to time. Mirrors the ChicoryWasmRuntime real-IO exception and the D4-2 optimizer test.

package us.tractat.kuilt.warp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

/**
 * D4-4 — the **local-only** wall-clock benchmark that records the D4 go/no-go numbers.
 *
 * This is deliberately a `main()` (an unannotated top-level function), **not** a `@Test`:
 * benchmarks flake on shared CI, so per the D4 design (`docs/superpowers/specs/
 * 2026-06-26-warp-d4-wasm-opt-compiler-node-design.md`, "Testing posture — Local-only") the
 * speedup proof is a recorded local number, never a CI gate. `./gradlew build` runs the JUnit
 * `jvmTest` task (which executes only `@Test` methods, so this is never run) but never invokes
 * a `JavaExec`; run it by hand with:
 *
 * ```
 * ./gradlew :kuilt-warp-compiler:benchmark            # default 2,000,000 iterations
 * ./gradlew :kuilt-warp-compiler:benchmark -Piters=5000000
 * ```
 *
 * The deterministic CI test [BinaryenWasmOptimizerTest] pins the *cause* (`wasm-opt` produces
 * a valid, smaller, ABI-preserving, distinct-hash module). This confirms the *effect*: the
 * `-O3` module runs measurably faster through the real [ChicoryWasmRuntime] interpreter,
 * because `wasm-opt` strips the [BLOATED_KERNEL]'s redundant per-iteration instructions.
 *
 * Methodology: warmup iterations (to reach the interpreter's steady state), then median-of-N
 * measured runs per variant, reporting the raw÷optimized ratio. Correctness is **asserted**
 * (every variant must compute the identical accumulator); the timing is **reported**, never
 * asserted — no flaky wall-clock gate.
 *
 * `wasm-opt` is real subprocess I/O and the interpreter burns real CPU — the sanctioned
 * real-threading exception, so [Dispatchers.IO] + [runBlocking], not a virtual clock.
 */
public object BloatedKernelBenchmark {

    public const val DEFAULT_ITERATIONS: Int = 2_000_000
    private const val WARMUP_RUNS = 8
    private const val MEASURED_RUNS = 21

    // The bloated kernel loops hard; give it far more than the 1 s production default so the
    // raw (slow) variant never trips the sandbox timeout. This benchmark isn't testing the guard.
    private val BENCH_CONFIG = WasmSandboxConfig(executionTimeout = 120.seconds)

    @Suppress("ForbiddenMethodCall") // deliberate: real interpreter CPU work; Dispatchers.IO waits on the guest thread (see ChicoryWasmRuntime's real-IO note).
    public fun run(iterations: Int): Unit = runBlocking(Dispatchers.IO) {
        val optimizer = BinaryenWasmOptimizer(Dispatchers.IO)
        val input = littleEndian(iterations)

        // Build every variant up front (raw + the three real wasm-opt levels).
        val variants: List<Variant> = buildList {
            add(Variant("raw (unoptimized)", BLOATED_KERNEL))
            for (level in listOf(OptLevel.O2, OptLevel.O3, OptLevel.Oz)) {
                add(Variant("wasm-opt $level", optimizer.optimize(BLOATED_KERNEL, level)))
            }
        }

        println("== warp D4-4 bloated-kernel benchmark ==")
        println("iterations per run: $iterations   warmup: $WARMUP_RUNS   measured (median of): $MEASURED_RUNS")
        println("JDK ${System.getProperty("java.version")} · ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        println()

        var expected: ByteArray? = null
        val rows = mutableListOf<Row>()
        for (variant in variants) {
            val (medianNanos, result) = ChicoryWasmRuntime(BENCH_CONFIG).use { runtime ->
                val op = runtime.load(variant.wasm)
                repeat(WARMUP_RUNS) { op.invoke(input) }
                val samples = LongArray(MEASURED_RUNS)
                lateinit var out: ByteArray
                for (i in 0 until MEASURED_RUNS) {
                    val t0 = System.nanoTime()
                    out = op.invoke(input)
                    samples[i] = System.nanoTime() - t0
                }
                median(samples) to out
            }
            // Correctness: every variant must compute the identical accumulator as the raw kernel.
            val ref = expected
            if (ref == null) {
                expected = result
            } else {
                check(result.contentEquals(ref)) {
                    "${variant.name} produced a different result (${result.toList()}) than raw (${ref.toList()}) " +
                        "— optimization must be semantics-preserving"
                }
            }
            rows += Row(variant.name, variant.wasm.size, medianNanos)
        }

        val rawMedian = rows.first().medianNanos
        val rawBytes = rows.first().bytes
        println("%-22s %10s %12s %10s %9s".format("variant", "bytes", "median (ms)", "vs raw", "smaller"))
        println("-".repeat(66))
        for (row in rows) {
            val ratio = rawMedian.toDouble() / row.medianNanos
            val speedup = if (row.medianNanos == rawMedian) "—" else "%.2fx".format(ratio)
            val sizePct = 100.0 * (1.0 - row.bytes.toDouble() / rawBytes)
            val sizeStr = if (row.bytes == rawBytes) "—" else "%.0f%%".format(sizePct)
            println(
                "%-22s %10d %12.2f %10s %9s".format(
                    row.name, row.bytes, row.medianNanos / 1_000_000.0, speedup, sizeStr,
                ),
            )
        }
        println()
        val best = rows.drop(1).maxByOrNull { rawMedian.toDouble() / it.medianNanos }
        if (best != null) {
            val ratio = rawMedian.toDouble() / best.medianNanos
            val pctFaster = 100.0 * (1.0 - best.medianNanos.toDouble() / rawMedian)
            println(
                "best: %s — %.2fx faster (%.0f%% wall-clock reduction), %d → %d bytes".format(
                    best.name, ratio, pctFaster, rawBytes, best.bytes,
                ),
            )
            println("GO bar (>=30-50% faster on the JVM interpreter): ${if (pctFaster >= 30.0) "MET" else "NOT MET"}")
        }
        println("(correctness: every variant computed the identical accumulator — asserted.)")
    }

    private data class Variant(val name: String, val wasm: ByteArray)

    private data class Row(val name: String, val bytes: Int, val medianNanos: Long)

    /** Encode [value] as 4 little-endian bytes — the kernel's trip-count input. */
    private fun littleEndian(value: Int): ByteArray = ByteArray(4) { i -> (value ushr (i * 8)).toByte() }

    private fun median(samples: LongArray): Long {
        val sorted = samples.sortedArray()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /**
     * The deliberately-bloated benchmark kernel, compiled from
     * `kuilt-warp-compiler/wat/bloated-kernel.wat` with `wat2wasm` (which does NO optimization,
     * so every redundant instruction survives). `warp_run` reads a 4-byte little-endian trip
     * count from linear memory and runs an LCG hot loop that many times, padded per iteration
     * with identity ops (`x+0`, `x*1`, `x^0`, …), redundant local shuffles, a dead computation,
     * and a loop-invariant recomputed every pass. `wasm-opt -O3` removes all of it, computing the
     * same accumulator with far fewer instructions per iteration — directly faster on Chicory.
     * Exposes the warp ABI (`warp_alloc`/`warp_run`) so it loads via [ChicoryWasmRuntime].
     */
    public val BLOATED_KERNEL: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, 0x01, 0x0c, 0x02, 0x60, 0x01, 0x7f, 0x01, 0x7f,
        0x60, 0x02, 0x7f, 0x7f, 0x01, 0x7e, 0x03, 0x03, 0x02, 0x00, 0x01, 0x05, 0x04, 0x01, 0x01, 0x01,
        0x10, 0x07, 0x22, 0x03, 0x06, 0x6d, 0x65, 0x6d, 0x6f, 0x72, 0x79, 0x02, 0x00, 0x0a, 0x77, 0x61,
        0x72, 0x70, 0x5f, 0x61, 0x6c, 0x6c, 0x6f, 0x63, 0x00, 0x00, 0x08, 0x77, 0x61, 0x72, 0x70, 0x5f,
        0x72, 0x75, 0x6e, 0x00, 0x01, 0x0a, 0xbf.toByte(), 0x01, 0x02, 0x05, 0x00, 0x41, 0x80.toByte(), 0x08, 0x0b, 0xb6.toByte(),
        0x01, 0x01, 0x07, 0x7f, 0x20, 0x00, 0x28, 0x02, 0x00, 0x21, 0x02, 0x41, 0xf8.toByte(), 0xac.toByte(), 0xd1.toByte(), 0x91.toByte(),
        0x01, 0x21, 0x04, 0x41, 0x00, 0x21, 0x03, 0x02, 0x40, 0x03, 0x40, 0x20, 0x03, 0x20, 0x02, 0x4f,
        0x0d, 0x01, 0x20, 0x01, 0x41, 0xb1.toByte(), 0xf3.toByte(), 0xdd.toByte(), 0xf1.toByte(), 0x79, 0x6c, 0x41, 0xb7.toByte(), 0xbc.toByte(), 0x02, 0x6a,
        0x21, 0x08, 0x20, 0x04, 0x41, 0x8d.toByte(), 0xcc.toByte(), 0xe5.toByte(), 0x00, 0x6c, 0x41, 0xdf.toByte(), 0xe6.toByte(), 0xbb.toByte(), 0xe3.toByte(), 0x03,
        0x6a, 0x21, 0x04, 0x20, 0x04, 0x20, 0x08, 0x73, 0x21, 0x04, 0x20, 0x04, 0x20, 0x03, 0x6a, 0x21,
        0x04, 0x20, 0x04, 0x41, 0x00, 0x6a, 0x21, 0x04, 0x20, 0x04, 0x41, 0x01, 0x6c, 0x21, 0x04, 0x20,
        0x04, 0x41, 0x00, 0x73, 0x21, 0x04, 0x20, 0x04, 0x41, 0x00, 0x72, 0x21, 0x04, 0x20, 0x04, 0x41,
        0x00, 0x6b, 0x21, 0x04, 0x20, 0x04, 0x41, 0x00, 0x74, 0x21, 0x04, 0x20, 0x04, 0x21, 0x05, 0x20,
        0x05, 0x21, 0x06, 0x20, 0x06, 0x21, 0x04, 0x20, 0x03, 0x41, 0x07, 0x6c, 0x41, 0x03, 0x6a, 0x21,
        0x07, 0x20, 0x07, 0x41, 0x02, 0x6c, 0x21, 0x07, 0x20, 0x03, 0x41, 0x01, 0x6a, 0x21, 0x03, 0x0c,
        0x00, 0x0b, 0x0b, 0x41, 0x80.toByte(), 0x10, 0x20, 0x04, 0x36, 0x02, 0x00, 0x41, 0x80.toByte(), 0x10, 0xad.toByte(), 0x42,
        0x20, 0x86.toByte(), 0x41, 0x04, 0xad.toByte(), 0x84.toByte(), 0x0b,
    )
}

/**
 * Local-only benchmark entrypoint (see [BloatedKernelBenchmark]). Not a `@Test`; run via the
 * `:kuilt-warp-compiler:benchmark` Gradle task. Optional first arg = iteration count.
 */
public fun main(args: Array<String>) {
    val iterations = args.firstOrNull()?.toIntOrNull() ?: BloatedKernelBenchmark.DEFAULT_ITERATIONS
    BloatedKernelBenchmark.run(iterations)
}
