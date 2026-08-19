plugins { id("kuilt.kmp-library") }

// Forward -Pnw.realnet.tests to test JVMs as a system property (mirrors :kuilt-mdns's
// mdns.multicast.tests / :kuilt-multipeer's multipeer.realnet.tests). Reserved for opt-in
// two-device hardware tests (Phase 6); the macOS-gated dylib smoke tests run without it.
tasks.withType<Test>().configureEach {
    val flag = providers.gradleProperty("nw.realnet.tests").orNull
    if (flag != null) systemProperty("nw.realnet.tests", flag)
}

// Forward -Pconcurrency.stress.tests to the Kotlin/Native macOS **host** test binary as the
// environment variable CONCURRENCY_STRESS_TESTS, readable via platform.posix.getenv. This gates
// the heavy, opt-in RealNwApi connection-leak stress probe (NwConnectionDrainStressTest) — hundreds
// of concurrent real Network.framework open/close cycles on Dispatchers.Default — so it is NEVER in
// ci-required; absent the flag the test self-skips at runtime. K/N test binaries don't support JVM
// system properties, so env vars are the mechanism (mirrors :kuilt-mdns's MDNS_MULTICAST_TESTS
// forwarding to the K/N simulator; here the target is the macosArm64 host test, KotlinNativeHostTest).
val concurrencyStressFlag = providers.gradleProperty("concurrency.stress.tests").orNull
if (concurrencyStressFlag != null) {
    tasks
        .withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeHostTest>()
        .configureEach { environment("CONCURRENCY_STRESS_TESTS", concurrencyStressFlag) }
}

// The JVM half of the same flag, copied verbatim from kuilt-core/build.gradle.kts (#2481).
// Every `*ConcurrencyTest` here is a real-threaded probe — the name is the contract, deliberately
// NOT an enumeration, which is what went stale in kuilt-core as probes were added. They run on real
// threads rather than virtual time, so their background pump coroutines get starved of CPU when the
// machine is saturated by sibling test JVMs, and an unbounded await can then blow the per-Test task
// budget → the task is killed and writes no XML (the #1135 hang). So they are EXCLUDED from the
// normal test run and only run under -Pconcurrency.stress.tests=true, on a dedicated CI runner with
// no co-scheduled test JVMs (the `nw-concurrency-probes` job in ci.yml). See #1158.
val runConcurrencyStress = concurrencyStressFlag == "true"
tasks.withType<Test>().configureEach {
    // Apply the exclusion only when the flag is OFF. With the flag ON the exclusion is absent, so a
    // command-line `--tests "*ConcurrencyTest"` include filter runs them (a build-defined exclude
    // would otherwise win over the include and match nothing — the CI job would be green by vacuity).
    if (!runConcurrencyStress) {
        filter { excludeTestsMatching("*ConcurrencyTest") }
    } else {
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
