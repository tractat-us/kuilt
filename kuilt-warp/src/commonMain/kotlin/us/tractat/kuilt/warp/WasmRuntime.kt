package us.tractat.kuilt.warp

/**
 * Injectable contract for a sandboxed WASM execution environment.
 *
 * Production implementations are target-specific (JVM: Chicory; browser: WebAssembly API).
 * Tests inject a fake that returns a known [Op] without compiling real WASM bytes.
 *
 * **Capability sandbox.** A conforming implementation MUST reject any WASM module that
 * declares an import (i.e. requests host capabilities beyond the compute sandbox). Malformed
 * bytes and modules missing the warp ABI exports are also rejected. All cases surface as
 * [WasmLoadException].
 *
 * **Memory ceiling — a bounded declared max is REQUIRED.** A conforming implementation MUST
 * reject at load any module whose declared linear memory has an initial size or explicit max
 * exceeding [WasmSandboxConfig.maxMemoryPages], declares memory with **no explicit max**, or
 * declares no linear memory at all (the warp ABI marshals args/results through memory). The
 * no-max rule is uniform across targets because it is the only one enforceable identically
 * everywhere — the browser cannot re-impose a max on a compiled module, so clamping is not an
 * option there, and a no-max module could otherwise `memory.grow` to ~4 GiB (a memory-bomb
 * DoS). Requiring a bounded `max <= cap` delegates growth enforcement to the engine: every
 * conforming engine traps/denies a `memory.grow` past the declared max.
 *
 * **Execution-time bound.** A conforming implementation MUST also bound each invocation of a
 * returned [Op] by the configured execution timeout ([WasmSandboxConfig.executionTimeout]): a
 * runaway kernel — e.g. an infinite `loop`/`br` spin (a CPU bomb) — is terminated and surfaces
 * as [WasmExecutionException], never a hung host. The mechanism is per-target (JVM: interpreter
 * interrupt; native: cooperative interpreter deadline; browser: Web Worker terminate) but the
 * contract is uniform: kernels arrive from untrusted peers, so an unbounded guest is a
 * remotely-triggerable denial of service.
 *
 * **Load-phase execution bound.** Instantiation runs a module's `(start)` function — guest
 * code, before any [Op] invocation exists — so the execution budget MUST also bound it. The
 * phase is impl-defined: an implementation may run `(start)` eagerly under a bounded [load]
 * (surfacing a budget-exceeded [WasmLoadException]) or defer instantiation to the first
 * bounded invocation (surfacing [WasmExecutionException]); either way a `(start)` CPU bomb
 * fails terminally near the budget, never a hung host.
 *
 * @see WasmException
 * @see WasmLoadException
 * @see WasmExecutionException
 */
public interface WasmRuntime {
    /**
     * Compile + instantiate [bytes] under the capability sandbox, returning a runnable [Op]
     * whose invocations are bounded by the configured execution timeout.
     *
     * @throws WasmLoadException if the module declares an import, violates the memory ceiling
     *   (over-cap initial or max, no explicit max, or no memory at all), is malformed, lacks
     *   the `warp_alloc`/`warp_run` ABI exports, or — on implementations that instantiate
     *   eagerly — its `(start)` function traps or exceeds the execution budget (see the
     *   load-phase execution bound above).
     */
    public fun load(bytes: ByteArray): Op
}

/**
 * Base class for all WASM-runtime failures.
 *
 * Sealed so callers can exhaustively handle load vs execution failures without a catch-all.
 *
 * @see WasmLoadException
 * @see WasmExecutionException
 */
public sealed class WasmException(message: String, cause: Throwable?) : Exception(message, cause)

/**
 * Thrown by [WasmRuntime.load] when a WASM module cannot be loaded into the sandbox.
 *
 * Covers every load-time guard: the module declares an import (capability violation), it
 * violates the memory ceiling (over-cap initial or max, no explicit max, or no linear memory
 * at all), the bytes are malformed / not valid WASM, or a required warp ABI export
 * (`warp_alloc`/`warp_run`) is missing. On implementations that instantiate eagerly at load,
 * also covers a `(start)` function that traps or exceeds the execution budget.
 */
public class WasmLoadException(message: String, cause: Throwable? = null) : WasmException(message, cause)

/**
 * Thrown by an [Op] returned from [WasmRuntime.load] when the WASM module traps or raises
 * an unhandled exception at runtime, or exceeds the sandbox's execution-time budget
 * ([WasmSandboxConfig.executionTimeout]).
 */
public class WasmExecutionException(message: String, cause: Throwable? = null) : WasmException(message, cause)
