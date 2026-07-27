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

        private const val RESOURCE_ROOT = "binaryen"

        /** Fallback when the generated coordinates resource is somehow absent. */
        private const val UNKNOWN_COORDINATES = "us.tractat.kuilt:kuilt-warp-compiler-jvm:<version>"

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
            val stream = resource("manifest.properties") ?: throw IOException(missingArtifactMessage(host))
            return Properties().apply { stream.use { load(it) } }
        }

        /**
         * This JVM's platform in published-classifier form (`<os>-<arch>`), or `null` when
         * no `wasm-opt` is published for it. A compiler node is a JVM/server peer on
         * macOS or Linux; every other host is a pure consumer of the optimized variant.
         */
        private fun hostPlatform(): String? {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            val arch = System.getProperty("os.arch").orEmpty().lowercase()
            val osKey = when {
                os.contains("mac") || os.contains("darwin") -> "macos"
                os.contains("linux") -> "linux"
                else -> return null
            }
            val archKey = when (arch) {
                "aarch64", "arm64" -> if (osKey == "macos") "arm64" else "aarch64"
                "x86_64", "amd64" -> "x86_64"
                else -> return null
            }
            return "$osKey-$archKey"
        }

        /** `<group>:<artifactId>:<version>` of the jvm publication, generated into this jar. */
        private fun coordinates(): String =
            metadata()?.getProperty("binaryen.coordinates") ?: UNKNOWN_COORDINATES

        /** Every classifier this build publishes, for the "published:" line of a failure. */
        private fun publishedPlatforms(): List<String> =
            metadata()?.getProperty("binaryen.platforms")
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                .orEmpty()

        private fun metadata(): Properties? {
            val stream = resource("coordinates.properties") ?: return null
            return Properties().apply { stream.use { load(it) } }
        }

        private fun missingArtifactMessage(host: String?): String {
            val published = publishedPlatforms()
            val header = "no Binaryen `wasm-opt` on the runtime classpath. :kuilt-warp-compiler " +
                "publishes the native binary as one classified companion jar per OS, so the main " +
                "artifact stays lean"
            if (host == null || (published.isNotEmpty() && host !in published)) {
                return "$header. This JVM (os.name=${System.getProperty("os.name")}, " +
                    "os.arch=${System.getProperty("os.arch")}) has no published `wasm-opt`" +
                    publishedSuffix(published) + ". A warp compiler node is a JVM/server peer on " +
                    "macOS or Linux; other peers consume the optimized variant without running wasm-opt."
            }
            return "$header. Add the one for this host:\n" +
                "    runtimeOnly(\"${coordinates()}:$host\")" +
                publishedSuffix(published)
        }

        private fun wrongPlatformMessage(bundled: String, host: String?): String =
            "the Binaryen `wasm-opt` on the runtime classpath is the '$bundled' build, but this JVM " +
                "is '${host ?: "an unsupported platform"}'. Replace the classified dependency with " +
                "the matching one:\n    runtimeOnly(\"${coordinates()}:${host ?: "<platform>"}\")"

        private fun publishedSuffix(published: List<String>): String =
            if (published.isEmpty()) "" else " (published: ${published.joinToString(", ")})"
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
