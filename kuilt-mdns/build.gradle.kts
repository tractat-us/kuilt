plugins {
    id("kuilt.kmp-library")
}

// Forward -Pmdns.multicast.tests=true to the JVM test process so that
// MDNSMulticastIntegrationTest can read it via System.getProperty().
tasks.withType<Test>().configureEach {
    val flag = providers.gradleProperty("mdns.multicast.tests").orNull
    if (flag != null) systemProperty("mdns.multicast.tests", flag)
}

// Every `*ConcurrencyTest` in this module is a real-threaded probe (the name is the contract, not an
// enumeration — the same convention as `:kuilt-core`, `:kuilt-multipeer`, `:kuilt-nearby` and
// `:kuilt-nw`). They run on real threads rather than virtual time, so their coroutines depend on the
// OS scheduling a `Dispatchers.Default` worker; a box saturated by sibling test JVMs can delay that
// far past any budget the probe sets, and the probe then reds — or is killed and writes no XML — for
// a reason that has nothing to do with the code under test (#1135 / #1158). So they are EXCLUDED
// from the normal run and only execute under -Pconcurrency.stress.tests=true, on a runner with no
// co-scheduled test JVMs.
//
// The cost, stated rather than discovered later: that job is deliberately NON-BLOCKING and is not
// aggregated into `ci-required`, so the merge gate does NOT pin `PeerRoster`'s lock. Nothing cheaper
// is available — a single-threaded test cannot distinguish a guarded read-modify-write from an
// unguarded one, because an unguarded one is *correct* when nothing runs between the read and the
// write, which is precisely what a test dispatcher guarantees.
val runConcurrencyStress = providers.gradleProperty("concurrency.stress.tests").orNull == "true"
tasks.withType<Test>().configureEach {
    // Apply the exclusion only when the flag is OFF. With the flag ON the exclusion is absent, so a
    // command-line `--tests "*ConcurrencyTest"` include filter runs them (a build-defined exclude
    // would otherwise win over the include and match nothing — the CI job would be green by vacuity).
    if (!runConcurrencyStress) {
        filter { excludeTestsMatching("*ConcurrencyTest") }
    } else {
        // The probe harness installs DebugProbes to dump *coroutine* stacks on a hang (#1784), which
        // attaches a java agent at runtime. JDK 21+ warns on stderr when that happens (JEP 451), and
        // stderr cleanliness is itself evidence on these hangs. Scoped to the stress runs.
        jvmArgs("-XX:+EnableDynamicAgentLoading")
    }
}

// Forward -Pmdns.multicast.tests=true to the iOS K/N simulator test binary as
// the environment variable MDNS_MULTICAST_TESTS, readable via platform.posix.getenv.
// K/N test binaries don't support JVM system properties — env vars are the
// standard mechanism.
val mdnsFlag = providers.gradleProperty("mdns.multicast.tests").orNull
if (mdnsFlag != null) {
    tasks
        .withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>()
        .configureEach { environment("MDNS_MULTICAST_TESTS", mdnsFlag) }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core"))  // public API exposes PeerId/Tag/Loom — expose the contract transitively
            implementation(project(":kuilt-crdt"))
            implementation(libs.kotlinx.coroutines.core)
            // Real mutual exclusion for PeerRoster's lattice read-modify-write (#2655). It is a
            // public primitive a consumer wires its own Bonjour/NSD callbacks into, and those are
            // not promised to arrive on one thread.
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.kotlin.logging)
        }
        // Android's MDNSServiceDiscoverer is bound to DiscoverySourceConformanceSuite here (#1903).
        // It runs as a plain JVM unit test against a fake NsdBrowser — NsdManager is a final class
        // with a package-private constructor, so the seam is the only way to reach this code at all.
        //
        // logback is the SLF4J backend kotlin-logging needs on the Android unit-test variant: the
        // discoverer holds a file-level logger, so class-init would otherwise throw
        // NoClassDefFoundError: org/slf4j/LoggerFactory. Mirrors :kuilt-liveness / :kuilt-session.
        androidUnitTest.dependencies {
            implementation(project(":kuilt-conformance"))
            implementation(libs.kotlin.testJunit)
            implementation(libs.kotlinx.coroutines.test)
            runtimeOnly(libs.logback)
        }
        iosMain.dependencies {
            implementation(libs.kotlin.logging)
            // Real mutual exclusion for ServiceDelegate's attachment bookkeeping and departures()'
            // name->peerId map. Both were previously correct only if every Bonjour callback and the
            // teardown shared one thread — an emergent property of `browseContext`, which this repo
            // forbids leaning on (CLAUDE.md: correctness must be a local property of each field).
            implementation(libs.kotlinx.atomicfu)
        }
        // iosMain's MDNSServiceDiscoverer is bound to the same suite (#2400), against a fake
        // Bonjour browser — NSNetServiceBrowser only delivers callbacks while the main run loop is
        // pumped, and a runTest body on Kotlin/Native occupies the thread that would pump it.
        iosTest.dependencies {
            implementation(project(":kuilt-conformance"))
        }
        jvmMain.dependencies {
            implementation(project(":kuilt-websocket"))
            implementation(libs.jmdns)
            implementation(libs.ktor.serverCore)
            implementation(libs.ktor.client.core)
        }
        jvmTest.dependencies {
            implementation(project(":kuilt-conformance"))
            // `runConcurrencyStress` / `assertAll` for PeerRosterConcurrencyTest, plus the
            // `DebugProbes` that harness installs to dump *coroutine* stacks on a hang (#1784) — a
            // thread dump shows only threads, and a suspended coroutine has none. Confined to
            // jvmTest so nothing published sees either.
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.debug)
            implementation(libs.kotlin.testJunit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.serverCore)
            implementation(libs.ktor.serverWebsockets)
            implementation(libs.ktor.serverNetty)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.okhttp)
        }
    }
}
