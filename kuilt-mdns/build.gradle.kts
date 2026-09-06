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

// The Kotlin/Native half of -Pmdns.multicast.tests. K/N test binaries don't support JVM system
// properties, so the JVM `Assume.assumeTrue` the multicast tests above use is not available there
// either — the gating has to be at the **task** level.
//
// ⚠ This REPLACES an `MDNS_MULTICAST_TESTS` env var the test read with `platform.posix.getenv` and
// self-skipped on (#2621). The gating decision was right; the mechanism was unsound as a *reporting*
// device: a `@Test` that returns early reports **passed**, not `skipped`, so a green results XML
// could not distinguish "drove live Bonjour end to end" from "never ran". `build-native` in ci.yml
// runs `iosSimulatorArm64Test` WITHOUT this flag, so that self-skip reported a green testcase on
// every `ci-required` build. An excluded test is *absent* from the XML instead. Duration is no
// substitute either: Kotlin/Native reports `time="0.0"` for runs that provably completed thousands
// of iterations, so the clock cannot tell the two states apart.
//
// `AbstractTestTask`, NOT `Test`: `KotlinNativeSimulatorTest` is not a `Test` task, so a
// `withType<Test>` exclusion would silently miss `iosSimulatorArm64Test` — the whole population here.
// `filter.excludeTestsMatching(...)` rather than `filter { … }`, because the Action-taking overload
// is declared on `Test` and the lambda form resolves to `CopySpec.filter` on this receiver.
//
// The cost, stated rather than discovered later: unlike the `*ConcurrencyTest` contract above this
// names ONE class, because nothing in the name marks it as multicast-gated and renaming it would
// churn four KDoc links. A second real-Bonjour native probe added tomorrow would NOT be covered —
// but it also cannot silently self-skip, because `forbidRuntimeSelfSkippingProbe` in the root build
// fails on a `getenv` gate in a test source, so the next author is forced to this line.
val runMulticastTests = providers.gradleProperty("mdns.multicast.tests").orNull == "true"
tasks.withType<AbstractTestTask>().configureEach {
    if (!runMulticastTests) {
        filter.excludeTestsMatching("*MDNSServiceDiscovererIosTest")
    }
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
