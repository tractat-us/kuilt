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

kotlin {
    val macosLibName = "kuilt"
    macosArm64 { binaries.sharedLib { baseName = macosLibName } }

    // #1516: install the nw_connection_receive completion via a C block (nwshim.def) rather than a
    // Kotlin-lambda-bridged Obj-C block — the latter intermittently aborted the process under load
    // when Kotlin/Native's block trampoline ran on the serial GCD queue. RealNwApi lives in appleMain,
    // so the cinterop is wired for every apple target. Def: src/nativeInterop/cinterop/nwshim.def.
    listOf(iosArm64(), iosSimulatorArm64(), macosArm64()).forEach { target ->
        target.compilations.getByName("main").cinterops.create("nwshim")
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
