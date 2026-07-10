package us.tractat.kuilt.warp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import us.tractat.kuilt.core.runCatchingCancellable
import java.io.IOException
import java.nio.file.Files
import java.util.Properties

/**
 * The real [WasmOptimizer] a warp **compiler node** runs: Binaryen's `wasm-opt`,
 * bundled and invoked as a subprocess.
 *
 * [optimize] writes the input module to a temp file, execs
 * `wasm-opt -O<level> <in> -o <out>`, and reads the leaner module back. The
 * `wasm-opt` binary (and its `libbinaryen` shared library) is resolved from the
 * official version-pinned, checksum-verified Binaryen release by the module's
 * `resolveWasmOpt` Gradle task and packaged as a JVM resource under
 * `us/tractat/kuilt/warp/binaryen/`; on first use it is extracted to a temp
 * directory (preserving the `bin/`+`lib/` layout `wasm-opt`'s `@loader_path/../lib`
 * / `$ORIGIN/../lib` rpath needs) and made executable. Extraction is JVM-wide and
 * happens once; the temp directory is cleaned on JVM exit.
 *
 * **ABI-preserving.** `wasm-opt` preserves a module's exported functions at every
 * level, so the warp `warp_alloc`/`warp_run` ABI survives — a weaker peer loads and
 * runs the optimized variant identically. See [WasmOptimizer].
 *
 * **Level mapping.** `O2 → -O2`, `O3 → -O3` (optimize for speed), `Oz → -Oz`
 * (optimize for size). [OptLevel.O0] is a **passthrough** — the input is returned
 * unchanged with no subprocess, matching [PassthroughWasmOptimizer].
 *
 * **Real blocking I/O off the caller's thread.** The subprocess is genuine
 * wall-clock work, so it runs on the injected [ioDispatcher] (a real dispatcher —
 * `Dispatchers.IO` in production, chosen at the app/factory boundary). A failed
 * exec surfaces a [WasmOptimizationException] — never a silent passthrough.
 * Cancellation propagates (via [runCatchingCancellable]).
 *
 * @param ioDispatcher Dispatcher the blocking `wasm-opt` subprocess runs on. Required
 *   (no real-dispatcher default inside the type): production supplies `Dispatchers.IO`.
 */
public class BinaryenWasmOptimizer(
    private val ioDispatcher: CoroutineDispatcher,
) : WasmOptimizer {

    override suspend fun optimize(bytes: ByteArray, optLevel: OptLevel): ByteArray {
        val flag = optLevel.wasmOptFlag ?: return bytes // O0 — nothing worth shipping.
        return withContext(ioDispatcher) {
            runCatchingCancellable { runWasmOpt(bytes, flag) }
                .getOrElse { cause ->
                    throw WasmOptimizationException("wasm-opt $flag failed: ${cause.message}", cause)
                }
        }
    }

    private fun runWasmOpt(bytes: ByteArray, flag: String): ByteArray {
        val wasmOpt = extractedBinary()
        val input = Files.createTempFile("warp-in-", ".wasm")
        val output = Files.createTempFile("warp-out-", ".wasm")
        try {
            Files.write(input, bytes)
            val process = ProcessBuilder(
                wasmOpt.absolutePath,
                flag,
                input.toAbsolutePath().toString(),
                "-o",
                output.toAbsolutePath().toString(),
            ).redirectErrorStream(false).start()
            val stderr = process.errorStream.readBytes().decodeToString()
            val exit = process.waitFor()
            check(exit == 0) { "wasm-opt exited $exit: ${stderr.ifBlank { "(no diagnostics)" }}" }
            return Files.readAllBytes(output)
        } finally {
            Files.deleteIfExists(input)
            Files.deleteIfExists(output)
        }
    }

    private fun extractedBinary(): java.io.File = extractedWasmOpt.value

    private companion object {
        /** `wasm-opt` maps only for the non-passthrough levels; `O0` returns the source unchanged. */
        private val OptLevel.wasmOptFlag: String?
            get() = when (this) {
                OptLevel.O0 -> null
                OptLevel.O2 -> "-O2"
                OptLevel.O3 -> "-O3"
                OptLevel.Oz -> "-Oz"
            }

        private const val RESOURCE_ROOT = "binaryen"

        /**
         * JVM-wide, thread-safe one-time extraction of the bundled `wasm-opt` + `libbinaryen`
         * into a temp directory. Reads the generated `manifest.properties` (written by
         * `resolveWasmOpt`) for the file list and executable path, preserving relative paths so
         * the binary's rpath finds the shared library alongside it.
         */
        private val extractedWasmOpt: Lazy<java.io.File> = lazy {
            val manifest = loadManifest()
            val files = manifest.getProperty("binaryen.files")
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: error("bundled Binaryen manifest is missing 'binaryen.files'")
            val executableRel = manifest.getProperty("binaryen.executable")
                ?: error("bundled Binaryen manifest is missing 'binaryen.executable'")

            val dir = Files.createTempDirectory("kuilt-binaryen-").toFile()
            Runtime.getRuntime().addShutdownHook(
                Thread { runCatching { dir.deleteRecursively() } },
            )
            for (rel in files) {
                val target = dir.resolve(rel)
                target.parentFile.mkdirs()
                val stream = BinaryenWasmOptimizer::class.java.getResourceAsStream("$RESOURCE_ROOT/$rel")
                    ?: error(
                        "bundled Binaryen resource '$RESOURCE_ROOT/$rel' not found on the classpath — " +
                            "was :kuilt-warp-compiler built with the resolveWasmOpt task on a supported host?",
                    )
                stream.use { input -> target.outputStream().use { out -> input.copyTo(out) } }
            }
            val executable = dir.resolve(executableRel)
            check(executable.setExecutable(true)) { "could not mark $executable executable" }
            executable
        }

        private fun loadManifest(): Properties {
            val stream = BinaryenWasmOptimizer::class.java.getResourceAsStream("$RESOURCE_ROOT/manifest.properties")
                ?: throw IOException(
                    "bundled Binaryen manifest not found on the classpath. :kuilt-warp-compiler must be " +
                        "built with the resolveWasmOpt task (macOS/Linux host); a compiler node is a JVM/server peer.",
                )
            return Properties().apply { stream.use { load(it) } }
        }
    }
}

/**
 * Thrown when the bundled `wasm-opt` subprocess fails — a missing binary, a non-zero exit, or an
 * I/O error. Surfaces the failure loudly rather than degrading to a silent passthrough (which
 * would publish an unoptimized "variant" indistinguishable from the source).
 */
public class WasmOptimizationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
