package us.tractat.kuilt.warp

/**
 * Injectable contract for a sandboxed WASM execution environment.
 *
 * Production implementations are target-specific (JVM: Chicory; browser: WebAssembly API).
 * Tests inject a fake that returns a known [Op] without compiling real WASM bytes.
 *
 * **Capability sandbox.** A conforming implementation MUST reject any WASM module that
 * declares an import (i.e. requests host capabilities beyond the compute sandbox) or that
 * exceeds the runtime's memory ceiling. Malformed bytes are also rejected. All three cases
 * surface as [WasmLoadException].
 *
 * **Execution-time bound.** A conforming implementation MUST also bound each invocation of a
 * returned [Op] by the configured execution timeout ([WasmSandboxConfig.executionTimeout]): a
 * runaway kernel — e.g. an infinite `loop`/`br` spin (a CPU bomb) — is terminated and surfaces
 * as [WasmExecutionException], never a hung host. The mechanism is per-target (JVM: interpreter
 * interrupt; native: cooperative interpreter deadline; browser: Web Worker terminate) but the
 * contract is uniform: kernels arrive from untrusted peers, so an unbounded guest is a
 * remotely-triggerable denial of service.
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
     * @throws WasmLoadException if the module declares an import, exceeds the memory ceiling,
     *   or is malformed.
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
 * Covers three cases: the module declares an import (capability violation), the module
 * exceeds the runtime's memory ceiling, or the bytes are malformed / not valid WASM.
 */
public class WasmLoadException(message: String, cause: Throwable? = null) : WasmException(message, cause)

/**
 * Thrown by an [Op] returned from [WasmRuntime.load] when the WASM module traps or raises
 * an unhandled exception at runtime, or exceeds the sandbox's execution-time budget
 * ([WasmSandboxConfig.executionTimeout]).
 */
public class WasmExecutionException(message: String, cause: Throwable? = null) : WasmException(message, cause)
