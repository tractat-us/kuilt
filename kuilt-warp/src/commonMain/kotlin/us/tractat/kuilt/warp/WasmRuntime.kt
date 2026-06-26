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
 * @see WasmException
 * @see WasmLoadException
 * @see WasmExecutionException
 */
public interface WasmRuntime {
    /**
     * Compile + instantiate [bytes] under the capability sandbox, returning a runnable [Op].
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
 * an unhandled exception at runtime.
 */
public class WasmExecutionException(message: String, cause: Throwable? = null) : WasmException(message, cause)
