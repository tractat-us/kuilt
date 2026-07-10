import org.gradle.process.ExecOperations
import java.io.File
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject

plugins {
    id("kuilt.kmp-library")
}

// ── Binaryen `wasm-opt`: resolved from the official prebuilt release ──────────
//
// A compiler node execs `wasm-opt` on a raw kernel to publish a leaner variant
// (see BinaryenWasmOptimizer). The binary is NOT committed to git (matching the
// repo's no-committed-binaries ethos — cf. the vendored-source wasm3 build in
// :kuilt-warp-runtime). Instead this task downloads the version-pinned, official
// Binaryen release archive for the BUILD HOST's OS/arch, verifies it against the
// upstream-published SHA-256, extracts `bin/wasm-opt` + `lib/libbinaryen.*`
// (wasm-opt links the shared lib via `@loader_path/../lib` / `$ORIGIN/../lib`,
// so the bin/lib layout is preserved), and packages them as JVM resources under
// `us/tractat/kuilt/warp/binaryen/`. At runtime BinaryenWasmOptimizer extracts
// those resources to a temp dir and execs the binary.
//
// **Host-only packaging (the honest cost — see the PR "Design" note).** The task
// bundles only the *build host's* binary, so a release jar staged on a Linux
// runner carries the Linux `wasm-opt`; a Mac consumer of that jar would find no
// matching binary and BinaryenWasmOptimizer throws a clear error. For D4-2 this
// is exactly right: it makes `wasm-opt` present on the CI runner (x86_64-linux)
// and locally (arm64-macos) so the deterministic tests run un-gated. Portable
// multi-OS packaging (bundle all four, or classifier jars) is a deliberate
// follow-up, flagged for review rather than guessed at here.
//
// Deliberately NOT @CacheableTask: the download is cached under build/binaryen/
// keyed by the pinned filename+checksum, and keeping the archive out of the
// shared (S3-backed) build cache means the bytes packed into the jar are always
// the checksum-verified upstream release produced on the building machine.

val binaryenVersion = "130"

// hostKey → "releaseFilename|sha256". Checksums are the upstream-published
// <archive>.sha256 values for version_130 (verified at pin time). The build host
// picks its row via os.name/os.arch; an unsupported host fails with a clear error.
private val binaryenArtifacts = mapOf(
    "arm64-macos" to
        "binaryen-version_130-arm64-macos.tar.gz|79d3ab9f417d9e215f15f598f523d001a7d9ac1e59367e5c869fbdabd1cba72e",
    "x86_64-macos" to
        "binaryen-version_130-x86_64-macos-14.tar.gz|d3e2d1235b70c93c54b52eabc1625ea960965152218754f1f4eeb0f873c48e03",
    "x86_64-linux" to
        "binaryen-version_130-x86_64-linux.tar.gz|0a18362361ad05465118cd8eeb72edaeec89de6894bc283576ef4e07aa3babcc",
    "aarch64-linux" to
        "binaryen-version_130-aarch64-linux.tar.gz|e6ae6e09ac40f4e14bc5be6f687c58e2995c84170013975fa641809dd3b480a0",
)

abstract class ResolveWasmOpt : DefaultTask() {
    @get:Inject
    protected abstract val execOperations: ExecOperations

    /** Binaryen release version (the `version_<N>` tag's `<N>`). */
    @get:Input
    abstract val version: Property<String>

    /** hostKey → "releaseFilename|sha256" for every supported build host. */
    @get:Input
    abstract val artifacts: MapProperty<String, String>

    /** Cache for the downloaded archive + extraction (kept out of the build cache). */
    @get:Internal
    abstract val downloadDir: DirectoryProperty

    /** The generated resources root — packaged into the jvm jar. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun resolve() {
        val hostKey = detectHostKey()
        val spec = artifacts.get()[hostKey] ?: error(
            "no pinned Binaryen release for build host '$hostKey' " +
                "(pinned: ${artifacts.get().keys.sorted()}). A compiler node is a JVM/server " +
                "peer on a supported OS; build :kuilt-warp-compiler on one of those hosts.",
        )
        val (filename, expectedSha) = spec.split("|", limit = 2)
            .also { check(it.size == 2) { "malformed artifact spec for '$hostKey': $spec" } }
        val cache = downloadDir.get().asFile.apply { mkdirs() }
        val archive = cache.resolve(filename)
        if (!archive.exists() || sha256(archive) != expectedSha) {
            val url = "https://github.com/WebAssembly/binaryen/releases/download/" +
                "version_${version.get()}/$filename"
            logger.lifecycle("Downloading Binaryen $filename …")
            downloadTo(url, archive)
        }
        val actualSha = sha256(archive)
        check(actualSha == expectedSha) {
            "Binaryen checksum mismatch for $filename: expected $expectedSha but got $actualSha"
        }

        val extractDir = cache.resolve("extracted-$hostKey").apply { deleteRecursively(); mkdirs() }
        execOperations.exec { commandLine("tar", "xzf", archive.absolutePath, "-C", extractDir.absolutePath) }
        val releaseRoot = extractDir.resolve("binaryen-version_${version.get()}")
        val wasmOpt = releaseRoot.resolve("bin/wasm-opt")
        check(wasmOpt.exists()) { "extracted archive is missing bin/wasm-opt at $wasmOpt" }
        val libFiles = releaseRoot.resolve("lib").listFiles()
            ?.filter { it.isFile && it.name.startsWith("libbinaryen") }
            ?: error("extracted archive has no lib/ directory at ${releaseRoot.resolve("lib")}")

        // Package under the BinaryenWasmOptimizer package so getResource("binaryen/…") resolves.
        val resourceRoot = outputDir.get().asFile.apply { deleteRecursively() }
        val binaryenDir = resourceRoot.resolve("us/tractat/kuilt/warp/binaryen")
        binaryenDir.resolve("bin").mkdirs()
        binaryenDir.resolve("lib").mkdirs()
        wasmOpt.copyTo(binaryenDir.resolve("bin/wasm-opt"), overwrite = true)
        libFiles.forEach { it.copyTo(binaryenDir.resolve("lib/${it.name}"), overwrite = true) }

        val relPaths = listOf("bin/wasm-opt") + libFiles.map { "lib/${it.name}" }
        binaryenDir.resolve("manifest.properties").writeText(
            buildString {
                append("# Generated by :kuilt-warp-compiler ResolveWasmOpt — do not edit.\n")
                append("binaryen.version=${version.get()}\n")
                append("binaryen.host=$hostKey\n")
                append("binaryen.executable=bin/wasm-opt\n")
                append("binaryen.files=${relPaths.joinToString(",")}\n")
            },
        )
        logger.lifecycle("Resolved Binaryen ${version.get()} for $hostKey (${relPaths.size} files).")
    }

    private fun detectHostKey(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val osKey = when {
            os.contains("mac") || os.contains("darwin") -> "macos"
            os.contains("linux") -> "linux"
            else -> error("unsupported build-host OS '$os' for wasm-opt (macOS/Linux only)")
        }
        val archKey = when (arch) {
            "aarch64", "arm64" -> if (osKey == "macos") "arm64" else "aarch64"
            "x86_64", "amd64" -> "x86_64"
            else -> error("unsupported build-host arch '$arch' for wasm-opt (x86_64/arm64 only)")
        }
        return "$archKey-$osKey"
    }

    private fun downloadTo(url: String, target: File) {
        target.parentFile.mkdirs()
        URI(url).toURL().openStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun sha256(file: File): String {
        if (!file.exists()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

val resolveWasmOpt = tasks.register<ResolveWasmOpt>("resolveWasmOpt") {
    group = "interop"
    description = "Downloads + checksum-verifies the pinned Binaryen wasm-opt for the build host and packages it as JVM resources."
    version.set(binaryenVersion)
    artifacts.set(binaryenArtifacts)
    downloadDir.set(layout.buildDirectory.dir("binaryen"))
    outputDir.set(layout.buildDirectory.dir("generated/binaryen-resources"))
}

// ── D4-4 local-only wall-clock benchmark ─────────────────────────────────────
//
// A JavaExec task that runs BloatedKernelBenchmark.main (a `main()` in jvmTest,
// NOT a @Test — so `./gradlew build` compiles it but never executes it; only this
// task does). It measures the real interpreter wall-clock of the raw bloated kernel
// vs the wasm-opt -O2/-O3/-Oz variants and records the D4 go/no-go numbers. Kept out
// of CI deliberately (benchmarks flake on shared runners — see the D4 design's
// "Testing posture — Local-only"). Run by hand:
//   ./gradlew :kuilt-warp-compiler:benchmark
//   ./gradlew :kuilt-warp-compiler:benchmark -Piters=5000000
tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Local-only wall-clock benchmark: raw vs wasm-opt-optimized bloated kernel through Chicory. Records the D4 go/no-go numbers (not a CI job)."
    val testCompilation = kotlin.jvm().compilations.getByName("test")
    dependsOn(testCompilation.compileTaskProvider)
    classpath(
        testCompilation.output.allOutputs,
        testCompilation.runtimeDependencyFiles,
    )
    mainClass.set("us.tractat.kuilt.warp.BloatedKernelBenchmarkKt")
    (project.findProperty("iters") as String?)?.let { args(it) }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The WasmOptimizer seam + OptLevel this module implements.
            api(project(":kuilt-warp"))
            implementation(project(":kuilt-core"))
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmMain {
            // The resolved wasm-opt binary + libbinaryen, packaged as resources.
            resources.srcDir(resolveWasmOpt)
        }
        jvmTest.dependencies {
            // ChicoryWasmRuntime — proves the optimized module still loads/runs (validity + ABI).
            implementation(project(":kuilt-warp-runtime"))
            // WasmKernelFixtures.REVERSE — the representative real kernel under test.
            implementation(project(":kuilt-warp-test"))
            implementation(project(":kuilt-test"))
            implementation(libs.kotlin.testJunit)
            implementation(libs.kotlinx.coroutines.test)
            runtimeOnly(libs.logback)
        }
    }
}
