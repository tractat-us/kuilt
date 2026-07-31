package us.tractat.kuilt.warp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import us.tractat.kuilt.core.runCatchingCancellable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Properties

/**
 * The real [WasmOptimizer] a warp **compiler node** runs: Binaryen's `wasm-opt`,
 * bundled and invoked as a subprocess.
 *
 * [optimize] writes the input module to a temp file, execs
 * `wasm-opt -O<level> <in> -o <out>`, and reads the leaner module back. On first use
 * the binary (and its `libbinaryen` shared library) is extracted from the classpath to
 * a temp directory — preserving the `bin/`+`lib/` layout `wasm-opt`'s
 * `@loader_path/../lib` / `$ORIGIN/../lib` rpath needs — and made executable.
 * Extraction is JVM-wide and happens once; the temp directory is cleaned on JVM exit.
 *
 * **Where the binary comes from: one classified artifact per OS.** `wasm-opt` is a
 * native executable, so there is no single jar that works everywhere. The module's main
 * artifact therefore carries **no** binary; each supported platform is published as a
 * classified companion jar built from the official version-pinned, SHA-256-verified
 * Binaryen release. A compiler-node operator adds the one matching the host they run on:
 *
 * ```kotlin
 * implementation("us.tractat.kuilt:kuilt-warp-compiler:<version>")
 * // …plus exactly one of:
 * runtimeOnly("us.tractat.kuilt:kuilt-warp-compiler-jvm:<version>:macos-arm64")
 * runtimeOnly("us.tractat.kuilt:kuilt-warp-compiler-jvm:<version>:macos-x86_64")
 * runtimeOnly("us.tractat.kuilt:kuilt-warp-compiler-jvm:<version>:linux-x86_64")
 * runtimeOnly("us.tractat.kuilt:kuilt-warp-compiler-jvm:<version>:linux-aarch64")
 * ```
 *
 * If none is present — or the one present is for a different OS — [optimize] fails with a
 * [WasmOptimizationException] naming the exact coordinate to add. It never degrades to a
 * silent passthrough.
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

    private fun extractedBinary(): File = extractedWasmOpt.value

    private companion object {
        /** `wasm-opt` maps only for the non-passthrough levels; `O0` returns the source unchanged. */
        private val OptLevel.wasmOptFlag: String?
            get() = when (this) {
                OptLevel.O0 -> null
                OptLevel.O2 -> "-O2"
                OptLevel.O3 -> "-O3"
                OptLevel.Oz -> "-Oz"
            }

        private val RESOURCE_ROOT = BinaryenArtifacts.RESOURCE_ROOT

        /**
         * JVM-wide, thread-safe one-time extraction of the classified `wasm-opt` +
         * `libbinaryen` into a temp directory. Reads the generated `manifest.properties`
         * (written by the module's `resolveWasmOpt<Platform>` task) for the file list and
         * executable path, preserving relative paths so the binary's rpath finds the shared
         * library alongside it.
         */
        private val extractedWasmOpt: Lazy<File> = lazy {
            val host = hostPlatform()
            val manifest = loadManifest(host)
            val bundled = manifest.getProperty("binaryen.platform")
            if (bundled != null && bundled != host) throw IOException(wrongPlatformMessage(bundled, host))
            val files = manifest.getProperty("binaryen.files")
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: error("bundled Binaryen manifest is missing 'binaryen.files'")
            val executableRel = manifest.getProperty("binaryen.executable")
                ?: error("bundled Binaryen manifest is missing 'binaryen.executable'")

            val dir = Files.createTempDirectory("kuilt-binaryen-").toFile()
            Runtime.getRuntime().addShutdownHook(
                // ALLOW-runCatching: raw JVM shutdown-hook Thread, not a coroutine — there is no job here to cancel and so no cancellation to swallow.
                Thread { runCatching { dir.deleteRecursively() } },
            )
            for (rel in files) {
                val target = dir.resolve(rel)
                target.parentFile.mkdirs()
                val stream = resource(rel)
                    ?: error(
                        "Binaryen resource '$RESOURCE_ROOT/$rel' is named by the manifest but not on " +
                            "the classpath — the '$bundled' companion jar looks truncated or shaded.",
                    )
                stream.use { input -> target.outputStream().use { out -> input.copyTo(out) } }
            }
            val executable = dir.resolve(executableRel)
            check(executable.setExecutable(true)) { "could not mark $executable executable" }
            executable
        }

        private fun resource(relative: String) =
            BinaryenWasmOptimizer::class.java.getResourceAsStream("$RESOURCE_ROOT/$relative")

        private fun loadManifest(host: String?): Properties {
            val stream = resource("manifest.properties") ?: throw IOException(
                BinaryenArtifacts.missingArtifact(
                    host = host,
                    osName = System.getProperty("os.name"),
                    osArch = System.getProperty("os.arch"),
                    coordinates = coordinates(),
                    published = publishedPlatforms(),
                ),
            )
            return Properties().apply { stream.use { load(it) } }
        }

        private fun hostPlatform(): String? =
            BinaryenArtifacts.hostPlatform(System.getProperty("os.name"), System.getProperty("os.arch"))

        private fun wrongPlatformMessage(bundled: String, host: String?): String =
            BinaryenArtifacts.wrongPlatform(bundled, host, coordinates())

        /** `<group>:<artifactId>:<version>` of the jvm publication, generated into this jar. */
        private fun coordinates(): String =
            metadata()?.getProperty("binaryen.coordinates") ?: BinaryenArtifacts.UNKNOWN_COORDINATES

        /** Every classifier this build publishes, for the "published:" line of a failure. */
        private fun publishedPlatforms(): List<String> =
            metadata()?.getProperty("binaryen.platforms")
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                .orEmpty()

        private fun metadata(): Properties? {
            val stream = resource("coordinates.properties") ?: return null
            return Properties().apply { stream.use { load(it) } }
        }
    }
}

/**
 * Thrown when the bundled `wasm-opt` subprocess fails — a missing or wrong-OS classified
 * artifact, a non-zero exit, or an I/O error. Surfaces the failure loudly rather than
 * degrading to a silent passthrough (which would publish an unoptimized "variant"
 * indistinguishable from the source).
 */
public class WasmOptimizationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
