# F2 — model-as-wasm-kernel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the local training step (one GD step of linear regression) as a real, ABI-conformant wasm kernel that is content-addressed, fetched on demand, run through the C5b `WasmRuntime`, and whose output feeds `FedAvg` (F1).

**Architecture:** A `commonMain` codec marshals `(weights, batch) → kernel input bytes` and `kernel output bytes → (sampleCount, weights)`. A pure-Kotlin `ReferenceTrainer` implements the identical GD arithmetic and is the kernel's correctness oracle. The wasm kernel (`fedavg_train.wat`/`.wasm`) exports the warp ABI (`memory`/`warp_alloc`/`warp_run`) and is proven bit-for-bit equal to the reference on the JVM. A jvmTest fetches the kernel from a `Creel`, runs it via `ChicoryWasmRuntime`, decodes the update, folds it into `FedAvg`, and asserts N peers converge.

**Tech Stack:** Kotlin Multiplatform, kotlinx-serialization (already in `:kuilt-warp`), Chicory (C5b, `jvmMain`), `wat2wasm` (build-time tool, `/opt/homebrew/bin/wat2wasm`), kotlin.test.

## Global Constraints

- Module: **`:kuilt-warp`**. New types follow existing package `us.tractat.kuilt.warp`.
- **`explicitApi()` is enforced** — every public declaration gets an explicit `public`.
- Test methods: **no `test` prefix** (the `@Test` annotation suffices); multi-assert tests use **`assertAll()`** (from `us.tractat.kuilt.test`).
- **Codec is little-endian, IEEE-754 f64** — bit-deterministic across platforms (matches `FedAvg`'s bit-for-bit requirement).
- The wasm kernel handles **dimension D = 2** (one feature + bias). Larger D is a documented follow-up. The codec itself is D-generic.
- Before any auto-merge: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem` then `./gradlew :kuilt-warp:build detektAll --rerun-tasks` (Android + Native variants compile `commonMain`/`commonTest`; confirm tasks are `EXECUTED`, not `FROM-CACHE`).
- Fast inner loop: `./gradlew :kuilt-warp:jvmTest --tests "*<Name>*"`.
- Spec: `docs/superpowers/specs/2026-06-26-warp-f2-model-as-wasm-kernel-design.md`. Issue: #939. Part of epic #856.

## Key existing APIs (verbatim, from merged C5b / F1)

```kotlin
// commonMain — F1 (FedAvg.kt)
FedAvg.ZERO: FedAvg
FedAvg.contribution(peer: ReplicaId, sampleCount: Long, localWeights: List<Double>, epoch: Long = 1L): FedAvg
fun FedAvg.piece(other: FedAvg): FedAvg
val FedAvg.weights: List<Double>        // throws IllegalStateException if no contributions

// commonMain — C5b (WasmRuntime.kt)
public interface WasmRuntime { public fun load(bytes: ByteArray): Op }
public fun interface Op { public suspend fun invoke(args: ByteArray): ByteArray }

// jvmMain — C5b (ChicoryWasmRuntime.kt)
public class ChicoryWasmRuntime(public val config: WasmSandboxConfig = WasmSandboxConfig()) : WasmRuntime, AutoCloseable

// commonMain — C4/C5 (Creel.kt) — content-addressed byte store
Creel().put(bytes: ByteArray): BobbinHash       // returns the SHA-256 content address
Creel().get(hash: BobbinHash): ByteArray?       // null = not loaded

// JVM resource-load idiom (from ChicoryWasmRuntimeTest)
val bytes = checkNotNull(X::class.java.getResourceAsStream("/us/tractat/kuilt/warp/file.wasm")).readBytes()
```

## File Structure

- `kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/TrainingUpdate.kt` — the decoded result type + `toContribution`.
- `kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/ReferenceTrainer.kt` — pure-Kotlin GD step (the oracle).
- `kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/FedAvgKernelCodec.kt` — encode input / decode output (the wire layout).
- `kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/ReferenceTrainerTest.kt`
- `kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/FedAvgKernelCodecTest.kt`
- `kuilt-warp/src/jvmTest/resources/us/tractat/kuilt/warp/fedavg_train.wat` (+ generated `.wasm`)
- `kuilt-warp/src/jvmTest/kotlin/us/tractat/kuilt/warp/FedAvgKernelEquivalenceTest.kt`
- `kuilt-warp/src/jvmTest/kotlin/us/tractat/kuilt/warp/FedAvgFetchAndTrainTest.kt`
- `kuilt-warp/src/commonSamples/kotlin/us/tractat/kuilt/warp/FedAvgKernelSample.kt`

## Wire layout (single source of truth)

All little-endian. Input pointer is always 0 (`warp_alloc` returns 0).

**Input** (written by `FedAvgKernelCodec.encodeInput`):
| offset | type | meaning |
|--------|------|---------|
| 0 | u32 | magic `0x46415631` |
| 4 | u32 | dim D (= 2) |
| 8 | f64 | learnRate η |
| 16 | u32 | count N |
| 20 | u32 | pad (0) |
| 24 | f64 | w0 (feature weight) |
| 32 | f64 | w1 (bias) |
| 40 + 16·i | f64, f64 | example i: `x_i`, `y_i` |

**Output** (result region base = 65536; read by `decodeOutput`):
| offset | type | meaning |
|--------|------|---------|
| +0 | u32 | magic `0x46415631` |
| +4 | u32 | dim D (= 2) |
| +8 | u64 | count N |
| +16 | f64 | w0' |
| +24 | f64 | w1' |

`warp_run` returns packed `(65536 << 32) | 32`.

---

### Task 1: `TrainingUpdate` + `ReferenceTrainer` (the oracle)

**Files:**
- Create: `kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/TrainingUpdate.kt`
- Create: `kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/ReferenceTrainer.kt`
- Test: `kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/ReferenceTrainerTest.kt`

**Interfaces:**
- Produces: `TrainingUpdate(sampleCount: Long, weights: List<Double>)` with `fun toContribution(peer: ReplicaId, epoch: Long = 1L): FedAvg`. `ReferenceTrainer.step(weights: List<Double>, examples: List<Pair<Double, Double>>, learnRate: Double): List<Double>`.

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.warp

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

class ReferenceTrainerTest {

    @Test
    fun `one GD step matches hand-computed weights`() {
        // w=[w0=1.0, b=0.0], examples (x,y): (1,2),(2,3) ; lr=0.1
        // i=0: pred=1*1+0=1, err=1-2=-1 ; i=1: pred=1*2+0=2, err=2-3=-1
        // gw0=(-1*1)+(-1*2)=-3 ; gb=(-1)+(-1)=-2 ; scale=2/2=1.0
        // w0'=1 - 0.1*1.0*(-3)=1.3 ; b'=0 - 0.1*1.0*(-2)=0.2
        val out = ReferenceTrainer.step(
            weights = listOf(1.0, 0.0),
            examples = listOf(1.0 to 2.0, 2.0 to 3.0),
            learnRate = 0.1,
        )
        assertAll(
            { assertEquals(1.3, out[0], absoluteTolerance = 1e-12) },
            { assertEquals(0.2, out[1], absoluteTolerance = 1e-12) },
        )
    }

    @Test
    fun `update converts to a FedAvg contribution`() {
        val update = TrainingUpdate(sampleCount = 2L, weights = listOf(1.3, 0.2))
        val merged = FedAvg.ZERO.piece(update.toContribution(ReplicaId("p")))
        assertAll(
            { assertEquals(1.3, merged.weights[0], absoluteTolerance = 1e-12) },
            { assertEquals(0.2, merged.weights[1], absoluteTolerance = 1e-12) },
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-warp:compileTestKotlinJvm`
Expected: FAIL — `ReferenceTrainer` / `TrainingUpdate` unresolved.

- [ ] **Step 3: Write minimal implementation**

`TrainingUpdate.kt`:
```kotlin
package us.tractat.kuilt.warp

import us.tractat.kuilt.crdt.ReplicaId

/**
 * One peer's decoded training result: how many examples it trained on and the updated weight
 * vector. Bridges a kernel run (or [ReferenceTrainer.step]) to [FedAvg].
 */
public data class TrainingUpdate(
    public val sampleCount: Long,
    public val weights: List<Double>,
) {
    /** This update as a single-peer [FedAvg] contribution for [peer] at [epoch]. */
    public fun toContribution(peer: ReplicaId, epoch: Long = 1L): FedAvg =
        FedAvg.contribution(peer, sampleCount, weights, epoch)
}
```

`ReferenceTrainer.kt` (the arithmetic MUST match the kernel operation order exactly — see Task 4):
```kotlin
package us.tractat.kuilt.warp

/**
 * The local training step in pure Kotlin: one gradient-descent step of linear regression
 * (`y ≈ w·x + b`) over a batch, mean-squared-error loss. This is the **oracle** the wasm kernel
 * (`fedavg_train.wasm`) is proven bit-for-bit equal to (see `FedAvgKernelEquivalenceTest`).
 *
 * Dimension is fixed at D = 2 (one feature + bias) to match the v1 kernel; [weights] is
 * `[featureWeight, bias]`.
 */
public object ReferenceTrainer {
    /**
     * One GD step. [examples] are `(x, y)` pairs. Returns the updated `[w0', b']`.
     *
     * Operation order is load-bearing: it is replicated verbatim in `fedavg_train.wat`, so the
     * two produce bit-identical f64 results on the JVM.
     */
    public fun step(
        weights: List<Double>,
        examples: List<Pair<Double, Double>>,
        learnRate: Double,
    ): List<Double> {
        val w0 = weights[0]
        val b = weights[1]
        var gradW0 = 0.0
        var gradB = 0.0
        for ((x, y) in examples) {
            val err = w0 * x + b - y
            gradW0 += err * x
            gradB += err
        }
        val scale = 2.0 / examples.size.toDouble()
        return listOf(
            w0 - learnRate * (scale * gradW0),
            b - learnRate * (scale * gradB),
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-warp:jvmTest --tests "*ReferenceTrainerTest*"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/TrainingUpdate.kt \
        kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/ReferenceTrainer.kt \
        kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/ReferenceTrainerTest.kt
git commit -m "feat(kuilt-warp): F2 reference trainer + TrainingUpdate (GD-step oracle)"
```

---

### Task 2: `FedAvgKernelCodec` (the wire layout)

**Files:**
- Create: `kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/FedAvgKernelCodec.kt`
- Test: `kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/FedAvgKernelCodecTest.kt`

**Interfaces:**
- Consumes: `TrainingUpdate` (Task 1).
- Produces: `FedAvgKernelCodec.encodeInput(weights: List<Double>, examples: List<Pair<Double,Double>>, learnRate: Double): ByteArray`; `FedAvgKernelCodec.decodeOutput(bytes: ByteArray): TrainingUpdate`; `const val RESULT_LEN = 32`.

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.warp

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FedAvgKernelCodecTest {

    @Test
    fun `encodeInput produces the documented layout`() {
        val bytes = FedAvgKernelCodec.encodeInput(
            weights = listOf(1.0, 0.0),
            examples = listOf(1.0 to 2.0),
            learnRate = 0.1,
        )
        // header(40) + 1 example(16) = 56 bytes
        assertAll(
            { assertEquals(56, bytes.size) },
            { assertEquals(0x46415631, readU32LE(bytes, 0)) },     // magic
            { assertEquals(2, readU32LE(bytes, 4)) },              // dim
            { assertEquals(0.1, readF64LE(bytes, 8), 1e-12) },     // learnRate
            { assertEquals(1, readU32LE(bytes, 16)) },             // count
            { assertEquals(1.0, readF64LE(bytes, 24), 1e-12) },    // w0
            { assertEquals(0.0, readF64LE(bytes, 32), 1e-12) },    // w1
            { assertEquals(1.0, readF64LE(bytes, 40), 1e-12) },    // x_0
            { assertEquals(2.0, readF64LE(bytes, 48), 1e-12) },    // y_0
        )
    }

    @Test
    fun `decodeOutput round-trips an encoded output`() {
        val out = FedAvgKernelCodec.encodeOutputForTest(sampleCount = 7L, weights = listOf(1.3, 0.2))
        val update = FedAvgKernelCodec.decodeOutput(out)
        assertAll(
            { assertEquals(7L, update.sampleCount) },
            { assertEquals(1.3, update.weights[0], 1e-12) },
            { assertEquals(0.2, update.weights[1], 1e-12) },
        )
    }

    @Test
    fun `decodeOutput rejects a bad magic`() {
        val out = FedAvgKernelCodec.encodeOutputForTest(1L, listOf(0.0, 0.0)).copyOf()
        out[0] = 0  // corrupt magic
        assertFailsWith<IllegalArgumentException> { FedAvgKernelCodec.decodeOutput(out) }
    }

    @Test
    fun `decodeOutput rejects truncated bytes`() {
        assertFailsWith<IllegalArgumentException> { FedAvgKernelCodec.decodeOutput(ByteArray(8)) }
    }

    // Little-endian readers local to the test.
    private fun readU32LE(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun readF64LE(b: ByteArray, o: Int): Double {
        var bits = 0L
        for (i in 7 downTo 0) bits = (bits shl 8) or (b[o + i].toLong() and 0xFF)
        return Double.fromBits(bits)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-warp:compileTestKotlinJvm`
Expected: FAIL — `FedAvgKernelCodec` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package us.tractat.kuilt.warp

/**
 * Marshals the FedAvg training kernel's linear-memory ABI payloads (see the wire layout in the F2
 * plan/spec). All integers and IEEE-754 f64 values are little-endian, so the bytes are
 * bit-deterministic across platforms — matching [FedAvg]'s reproducibility requirement.
 *
 * Dimension is fixed at D = 2 (one feature + bias) for the v1 kernel.
 */
public object FedAvgKernelCodec {

    private const val MAGIC: Int = 0x46415631
    private const val DIM: Int = 2
    private const val HEADER_BYTES: Int = 40
    private const val EXAMPLE_BYTES: Int = 16

    /** Length in bytes of the kernel's output region. */
    public const val RESULT_LEN: Int = 32

    /** Encodes `(weights, examples, learnRate)` into the kernel input layout. */
    public fun encodeInput(
        weights: List<Double>,
        examples: List<Pair<Double, Double>>,
        learnRate: Double,
    ): ByteArray {
        require(weights.size == DIM) { "v1 kernel requires D=$DIM weights, got ${weights.size}" }
        val out = ByteArray(HEADER_BYTES + examples.size * EXAMPLE_BYTES)
        putU32(out, 0, MAGIC)
        putU32(out, 4, DIM)
        putF64(out, 8, learnRate)
        putU32(out, 16, examples.size)
        putU32(out, 20, 0)
        putF64(out, 24, weights[0])
        putF64(out, 32, weights[1])
        var off = HEADER_BYTES
        for ((x, y) in examples) {
            putF64(out, off, x); putF64(out, off + 8, y); off += EXAMPLE_BYTES
        }
        return out
    }

    /** Decodes the kernel output region into a [TrainingUpdate]; fails loud on a bad shape. */
    public fun decodeOutput(bytes: ByteArray): TrainingUpdate {
        require(bytes.size >= RESULT_LEN) { "output too short: ${bytes.size} < $RESULT_LEN" }
        require(getU32(bytes, 0) == MAGIC) { "bad output magic" }
        val dim = getU32(bytes, 4)
        require(dim == DIM) { "unexpected output dim $dim" }
        val count = getU64(bytes, 8)
        return TrainingUpdate(count, listOf(getF64(bytes, 16), getF64(bytes, 24)))
    }

    /** Test-only: builds an output region matching the kernel's, for round-trip tests. */
    public fun encodeOutputForTest(sampleCount: Long, weights: List<Double>): ByteArray {
        val out = ByteArray(RESULT_LEN)
        putU32(out, 0, MAGIC); putU32(out, 4, DIM); putU64(out, 8, sampleCount)
        putF64(out, 16, weights[0]); putF64(out, 24, weights[1])
        return out
    }

    private fun putU32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte()
        b[o + 2] = (v ushr 16).toByte(); b[o + 3] = (v ushr 24).toByte()
    }
    private fun putU64(b: ByteArray, o: Int, v: Long) { for (i in 0 until 8) b[o + i] = (v ushr (8 * i)).toByte() }
    private fun putF64(b: ByteArray, o: Int, v: Double) = putU64(b, o, v.toRawBits())
    private fun getU32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    private fun getU64(b: ByteArray, o: Int): Long {
        var v = 0L; for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF); return v
    }
    private fun getF64(b: ByteArray, o: Int): Double = Double.fromBits(getU64(b, o))
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-warp:jvmTest --tests "*FedAvgKernelCodecTest*"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/FedAvgKernelCodec.kt \
        kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/FedAvgKernelCodecTest.kt
git commit -m "feat(kuilt-warp): F2 kernel codec — little-endian f64 input/output marshalling"
```

---

### Task 3: FedAvg multi-peer wiring test (runtime-free convergence)

**Files:**
- Test: `kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/FedAvgKernelCodecTest.kt` (add a method) — or a new `FedAvgWiringTest.kt`. Use a new file to keep responsibilities focused.
- Create: `kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/FedAvgWiringTest.kt`

**Interfaces:**
- Consumes: `ReferenceTrainer`, `TrainingUpdate`, `FedAvg` (Tasks 1–2).

This proves the *whole F2 thesis minus wasm*: peers train locally (reference), contribute, and converge — so the FedAvg-facing correctness is pinned independent of the runtime.

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.warp

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FedAvgWiringTest {

    /**
     * Three peers, each with a different local batch drawn from the same true line y = 2x + 1,
     * each run one reference GD step from a shared start, contribute, and the merged FedAvg moves
     * toward the true weights. Order-independent (CRDT).
     */
    @Test
    fun `three peers' local steps merge toward the true line`() {
        val truth = { x: Double -> 2.0 * x + 1.0 }
        val batches = listOf(
            listOf(0.0, 1.0, 2.0),
            listOf(3.0, 4.0, 5.0),
            listOf(6.0, 7.0, 8.0),
        ).map { xs -> xs.map { it to truth(it) } }

        val start = listOf(0.0, 0.0)   // w0, bias
        val lr = 0.01

        var model = start
        repeat(50) {
            val merged = batches.foldIndexed(FedAvg.ZERO) { i, acc, batch ->
                val updated = ReferenceTrainer.step(model, batch, lr)
                acc.piece(TrainingUpdate(batch.size.toLong(), updated).toContribution(ReplicaId("p$i")))
            }
            model = merged.weights
        }

        assertAll(
            { assertEquals(2.0, model[0], absoluteTolerance = 0.05) },   // slope → 2
            { assertEquals(1.0, model[1], absoluteTolerance = 0.05) },   // bias → 1
        )
    }

    @Test
    fun `merge is order-independent`() {
        val a = TrainingUpdate(2L, listOf(1.0, 4.0)).toContribution(ReplicaId("a"))
        val b = TrainingUpdate(3L, listOf(3.0, 2.0)).toContribution(ReplicaId("b"))
        assertTrue(FedAvg.ZERO.piece(a).piece(b) == FedAvg.ZERO.piece(b).piece(a))
    }
}
```

- [ ] **Step 2: Run test to verify it fails (red), then passes**

Run: `./gradlew :kuilt-warp:jvmTest --tests "*FedAvgWiringTest*"`
Expected: PASS once Tasks 1–2 are in (this task adds no production code — it's a characterization test of the wiring). If the convergence assertion fails, tune `repeat` count / `lr` so it converges (50 steps at lr=0.01 over these batches converges well within 0.05).

- [ ] **Step 3: Commit**

```bash
git add kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/FedAvgWiringTest.kt
git commit -m "test(kuilt-warp): F2 — local reference steps merge toward the true line via FedAvg"
```

---

### Task 4: The training kernel (`fedavg_train.wat` → `.wasm`)

**Files:**
- Create: `kuilt-warp/src/jvmTest/resources/us/tractat/kuilt/warp/fedavg_train.wat`
- Generate: `kuilt-warp/src/jvmTest/resources/us/tractat/kuilt/warp/fedavg_train.wasm`
- Test: `kuilt-warp/src/jvmTest/kotlin/us/tractat/kuilt/warp/FedAvgKernelEquivalenceTest.kt` (Task 5 fills the asserts; Task 4 adds a smoke test)

**Interfaces:**
- Produces: a wasm module exporting `memory`, `warp_alloc`, `warp_run` per the wire layout. Consumed via `ChicoryWasmRuntime().load(bytes)`.

- [ ] **Step 1: Write the kernel `.wat`** (operation order mirrors `ReferenceTrainer.step` exactly)

```wat
;; fedavg_train.wat — one GD step of linear regression (D=2: one feature + bias)
;; over the warp linear-memory ABI (memory, warp_alloc, warp_run).
;;
;; Input pointer is always 0 (warp_alloc returns 0). Layout (little-endian):
;;   [0]  u32 magic 0x46415631   [4]  u32 dim=2     [8]  f64 learnRate
;;   [16] u32 count N            [20] u32 pad       [24] f64 w0   [32] f64 w1(bias)
;;   [40 + 16*i] f64 x_i, f64 y_i
;; Output region base = 65536 (page 1):
;;   [+0] u32 magic  [+4] u32 dim=2  [+8] u64 N  [+16] f64 w0'  [+24] f64 w1'
;; warp_run returns packed (65536 << 32) | 32.
;;
;; To regenerate fedavg_train.wasm:
;;   /opt/homebrew/bin/wat2wasm fedavg_train.wat -o fedavg_train.wasm
(module
  (memory (export "memory") 2)   ;; 2 pages = 128 KiB; input [0,65536), result [65536,65568)

  (func $warp_alloc (export "warp_alloc") (param $len i32) (result i32)
    i32.const 0)

  (func $warp_run (export "warp_run") (param $ptr i32) (param $len i32) (result i64)
    (local $n i32) (local $i i32) (local $off i32)
    (local $w0 f64) (local $w1 f64) (local $eta f64)
    (local $gw0 f64) (local $gb f64)
    (local $x f64) (local $y f64) (local $err f64) (local $scale f64)

    (local.set $eta (f64.load offset=8  (local.get $ptr)))
    (local.set $n   (i32.load offset=16 (local.get $ptr)))
    (local.set $w0  (f64.load offset=24 (local.get $ptr)))
    (local.set $w1  (f64.load offset=32 (local.get $ptr)))
    (local.set $gw0 (f64.const 0))
    (local.set $gb  (f64.const 0))
    (local.set $i   (i32.const 0))
    (local.set $off (i32.add (local.get $ptr) (i32.const 40)))

    (block $break
      (loop $loop
        (br_if $break (i32.ge_u (local.get $i) (local.get $n)))
        (local.set $x (f64.load        (local.get $off)))
        (local.set $y (f64.load offset=8 (local.get $off)))
        ;; err = w0*x + w1 - y
        (local.set $err
          (f64.sub
            (f64.add (f64.mul (local.get $w0) (local.get $x)) (local.get $w1))
            (local.get $y)))
        ;; gw0 += err*x ; gb += err
        (local.set $gw0 (f64.add (local.get $gw0) (f64.mul (local.get $err) (local.get $x))))
        (local.set $gb  (f64.add (local.get $gb)  (local.get $err)))
        (local.set $off (i32.add (local.get $off) (i32.const 16)))
        (local.set $i   (i32.add (local.get $i)   (i32.const 1)))
        (br $loop)))

    ;; scale = 2.0 / n
    (local.set $scale (f64.div (f64.const 2) (f64.convert_i32_u (local.get $n))))
    ;; w0' = w0 - eta*(scale*gw0) ; w1' = w1 - eta*(scale*gb)
    (local.set $w0 (f64.sub (local.get $w0)
      (f64.mul (local.get $eta) (f64.mul (local.get $scale) (local.get $gw0)))))
    (local.set $w1 (f64.sub (local.get $w1)
      (f64.mul (local.get $eta) (f64.mul (local.get $scale) (local.get $gb)))))

    ;; write output at 65536
    (i32.store offset=65536 (i32.const 0) (i32.const 0x46415631))
    (i32.store offset=65540 (i32.const 0) (i32.const 2))
    (i64.store offset=65544 (i32.const 0) (i64.extend_i32_u (local.get $n)))
    (f64.store offset=65552 (i32.const 0) (local.get $w0))
    (f64.store offset=65560 (i32.const 0) (local.get $w1))

    (i64.or
      (i64.shl (i64.const 65536) (i64.const 32))
      (i64.const 32))))
```

- [ ] **Step 2: Compile to `.wasm`**

Run: `/opt/homebrew/bin/wat2wasm kuilt-warp/src/jvmTest/resources/us/tractat/kuilt/warp/fedavg_train.wat -o kuilt-warp/src/jvmTest/resources/us/tractat/kuilt/warp/fedavg_train.wasm`
Expected: no output, `fedavg_train.wasm` created. (If `wat2wasm` is absent: `brew install wabt`.)

- [ ] **Step 3: Write the smoke test** (`FedAvgKernelEquivalenceTest.kt`, first method)

```kotlin
package us.tractat.kuilt.warp

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

class FedAvgKernelEquivalenceTest {

    private val kernel: ByteArray = checkNotNull(
        FedAvgKernelEquivalenceTest::class.java.getResourceAsStream(
            "/us/tractat/kuilt/warp/fedavg_train.wasm",
        ),
    ) { "fedavg_train.wasm not found on classpath" }.readBytes()

    @Test
    fun `kernel loads and produces a well-formed update`() = runTest {
        ChicoryWasmRuntime().use { rt ->
            val op = rt.load(kernel)
            val input = FedAvgKernelCodec.encodeInput(
                weights = listOf(1.0, 0.0),
                examples = listOf(1.0 to 2.0, 2.0 to 3.0),
                learnRate = 0.1,
            )
            val update = FedAvgKernelCodec.decodeOutput(op.invoke(input))
            assertAll(
                { assertEquals(2L, update.sampleCount) },
                { assertEquals(2, update.weights.size) },
            )
        }
    }
}
```

- [ ] **Step 4: Run**

Run: `./gradlew :kuilt-warp:jvmTest --tests "*FedAvgKernelEquivalenceTest*"`
Expected: PASS (1 test). If `load` throws `WasmLoadException`, re-check the `.wat` exports/memory; if `decodeOutput` throws, re-check the output offsets vs the codec layout.

- [ ] **Step 5: Commit**

```bash
git add kuilt-warp/src/jvmTest/resources/us/tractat/kuilt/warp/fedavg_train.wat \
        kuilt-warp/src/jvmTest/resources/us/tractat/kuilt/warp/fedavg_train.wasm \
        kuilt-warp/src/jvmTest/kotlin/us/tractat/kuilt/warp/FedAvgKernelEquivalenceTest.kt
git commit -m "feat(kuilt-warp): F2 training kernel — fedavg_train.wat/.wasm over the warp ABI"
```

---

### Task 5: Kernel ≡ reference equivalence (the correctness pin)

**Files:**
- Modify: `kuilt-warp/src/jvmTest/kotlin/us/tractat/kuilt/warp/FedAvgKernelEquivalenceTest.kt` (add the equivalence method)

**Interfaces:**
- Consumes: the kernel (Task 4), `ReferenceTrainer` + `FedAvgKernelCodec` (Tasks 1–2).

- [ ] **Step 1: Add the failing test**

```kotlin
    @Test
    fun `kernel output equals the reference trainer bit-for-bit across inputs`() = runTest {
        val cases = listOf(
            Triple(listOf(1.0, 0.0), listOf(1.0 to 2.0, 2.0 to 3.0), 0.1),
            Triple(listOf(0.0, 0.0), listOf(0.0 to 1.0, 1.0 to 3.0, 2.0 to 5.0), 0.05),
            Triple(listOf(-1.5, 2.0), listOf(3.0 to 7.0, 4.0 to 9.0, 5.0 to 11.0, 6.0 to 13.0), 0.001),
        )
        ChicoryWasmRuntime().use { rt ->
            val op = rt.load(kernel)
            for ((w, ex, lr) in cases) {
                val kernelOut = FedAvgKernelCodec.decodeOutput(op.invoke(FedAvgKernelCodec.encodeInput(w, ex, lr)))
                val ref = ReferenceTrainer.step(w, ex, lr)
                assertAll(
                    { assertEquals(ex.size.toLong(), kernelOut.sampleCount) },
                    // bit-for-bit: same IEEE-754 double, same operation order
                    { assertEquals(ref[0].toRawBits(), kernelOut.weights[0].toRawBits(), "w0' bits for $w/$lr") },
                    { assertEquals(ref[1].toRawBits(), kernelOut.weights[1].toRawBits(), "w1' bits for $w/$lr") },
                )
            }
        }
    }
```

- [ ] **Step 2: Run**

Run: `./gradlew :kuilt-warp:jvmTest --tests "*FedAvgKernelEquivalenceTest*"`
Expected: PASS (2 tests). If the bit-for-bit assert fails, the `.wat` operation order diverged from `ReferenceTrainer` — align them (the kernel is the thing to fix; the reference is the spec). If a genuine cross-impl f64 rounding difference is proven, relax to `absoluteTolerance = 1e-12` and note why in a comment — but exact is expected on the JVM.

- [ ] **Step 3: Commit**

```bash
git add kuilt-warp/src/jvmTest/kotlin/us/tractat/kuilt/warp/FedAvgKernelEquivalenceTest.kt
git commit -m "test(kuilt-warp): F2 — kernel proven bit-for-bit equal to the reference trainer"
```

---

### Task 6: Fetch-and-train → FedAvg convergence (end-to-end, runtime + Creel)

**Files:**
- Create: `kuilt-warp/src/jvmTest/kotlin/us/tractat/kuilt/warp/FedAvgFetchAndTrainTest.kt`

**Interfaces:**
- Consumes: `Creel`, `ChicoryWasmRuntime`, the kernel, codec, `FedAvg`.

This proves F2's headline: the kernel is **fetched as content-addressed bytes** (code mobility) and run on each peer's private batch, and the contributions converge. Scope note: this stays at the Creel+runtime altitude — the full WarpNode/Raft multi-node board convergence is **F4**.

- [ ] **Step 1: Write the test**

```kotlin
package us.tractat.kuilt.warp

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F2 end-to-end (Creel + runtime altitude): the training kernel is published once as a
 * content-addressed bobbin; each peer fetches the *same bytes*, runs them on its own private
 * batch via the sandboxed runtime, and the per-peer updates merge through [FedAvg] toward the
 * true line. The data never moves; only the model update does.
 */
class FedAvgFetchAndTrainTest {

    private val kernel: ByteArray = checkNotNull(
        FedAvgFetchAndTrainTest::class.java.getResourceAsStream(
            "/us/tractat/kuilt/warp/fedavg_train.wasm",
        ),
    ) { "fedavg_train.wasm not found on classpath" }.readBytes()

    @Test
    fun `peers fetch one kernel, train on private data, and converge`() = runTest {
        val truth = { x: Double -> 2.0 * x + 1.0 }
        val peerBatches = listOf(
            ReplicaId("alice") to listOf(0.0, 1.0, 2.0),
            ReplicaId("bob")   to listOf(3.0, 4.0, 5.0),
            ReplicaId("carol") to listOf(6.0, 7.0, 8.0),
        ).map { (id, xs) -> id to xs.map { it to truth(it) } }

        // Publisher puts the kernel into a Creel; the content address is shared with peers.
        val publisherCreel = Creel()
        val hash = publisherCreel.put(kernel)

        val lr = 0.01
        var model = listOf(0.0, 0.0)

        ChicoryWasmRuntime().use { rt ->
            repeat(50) {
                var merged = FedAvg.ZERO
                for ((peer, batch) in peerBatches) {
                    // Each peer fetches the kernel bytes by content address (code mobility).
                    val fetched = checkNotNull(publisherCreel.get(hash)) { "kernel bobbin must resolve" }
                    val op = rt.load(fetched)
                    val out = op.invoke(FedAvgKernelCodec.encodeInput(model, batch, lr))
                    val update = FedAvgKernelCodec.decodeOutput(out)
                    merged = merged.piece(update.toContribution(peer))
                }
                model = merged.weights
            }
        }

        assertAll(
            { assertEquals(2.0, model[0], absoluteTolerance = 0.05) },   // slope → 2
            { assertEquals(1.0, model[1], absoluteTolerance = 0.05) },   // bias → 1
        )
    }
}
```

- [ ] **Step 2: Run**

Run: `./gradlew :kuilt-warp:jvmTest --tests "*FedAvgFetchAndTrainTest*"`
Expected: PASS (1 test).

- [ ] **Step 3: Commit**

```bash
git add kuilt-warp/src/jvmTest/kotlin/us/tractat/kuilt/warp/FedAvgFetchAndTrainTest.kt
git commit -m "test(kuilt-warp): F2 — peers fetch one kernel, train privately, converge via FedAvg"
```

---

### Task 7: Docs, `@sample`, epic tick

**Files:**
- Create: `kuilt-warp/src/commonSamples/kotlin/us/tractat/kuilt/warp/FedAvgKernelSample.kt`
- Modify: `kuilt-warp/module.md` (add an F2 paragraph under the FedAvg/code-mobility section)
- Modify: KDoc `@sample` tag on `FedAvgKernelCodec` (point at the sample)

**Interfaces:**
- Consumes: codec + `ReferenceTrainer` (compiled as part of `commonTest` via the samples wiring).

- [ ] **Step 1: Write the sample**

```kotlin
package us.tractat.kuilt.warp

import us.tractat.kuilt.crdt.ReplicaId

/** @suppress sample for [FedAvgKernelCodec]. */
public fun sampleFedAvgKernelCodec() {
    // A peer encodes its local batch + the shared model, runs the kernel (omitted), and folds the
    // decoded update into FedAvg. Here we use the reference trainer in place of the wasm run.
    val model = listOf(0.0, 0.0)
    val batch = listOf(1.0 to 3.0, 2.0 to 5.0)            // y = 2x + 1
    val input = FedAvgKernelCodec.encodeInput(model, batch, learnRate = 0.05)
    require(input.isNotEmpty())

    val updatedWeights = ReferenceTrainer.step(model, batch, 0.05)
    val update = TrainingUpdate(batch.size.toLong(), updatedWeights)
    val contribution = update.toContribution(ReplicaId("alice"))
    require(contribution.weights.size == 2)
}
```

- [ ] **Step 2: Add the `@sample` tag** to `FedAvgKernelCodec`'s KDoc:

```kotlin
/**
 * ... existing KDoc ...
 *
 * @sample us.tractat.kuilt.warp.sampleFedAvgKernelCodec
 */
public object FedAvgKernelCodec {
```

- [ ] **Step 3: Add the module.md paragraph** (plain-language first, per repo docs convention):

```markdown
### Training without sharing data (F2)

Everyone's device helps train a shared model without any private data leaving it. The training
step itself travels as a tiny WebAssembly kernel: it is content-addressed in the `Creel`, fetched
on demand, and run in the sandbox. Each peer runs the same kernel on its own examples and shares
only the resulting weights, which merge through `FedAvg`. See `FedAvgKernelCodec` and
`ReferenceTrainer`.
```

- [ ] **Step 4: Verify samples compile**

Run: `./gradlew :kuilt-warp:compileTestKotlinJvm`
Expected: PASS (samples are compiled into `commonTest`).

- [ ] **Step 5: Tick F2 + commit**

```bash
git add kuilt-warp/src/commonSamples/kotlin/us/tractat/kuilt/warp/FedAvgKernelSample.kt \
        kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/FedAvgKernelCodec.kt \
        kuilt-warp/module.md
git commit -m "docs(kuilt-warp): F2 sample + module.md — training without sharing data"
```

Then tick the F2 box on epic #856 and close #939 via the PR body (`Closes #939`).

---

## Final verification (before auto-merge)

- [ ] `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem`
- [ ] `./gradlew :kuilt-warp:build detektAll --rerun-tasks` — all tasks `EXECUTED`, green. (Confirms Android + Native variants compile the new `commonMain`/`commonTest`; only the kernel tests are jvmTest.)
- [ ] PR body: `Closes #939`, "Part of #856" (non-closing on the epic). Stack on `main` (C5b already merged).

## Self-Review (completed by plan author)

- **Spec coverage:** kernel (Task 4) ✓; codec (Task 2) ✓; reference oracle (Task 1) ✓; equivalence pin (Task 5) ✓; fetch-and-converge sim (Task 6) ✓; docs/@sample (Task 7) ✓. The spec's "full WarpNode/board multi-node convergence" is explicitly deferred to F4 (Task 6 note) — a scope narrowing worth confirming in review.
- **Placeholders:** none — every code step is complete, including the full `.wat`.
- **Type consistency:** `TrainingUpdate(sampleCount, weights)`, `ReferenceTrainer.step(weights, examples, learnRate)`, `FedAvgKernelCodec.encodeInput/decodeOutput/encodeOutputForTest/RESULT_LEN`, `toContribution(peer, epoch)` — used identically across tasks. Wire offsets identical in codec (Task 2) and kernel (Task 4).
- **Risk:** the hand-written `.wat` is the one place a bug could hide; Task 5 pins it bit-for-bit against the Kotlin oracle, so any divergence fails loudly at build time.
