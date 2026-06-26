# Plan — Warp C5b: production wasm-runtime + lazy fetch-and-run (JVM slice)

**Issue:** [#929](https://github.com/tractat-us/kuilt/issues/929) · **Epic:** [#853](https://github.com/tractat-us/kuilt/issues/853) (Epic C — code mobility)
**Design spec:** `docs/superpowers/specs/2026-06-26-warp-c5b-wasm-runtime-design.md` (approved; corrected so `WarpLazyFetch` carries a `Creel` and `WarpNode` owns the exchange over `CHANNEL_BOBBIN`)
**Branch:** `warp/c5b-wasm-runtime` (rebased on `origin/main`; spec commit on top)
**Module:** `:kuilt-warp` · **Date:** 2026-06-26

## Goal

Close warp's lazy-code-mobility loop on the JVM: when a `WarpNode` is assigned an op it does
not hold, fetch the bobbin (C5a `BobbinExchange`), turn the verified bytes into a runnable `Op`
via a **production WASM loader**, run it inside a **capability sandbox** (no imports, memory cap,
execution-time bound), and converge the result — recording a **terminal error** for a
verified-but-broken kernel instead of looping anti-entropy forever.

## Context for every task

- **Chicory 1.4.0**, dependency already in `jvmMain` (`kuilt-warp/build.gradle.kts:28`) — no build change. There is **no fuel/gas API**; the CPU bound is thread interruption (see Task 4).
- **TDD per task.** Failing test first (its own commit), then implementation (separate commit), then revert-fix-and-confirm-red where practical. Never squash test + fix.
- **Test harness model — copy `kuilt-warp/src/jvmTest/kotlin/.../ChicoryRuntimeDispatchTest.kt`:** `runTest(UnconfinedTestDispatcher(), timeout = 5.seconds)`, its `settle()` helper (bounded `advanceTimeBy` over the anti-entropy interval + `runCurrent()`), `C3_QUILTER_CONFIG`, `c3Clock`. **Never `advanceUntilIdle`** — anti-entropy timers re-arm forever.
- **Existing reference reads:** `ChicorySquareOp.kt` (Chicory load/invoke + `readI32Le`/`writeI32Le`), `Creel.kt`, `BobbinExchange.kt`, `OpRegistry.kt`, `WarpNode.kt` (`executeViaRegistry` at `:731`, `recordResult` at `:833`, `removeFromQueue` at `:861`, channel constants `0x01..0x05` at `:881`).
- **Build verification before auto-merge:** `./gradlew :kuilt-warp:build detektAll --rerun-tasks` — confirms the Android + Native variants compile `commonMain` (the new API lives in `commonMain`; the impl in `jvmMain`). `jvmTest` alone is **not** proof.
- Exact Chicory class/method names below (e.g. `withMemoryLimits`, `MemoryLimits`, declared-max inspection) are the *intended approach* — verify each against the 1.4.0 jar as you implement; TDD will catch any signature drift.

## Tasks

### Task 1 — `commonMain` API surface (interface + exceptions + `OpResult.error`)

New `commonMain` files and one change, all target-agnostic (no Chicory).

**`OpResult.kt` — add a terminal-error discriminator** (TDD via `OpResultTest` in `commonTest`; CRDT-safety is the property under test):

```kotlin
@Serializable
public class OpResult private constructor(
    public val bytes: ByteArray,
    public val error: String? = null,   // non-null ⇒ terminal failure; bytes empty
) {
    public constructor(bytes: ByteArray) : this(bytes, null)
    public val isError: Boolean get() = error != null
    public companion object {
        public fun failure(message: String): OpResult = OpResult(ByteArray(0), message)
    }
    override fun equals(other: Any?): Boolean =
        this === other || (other is OpResult && bytes.contentEquals(other.bytes) && error == other.error)
    override fun hashCode(): Int = 31 * bytes.contentHashCode() + (error?.hashCode() ?: 0)
    override fun toString(): String =
        if (error != null) "OpResult(error=$error)" else "OpResult(${bytes.size} bytes)"
}
```

**`WasmRuntime.kt`** — the injectable runtime contract + sealed exception family:

```kotlin
public interface WasmRuntime {
    /** Compile + instantiate [bytes] under the capability sandbox, returning a runnable [Op].
     *  @throws WasmLoadException if the module declares an import, exceeds the memory ceiling, or is malformed. */
    public fun load(bytes: ByteArray): Op
}

public sealed class WasmException(message: String, cause: Throwable?) : Exception(message, cause)
public class WasmLoadException(message: String, cause: Throwable? = null) : WasmException(message, cause)
public class WasmExecutionException(message: String, cause: Throwable? = null) : WasmException(message, cause)
```

**`WarpLazyFetch.kt`** — the all-or-nothing capability bundle (carries a `Creel`, **not** a `BobbinExchange`):

```kotlin
public class WarpLazyFetch(
    public val creel: Creel,
    public val runtime: WasmRuntime,
    /** How a missing op names the bobbin to fetch. */
    public val opToBobbin: (OpId) -> BobbinHash?,
)
```

**Tests (`commonTest/OpResultTest.kt`):** `failure(msg).isError == true`, bytes empty; two `failure("x")` are `equals` + equal `hashCode`; `failure("x") != failure("y")`; a `bytes`-only `OpResult` is not equal to a `failure`; round-trips through the CBOR/JSON serializer used elsewhere (mirror an existing serialization test). `WasmRuntime`/`WarpLazyFetch`/`WasmException` need no behavioural test yet (Tasks 2/5 exercise them) — just that they compile and are `public`/`explicitApi`-clean.

**Success:** `./gradlew :kuilt-warp:compileKotlinJvm :kuilt-warp:jvmTest --tests "*OpResultTest"` green; `detektAll` clean. `OpResult(result)` callsite in `WarpNode.recordResult` still compiles (the public `ByteArray` constructor is retained).

---

### Task 2 — `ChicoryWasmRuntime` happy path + the `reverse` ABI fixture

**Fixture first.** `wat2wasm` is installed (`/opt/homebrew/bin/wat2wasm`, wabt 1.0.41). Author `kuilt-warp/src/jvmTest/resources/us/tractat/kuilt/warp/reverse.wat` exporting the warp ABI and reversing the arg bytes, then `wat2wasm reverse.wat -o reverse.wasm`. Mirror `square.wat`'s provenance header (the `wat2wasm` command + a one-line "byte-reverse over the warp linear-memory ABI"). Commit both `.wat` and `.wasm`.

The ABI the kernel exports (spec §2):

| Export | Signature | Role |
|--------|-----------|------|
| `memory` | linear memory | host-shared buffer |
| `warp_alloc` | `(i32) -> i32` | guest returns a writable pointer for `len` bytes |
| `warp_run` | `(i32 ptr, i32 len) -> i64` | packed result = `(resPtr.toLong() shl 32) or (resLen.toLong() and 0xFFFFFFFF)` |

**`ChicoryWasmRuntime.kt` (`jvmMain`)** — `load(bytes)` parses + instantiates once, returns an `Op` whose `invoke(args)` marshals over linear memory:

1. `argPtr = warp_alloc(args.size)`
2. `memory.write(argPtr, args)`
3. `packed = warp_run(argPtr, args.size)` (on the bounded executor — Task 4)
4. `resPtr = (packed ushr 32).toInt()`, `resLen = (packed and 0xFFFFFFFF).toInt()`
5. `return memory.readBytes(resPtr, resLen)`

Instance built once at `load`, reused across `invoke` (same lifecycle as `ChicorySquareOp`). Use `Parser.parse` + `Instance.builder(module)` exactly as `ChicorySquareOp.kt` does; resolve `memory`, `warp_alloc`, `warp_run` via `instance.export(...)` / `instance.memory()`. **This task wires the sandbox config object but the happy path only** — guards land in Tasks 3–4.

`WasmSandboxConfig.kt` (`jvmMain`):
```kotlin
public class WasmSandboxConfig(
    public val maxMemoryPages: Int = 16,          // 1 MiB
    public val executionTimeout: Duration = 1.seconds,
)
```
`ChicoryWasmRuntime(config: WasmSandboxConfig = WasmSandboxConfig())`.

**Test (`jvmTest/ChicoryWasmRuntimeTest.kt`):** load `reverse.wasm`, `op.invoke(byteArrayOf(1,2,3,4))` returns `byteArrayOf(4,3,2,1)`; an empty input returns empty; a longer ASCII payload round-trips reversed. (`invoke` is `suspend` — wrap in `runTest`.)

**Success:** `./gradlew :kuilt-warp:jvmTest --tests "*ChicoryWasmRuntimeTest"` green over real wasm.

**Fallback if `wat2wasm` ever regresses:** commit pre-built `.wasm` bytes generated offline — do **not** retreat to a scalar/`square`-shaped shortcut. The minimal linear-memory ABI is a settled design decision.

---

### Task 3 — Sandbox load-guards (imports rejected, oversize memory rejected)

Extend `ChicoryWasmRuntime.load` to fail loud at instantiation:

- **No imports.** Omit `withImportValues` so any declared import makes `Instance.builder(module).build()` throw Chicory's link error (`UnlinkableException` / `ChicoryException` subtype). Catch it → `WasmLoadException`.
- **Memory cap.** Inspect the parsed module's declared **max** pages (memory section) *before* build; if it exceeds `config.maxMemoryPages`, throw `WasmLoadException`. Then `withMemoryLimits(MemoryLimits(declaredInitial, config.maxMemoryPages))` — read the module's declared *initial* as the floor so data-segment init stays valid.
- Wrap malformed-module parse failures as `WasmLoadException` too.

**Fixtures (`jvmTest/resources/...`, `.wat` + `.wasm`):**
- `imports.wat` — declares `(import "env" "host" (func ...))`; otherwise exports the ABI.
- `bigmem.wat` — `(memory 1 64)` (declared max 64 pages > 16 cap).

**Tests (add to `ChicoryWasmRuntimeTest`):** `load(imports.wasm)` throws `WasmLoadException`; `load(bigmem.wasm)` throws `WasmLoadException`; the `reverse` happy path from Task 2 still passes (guard didn't over-reject — its declared max ≤ 16).

**Success:** `--tests "*ChicoryWasmRuntimeTest"` green; both guards proven by a thrown `WasmLoadException`.

---

### Task 4 — Sandbox run-guards (timeout + trap), **interpreter-only**

The CPU-bomb defense. Run `warp_run` on a dedicated **single-thread `ExecutorService`** owned by `ChicoryWasmRuntime`; `future.get(config.executionTimeout)`; on `TimeoutException` call `future.cancel(true)` (sets the worker's interrupt flag). Chicory's interpreter checks `Thread.isInterrupted()` at every call entry and every backward branch → throws `ChicoryInterruptedException`. Map timeout → `WasmExecutionException`; map any `ChicoryException` from `warp_run` (trap, OOB, bad packed result) → `WasmExecutionException`.

- **Interpreter-only — never call `withMachineFactory`.** The AOT path emits bytecode *without* the interrupt checks, defeating the bound. Default `InterpreterMachine` is required. Add a code comment stating this.
- `close()` shuts the executor down. `invoke` is `suspend`; bridge the blocking `future.get` appropriately (e.g. `withContext` onto the executor, or run the executor submit + timed get directly — keep it off the test's virtual scheduler since this is real CPU work, and document why).

**Fixtures:** `loop.wat` (`warp_run` body = `loop ... br 0` infinite), `trap.wat` (`warp_run` body = `unreachable`).

**Tests (add to `ChicoryWasmRuntimeTest`):** `loop.wasm` invoke throws `WasmExecutionException` and **the test does not hang** (real wall-clock timeout ~1s — keep the test's own `runTest` timeout comfortably above `executionTimeout`, or run the invoke off `runTest`); `trap.wasm` invoke throws `WasmExecutionException`. Assert the loop test completes in bounded time.

**If a test hangs: that is the bug, not a flake.** `jstack` the test JVM, confirm interpreter-only (no AOT factory) and the interrupt path. **Never widen the timeout to make it pass.**

**Success:** `--tests "*ChicoryWasmRuntimeTest"` green; loop + trap both surface `WasmExecutionException`; no hang.

---

### Task 5 — `WarpNode` wiring (node-owned exchange + fetch-load-run + `recordTerminalError`)

The integration. Edits to `WarpNode.kt` (`commonMain`):

1. **Constructor param** `private val lazyFetch: WarpLazyFetch? = null` (append after `raftNode` to preserve existing positional/named callsites).
2. **Reserved channel** `const val CHANNEL_BOBBIN: Byte = 0x06` in the companion alongside `0x01..0x05`.
3. **Node-owned exchange** (single-collection correct — decorates the node's own mux, no second `seam.incoming` collector):
   ```kotlin
   private val bobbinExchange: BobbinExchange? =
       lazyFetch?.let { BobbinExchange(mux.channel(CHANNEL_BOBBIN), it.creel, scope, quilterConfig) }
   ```
   (`quilterConfig` is a constructor param already passed into the other Quilters.)
4. **Rewrite the unresolved-op branch of `executeViaRegistry` (`:731`)** from "stand by + return null" into the fetch-load-register-run path:
   ```kotlin
   val op = registry.resolve(descriptor.op) ?: run {
       val lf = lazyFetch ?: return standBy(taskId)              // no capability → today's behaviour
       val hash = lf.opToBobbin(descriptor.op) ?: return standBy(taskId)   // unknown op → transient
       val bytes = checkNotNull(bobbinExchange).fetch(hash)      // suspends until a peer serves it
       val loaded = try { lf.runtime.load(bytes) }
           catch (e: WasmException) { return recordTerminalError(taskId, e) }  // verified-but-broken
       try { registry.register(descriptor.op, loaded) }
           catch (e: IllegalStateException) { registry.resolve(descriptor.op) ?: throw e }  // lost a register race
       registry.resolve(descriptor.op)!!
   }
   return try { op.invoke(descriptor.args) }
       catch (e: WasmException) { recordTerminalError(taskId, e); null }   // trap/timeout at run time
   ```
   Keep `executeViaRegistry`'s existing null-descriptor branch. `standBy(taskId)` = the current `lock.withLock { claimed.remove(taskId) }; return null` (extract a tiny private helper or inline — match surrounding style). `fetch`/`load` run on the existing executor coroutine, **outside `lock`**.
5. **`recordTerminalError(taskId, e: WasmException): ByteArray?`** — mirror `recordResult` (`:833`) but apply `OpResult.failure(e.message ?: e.toString())` instead of `OpResult(result)`, then `removeFromQueue(taskId, CoordinationKind.Free)` and bump `_executions` exactly as the `doExecute` Free branch does. Returns `null` (the caller already recorded). Lock-guarded read-compute-apply like `recordResult`.
6. **Fold in the #927 nit** (Task 5 touches the exchange wiring path): the `BobbinExchange` serve-loop `runCatching` on the non-suspend decode → `runCatchingCancellable`. (One-line change in `BobbinExchange.kt`'s serve `collect`.)

**Test (`jvmTest/LazyFetchAndRunTest.kt`, copying `ChicoryRuntimeDispatchTest`'s harness):** two `WarpNode`s over one `InMemoryLoom`. Node A's registry **lacks** `reverse`, and A gets a `WarpLazyFetch(creel = Creel(), runtime = ChicoryWasmRuntime(), opToBobbin = { reverseHash })`. Node B holds the `reverse` bytes — pre-populate B's `WarpLazyFetch.creel` with `reverse.wasm` (so B's exchange serves the fetch) **and** register `reverse` in B's registry OR also give B the lazyFetch creel; A enqueues a `reverse` task it owns, `settle()`, assert the reversed-bytes `OpResult` is non-error and converges on **both** boards, and that A's registry now contains `reverse` (cached). Wire the bobbin hash via `Creel().put(reverseBytes)` to get the `BobbinHash`.

**Success:** `--tests "*LazyFetchAndRunTest"` green; A fetches+runs a kernel it never compiled, result converges.

---

### Task 6 — Terminal-error + regression sims

Add to `LazyFetchAndRunTest` (or a sibling):

1. **Verified-but-broken kernel → terminal error converges.** A serves an `imports.wasm` (or `loop`/`bigmem`) bobbin for the assigned op; A fetches, `load`/`invoke` fails, `recordTerminalError` records an `OpResult.failure(...)`. Assert: the failure result converges on both boards (`isError == true`), the task is removed from the queue, and **no hot loop** — `settle()` completes, the test does not hang, and a second `settle()` produces no churn (board stable; same `OpResult` on both).
2. **Symbolic-only node regression guard (`lazyFetch == null`).** Reproduce the pre-C5b behaviour: a node with no `lazyFetch` assigned an op its registry lacks stands by — task stays pending, no result recorded, no crash. (Mirror an existing "stands by" assertion from `SymbolicDispatchTest` if one exists.)

**Success:** both sims green; terminal failure converges without a re-execution storm; null-lazyFetch path unchanged.

---

### Task 7 — Docs + close-out

- **`docs/warp-execution.md`** — update the "Lazy bobbins" and "Shipping real code: WASM kernels" sections: the fetch→load→sandbox→run loop now actually runs a fetched kernel on the JVM; describe the ABI (`warp_alloc`/`warp_run`/`memory`), the capability sandbox (no imports, memory cap, interpreter-interrupt timeout), and terminal-vs-transient failure. Keep the accessible→technical descent (CLAUDE.md docs rule): lead with "a peer can now run code it was never shipped", reveal the ABI/sandbox deeper.
- **Epic #853** — tick the C5 / C5b box.
- **Full build gate:** `./gradlew :kuilt-warp:build detektAll --rerun-tasks` (Android + Native variants compile `commonMain`; add `--no-build-cache` if any test-compile shows `FROM-CACHE`). Confirm tasks `EXECUTED`.
- **PR:** `Closes #929`, `Part of #853` (non-closing for the epic). Open it **after Task 2 lands green** to claim early, keep pushing. Auto-merge `--auto --squash` once `ci-required` is green (poll on `mergeState == CLEAN` + a `SUCCESS` `ci-required`, not bare `FAILURE` presence).

## Execution

`superpowers:subagent-driven-development` — one fresh `coding-partner` per task, `isolation: "worktree"`, review between tasks. Tasks 2→3→4 are sequential (same files, layered guards); Task 1 is independent and can lead. Tasks 5→6 sequential after 1–4. Task 7 last.

## Risks / watch-items

- **Chicory API drift** — `withMemoryLimits` / `MemoryLimits` / declared-max inspection / `ChicoryInterruptedException` names are from the 1.4.0 design review; verify against the jar. TDD catches mismatches.
- **A sandbox test hanging is the bug** (Task 4) — `jstack`, confirm interpreter-only + interrupt path, never widen the timeout.
- **Single-collection** — the node-owned `BobbinExchange` must decorate `mux.channel(CHANNEL_BOBBIN)`, never the raw `seam`; the corrected spec §4 is authoritative over the original committed wording.
