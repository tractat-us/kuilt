# C5b — production wasm-runtime + lazy fetch-and-run (JVM slice)

**Issue:** [#929](https://github.com/tractat-us/kuilt/issues/929) · **Epic:** [#853](https://github.com/tractat-us/kuilt/issues/853) (Epic C — code mobility) · **Design:** `docs/warp-execution.md` (§ "Shipping real code: WASM kernels", "Lazy bobbins")
**Date:** 2026-06-26 · **Status:** approved design, pre-implementation

## Problem

Warp's lazy-code-mobility loop is one wiring step from closed. The pieces exist:

- **C2-int (#877):** `WarpNode` resolves `descriptor.op` in its own `OpRegistry` and runs its own registered copy.
- **C4 + C4-harden (#897/#899):** `Creel`/`BobbinHash` content-addressed store, SHA-256.
- **C5a (#927):** `BobbinExchange` — `GSet<BobbinHash>` manifest gossiped eagerly + on-demand `fetch(hash)` with tamper-verify (`Creel.putVerified`).
- **C3 (#918 jvm, #928 browser):** two wasm runtimes proven to dispatch+execute a real `.wasm` kernel end-to-end.

The gap: when `WarpNode` is assigned an op it does not hold, `executeViaRegistry` (`WarpNode.kt:731`) logs "bobbin not loaded yet — standing by" and returns `null`. It never **fetches** the bobbin and **runs** it. Two reasons it can't yet:

1. **No production `bytes → Op` loader.** The C3 runtimes live in *test* source sets and only run a square-shaped `(i32) -> i32` kernel. Production `Op` is `ByteArray → ByteArray` (`Op.kt`). A general loader needs a **calling convention** (how opaque arg-bytes enter guest linear memory and result-bytes come back) — neither C3 proof solved this.
2. **Running a peer-supplied kernel is the first point kuilt executes code it did not compile.** SHA-256 verify (`Creel.putVerified`) guarantees *integrity* — the bytes match the advertised hash — but not *safety* of what those bytes do. This needs an explicit **capability sandbox**: which imports a kernel may bind, resource limits, fail-loud handling.

## Scope

**JVM vertical slice.** Land fetch→load→run end-to-end for JVM only, with a real (not square-shaped) ABI and a sandbox that actually contains a malicious kernel. Browser (`WebAssembly` JS API) and native (wasm3) `WasmRuntime` impls are deferred to follow-up issues; the `commonMain` API is shaped so they slot in without rework.

Non-goals this slice: per-import host-function allowlist (we reject *all* imports); browser/native runtimes; KSP op auto-registration (#925).

## Design

### 1. Runtime API (`commonMain`)

A plain interface — injectable and fakeable, chosen over `expect`/`actual` because `WarpNode` takes it as a *dependency* and tests inject a fake:

```kotlin
public interface WasmRuntime {
    /**
     * Compile and instantiate [bytes] under the capability sandbox, returning a runnable [Op].
     *
     * Fail-loud: throws [WasmLoadException] if the module declares any import, exceeds the
     * memory ceiling, or is malformed. The returned [Op.invoke] throws [WasmExecutionException]
     * if the kernel traps or exceeds the execution-time budget.
     */
    public fun load(bytes: ByteArray): Op
}
```

`WasmLoadException` (instantiation-time: imports present, oversize, malformed) and `WasmExecutionException` (run-time: trap, timeout) are `public` exceptions in `commonMain`, both extending a `public sealed class WasmException`. The `Op` returned by `load` is the standard `Op` fun-interface — its `invoke` performs the marshalling described in §2.

### 2. The ABI — what a warp wasm kernel must export

A kernel is a wasm module exporting:

| Export | Signature | Role |
|--------|-----------|------|
| `memory` | (linear memory) | the shared buffer the host reads/writes |
| `warp_alloc` | `(len: i32) -> i32` | guest returns a pointer to `len` writable bytes |
| `warp_run` | `(ptr: i32, len: i32) -> i64` | run over the arg bytes at `[ptr, ptr+len)`; return a packed `i64` = `(resPtr.toLong() shl 32) or (resLen.toLong() and 0xFFFFFFFF)` |

`ChicoryWasmRuntime.load(bytes)` returns an `Op` whose `invoke(args)`:

1. `argPtr = warp_alloc(args.size)`
2. `memory.write(argPtr, args)`
3. `packed = warp_run(argPtr, args.size)` (run on the bounded executor — §3)
4. `resPtr = (packed ushr 32).toInt()`, `resLen = (packed and 0xFFFFFFFF).toInt()`
5. `return memory.readBytes(resPtr, resLen)`

The guest owns its memory layout; the host never frees (kernels are single-shot per `invoke`, instance reused across invokes — same lifecycle as the C3 proofs). The instance is built once at `load` time and reused.

**Test kernel:** a tiny `reverse.wat`/`reverse.wasm` exporting this ABI (reverses the arg bytes) replaces `square` as the ABI exemplar — proves real `ByteArray → ByteArray` marshalling, not a scalar shortcut. Committed under `kuilt-warp/src/jvmTest/resources/...` with `.wat` source + provenance comment (mirrors `square.wat`/`square.wasm`).

### 3. Capability sandbox (`ChicoryWasmRuntime`, jvmMain)

Chicory 1.4.0 capabilities were verified against the 1.4.0 source tag. **There is no fuel/gas API** (the only per-instruction hook, `withUnsafeExecutionListener`, is experimental, hot-path, and unsupported — not used). The idiomatic and *complete* CPU bound is thread interruption.

- **No imports (automatic).** Omit `withImportValues` → `Instance.builder(module).build()` throws `UnlinkableException` for any declared import. Any kernel needing a host function fails loud at instantiation. Wrapped as `WasmLoadException`.
- **Memory cap.** Inspect the parsed module's declared `max` pages before build; reject (`WasmLoadException`) if it exceeds `config.maxMemoryPages`. Then `withMemoryLimits(MemoryLimits(declaredInitial, config.maxMemoryPages))`, reading the module's declared initial as the floor so data-segment init stays valid.
- **Execution-time bound (the CPU-bomb defense).** Run `warp_run` on a dedicated single-thread `ExecutorService`; `future.get(config.executionTimeout)`; on `TimeoutException`, `future.cancel(true)` sets the worker's interrupt flag. Chicory's **interpreter checks `Thread.isInterrupted()` at every function-call entry and every backward branch**, throwing `ChicoryInterruptedException`. Unbounded CPU in wasm *requires* a loop or recursion (the code section is finite, so straight-line code is always bounded) — and those are exactly the checked sites — so the timeout **fully bounds** the threat. Surface as `WasmExecutionException`.
  - **Interpreter-only.** Never call `withMachineFactory` (the AOT path emits bytecode without the interrupt checks). Default machine factory (`InterpreterMachine`) is what we want.
- **Fail-loud on trap.** Any `ChicoryException` from `warp_run` (trap, OOB memory, bad packed result) → `WasmExecutionException`. Never corrupt the results board.

```kotlin
public class WasmSandboxConfig(
    public val maxMemoryPages: Int = 16,          // 1 MiB; conservative
    public val executionTimeout: Duration = 1.seconds,
)
```

`ChicoryWasmRuntime(config: WasmSandboxConfig = WasmSandboxConfig())` owns the single-thread executor; a `close()` shuts it down. The Chicory `implementation(libs.chicory.runtime)` dependency is **already in `jvmMain`** (`kuilt-warp/build.gradle.kts:28`) — no build change; the production loader code is new.

### 4. WarpNode wiring

The capability is injected as one **all-or-nothing bundle** rather than a bare nullable runtime — so it cannot be half-configured (creel without runtime, or vice versa), and `null` is the genuine pre-existing optional (a symbolic-only node):

```kotlin
public class WarpLazyFetch(
    /** The local content-addressed byte store a serving peer pre-populates and a fetching peer caches into. */
    public val creel: Creel,
    public val runtime: WasmRuntime,
    /** How a missing op names the bobbin to fetch. Gossiped alongside the manifest. */
    public val opToBobbin: (OpId) -> BobbinHash?,
)
```

**`WarpLazyFetch` carries a `Creel`, not a pre-built `BobbinExchange`.** A `BobbinExchange`
builds its *own* internal `MuxSeam` over the seam it is handed; `WarpNode` already owns a
`MuxSeam` over the same fabric. Handing `WarpNode` a ready-made exchange over the *raw*
seam would create a **second collector** on `seam.incoming` — a single-collection (ADR-034)
violation. Instead, `WarpNode` **owns** the exchange internally, built over a reserved mux
channel so it shares the node's one event loop:

```kotlin
private const val CHANNEL_BOBBIN: Byte = 0x06   // reserved alongside CHANNEL_QUEUE..CHANNEL_COORD_QUEUE (0x01..0x05)

private val bobbinExchange: BobbinExchange? =
    lazyFetch?.let { BobbinExchange(mux.channel(CHANNEL_BOBBIN), it.creel, scope, quilterConfig) }
```

A serving peer pre-populates its `Creel` (`Creel().also { it.put(reverseBytes) }`) before
constructing `WarpLazyFetch`; manifest discovery is off C5b's critical path because
`opToBobbin` hands the hash directly.

New `WarpNode` constructor parameter: `private val lazyFetch: WarpLazyFetch? = null`.

- **`null`** = today's exact behavior: `executeViaRegistry` stands by on an unresolved op ("bobbin not loaded yet"), task stays pending, anti-entropy re-evaluates. Documented, explicit — not a silent disable.
- **non-null** = the unresolved-op branch in `executeViaRegistry` (`WarpNode.kt:731`) becomes a fetch-load-register-run path (`bobbinExchange` is the node-owned exchange above):

```kotlin
val op = registry.resolve(descriptor.op) ?: lazyFetch?.let { lf ->
    val hash = lf.opToBobbin(descriptor.op) ?: return@let null   // unknown op → stand by (transient)
    val bytes = checkNotNull(bobbinExchange).fetch(hash)         // suspends until a peer serves it
    val loaded = try {
        lf.runtime.load(bytes)
    } catch (e: WasmException) {                                 // verified bytes, but broken/malicious
        return recordTerminalError(taskId, e)                    // §5 — terminal, do not retry
    }
    registry.register(descriptor.op, loaded)                     // cache for subsequent tasks
    loaded
} ?: run {
    // still unresolved (no lazyFetch, or opToBobbin returned null) — stand by as today
    lock.withLock { claimed.remove(taskId) }
    return null
}
return try { op.invoke(descriptor.args) } catch (e: WasmException) {
    recordTerminalError(taskId, e); null                         // trap/timeout at run time → terminal
}
```

`registry.register` throws on duplicate (`OpRegistry`); a concurrent register race is contained by re-resolving under the registry lock — on `IllegalStateException` from a lost race, fall back to `registry.resolve(descriptor.op)`.

The `coordinatedExecutor` path is untouched. The single-collection (ADR-034), lock-discipline, and dispatcher conventions of `WarpNode` are preserved — the node-owned `bobbinExchange` decorates the node's own `MuxSeam` (no second `seam.incoming` collector), and `fetch`/`load` are called from the existing executor coroutine, outside the `lock`.

### 5. Terminal-failure handling (convergence-critical)

A *verified* kernel that fails to load (imports/oversize/malformed) or fails to run (trap/timeout) will **never** succeed. Retrying it forever via anti-entropy is exactly the kind of churn the OpResult/Quilter storm guardrail warns against. So such a failure is **terminal**: it records an error result that converges, instead of standing by.

`OpResult` gains an optional error discriminator (kept CRDT-safe — content equality, serializable):

```kotlin
@Serializable
public class OpResult private constructor(
    public val bytes: ByteArray,
    public val error: String? = null,   // non-null ⇒ terminal failure; bytes empty
) {
    public constructor(bytes: ByteArray) : this(bytes, null)
    public val isError: Boolean get() = error != null
    public companion object { public fun failure(message: String): OpResult = OpResult(ByteArray(0), message) }
    // equals/hashCode include both bytes (contentEquals) and error
}
```

`recordTerminalError(taskId, e)` records `OpResult.failure(e.message)` on the board via the existing `recordResult` lock-guarded path and removes the task from the queue — the task converges to a terminal error entry on every peer, diagnosable, no hot loop. A *transient* unresolved op (no `lazyFetch`, or `opToBobbin` returns `null`, or `fetch` hasn't completed) is **not** terminal — it stands by and retries, exactly as today.

Distinction in one line: **transient = we can't run it yet; terminal = we ran it (or tried to instantiate the verified bytes) and it failed.**

## Testing

Multi-node sim over the **coordination-free path** (no Raft — the reverse task is `CoordinationKind.Free`), copying the existing C3 harness `ChicoryRuntimeDispatchTest.kt`: `InMemoryLoom` host+join, `runTest(UnconfinedTestDispatcher(), timeout = 5.seconds)`, the C3 `settle()` helper (bounded `advanceTimeBy` over the anti-entropy interval — **never `advanceUntilIdle`**, the anti-entropy timers re-arm forever). Both nodes get a real `ChicoryWasmRuntime`; the *serving* node pre-populates its `Creel` with the `reverse` bytes, the *fetching* node's registry lacks `reverse`:

1. **Happy path:** a node whose registry lacks `reverse` is assigned a `reverse` task; it fetches the bobbin from a peer that holds it, loads it sandboxed, runs it, and the reversed-bytes result merges onto every peer's board.
2. **Import-declaring kernel** → `WasmLoadException` → terminal error `OpResult` on the board, never executed; board converges.
3. **Infinite-loop kernel** (`loop … br 0`) → interrupted at the timeout → `WasmExecutionException` → terminal error; the test does not hang (proves the interrupt bound).
4. **Oversize-memory kernel** (declared max > cap) → `WasmLoadException` → terminal error.
5. **Symbolic-only node** (`lazyFetch == null`): unresolved op stands by, task stays pending — unchanged behavior (regression guard).

JVM only this slice. Full `./gradlew :kuilt-warp:build detektAll --rerun-tasks` before auto-merge (Android + Native variants compile commonMain; the new production code is jvmMain/commonMain).

## Files

- **New (`commonMain`):** `WasmRuntime.kt` (interface + `WasmException`/`WasmLoadException`/`WasmExecutionException`), `WarpLazyFetch.kt`.
- **New (`jvmMain`):** `ChicoryWasmRuntime.kt`, `WasmSandboxConfig.kt`.
- **Changed (`commonMain`):** `OpResult.kt` (+ `error` discriminator), `WarpNode.kt` (`lazyFetch: WarpLazyFetch?` param + node-owned `BobbinExchange` over `CHANNEL_BOBBIN: Byte = 0x06` + `executeViaRegistry` fetch-load-run + `recordTerminalError`).
- **New (`jvmTest`):** `reverse.wat` + `reverse.wasm` resources, `ChicoryWasmRuntimeTest.kt` (runtime + sandbox units: imports/oversize/timeout/trap), `LazyFetchAndRunTest.kt` (multi-node sim).
- **Docs:** update `docs/warp-execution.md` "Lazy bobbins" / "Shipping real code" sections once landed; tick C5 on epic #853.

## Deferred (follow-up issues)

- **Browser `WasmRuntime`** via the `WebAssembly` JS API (`wasmJsMain`) — promote the #928 proof to the production ABI.
- **Native `WasmRuntime`** via wasm3 (`nativeMain`) — coordinate with the sibling `warp/c3-native-wasm3` work (#923).
- **Per-import host-function allowlist** — if kernels ever need vetted capabilities beyond pure compute.
- **`runCatchingCancellable` nit** in `BobbinExchange.kt` serve-loop decode (logged on #927) — fix when C5b next touches that file.
