import org.gradle.process.ExecOperations
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
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
// :kuilt-warp-runtime). Instead, for EVERY supported OS/architecture, this build
// downloads the version-pinned official Binaryen release archive, verifies it
// against the upstream-published SHA-256, extracts `bin/wasm-opt` +
// `lib/libbinaryen.*` (wasm-opt links the shared lib via `@loader_path/../lib` /
// `$ORIGIN/../lib`, so the bin/lib layout is preserved), and packages them under
// `us/tractat/kuilt/warp/binaryen/`.
//
// **Packaging: one classified jar per OS (#1335).** The MAIN jvm jar carries no
// binary at all — only a tiny generated `coordinates.properties` so a runtime
// failure can name the exact artifact to add. Each supported platform gets its own
// classified companion jar published alongside the main one:
//
//     us.tractat.kuilt:kuilt-warp-compiler-jvm:<version>:macos-arm64
//     us.tractat.kuilt:kuilt-warp-compiler-jvm:<version>:macos-x86_64
//     us.tractat.kuilt:kuilt-warp-compiler-jvm:<version>:linux-x86_64
//     us.tractat.kuilt:kuilt-warp-compiler-jvm:<version>:linux-aarch64
//
// A consumer adds the classifier for the host their compiler node runs on. This
// replaces the previous build-host-only bundling, under which a release staged on
// a Linux runner shipped a Linux `wasm-opt` to every consumer regardless of OS.
//
// **The build host's jar is still wired into `jvmTest` resources**, so the module's
// deterministic tests find `wasm-opt` with no consumer-style declaration and stay
// un-gated locally and in CI — exactly as before.
//
// Every classified jar is wired into `assemble`, so `./gradlew build` (i.e. the
// `ci-required` check) builds all four. Publish tasks are NOT in that graph, so
// without this a broken classified artifact would only surface post-merge.
//
// Deliberately NOT @CacheableTask: the download is cached under build/binaryen/
// keyed by the pinned filename+checksum, and keeping the archive out of the
// shared (S3-backed) build cache means the bytes packed into the jar are always
// the checksum-verified upstream release produced on the building machine.

val binaryenVersion = "130"

/**
 * One pinned upstream Binaryen release.
 *
 * [classifier] is the published Maven classifier (`<os>-<arch>`, the order a reader
 * scans `os.name` then `os.arch`); [taskSuffix] keeps the generated task names
 * legible; [sha256] is the upstream-published `<archive>.sha256` value, verified at
 * pin time and re-verified on every download.
 */
data class BinaryenRelease(
    val classifier: String,
    val taskSuffix: String,
    val archive: String,
    val sha256: String,
)

private val binaryenReleases = listOf(
    BinaryenRelease(
        classifier = "macos-arm64",
        taskSuffix = "MacosArm64",
        archive = "binaryen-version_130-arm64-macos.tar.gz",
        sha256 = "79d3ab9f417d9e215f15f598f523d001a7d9ac1e59367e5c869fbdabd1cba72e",
    ),
    BinaryenRelease(
        classifier = "macos-x86_64",
        taskSuffix = "MacosX64",
        // NB: upstream's `.sha256` file names this `…-x86_64-macos-14.tar.gz` (their
        // pre-rename build artifact); the published release asset drops the `-14`.
        archive = "binaryen-version_130-x86_64-macos.tar.gz",
        sha256 = "d3e2d1235b70c93c54b52eabc1625ea960965152218754f1f4eeb0f873c48e03",
    ),
    BinaryenRelease(
        classifier = "linux-x86_64",
        taskSuffix = "LinuxX64",
        archive = "binaryen-version_130-x86_64-linux.tar.gz",
        sha256 = "0a18362361ad05465118cd8eeb72edaeec89de6894bc283576ef4e07aa3babcc",
    ),
    BinaryenRelease(
        classifier = "linux-aarch64",
        taskSuffix = "LinuxArm64",
        archive = "binaryen-version_130-aarch64-linux.tar.gz",
        sha256 = "e6ae6e09ac40f4e14bc5be6f687c58e2995c84170013975fa641809dd3b480a0",
    ),
)

/**
 * The [BinaryenRelease.classifier] matching the machine running this build, or `null`
 * on a host with no pinned release. `null` is deliberately not an error: it only means
 * this host cannot run the module's `wasm-opt` tests (they fail with
 * `BinaryenWasmOptimizer`'s actionable message), not that the build cannot configure.
 */
fun detectHostClassifier(): String? {
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

abstract class ResolveWasmOpt : DefaultTask() {
    @get:Inject
    protected abstract val execOperations: ExecOperations

    /** Binaryen release version (the `version_<N>` tag's `<N>`). */
    @get:Input
    abstract val version: Property<String>

    /** The published Maven classifier this release is packaged under (`<os>-<arch>`). */
    @get:Input
    abstract val platform: Property<String>

    /** Upstream release archive filename for [platform]. */
    @get:Input
    abstract val archiveName: Property<String>

    /** Upstream-published SHA-256 of [archiveName]; a mismatch fails the build. */
    @get:Input
    abstract val sha256: Property<String>

    /** Cache for the downloaded archive + extraction (kept out of the build cache). */
    @get:Internal
    abstract val downloadDir: DirectoryProperty

    /** The generated resources root — packaged into this platform's classified jar. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun resolve() {
        val platformKey = platform.get()
        val filename = archiveName.get()
        val expectedSha = sha256.get()
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

        val extractDir = cache.resolve("extracted-$platformKey").apply { deleteRecursively(); mkdirs() }
        execOperations.exec { commandLine("tar", "xzf", archive.absolutePath, "-C", extractDir.absolutePath) }
        val releaseRoot = extractDir.resolve("binaryen-version_${version.get()}")
        val wasmOpt = releaseRoot.resolve("bin/wasm-opt")
        check(wasmOpt.exists()) { "extracted archive is missing bin/wasm-opt at $wasmOpt" }
        // Shared libraries only. macOS ships `libbinaryen.dylib`, which `wasm-opt` loads via
        // its `@loader_path/../lib` rpath — that one must travel with the binary. Linux ships
        // a 14 MB `libbinaryen.a` static archive instead, and its `wasm-opt` is `static-pie`
        // linked, so the archive is link-time-only dead weight in a published jar.
        val libFiles = releaseRoot.resolve("lib").listFiles()
            ?.filter { it.isFile && it.name.startsWith("libbinaryen") && it.isSharedLibrary() }
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
                append("binaryen.platform=$platformKey\n")
                append("binaryen.executable=bin/wasm-opt\n")
                append("binaryen.files=${relPaths.joinToString(",")}\n")
            },
        )
        logger.lifecycle("Resolved Binaryen ${version.get()} for $platformKey (${relPaths.size} files).")
    }

    /** `.dylib` / `.so` / `.so.<n>` — a library the runtime loader can actually use. */
    private fun File.isSharedLibrary(): Boolean =
        name.endsWith(".dylib") || name.contains(".so")

    /**
     * Fetches [url] into [target], retrying a transient network failure rather than
     * failing the whole build on one blip (#1728: a single `UnknownHostException:
     * github.com` threw away 2998 executed tasks of a publish run).
     *
     * Only an [IOException] — DNS, connect, reset, timeout, 5xx — is retried, and a
     * [FileNotFoundException] (HTTP 404/410) is excluded because a missing asset on a
     * *version-pinned* URL is a wrong pin, not a blip, and no number of attempts fixes
     * it. A checksum mismatch is likewise never retried: verification stays in
     * [resolve], outside this loop, so a corrupt or wrong artifact fails immediately
     * with its own message instead of burning the backoff first.
     */
    private fun downloadTo(url: String, target: File) {
        target.parentFile.mkdirs()
        var lastFailure: IOException? = null
        for (attempt in 1..DOWNLOAD_ATTEMPTS) {
            try {
                openStream(url).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                return
            } catch (failure: FileNotFoundException) {
                throw GradleException("Binaryen download failed — no such release asset: $url", failure)
            } catch (failure: IOException) {
                lastFailure = failure
                // A partial body must not survive as a plausible-looking cache entry.
                target.delete()
                if (attempt == DOWNLOAD_ATTEMPTS) break
                val backoffMillis = RETRY_BASE_DELAY_MILLIS shl (attempt - 1)
                logger.warn(
                    "Binaryen download attempt $attempt/$DOWNLOAD_ATTEMPTS failed ($failure); " +
                        "retrying in ${backoffMillis}ms …",
                )
                Thread.sleep(backoffMillis)
            }
        }
        throw GradleException(
            "Binaryen download failed after $DOWNLOAD_ATTEMPTS attempts: $url " +
                "(last failure: $lastFailure)",
            lastFailure,
        )
    }

    /** Opens [url] with both timeouts set, so a hung socket fails fast instead of never. */
    private fun openStream(url: String): InputStream =
        URI(url).toURL().openConnection().run {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            getInputStream()
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

    private companion object {
        /** Total download attempts before the task gives up — enough to ride out a DNS/network blip. */
        const val DOWNLOAD_ATTEMPTS = 4

        /** Backoff before the first retry; doubled per attempt (1s, 2s, 4s — ~7s worst case). */
        const val RETRY_BASE_DELAY_MILLIS = 1_000L

        /** Cap on establishing the connection, so an unreachable host is a retry rather than a stall. */
        const val CONNECT_TIMEOUT_MILLIS = 30_000

        /** Cap on any single read, so a half-open socket cannot block the build indefinitely. */
        const val READ_TIMEOUT_MILLIS = 60_000
    }
}

/**
 * Writes the one resource the MAIN jvm jar carries: the module's own Maven coordinates
 * and the list of published classifiers. `BinaryenWasmOptimizer` reads it so a missing
 * `wasm-opt` fails with the exact `runtimeOnly("…:<classifier>")` line to add rather
 * than an opaque "no bundled binary".
 */
abstract class WriteBinaryenCoordinates : DefaultTask() {
    /** `<group>:<artifactId>:<version>` of the jvm publication these classifiers hang off. */
    @get:Input
    abstract val coordinates: Property<String>

    /** Every published classifier, for the "available:" line of the failure message. */
    @get:Input
    abstract val platforms: ListProperty<String>

    /** Generated resources root — packaged into the main jvm jar. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val root = outputDir.get().asFile.apply { deleteRecursively() }
        val binaryenDir = root.resolve("us/tractat/kuilt/warp/binaryen").apply { mkdirs() }
        binaryenDir.resolve("coordinates.properties").writeText(
            buildString {
                append("# Generated by :kuilt-warp-compiler — do not edit.\n")
                append("binaryen.coordinates=${coordinates.get()}\n")
                append("binaryen.platforms=${platforms.get().sorted().joinToString(",")}\n")
            },
        )
    }
}

val binaryenDownloadDir = layout.buildDirectory.dir("binaryen")

val resolveWasmOptTasks = binaryenReleases.associate { release ->
    release.classifier to tasks.register<ResolveWasmOpt>("resolveWasmOpt${release.taskSuffix}") {
        group = "interop"
        description = "Downloads + checksum-verifies the pinned Binaryen wasm-opt for " +
            "${release.classifier} and packages it as JVM resources."
        version.set(binaryenVersion)
        platform.set(release.classifier)
        archiveName.set(release.archive)
        sha256.set(release.sha256)
        downloadDir.set(binaryenDownloadDir)
        outputDir.set(layout.buildDirectory.dir("generated/binaryen-resources/${release.classifier}"))
    }
}

/** One classified companion jar per platform — the published per-OS artifacts (#1335). */
val wasmOptJarTasks = binaryenReleases.associate { release ->
    release.classifier to tasks.register<Jar>("wasmOptJar${release.taskSuffix}") {
        group = "build"
        description = "Packages the pinned Binaryen wasm-opt for ${release.classifier} as the " +
            "'${release.classifier}'-classified companion jar."
        archiveBaseName.set("${project.name}-jvm")
        archiveClassifier.set(release.classifier)
        from(resolveWasmOptTasks.getValue(release.classifier))
    }
}

val jvmPublicationCoordinates = "${project.group}:${project.name}-jvm:${project.version}"

val writeBinaryenCoordinates = tasks.register<WriteBinaryenCoordinates>("writeBinaryenCoordinates") {
    group = "interop"
    description = "Writes the coordinates + published-classifier list packaged into the main jvm jar."
    coordinates.set(jvmPublicationCoordinates)
    platforms.set(binaryenReleases.map { it.classifier })
    outputDir.set(layout.buildDirectory.dir("generated/binaryen-coordinates"))
}

// `ci-required` runs `./gradlew build`, which reaches `assemble` but never a publish
// task — so without this wiring a broken classified jar would land on main and only
// fail in publish.yml, after the merge. (#1014 landed exactly that way.)
tasks.named("assemble") { dependsOn(wasmOptJarTasks.values) }

// Publish the classified jars alongside the jvm publication, so both channels carry
// them: the TigrisStaging file:// repo (per-merge snapshots) and Maven Central (tags).
// They hang off the `jvm` publication rather than the KMP root because the binary is
// a JVM-target concern; the coordinate a consumer writes is therefore
// `us.tractat.kuilt:kuilt-warp-compiler-jvm:<version>:<classifier>`.
configure<PublishingExtension> {
    publications.withType<MavenPublication>().configureEach {
        if (name == "jvm") {
            wasmOptJarTasks.values.forEach { artifact(it) }
        }
    }
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
            // The main jar carries NO binary — only the coordinates of the classified
            // companion jars, so a runtime miss names the artifact to add.
            resources.srcDir(writeBinaryenCoordinates)
        }
        jvmTest {
            // The build host's own wasm-opt, straight from its resolve task. This keeps
            // the module's tests deterministic and un-gated without making them declare
            // a consumer-style classified dependency on the module under test.
            detectHostClassifier()
                ?.let(resolveWasmOptTasks::get)
                ?.let { resources.srcDir(it) }
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
