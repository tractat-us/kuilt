plugins { id("kuilt.kmp-library") }

tasks.withType<Test>().configureEach {
    val flag = providers.gradleProperty("multipeer.realnet.tests").orNull
    if (flag != null) systemProperty("multipeer.realnet.tests", flag)
}

// Every `*ConcurrencyTest` in this module is a real-threaded probe (the name is the contract, not an
// enumeration — the same convention as `:kuilt-core`, `:kuilt-nearby` and `:kuilt-nw`). They run on
// real threads rather than virtual time, so their coroutines depend on the OS scheduling a
// `Dispatchers.Default` worker; when the machine is saturated by sibling test JVMs that dispatch can
// be delayed far past any budget the probe sets, and the probe reds — or is killed and writes no XML
// — for a reason that has nothing to do with the code under test (#1135 / #1158). So they are
// EXCLUDED from the normal run and only execute under -Pconcurrency.stress.tests=true, on a runner
// with no co-scheduled test JVMs.
//
// The cost, stated rather than discovered later: that job is deliberately NON-BLOCKING and is not
// aggregated into `ci-required`, so the merge gate does NOT pin the #1803 fix in `BridgePeerLink`.
// Nothing cheaper is available — a single-threaded test cannot distinguish the check-then-act from
// the `SeamStateGate`, because the check-then-act is *correct* when nothing runs between the read
// and the write, which is precisely what a test dispatcher guarantees. That is the same posture
// every sibling lost-terminal-`Torn` probe in `:kuilt-core` and `:kuilt-nearby` already has.
val runConcurrencyStress = providers.gradleProperty("concurrency.stress.tests").orNull == "true"

// `AbstractTestTask`, NOT `Test` — this module's probes are on BOTH the JVM (`BridgePeerLink`) and
// Kotlin/Native (`MCSessionLink`), and `KotlinNativeHostTest` is not a `Test` task, so a
// `withType<Test>` exclusion silently misses `macosArm64Test` entirely. `AbstractTestTask` is the
// common supertype and covers both with one rule.
//
// This is deliberately NOT the env-var-plus-self-skip mechanism `:kuilt-nw` uses for its native
// probe. That shape was tried here first and **failed silently**: the env var did not arrive, the
// probe's own `getenv` gate read absent, and it self-skipped — reporting as a PASS in 0.0s for what
// is supposed to be 3 000 races. A gate whose failure mode is a green is the wrong gate for a test
// whose whole job is to red. Excluding at the task level instead means an un-run probe is *absent*
// from the results XML rather than present and passing, which is a checkable signal.
tasks.withType<AbstractTestTask>().configureEach {
    // Apply the exclusion only when the flag is OFF. With the flag ON the exclusion is absent, so a
    // command-line `--tests "*ConcurrencyTest"` include filter runs them (a build-defined exclude
    // would otherwise win over the include and match nothing — the CI job would be green by vacuity).
    // `filter.excludeTestsMatching(...)`, not `filter { … }`: the Action-taking overload is declared
    // on `Test`, so on the `AbstractTestTask` receiver the lambda form resolves to `CopySpec.filter`
    // and fails to compile. The property form is on the common supertype.
    if (!runConcurrencyStress) {
        filter.excludeTestsMatching("*ConcurrencyTest")
    }
}

kotlin {
    val macosLibName = "kuilt"
    macosArm64 { binaries.sharedLib { baseName = macosLibName } }

    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core"))  // public API returns Seam from weave() — expose the contract transitively
            implementation(project(":kuilt-session"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)  // reentrantLock in the apple discovery-source conformance binding
            implementation(libs.kotlin.logging)
        }
        // MANUAL appleMain → disables default-hierarchy auto-wiring → hand-wire ALL intermediates:
        val appleMain by creating { dependsOn(commonMain.get()) }
        val iosArm64Main by getting { dependsOn(appleMain) }
        val iosSimulatorArm64Main by getting { dependsOn(appleMain) }
        val macosMain by creating { dependsOn(appleMain) }
        val macosArm64Main by getting { dependsOn(macosMain) }
        jvmMain.dependencies { implementation(libs.jna) }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        // Mirror the manual appleMain wiring for the test compilations so the
        // apple-only unit tests (MCSessionLink / MultipeerPeerLinkFactory) share
        // one appleTest source set.
        val appleTest by creating {
            dependsOn(commonTest.get())
            // appleMain's MultipeerServiceBrowser is bound to DiscoverySourceConformanceSuite here
            // (kuilt #2410). It runs against the real MCNearbyServiceBrowser with its delegate driven by
            // hand — a real foundPeer needs a second physical device, so the delegate is the only
            // place this platform's arrivals and departures can be staged at all.
            dependencies { implementation(project(":kuilt-conformance")) }
        }
        val iosArm64Test by getting { dependsOn(appleTest) }
        val iosSimulatorArm64Test by getting { dependsOn(appleTest) }
        val macosArm64Test by getting { dependsOn(appleTest) }
        // The android stub is asserted directly rather than through
        // DiscoverySourceConformanceSuite: its discoveries() throws, so no honest causeArrival
        // exists and a binding could only pass by not running. See MultipeerAndroidStubTest.
        // kotlin-test resolves via a JUnit typealias on the Android unit-test variant, so that one
        // artifact has to be named here; coroutines-test already arrives through commonTest.
        androidUnitTest.dependencies { implementation(libs.kotlin.testJunit) }
        jvmTest.dependencies {
            implementation(project(":kuilt-conformance"))
            implementation(libs.kotlin.testJunit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.logback)
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
