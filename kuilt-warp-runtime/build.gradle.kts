import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

plugins {
    id("kuilt.kmp-library")
}

// ── wasm3: libwasm3.a built from the vendored source ────────────────────────
//
// wasm3 (github.com/wasm3/wasm3 v0.5.0, MIT) is a pure C99 wasm interpreter
// with no JIT; it works on iOS (which bans JIT) and macOS from one shared
// source tree + one .def, making it the right choice for all three apple K/N
// targets. The static library is compiled from the vendored — and locally
// patched, grep "WARP PATCH (kuilt)" — source tree in
// src/nativeInterop/wasm3/source/ by the buildWasm3<Target> tasks below, so a
// wasm3 source patch takes effect on the next build; there are no committed
// hand-rebuilt binaries (#934).
//
// The build needs a macOS host with Xcode (xcrun clang/libtool). That is not a
// new constraint: Kotlin disables the cinterop-bearing Apple compilations on
// non-Mac hosts anyway (the cinterop tool needs the Apple sysroot — see
// publish.yml / #982), so everywhere these compilations run (the publish +
// apple-nightly macOS runners, local Macs) xcrun is available, and on ubuntu
// CI both the cinterop and these tasks are skipped.
//
// Object list: every vendored .c except the WASI system-interface bindings
// (m3_api_wasi / m3_api_uvwasi — host capabilities the warp sandbox never
// links). That includes the kuilt-added warp_deadline.c (the cooperative
// execution-deadline m3_Yield). Minimum-OS targets mirror the Kotlin/Native
// defaults the cinterop klib is built against (macOS 11.0, iOS 12.0, iOS
// simulator 14.0, all arm64).
//
// Deliberately NOT @CacheableTask: compiling ~16 small C files takes seconds,
// and keeping the archive out of the shared (S3-backed) build cache means the
// bytes packed into the klib are always produced from the checked-in source on
// the building machine — the supply-chain property this task exists for.
abstract class BuildWasm3StaticLib : DefaultTask() {
    @get:Inject
    protected abstract val execOperations: ExecOperations

    /** The vendored wasm3 source tree (headers included, so a .h edit retriggers the build). */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    /** Xcode SDK name (macosx / iphoneos / iphonesimulator). */
    @get:Input
    abstract val sdk: Property<String>

    /** Clang target triple, including the minimum OS version. */
    @get:Input
    abstract val triple: Property<String>

    @get:OutputFile
    abstract val outputLib: RegularFileProperty

    @TaskAction
    fun build() {
        val objDir = temporaryDir.resolve("obj")
        objDir.deleteRecursively()
        check(objDir.mkdirs()) { "could not create $objDir" }
        val excluded = setOf("m3_api_wasi.c", "m3_api_uvwasi.c")
        val sources = checkNotNull(
            sourceDir.get().asFile.listFiles { file: File ->
                file.name.endsWith(".c") && file.name !in excluded
            },
        ) { "could not list ${sourceDir.get()}" }.sortedBy { it.name }
        check(sources.isNotEmpty()) { "no wasm3 .c sources found in ${sourceDir.get()}" }
        val objects = sources.map { src ->
            val obj = objDir.resolve(src.name.removeSuffix(".c") + ".o")
            execOperations.exec {
                commandLine(
                    "xcrun", "--sdk", sdk.get(), "clang",
                    "-target", triple.get(), "-O3",
                    "-c", src.absolutePath, "-o", obj.absolutePath,
                )
            }
            obj
        }
        val out = outputLib.get().asFile
        out.parentFile.mkdirs()
        out.delete()
        execOperations.exec {
            commandLine(
                listOf("xcrun", "--sdk", sdk.get(), "libtool", "-static", "-o", out.absolutePath) +
                    objects.map { it.absolutePath },
            )
        }
    }
}

// Gradle target name → (Xcode SDK, clang triple with min-OS).
val wasm3TargetFlavors = listOf(
    Triple("macosArm64", "macosx", "arm64-apple-macos11.0"),
    Triple("iosArm64", "iphoneos", "arm64-apple-ios12.0"),
    Triple("iosSimulatorArm64", "iphonesimulator", "arm64-apple-ios14.0-simulator"),
)

val isMacOsHost = System.getProperty("os.name").startsWith("Mac")

val buildWasm3: Map<String, TaskProvider<BuildWasm3StaticLib>> =
    wasm3TargetFlavors.associate { (targetName, sdkName, clangTriple) ->
        targetName to tasks.register<BuildWasm3StaticLib>(
            "buildWasm3${targetName.replaceFirstChar(Char::uppercase)}",
        ) {
            group = "interop"
            description = "Compiles the vendored wasm3 source into libwasm3.a for $targetName."
            // Non-Mac hosts have no xcrun — and never run the consuming cinterop either.
            enabled = isMacOsHost
            sourceDir.set(layout.projectDirectory.dir("src/nativeInterop/wasm3/source"))
            sdk.set(sdkName)
            triple.set(clangTriple)
            outputLib.set(layout.buildDirectory.file("wasm3/$targetName/libwasm3.a"))
        }
    }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-warp"))
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            // The shared WasmRuntime TCK: each target's tests bind their impl to it
            // (ChicoryWasmRuntimeConformanceTest / Wasm3WasmRuntimeConformanceTest /
            // BrowserWasmRuntimeConformanceTest).
            implementation(project(":kuilt-warp-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            // Chicory — pure-JVM wasm runtime (C3 substrate; JVM only, never touches commonMain).
            implementation(libs.chicory.runtime)
        }
        jvmTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(libs.kotlin.testJunit)
            implementation(libs.kotlinx.coroutines.test)
            runtimeOnly(libs.logback)
        }
        androidUnitTest.dependencies {
            runtimeOnly(libs.logback)
        }
    }

    // wasm3 cinterop — Apple Kotlin/Native targets, main compilation.
    //
    // Wired into the MAIN compilation: `Wasm3WasmRuntime` (appleMain) is the
    // production native WasmRuntime impl, so the cinterop must be on the
    // production source set. The test compilation inherits the cinterop from
    // main (test depends on main), so the existing C3-gate dispatch tests keep
    // resolving the wasm3.* symbols.
    //
    // `staticLibraries = libwasm3.a` in the .def makes the cinterop pack the
    // archive into the klib; -libraryPath points it at the matching
    // buildWasm3<Target> output (see the task wiring below the kotlin block).
    val wasm3DefFile = layout.projectDirectory.file("src/nativeInterop/cinterop/wasm3.def")
    val wasm3IncludeDir = layout.projectDirectory.dir("src/nativeInterop/wasm3/source")
    val wasm3LibDir = { targetName: String ->
        layout.buildDirectory.dir("wasm3/$targetName").get().asFile.absolutePath
    }

    macosArm64 {
        compilations.named("main") {
            cinterops.create("wasm3") {
                defFile(wasm3DefFile)
                includeDirs(wasm3IncludeDir)
                extraOpts("-libraryPath", wasm3LibDir("macosArm64"))
            }
        }
    }
    iosArm64 {
        compilations.named("main") {
            cinterops.create("wasm3") {
                defFile(wasm3DefFile)
                includeDirs(wasm3IncludeDir)
                extraOpts("-libraryPath", wasm3LibDir("iosArm64"))
            }
        }
    }
    iosSimulatorArm64 {
        compilations.named("main") {
            cinterops.create("wasm3") {
                defFile(wasm3DefFile)
                includeDirs(wasm3IncludeDir)
                extraOpts("-libraryPath", wasm3LibDir("iosSimulatorArm64"))
            }
        }
    }
}

// Each cinterop task consumes the matching buildWasm3<Target> archive
// (`staticLibraries` packs it into the klib), so it must run after — and re-run
// whenever — that archive is (re)built. inputs.files(<task>) wires both the
// dependency and the up-to-date sensitivity. Matching by name keeps this inert
// on hosts where KGP never creates the Apple cinterop tasks.
wasm3TargetFlavors.forEach { (targetName, _, _) ->
    val libTask = buildWasm3.getValue(targetName)
    tasks.matching { it.name == "cinteropWasm3${targetName.replaceFirstChar(Char::uppercase)}" }
        .configureEach { inputs.files(libTask) }
}
