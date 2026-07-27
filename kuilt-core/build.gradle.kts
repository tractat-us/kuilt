plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

// Every `*ConcurrencyTest` in this module is a real-threaded probe (the name is the
// contract — deliberately NOT an enumeration, which is what went stale as probes were
// added). They run on real threads (not
// virtual time), so their background pump coroutines get starved of CPU when the
// machine is saturated by sibling test JVMs. On an idle box they finish in ~0.45s;
// under CI's full parallel `./gradlew build` (load ~40) their unbounded awaits can
// take tens of seconds and blow the per-Test task budget → the task is killed and
// writes no XML (the #1135 hang). So they are EXCLUDED from the normal test run and
// only run when -Pconcurrency.stress.tests=true is passed — a dedicated CI job runs
// them on their own runner with no co-scheduled test JVMs (see ci.yml). Mirrors the
// -Pmdns.multicast.tests precedent in kuilt-mdns/build.gradle.kts. See #1158.
val runConcurrencyStress = providers.gradleProperty("concurrency.stress.tests").orNull == "true"
tasks.withType<Test>().configureEach {
    // Apply the exclusion only when the flag is OFF. With the flag ON the exclusion
    // is absent, so a command-line `--tests "*ConcurrencyTest"` include filter runs
    // them (a build-defined exclude would otherwise win over the include and match
    // nothing).
    if (!runConcurrencyStress) {
        filter { excludeTestsMatching("*ConcurrencyTest") }
    } else {
        // The probe harness installs DebugProbes to dump *coroutine* stacks on a hang (#1784), which
        // attaches a java agent at runtime. JDK 21+ warns on stderr when that happens (JEP 451), and
        // stderr cleanliness is itself evidence on these hangs — an uncaught-exception flood in
        // `system-err` is how #1787's defect was found. Pre-approving the attach keeps that channel
        // meaningful. Scoped to the stress runs, so the normal build's test JVMs are untouched.
        jvmArgs("-XX:+EnableDynamicAgentLoading")
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // JVM-only, test-only. `DebugProbes` is what lets the concurrency-probe harness dump
        // *coroutine* stacks on a hang; a thread dump shows only threads, and a suspended coroutine
        // has none — the gap that made #1784 cost four investigation cycles. Confined to jvmTest so
        // no other target, and nothing published, sees it.
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.debug)
        }
    }
}
