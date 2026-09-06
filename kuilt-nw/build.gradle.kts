plugins { id("kuilt.kmp-library") }

// Forward -Pnw.realnet.tests to test JVMs as a system property (mirrors :kuilt-mdns's
// mdns.multicast.tests / :kuilt-multipeer's multipeer.realnet.tests). Reserved for opt-in
// two-device hardware tests (Phase 6); the macOS-gated dylib smoke tests run without it.
tasks.withType<Test>().configureEach {
    val flag = providers.gradleProperty("nw.realnet.tests").orNull
    if (flag != null) systemProperty("nw.realnet.tests", flag)
}

// `-Pconcurrency.stress.tests=true` opts this module's real-threaded probes in. Two name contracts,
// deliberately NOT an enumeration (which is what went stale in kuilt-core as probes were added):
//
//   *ConcurrencyTest — the JVM probes (`NwSeamConcurrencyTest`), copied verbatim from
//                      kuilt-core/build.gradle.kts (#2481).
//   *StressTest      — the Kotlin/Native probe (`NwConnectionDrainStressTest`), hundreds of
//                      concurrent real Network.framework open/close cycles on Dispatchers.Default.
//
// Both run on real threads rather than virtual time, so their background pump coroutines get starved
// of CPU when the machine is saturated by sibling test JVMs, and an unbounded await can then blow the
// per-task budget → the task is killed and writes no XML (the #1135 hang). So they are EXCLUDED from
// the normal test run and only run under the flag, on a dedicated runner with no co-scheduled test
// JVMs (the `nw-concurrency-probes` job in ci.yml). See #1158.
//
// `AbstractTestTask`, NOT `Test` (#2621). `KotlinNativeHostTest`/`KotlinNativeSimulatorTest` are not
// `Test` tasks, so a `withType<Test>` exclusion silently misses `macosArm64Test` and
// `iosSimulatorArm64Test` entirely — the trap `:kuilt-multipeer`'s build file already records.
// `filter.excludeTestsMatching(...)`, not `filter { … }`: the Action-taking overload is declared on
// `Test`, so on the `AbstractTestTask` receiver the lambda form resolves to `CopySpec.filter` and
// fails to compile.
//
// ⚠ This REPLACES an env-var-plus-`getenv`-self-skip that gated the native probe from inside the test
// body (#2621). That mechanism is unsound as a *reporting* device: a self-skipped `@Test` reports
// **passed**, not `skipped`, so a green results XML could not distinguish "ran all 240 cycles" from
// "never ran one". `build-native` in ci.yml runs `macosArm64Test iosSimulatorArm64Test` WITHOUT this
// flag, so that self-skip was reporting a green testcase on every `ci-required` run. An excluded test
// is *absent* from the XML instead, which is a verdict a reader can act on. And duration is no
// substitute: `macosArm64Test` reports `time="0.0"` for a K/N run that provably completed 3 000
// iterations — on this target the clock is not a witness that anything ran.
val runConcurrencyStress = providers.gradleProperty("concurrency.stress.tests").orNull == "true"
tasks.withType<AbstractTestTask>().configureEach {
    // Apply the exclusion only when the flag is OFF. With the flag ON the exclusion is absent, so a
    // command-line `--tests "*ConcurrencyTest"` include filter runs them (a build-defined exclude
    // would otherwise win over the include and match nothing — the CI job would be green by vacuity).
    if (!runConcurrencyStress) {
        filter.excludeTestsMatching("*ConcurrencyTest")
        filter.excludeTestsMatching("*StressTest")
    }
}

// A SECOND block, on `Test` rather than `AbstractTestTask`, because `jvmArgs` is declared on `Test`
// and does not exist on the common supertype — so this cannot be folded into an `else` above.
if (runConcurrencyStress) {
    tasks.withType<Test>().configureEach {
        // The probe harness installs DebugProbes to dump *coroutine* stacks on a hang (#1784), which
        // attaches a java agent at runtime. JDK 21+ warns on stderr when that happens (JEP 451), and
        // stderr cleanliness is itself evidence on these hangs. Scoped to the stress runs, so the
        // normal build's test JVMs are untouched.
        jvmArgs("-XX:+EnableDynamicAgentLoading")
    }
}

kotlin {
    val macosLibName = "kuilt"
    macosArm64 { binaries.sharedLib { baseName = macosLibName } }

    // #1516: install the nw_connection_receive completion via a C block (nwshim.def) rather than a
    // Kotlin-lambda-bridged Obj-C block — the latter intermittently aborted the process under load
    // when Kotlin/Native's block trampoline ran on the serial GCD queue. RealNwApi lives in appleMain,
    // so the cinterop is wired for every apple target. Def: src/nativeInterop/cinterop/nwshim.def.
    listOf(iosArm64(), iosSimulatorArm64(), macosArm64()).forEach { target ->
        target.compilations.getByName("main").cinterops.create("nwshim")
        // #2457: a TEST-ONLY interop for NwHalfCloseProbeTest. The half-close question turns on
        // values Kotlin/Native does not reliably bridge — NW_CONNECTION_FINAL_MESSAGE_CONTEXT
        // (RealNwApi.send already records that the NW_CONNECTION_*_CONTEXT constants mis-bridge)
        // and NW_PARAMETERS_DISABLE_PROTOCOL — so the probe spells them in C, with Apple's own
        // macros. That is what makes a NEGATIVE verdict attributable to the platform rather than
        // to the caller. Not on the main compilation: no production code links it.
        target.compilations.getByName("test").cinterops.create("nwprobe")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core"))  // public API returns Seam from weave() — expose the contract transitively
            implementation(project(":kuilt-session"))
            implementation(project(":kuilt-stream"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlin.logging)
            implementation(libs.kotlincrypto.macs.hmac.sha2)  // HKDF-SHA256 for TLS-PSK derivation (NwPsk)
        }
        // MANUAL appleMain → disables default-hierarchy auto-wiring → hand-wire ALL intermediates:
        val appleMain by creating { dependsOn(commonMain.get()) }
        val iosArm64Main by getting { dependsOn(appleMain) }
        val iosSimulatorArm64Main by getting { dependsOn(appleMain) }
        val macosMain by creating { dependsOn(appleMain) }
        val macosArm64Main by getting { dependsOn(macosMain) }
        jvmMain.dependencies { implementation(libs.jna) }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(project(":kuilt-conformance"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // Mirror the manual appleMain wiring for the test compilations so any
        // apple-only unit tests share one appleTest source set.
        val appleTest by creating { dependsOn(commonTest.get()) }
        val iosArm64Test by getting { dependsOn(appleTest) }
        val iosSimulatorArm64Test by getting { dependsOn(appleTest) }
        val macosArm64Test by getting { dependsOn(appleTest) }
        jvmTest.dependencies {
            implementation(libs.kotlin.testJunit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.logback)
        }
        // kotlin-logging needs an SLF4J backend on the Android unit-test variant too — otherwise the
        // first actual logger USE (now on the happy path, not just error paths) throws
        // NoClassDefFoundError: org/slf4j/impl/StaticLoggerBinder via ExceptionInInitializerError and
        // poisons every NwSeam test. Mirrors kuilt-session's runtimeOnly(logback) for both variants.
        androidUnitTest.dependencies {
            runtimeOnly(libs.logback)
        }
    }
}

val nativeBinariesDir = layout.buildDirectory.dir("native-binaries-jvm")
val packageMacosNatives = tasks.register<Copy>("packageMacosNatives") {
    group = "build"
    from(layout.buildDirectory.dir("bin/macosArm64/releaseShared")) {
        include("libkuilt.dylib"); into("darwin-aarch64")
    }
    into(nativeBinariesDir); dependsOn("linkReleaseSharedMacosArm64")
}
kotlin.sourceSets.named("jvmMain") { resources.srcDir(packageMacosNatives.map { it.destinationDir }) }
