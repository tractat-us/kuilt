# Warp Epic D — Tiered-Compilation Mechanism Spike — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove a peer tiers up from interpreting a raw wasm kernel to running a compiled variant once a stronger peer builds it and gossips it across the mesh — measured by a durable per-tier metric, using a fake (custom-section) compiler.

**Architecture:** Compiled bobbins are content-addressed bytes like any other; their *provenance* rides the existing `BobbinExchange` manifest additively (`BobbinMeta(hash, variantOf?)`). `WarpNode`'s lazy-fetch resolution (C5b, #942) becomes variant-aware: for a bobbin-backed op it resolves the best variant for the peer's target per execution, caches loaded `Op`s by `BobbinHash`, and counts interpreted-vs-compiled executions in two new GCounters exported through the existing `:kuilt-warp-otel` bridge.

**Tech Stack:** Kotlin Multiplatform (commonMain/commonTest), kotlinx-coroutines (virtual time), kotlinx-serialization (CBOR), kotlinx-atomicfu (locks), `:kuilt-crdt` (`GSet`/`GCounter`), `:kuilt-quilter` (`Quilter`), JUnit-style `kotlin.test` + `assertAll`.

## Global Constraints

- **`explicitApi()` is enforced** — every public declaration needs an explicit `public`.
- **`detektAll`** must pass (not bare `detekt` — that is `NO-SOURCE` here).
- **Build with JDK 21:** `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem` first in every non-interactive shell.
- **Coroutine determinism:** sim/dispatch tests use `runTest(UnconfinedTestDispatcher(), timeout = 5.seconds)` with bounded `advanceTimeBy + runCurrent` via a `settle()` helper. **Never `advanceUntilIdle()`** (anti-entropy timers re-arm forever). Seed every `Quilter.random`.
- **Exception discipline:** suspend code uses `runCatchingCancellable` (in `:kuilt-core`), never bare `runCatching`. A `catch (e: Exception)` rethrows `CancellationException`.
- **Thread-safety:** shared mutable state guarded by an explicit atomicfu `reentrantLock`; no suspend calls inside a locked section; no `limitedParallelism(1)` confinement.
- **One behaviour per PR.** Each task below is its own branch off `origin/main`, its own PR closing its sub-issue. TDD: failing test commit first, then implementation commit.
- **`:kuilt-warp` stays out of the BOM.**
- **The fake compiler is honest only about *distribution + swap*, not *speedup*.** Every doc/KDoc touching it says so. iOS ceiling stays *interpret* (Apple bans externally-delivered machine code).
- Test methods: no `test` prefix; multi-assert tests use `assertAll()`.

## File Structure

**New (commonMain):**
- `kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/Target.kt` — `Target` + `OptLevel` enums.
- `kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/Variant.kt` — `VariantKey` + `BobbinMeta`.

**Modified (commonMain):**
- `kuilt-warp/.../BobbinExchange.kt` — manifest `GSet<BobbinHash>` → `GSet<BobbinMeta>`; `put` emits `BobbinMeta(hash, null)`; new `putVariant`.
- `kuilt-warp/.../WarpNode.kt` — `target: Target?` ctor param; two tier GCounters; variant-aware resolution + per-`BobbinHash` `Op` cache in `executeViaRegistry`; `publishVariant`.
- `kuilt-warp-otel/.../WarpMetricBridge.kt` — `recordWarp` exports the two tier counters.

**New (commonTest):**
- `kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/FakeWasmRuntime.kt` — test `WasmRuntime`.
- `kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/FakeCompiler.kt` — `fakeCompile(bytes, target)` custom-section append + a tiny valid wasm fixture.
- `kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/VariantManifestTest.kt` — variant publish/gossip test.
- `kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/TieredCompilationGoNoGoTest.kt` — the GO/NO-GO sim.

**Modified (commonTest):**
- `kuilt-warp/.../BobbinExchangeTest.kt` — assertions migrate from `contains(hash)` to `any { it.hash == hash }`.

**Sub-issue mapping (under epic #855):** Task 1 → D-spike-1 · Task 2 → D-spike-2 · Tasks 3–5 → D-spike-3 (D1/D2/D3 reified) · Task 6 → D-polish. (Open: whether each gets its own sub-issue — decide with the epic owner before filing; the plan executes regardless.)

---

### Task 1: Variant types + additive manifest migration

**Files:**
- Create: `kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/Target.kt`
- Create: `kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/Variant.kt`
- Modify: `kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/BobbinExchange.kt`
- Test: `kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/BobbinExchangeTest.kt` (migrate), and a new case in it for `putVariant`.

**Interfaces:**
- Produces:
  - `enum class Target { Jvm, Browser, MacosArm64, IosArm64 }` and `enum class OptLevel { O0, O2 }` (both `@Serializable`).
  - `data class VariantKey(val sourceHash: BobbinHash, val target: Target, val optLevel: OptLevel)` (`@Serializable`).
  - `data class BobbinMeta(val hash: BobbinHash, val variantOf: VariantKey?)` (`@Serializable`); `variantOf == null` ⇒ raw/source bobbin.
  - `BobbinExchange.manifest: StateFlow<Set<BobbinMeta>>` (type changed).
  - `BobbinExchange.put(bytes: ByteArray): BobbinHash` (now emits `BobbinMeta(hash, null)`).
  - `BobbinExchange.putVariant(bytes: ByteArray, variantOf: VariantKey): BobbinHash` (new).

- [ ] **Step 1: Write the failing test** — add to `BobbinExchangeTest.kt` a case proving a variant is published with metadata. Append inside the test class:

```kotlin
    /**
     * A variant published via [BobbinExchange.putVariant] appears in the local manifest as a
     * [BobbinMeta] carrying its [VariantKey] provenance, distinct from a raw bobbin (null variantOf).
     */
    @Test
    fun putVariantPublishesMetaWithProvenance() =
        runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seam = loom.host(Pattern("variant-meta-local"))
            val exchange = BobbinExchange(seam, Creel(), backgroundScope, BOBBIN_QUILTER_CONFIG)

            val rawHash = exchange.put(byteArrayOf(1, 2, 3))
            val variantHash = exchange.putVariant(
                byteArrayOf(1, 2, 3, 9),
                VariantKey(rawHash, Target.Jvm, OptLevel.O2),
            )
            settle()

            assertAll(
                { assertTrue(exchange.manifest.value.any { it.hash == rawHash && it.variantOf == null }, "raw bobbin has null variantOf") },
                {
                    assertTrue(
                        exchange.manifest.value.any {
                            it.hash == variantHash && it.variantOf == VariantKey(rawHash, Target.Jvm, OptLevel.O2)
                        },
                        "variant bobbin carries its VariantKey",
                    )
                },
            )
        }
```

(If `BOBBIN_QUILTER_CONFIG`/`settle` names differ in the file, reuse whatever the file already defines — check the top of `BobbinExchangeTest.kt`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew :kuilt-warp:jvmTest --tests "*BobbinExchangeTest"`
Expected: FAIL to **compile** — `Target`, `OptLevel`, `VariantKey`, `putVariant` unresolved.

- [ ] **Step 3: Create `Target.kt`**

```kotlin
package us.tractat.kuilt.warp

import kotlinx.serialization.Serializable

/**
 * A compilation target — the platform a compiled bobbin variant is built for.
 *
 * A weaker peer tiers up only to a variant whose [Target] matches its own runtime. The
 * iOS ceiling stays *interpret optimized wasm*: a compiler node may ship [IosArm64] an
 * optimized wasm→wasm variant, never native machine code (Apple forbids executing
 * externally-delivered machine code at all).
 */
@Serializable
public enum class Target { Jvm, Browser, MacosArm64, IosArm64 }

/**
 * Optimization level of a compiled bobbin variant. Higher wins when several variants exist
 * for the same [Target]. The spike's fake compiler produces a single level; the enum exists
 * so the durable [VariantKey] address survives into the real-toolchain epic (D4).
 */
@Serializable
public enum class OptLevel { O0, O2 }
```

- [ ] **Step 4: Create `Variant.kt`**

```kotlin
package us.tractat.kuilt.warp

import kotlinx.serialization.Serializable

/**
 * The address of a compiled bobbin *variant*: which source kernel it was built from, for
 * which [Target], at which [OptLevel]. Recorded as the `variantOf` provenance on a
 * [BobbinMeta] so a peer can discover "the compiled-for-my-target version of source S".
 */
@Serializable
public data class VariantKey(
    val sourceHash: BobbinHash,
    val target: Target,
    val optLevel: OptLevel,
)

/**
 * A manifest entry: a content-addressed bobbin plus optional variant provenance.
 *
 * `variantOf == null` is a **raw/source** bobbin (the pre-variant meaning — every bobbin
 * the C5 path published was implicitly this). A non-null [variantOf] marks a compiled
 * variant. The [hash] is always `hash(bytes)`, so `Creel`'s content-addressing invariant is
 * untouched; provenance rides alongside, never replacing the key.
 *
 * **Determinism invariant:** a given [hash] always carries the same [variantOf] (the bytes
 * are either always raw or always the variant-of-X they hash to), so the grow-only manifest
 * `GSet` never holds two conflicting entries for one hash.
 */
@Serializable
public data class BobbinMeta(
    val hash: BobbinHash,
    val variantOf: VariantKey?,
)
```

- [ ] **Step 5: Migrate `BobbinExchange.kt` manifest type + add `putVariant`**

Change the manifest Quilter, backing flow, and public `manifest` from `BobbinHash` to `BobbinMeta`:

```kotlin
    private val manifestQuilter: Quilter<GSet<BobbinMeta>> = Quilter(
        seam = manifestSeam,
        initial = GSet.empty(),
        valueSerializer = GSet.serializer(serializer<BobbinMeta>()),
        scope = scope,
        replica = replica,
        config = quilterConfig,
        random = kotlin.random.Random(seam.selfId.value.hashCode().toLong()),
    )

    private val _manifest = MutableStateFlow<Set<BobbinMeta>>(emptySet())
```

```kotlin
    public val manifest: StateFlow<Set<BobbinMeta>> = _manifest.asStateFlow()
```

The `init` mapping stays `gset.elements` (now a `Set<BobbinMeta>`). Replace `put` and add `putVariant`:

```kotlin
    /**
     * Stores [bytes] as a **raw** bobbin and advertises `BobbinMeta(hash, variantOf = null)`
     * on the gossiped manifest so all peers learn the hash immediately.
     */
    public fun put(bytes: ByteArray): BobbinHash {
        val hash = creel.put(bytes)
        manifestQuilter.mutate { it.add(BobbinMeta(hash, variantOf = null)) }
        return hash
    }

    /**
     * Stores [bytes] as a **compiled variant** and advertises `BobbinMeta(hash, [variantOf])`
     * so peers can discover the compiled-for-target version of the source kernel.
     */
    public fun putVariant(bytes: ByteArray, variantOf: VariantKey): BobbinHash {
        val hash = creel.put(bytes)
        manifestQuilter.mutate { it.add(BobbinMeta(hash, variantOf)) }
        return hash
    }
```

Update the KDoc references to `GSet<BobbinHash>` in the class/property docs to `GSet<BobbinMeta>`.

- [ ] **Step 6: Migrate existing `BobbinExchangeTest` assertions**

Change every `manifest.value.contains(hash)` / `contains(h1)` … to the meta form, e.g.:

```kotlin
                { assertTrue(exchangeB.manifest.value.any { it.hash == hash }, "B's manifest must contain the hash after settling") },
```
and similarly for `h1`/`h2`/`h3` in `manifestReflectsAllPutHashes`.

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew :kuilt-warp:jvmTest --tests "*BobbinExchangeTest"`
Expected: PASS (migrated cases + the new `putVariantPublishesMetaWithProvenance`).

- [ ] **Step 8: Detekt + full module build**

Run: `./gradlew :kuilt-warp:detektAll :kuilt-warp:build`
Expected: BUILD SUCCESSFUL (no `explicitApi` violations on the new public types).

- [ ] **Step 9: Commit**

```bash
git add kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/Target.kt \
        kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/Variant.kt \
        kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/BobbinExchange.kt \
        kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/BobbinExchangeTest.kt
git commit -m "feat(kuilt-warp): D-spike-1 — additive BobbinMeta variant manifest

Target/OptLevel/VariantKey/BobbinMeta; BobbinExchange manifest GSet<BobbinHash>
→ GSet<BobbinMeta> (raw bobbins → variantOf=null); new putVariant. Creel
content-addressing untouched.

Closes #<D-spike-1>. Part of #855."
```

---

### Task 2: Fake compiler + variant gossip test

**Files:**
- Create: `kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/FakeCompiler.kt`
- Create: `kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/VariantManifestTest.kt`

**Interfaces:**
- Consumes (Task 1): `BobbinExchange.putVariant`, `VariantKey`, `Target`, `OptLevel`, `BobbinMeta`.
- Produces:
  - `MINIMAL_WASM: ByteArray` — the 8-byte empty-but-valid wasm module header.
  - `fakeCompile(bytes: ByteArray, target: Target): ByteArray` — appends a wasm custom section `"compiled-for:<target>"`; deterministic; distinct hash per target; still a valid module.

- [ ] **Step 1: Write the failing test** — `VariantManifestTest.kt`:

```kotlin
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp

import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val VM_QUILTER_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

private fun TestScope.settle() {
    repeat(8) { advanceTimeBy(VM_QUILTER_CONFIG.antiEntropyInterval); runCurrent() }
}

class VariantManifestTest {

    /**
     * A compiler node publishes a fake-compiled variant of a raw kernel; the variant's
     * BobbinMeta (with provenance) gossips to a second peer, and the variant bytes are
     * fetchable on demand and re-hash to the advertised hash.
     */
    @Test
    fun compiledVariantGossipsWithProvenanceAndIsFetchable() =
        runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamA = loom.host(Pattern("variant-gossip"))
            val seamB = loom.join(InMemoryTag("b"))
            val exchangeA = BobbinExchange(seamA, Creel(), backgroundScope, VM_QUILTER_CONFIG)
            val exchangeB = BobbinExchange(seamB, Creel(), backgroundScope, VM_QUILTER_CONFIG)

            val source = exchangeA.put(MINIMAL_WASM)
            val compiled = fakeCompile(MINIMAL_WASM, Target.Jvm)
            val variantHash = exchangeA.putVariant(compiled, VariantKey(source, Target.Jvm, OptLevel.O2))
            settle()

            val fetched = exchangeB.fetch(variantHash)

            assertAll(
                {
                    assertTrue(
                        exchangeB.manifest.value.any { it.hash == variantHash && it.variantOf == VariantKey(source, Target.Jvm, OptLevel.O2) },
                        "B learns the variant's provenance via gossip",
                    )
                },
                { assertEquals(compiled.toList(), fetched.toList(), "fetched variant bytes match the compiled bytes") },
                { assertTrue(variantHash != source, "the variant is a distinct bobbin from its source") },
            )
        }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-warp:jvmTest --tests "*VariantManifestTest"`
Expected: FAIL to compile — `MINIMAL_WASM` / `fakeCompile` unresolved.

- [ ] **Step 3: Create `FakeCompiler.kt`**

```kotlin
package us.tractat.kuilt.warp

/**
 * The 8-byte header of an empty-but-valid WebAssembly module: magic `\0asm` + version 1.
 * A conforming runtime loads it without error; it exports nothing. Sufficient as a spike
 * fixture — the go/no-go injects a [FakeWasmRuntime], so no real export is executed.
 */
internal val MINIMAL_WASM: ByteArray = byteArrayOf(
    0x00, 0x61, 0x73, 0x6D, // "\0asm"
    0x01, 0x00, 0x00, 0x00, // version 1
)

/**
 * The spike's **fake compiler**: appends a WebAssembly *custom section* tagging [bytes] as
 * "compiled-for:<target>". A custom section is valid wasm that runtimes ignore, so the
 * module still loads and runs identically — but the bytes (and therefore the content hash)
 * differ per [target], making the result a genuinely distinct, fetchable bobbin.
 *
 * Deterministic and pure: same input ⇒ same output ⇒ same hash. This proves *distribution
 * and swap*, NOT *speedup* — the transform is a no-op optimization. Real optimization is the
 * D4 toolchain epic.
 *
 * Custom-section encoding: section id `0x00`, then a LEB128 length, then a name (LEB128
 * length-prefixed UTF-8) followed by the (empty) payload.
 */
internal fun fakeCompile(bytes: ByteArray, target: Target): ByteArray {
    val name = "compiled-for:${target.name}".encodeToByteArray()
    val nameField = leb128(name.size) + name
    return bytes + byteArrayOf(0x00) + leb128(nameField.size) + nameField
}

/** Minimal unsigned LEB128 encoder (sufficient for small section lengths). */
private fun leb128(value: Int): ByteArray {
    var v = value
    val out = ArrayList<Byte>()
    do {
        var b = (v and 0x7F)
        v = v ushr 7
        if (v != 0) b = b or 0x80
        out.add(b.toByte())
    } while (v != 0)
    return out.toByteArray()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-warp:jvmTest --tests "*VariantManifestTest"`
Expected: PASS.

- [ ] **Step 5: Detekt**

Run: `./gradlew :kuilt-warp:detektAll`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/FakeCompiler.kt \
        kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/VariantManifestTest.kt
git commit -m "feat(kuilt-warp): D-spike-2 — fake custom-section compiler + variant gossip test

fakeCompile appends a wasm custom section (distinct hash, still-runnable, no
toolchain). Proves a compiled variant gossips with provenance and is fetchable.
Distribution+swap only, not speedup.

Closes #<D-spike-2>. Part of #855."
```

---

### Task 3: WarpNode tier counters + variant-aware resolution + `publishVariant`

**Files:**
- Modify: `kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/WarpNode.kt`
- Create: `kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/FakeWasmRuntime.kt`
- Test: a new unit test file `kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/WarpNodeTieringTest.kt`

**Interfaces:**
- Consumes (Tasks 1–2): `BobbinMeta`, `VariantKey`, `Target`, `OptLevel`, `BobbinExchange.manifest`, `BobbinExchange.putVariant`, `WarpLazyFetch`, `WasmRuntime`.
- Produces:
  - `WarpNode(..., target: Target? = null)` — new trailing optional ctor param. Non-null enables tiered resolution; null preserves #942 behaviour exactly.
  - `WarpNode.executionsInterpreted: GCounter` and `WarpNode.executionsCompiled: GCounter` — public GCounter snapshots.
  - `WarpNode.publishVariant(bytes: ByteArray, variantOf: VariantKey): BobbinHash` (suspend) — publishes a variant via the node's own `BobbinExchange`; `checkNotNull(lazyFetch)`.
  - `FakeWasmRuntime(op: Op)` — a `WasmRuntime` whose `load` returns the given `Op` for any bytes.

- [ ] **Step 1: Create `FakeWasmRuntime.kt`**

```kotlin
package us.tractat.kuilt.warp

/**
 * A test [WasmRuntime] that returns a fixed [Op] for any bytes — proving the tiering
 * *mechanism* (fetch → load → run → count) without compiling real wasm. The real runtimes
 * (Chicory / wasm3 / browser) are proven separately by the C3 dispatch tests.
 */
internal class FakeWasmRuntime(private val op: Op) : WasmRuntime {
    override fun load(bytes: ByteArray): Op = op
}
```

- [ ] **Step 2: Write the failing test** — `WarpNodeTieringTest.kt`:

```kotlin
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val TIER_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

private fun tierClock(scheduler: TestCoroutineScheduler): () -> Instant =
    { Instant.fromEpochMilliseconds(scheduler.currentTime) }

private fun TestScope.settle() {
    repeat(6) { advanceTimeBy(TIER_CONFIG.antiEntropyInterval); runCurrent() }
    advanceTimeBy(ClaimStrategy.DEFAULT_SETTLE_WINDOW); runCurrent()
    repeat(6) { advanceTimeBy(TIER_CONFIG.antiEntropyInterval); runCurrent() }
}

class WarpNodeTieringTest {

    /**
     * A single tiering node, target = Jvm: it interprets the raw bobbin first
     * (executionsInterpreted ≥ 1), then after a Jvm variant is published it tiers up
     * (executionsCompiled ≥ 1) — results identical throughout.
     */
    @Test
    fun nodeTiersUpFromInterpretedToCompiledWhenVariantAppears() =
        runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seam = loom.host(Pattern("tiering-solo"))
            val op = OpId("square")
            val creel = Creel()

            // The raw bobbin's bytes; opToBobbin maps the op to its hash.
            val rawHash = creel.put(MINIMAL_WASM)
            val squareOp = Op { args -> args } // identity is enough — FakeWasmRuntime returns it
            val lazyFetch = WarpLazyFetch(
                creel = creel,
                runtime = FakeWasmRuntime(squareOp),
                opToBobbin = { id -> if (id == op) rawHash else null },
            )

            // Roster = {self}: this node owns every task.
            val roster = MutableStateFlow<Set<PeerId>>(setOf(seam.selfId))
            val node = WarpNode(
                selfId = seam.selfId, seam = seam, rosterFlow = roster, scope = backgroundScope,
                quilterConfig = TIER_CONFIG, clock = tierClock(testScheduler),
                strategy = ClaimStrategy.Ring,
                registry = OpRegistry(), // op NOT registered ⇒ resolved via lazyFetch
                lazyFetch = lazyFetch, target = Target.Jvm,
            )

            // Interpret phase: run a task; only the raw bobbin exists.
            node.enqueue(TaskId("t1"), TaskDescriptor(op, byteArrayOf(7)))
            settle()
            val interpretedAfterT1 = node.executionsInterpreted.value
            val compiledAfterT1 = node.executionsCompiled.value

            // Publish a Jvm variant, then run another task: it must tier up.
            node.publishVariant(fakeCompile(MINIMAL_WASM, Target.Jvm), VariantKey(rawHash, Target.Jvm, OptLevel.O2))
            settle()
            node.enqueue(TaskId("t2"), TaskDescriptor(op, byteArrayOf(8)))
            settle()

            assertAll(
                { assertTrue(interpretedAfterT1 >= 1L, "first task interpreted (raw bobbin), was $interpretedAfterT1") },
                { assertEquals(0L, compiledAfterT1, "no compiled execution before any variant exists") },
                { assertTrue(node.executionsCompiled.value >= 1L, "second task tiered up to compiled, was ${node.executionsCompiled.value}") },
                { assertTrue(node.results[TaskId("t2")] != null, "result still recorded after tiering") },
            )
        }
}
```

(If `GCounter`'s scalar accessor is not `.value`, use the project's accessor — check `kuilt-crdt/.../GCounter.kt`. Adjust both call sites.)

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :kuilt-warp:jvmTest --tests "*WarpNodeTieringTest"`
Expected: FAIL to compile — `target` param, `executionsInterpreted`/`executionsCompiled`, `publishVariant` unresolved.

- [ ] **Step 4: Add the ctor param + tier counters to `WarpNode.kt`**

Add the trailing optional constructor parameter (after `lazyFetch`):

```kotlin
    private val lazyFetch: WarpLazyFetch? = null,
    private val target: Target? = null,
) {
```

KDoc for the param (add to the class KDoc `@param` list):

```
 * @param target This peer's compilation [Target]. When non-null **and** a [lazyFetch] is present,
 *   a bobbin-backed op resolves the best compiled variant for this target per execution and tiers
 *   up when one gossips in (counted in [executionsCompiled]). When null, resolution is exactly the
 *   C5b lazy-fetch behaviour (no tiering). Required to be explicit — a platform's target is never
 *   guessed.
```

Add the backing counters beside the existing `_executions`/`_failovers`/`_duplicates`:

```kotlin
    private var _executionsInterpreted: GCounter = GCounter.ZERO
    private var _executionsCompiled: GCounter = GCounter.ZERO
```

Add the per-`BobbinHash` loaded-`Op` cache beside the other guarded state:

```kotlin
    /** Loaded ops keyed by the BobbinHash actually executed — lets the chosen tier change per
     *  execution without the OpId-registry short-circuit pinning the raw bobbin. Guarded by [lock]. */
    private val bobbinToOp = mutableMapOf<BobbinHash, Op>()
```

Public snapshots (beside `executions`):

```kotlin
    /**
     * Cumulative count of task executions this node ran by **interpreting the raw bobbin** —
     * the un-tiered path. A [GCounter] snapshot; forward via [recordWarp] into a SUM series.
     */
    public val executionsInterpreted: GCounter get() = lock.withLock { _executionsInterpreted }

    /**
     * Cumulative count of task executions this node ran on a **compiled variant** after tiering
     * up. Goes from 0 to ≥1 the first time a target-matching variant gossips in. The durable
     * tiered-compilation signal — the same counter measures real tiering once D4 lands a real
     * compiler. A [GCounter] snapshot; forward via [recordWarp] into a SUM series.
     */
    public val executionsCompiled: GCounter get() = lock.withLock { _executionsCompiled }
```

- [ ] **Step 5: Add `bestBobbin` + variant-aware branch in `executeViaRegistry`, and `publishVariant`**

Add the resolver (private):

```kotlin
    /**
     * The best bobbin to run [op] on for this node's [target]: the highest-[OptLevel] compiled
     * variant of the op's source bobbin advertised on the manifest, or the raw source hash when
     * none exists. Returns null only when the op is not bobbin-backed.
     */
    private fun bestBobbin(op: OpId): BobbinHash? {
        val source = lazyFetch?.opToBobbin(op) ?: return null
        val t = target ?: return source
        val manifest = bobbinExchange?.manifest?.value.orEmpty()
        val best = manifest
            .filter { it.variantOf?.sourceHash == source && it.variantOf?.target == t }
            .maxByOrNull { it.variantOf!!.optLevel.ordinal }
        return best?.hash ?: source
    }
```

Replace the unresolved-op branch of `executeViaRegistry`. The current code is:

```kotlin
        val op = registry.resolve(descriptor.op) ?: run {
            val lf = lazyFetch ?: return standBy(taskId, descriptor.op)
            val hash = lf.opToBobbin(descriptor.op) ?: return standBy(taskId, descriptor.op)
            val bytes = checkNotNull(bobbinExchange).fetch(hash)
            val loaded = try {
                lf.runtime.load(bytes)
            } catch (e: WasmException) {
                return recordTerminalError(taskId, e)
            }
            registerOrResolve(descriptor.op, loaded)
        }
        return try {
            op.invoke(descriptor.args)
        } catch (e: WasmException) {
            recordTerminalError(taskId, e)
        }
```

Change it so a bobbin-backed op with tiering enabled (`target != null`) resolves the best variant per execution, caches loaded ops by `BobbinHash`, and counts the tier:

```kotlin
        // Symbolic op already in the registry: run it directly (non-tiered path, unchanged).
        registry.resolve(descriptor.op)?.let { op ->
            return try {
                op.invoke(descriptor.args)
            } catch (e: WasmException) {
                recordTerminalError(taskId, e)
            }
        }

        val lf = lazyFetch ?: return standBy(taskId, descriptor.op)
        val source = lf.opToBobbin(descriptor.op) ?: return standBy(taskId, descriptor.op)

        // Tiering disabled (target == null): preserve C5b behaviour — load once, register under OpId.
        if (target == null) {
            val bytes = checkNotNull(bobbinExchange).fetch(source)
            val loaded = try {
                lf.runtime.load(bytes)
            } catch (e: WasmException) {
                return recordTerminalError(taskId, e)
            }
            val op = registerOrResolve(descriptor.op, loaded)
            return try {
                op.invoke(descriptor.args)
            } catch (e: WasmException) {
                recordTerminalError(taskId, e)
            }
        }

        // Tiering enabled: resolve best variant per execution, cache loaded ops by BobbinHash.
        val hash = bestBobbin(descriptor.op) ?: source
        val cached = lock.withLock { bobbinToOp[hash] }
        val op = cached ?: run {
            val bytes = checkNotNull(bobbinExchange).fetch(hash) // suspends, outside lock
            val loaded = try {
                lf.runtime.load(bytes)
            } catch (e: WasmException) {
                return recordTerminalError(taskId, e)
            }
            lock.withLock { bobbinToOp.getOrPut(hash) { loaded } }
        }
        val isCompiled = hash != source
        return try {
            val result = op.invoke(descriptor.args)
            lock.withLock {
                if (isCompiled) {
                    _executionsCompiled = _executionsCompiled.piece(_executionsCompiled.inc(replica).delta)
                } else {
                    _executionsInterpreted = _executionsInterpreted.piece(_executionsInterpreted.inc(replica).delta)
                }
            }
            result
        } catch (e: WasmException) {
            recordTerminalError(taskId, e)
        }
```

Add the public publisher near the other public API (after `enqueue`):

```kotlin
    /**
     * Publish a compiled bobbin **variant** through this node's own [BobbinExchange] so it gossips
     * to the mesh. This is what a *compiler node* calls after building a variant (in the spike, via
     * the fake compiler). Requires a [lazyFetch] capability — throws [IllegalStateException] otherwise,
     * fail-loud rather than silently dropping the variant.
     *
     * @return the [BobbinHash] of the published variant bytes.
     */
    public suspend fun publishVariant(bytes: ByteArray, variantOf: VariantKey): BobbinHash =
        checkNotNull(bobbinExchange) {
            "WarpNode($selfId): publishVariant requires a lazyFetch capability (no BobbinExchange)"
        }.putVariant(bytes, variantOf)
```

- [ ] **Step 6: Run the tiering test to verify it passes**

Run: `./gradlew :kuilt-warp:jvmTest --tests "*WarpNodeTieringTest"`
Expected: PASS.

- [ ] **Step 7: Run the full warp JVM suite to confirm no regression** (the `target == null` path must be byte-identical to #942)

Run: `./gradlew :kuilt-warp:jvmTest`
Expected: PASS (all existing tests, incl. C5b lazy-fetch + symbolic dispatch).

- [ ] **Step 8: Detekt + module build (all targets compile)**

Run: `./gradlew :kuilt-warp:detektAll :kuilt-warp:build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/WarpNode.kt \
        kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/FakeWasmRuntime.kt \
        kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/WarpNodeTieringTest.kt
git commit -m "feat(kuilt-warp): D-spike-3 — variant-aware resolution + tier counters

WarpNode gains target: Target?; per-execution best-variant resolution with a
per-BobbinHash Op cache (bypasses the OpId-registry short-circuit so a peer can
swap interpret→compiled); executionsInterpreted/executionsCompiled GCounters;
publishVariant. target==null preserves C5b behaviour exactly.

Part of #855."
```

---

### Task 4: The GO/NO-GO sim

**Files:**
- Test: `kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/TieredCompilationGoNoGoTest.kt`

**Interfaces:**
- Consumes (Tasks 1–3): `WarpNode(target=…, lazyFetch=…)`, `publishVariant`, `FakeWasmRuntime`, `fakeCompile`, `MINIMAL_WASM`, the tier counters.

- [ ] **Step 1: Write the go/no-go test** (this IS the deliverable — a real two-node mesh):

```kotlin
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val GNG_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

private fun gngClock(scheduler: TestCoroutineScheduler): () -> Instant =
    { Instant.fromEpochMilliseconds(scheduler.currentTime) }

private fun TestScope.settle() {
    repeat(6) { advanceTimeBy(GNG_CONFIG.antiEntropyInterval); runCurrent() }
    advanceTimeBy(ClaimStrategy.DEFAULT_SETTLE_WINDOW); runCurrent()
    repeat(6) { advanceTimeBy(GNG_CONFIG.antiEntropyInterval); runCurrent() }
}

/**
 * **Epic D go/no-go.** A weak (interpreting) peer tiers up to a compiled variant produced and
 * gossiped by a compiler-node peer — on a non-iOS target — observed via the durable
 * executionsCompiled counter. Proves the *distribution + swap* mechanism end-to-end. It does
 * NOT prove speedup (the fake compiler is a no-op transform); that is the D4 toolchain epic.
 */
class TieredCompilationGoNoGoTest {

    @Test
    fun weakPeerTiersUpViaGossipedVariant() =
        runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamC = loom.host(Pattern("tiered-gng"))   // compiler node
            val seamW = loom.join(InMemoryTag("w"))         // weak node (owns the tasks)
            val op = OpId("square")

            // Each peer has its own Creel seeded with the raw kernel; opToBobbin maps op→rawHash.
            fun lazyFetchFor(): Pair<Creel, WarpLazyFetch> {
                val creel = Creel()
                val rawHash = creel.put(MINIMAL_WASM)
                val lf = WarpLazyFetch(creel, FakeWasmRuntime(Op { args -> args }), { id -> if (id == op) rawHash else null })
                return creel to lf
            }
            val (creelC, lfC) = lazyFetchFor()
            val (_, lfW) = lazyFetchFor()
            val rawHash = creelC.loaded.first() // same content ⇒ same hash on both peers

            // Roster = {W}: the weak node owns and executes every task; C is the compiler node.
            val roster = MutableStateFlow<Set<PeerId>>(setOf(seamW.selfId))

            val compilerNode = WarpNode(
                selfId = seamC.selfId, seam = seamC, rosterFlow = roster, scope = backgroundScope,
                quilterConfig = GNG_CONFIG, clock = gngClock(testScheduler), strategy = ClaimStrategy.Ring,
                registry = OpRegistry(), lazyFetch = lfC, target = Target.Jvm,
            )
            val weakNode = WarpNode(
                selfId = seamW.selfId, seam = seamW, rosterFlow = roster, scope = backgroundScope,
                quilterConfig = GNG_CONFIG, clock = gngClock(testScheduler), strategy = ClaimStrategy.Ring,
                registry = OpRegistry(), lazyFetch = lfW, target = Target.Jvm,
            )

            // Phase 1 — interpret: the weak node runs a task on the raw bobbin.
            weakNode.enqueue(TaskId("g1"), TaskDescriptor(op, byteArrayOf(5)))
            settle()
            val compiledBefore = weakNode.executionsCompiled.value

            // Phase 2 — the compiler node builds + gossips a Jvm variant.
            compilerNode.publishVariant(fakeCompile(MINIMAL_WASM, Target.Jvm), VariantKey(rawHash, Target.Jvm, OptLevel.O2))
            settle()

            // Phase 3 — the weak node runs another task: it must now tier up.
            weakNode.enqueue(TaskId("g2"), TaskDescriptor(op, byteArrayOf(6)))
            settle()

            assertAll(
                { assertTrue(weakNode.executionsInterpreted.value >= 1L, "weak peer interpreted before the variant arrived") },
                { assertTrue(compiledBefore == 0L, "no compiled execution before the variant gossiped in") },
                { assertTrue(weakNode.executionsCompiled.value >= 1L, "GO: weak peer tiered up to compiled via the gossiped variant") },
                { assertTrue(weakNode.results[TaskId("g2")] != null, "result still recorded after tiering") },
            )
        }
}
```

- [ ] **Step 2: Run the go/no-go**

Run: `./gradlew :kuilt-warp:jvmTest --tests "*TieredCompilationGoNoGoTest"`
Expected: PASS — `executionsCompiled` 0 → ≥1 after the variant gossips in.

- [ ] **Step 3: Run on a second platform to confirm commonTest portability**

Run: `./gradlew :kuilt-warp:wasmJsTest --tests "*TieredCompilationGoNoGoTest"`
Expected: PASS (the fake runtime keeps it target-agnostic).

- [ ] **Step 4: Commit**

```bash
git add kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/TieredCompilationGoNoGoTest.kt
git commit -m "test(kuilt-warp): D-spike go/no-go — weak peer tiers up via gossiped variant

A weak interpreting peer tiers up to a compiler-node's gossiped Jvm variant,
observed via executionsCompiled 0→≥1. GO for the distribution+swap mechanism
(not speedup — fake compiler).

Closes #<D-spike-3>. Part of #855."
```

---

### Task 5: Export the tier counters through the OTel bridge

**Files:**
- Modify: `kuilt-warp-otel/src/commonMain/kotlin/us/tractat/kuilt/warp/otel/WarpMetricBridge.kt`
- Test: `kuilt-warp-otel/src/commonTest/kotlin/us/tractat/kuilt/warp/otel/WarpMetricBridgeTest.kt`

**Interfaces:**
- Consumes (Task 3): `WarpNode.executionsInterpreted`, `WarpNode.executionsCompiled`.
- Produces: `recordWarp` additionally merges `warp.tasks.interpreted` and `warp.tasks.compiled` SUM series.

- [ ] **Step 1: Write the failing test** — add to `WarpMetricBridgeTest.kt` a case asserting the two new series appear after `recordWarp`. Mirror the existing assertions in that file for `warp.tasks.executed` (read the file first to copy the exporter-construction + series-read pattern, then add):

```kotlin
    @Test
    fun recordWarpExportsTierCounters() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        // … construct an exporter + a WarpNode with target = Target.Jvm + lazyFetch, drive at
        // least one interpreted and one compiled execution (reuse the Task-4 go/no-go setup
        // pattern), then: …
        exporter.recordWarp(weakNode)
        assertAll(
            { assertTrue(exporter.sumSeries("warp.tasks.interpreted") >= 1L, "interpreted SUM exported") },
            { assertTrue(exporter.sumSeries("warp.tasks.compiled") >= 1L, "compiled SUM exported") },
        )
    }
```

(Use the exact exporter inspection helper the existing tests use — e.g. however `warp.tasks.executed` is read back in this file. Match it.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :kuilt-warp-otel:jvmTest --tests "*WarpMetricBridgeTest"`
Expected: FAIL — the two new series are absent.

- [ ] **Step 3: Add the two merges to `recordWarp`**

```kotlin
    mergeSum(MetricKey("warp.tasks.executed", MetricKind.SUM, attributes), node.executions)
    mergeSum(MetricKey("warp.tasks.duplicate", MetricKind.SUM, attributes), node.duplicates)
    mergeSum(MetricKey("warp.failover.count", MetricKind.SUM, attributes), node.failovers)
    mergeSum(MetricKey("warp.tasks.interpreted", MetricKind.SUM, attributes), node.executionsInterpreted)
    mergeSum(MetricKey("warp.tasks.compiled", MetricKind.SUM, attributes), node.executionsCompiled)
```

Update the KDoc table to add the two rows (`warp.tasks.interpreted` ← `executionsInterpreted`; `warp.tasks.compiled` ← `executionsCompiled`).

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :kuilt-warp-otel:jvmTest --tests "*WarpMetricBridgeTest"`
Expected: PASS.

- [ ] **Step 5: Detekt + build the otel module**

Run: `./gradlew :kuilt-warp-otel:detektAll :kuilt-warp-otel:build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add kuilt-warp-otel/src/commonMain/kotlin/us/tractat/kuilt/warp/otel/WarpMetricBridge.kt \
        kuilt-warp-otel/src/commonTest/kotlin/us/tractat/kuilt/warp/otel/WarpMetricBridgeTest.kt
git commit -m "feat(kuilt-warp-otel): export interpreted/compiled tier counters

recordWarp merges warp.tasks.interpreted and warp.tasks.compiled SUM series, so
tiered-compilation shows up on dashboards. Same bridge will carry real tiering
once D4 lands a real compiler.

Part of #855."
```

---

### Task 6: D-polish — docs + cleanup

**Files:**
- Modify: `kuilt-warp/module.md` (add a tiered-compilation paragraph + the honesty asterisks).
- Modify: `Writerside/topics/warp.md` (if it has an execution/code-mobility section — add a plain-language line on tiering; keep accessible-first per CLAUDE.md).
- Verify: `docs/warp-execution.md` "Compiler nodes" section already states the iOS asterisk + "reuse mature toolchains" — confirm it still reads correctly against what shipped; adjust only if the spike contradicts it.

- [ ] **Step 1: Add the tiered-compilation note to `kuilt-warp/module.md`**

Add a short paragraph (place it after the bobbin/creel material, matching the file's existing voice):

```markdown
### Tiered compilation (mechanism spike)

A peer that lacks a compiled kernel **interprets** the raw bobbin immediately, and **tiers
up** to a compiled variant once a stronger *compiler node* builds one and gossips it across
the mesh — a JIT smeared across the network. A variant rides the bobbin manifest additively
as a `BobbinMeta(hash, variantOf = VariantKey(source, target, optLevel))`; a node with a
`target` resolves the best variant for its platform per execution and counts
interpreted-vs-compiled executions (`executionsInterpreted` / `executionsCompiled`).

The current spike proves **distribution and swap**, not speedup — its compiler is a
deterministic no-op transform. Genuine optimization (GraalWasm / Kotlin-Wasm / `wasm-opt`)
is a later epic. The iOS ceiling stays *interpret*: Apple forbids executing
externally-delivered machine code, so a compiler node can ship iOS an optimized wasm→wasm
variant but never native code.
```

- [ ] **Step 2: Build the Dokka site to confirm the KDoc/module.md compiles**

Run: `./gradlew :kuilt-warp:dokkaGenerate`
Expected: BUILD SUCCESSFUL (module.md is consumed by Dokka).

- [ ] **Step 3: Full build (final green checkpoint)**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL across all targets.

- [ ] **Step 4: Commit**

```bash
git add kuilt-warp/module.md Writerside/topics/warp.md
git commit -m "docs(kuilt-warp): D-polish — tiered-compilation notes + honesty asterisks

Closes #<D-polish>. Part of #855."
```

---

## Self-Review

**Spec coverage:**
- Variant model (Option C, additive `BobbinMeta`) → Task 1. ✔
- `compile` op + fake custom-section compiler → Task 2 (fake) + Task 3 (`publishVariant` is the node-level "a compiler node ran compile"). The op is test-wired (registry-assembled) per the spike; full ring-dispatched `compile` is noted as the thin remaining gap, deferred with D4. ✔
- Tiering (variant-aware resolution + per-`BobbinHash` cache + one-way swap) → Task 3. ✔ (registry-cache wrinkle handled explicitly).
- Tier-count proof (GCounters) → Task 3; OTel export → Task 5. ✔
- Go/no-go sim in commonTest with fake runtime → Task 4. ✔
- Honest asterisks (distribution≠speedup; iOS interpret-only) → Tasks 2/6 KDoc + module.md. ✔
- Conventions (explicitApi, detektAll, harness, seeded RNG, no advanceUntilIdle) → Global Constraints + every task's build steps. ✔

**Known follow-ups (not gaps — out of spike scope):** ring-dispatched `compile` op (vs node-level `publishVariant`); de-opt / multi-optLevel selection beyond highest-wins; real toolchains (D4).

**Placeholder scan:** the only intentionally-open token is `#<D-spike-N>` / `#<D-polish>` in commit messages — fill with the real sub-issue numbers at execution time (or drop the closing keyword and keep `Part of #855` if no sub-issue is filed). The Task-5 test body references the existing exporter inspection helper rather than inlining it — the executor must read `WarpMetricBridgeTest.kt` first (instructed in-step) because that helper's exact name lives in the file.

**Type consistency:** `Target`/`OptLevel`/`VariantKey`/`BobbinMeta` names and fields are identical across Tasks 1–6; `executionsInterpreted`/`executionsCompiled` and `publishVariant`/`bestBobbin` signatures match between Task 3's definitions and Tasks 4–5's uses. GCounter scalar accessor is written as `.value` throughout — verify against `GCounter.kt` at Task 3 Step 2 and fix all call sites together if it differs.
