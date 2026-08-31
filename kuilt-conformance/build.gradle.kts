plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.kover)
}

// Forward -Plattice.vacuity.breakdown=true to the JVM test process so that
// VacuityBreakdownProbe can read it via System.getProperty(). The probe is a measuring
// instrument rather than a check — it asserts nothing about any binding — so it is off by
// default and costs a normal run nothing. JVM only: it is a developer surface, and every
// number it produces is target-independent (the pool builder is seeded).
// Every `*ConcurrencyTest` in this module is a real-threaded probe (the name is the contract,
// mirroring :kuilt-core and :kuilt-nw — deliberately not an enumeration, which is what went stale
// there as probes were added). They race dedicated OS threads rather than coroutines, so sibling
// test JVMs saturating the box starve them; they are EXCLUDED from the normal test run and only run
// under -Pconcurrency.stress.tests=true, on a dedicated CI runner with no co-scheduled test forks
// (the `concurrency-probes` job in ci.yml). See #1158.
val runConcurrencyStress = providers.gradleProperty("concurrency.stress.tests").orNull == "true"
tasks.withType<Test>().configureEach {
    val flag = providers.gradleProperty("lattice.vacuity.breakdown").orNull
    if (flag != null) systemProperty("lattice.vacuity.breakdown", flag)

    // Apply the exclusion only when the flag is OFF. With the flag ON the exclusion is absent, so a
    // command-line `--tests "*ConcurrencyTest"` include filter runs them (a build-defined exclude
    // would otherwise win over the include and match nothing).
    if (!runConcurrencyStress) {
        filter { excludeTestsMatching("*ConcurrencyTest") }
    } else {
        // The probe harness installs DebugProbes to dump *coroutine* stacks on a hang (#1784),
        // attaching a java agent at runtime; JDK 21+ warns on stderr when that happens (JEP 451),
        // and stderr cleanliness is itself evidence on these hangs. Scoped to the stress runs.
        jvmArgs("-XX:+EnableDynamicAgentLoading")
    }
}

// Shareable transport-contract conformance harness. Unlike a normal module's
// commonTest (which sibling modules cannot see), this lives in commonMain so
// every fabric adapter can subclass SeamConformanceSuite from its own test
// source set. It therefore exposes kotlin-test / coroutines-test as `api`.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core"))
            api(project(":kuilt-liveness"))
            api(project(":kuilt-session"))
            api(project(":kuilt-raft"))
            api(project(":kuilt-crdt"))
            api(project(":kuilt-store"))
            api(project(":kuilt-test"))
            // The lattice-law harness asserts byte-level canonicality of encoded CRDT
            // states (#1957), so it needs a concrete BinaryFormat. CBOR matches Quilter's
            // default wire format. `implementation`, not `api`: Cbor appears only inside
            // LatticeLawHarness's private members, so no consumer of this module needs
            // it on their compile classpath (KSerializer itself comes from
            // serialization-core, already api-transitive via :kuilt-crdt).
            implementation(libs.kotlinx.serialization.cbor)
            // This module intentionally ships a kotlin-test-based suite in MAIN
            // (not commonTest) so other modules' tests can subclass it. That means
            // each platform's main compilation needs the kotlin.test framework
            // backing that is normally auto-wired only for test source sets.
            api(kotlin("test"))
            api(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.coroutines.core)
            // DelayedWovenSeam's close-once latch (#2328). `implementation`: the atomic is private,
            // so no consumer of this module sees it.
            implementation(libs.kotlinx.atomicfu)
        }
        // JVM & Android resolve kotlin.test.Test via a JUnit typealias — supply it.
        jvmMain.dependencies { api(kotlin("test-junit")) }
        androidMain.dependencies { api(kotlin("test-junit")) }
        // SLF4J backend for kotlin-logging on the JVM + Android unit-test variants.
        // RoomConformanceSuite constructs SeamRoom, which (via HeartbeatPartitionDetector
        // and its own file-level logger) initialises kotlin-logging. kuilt-session /
        // kuilt-liveness declare kotlin-logging as `implementation` (non-transitive), so
        // slf4j-api never reaches this module's test runtime classpath; the first logger
        // call on the room-startup path then throws NoClassDefFoundError:
        // org/slf4j/LoggerFactory and poisons every conformance test. logback brings the
        // slf4j-api + a backend. Mirrors :kuilt-session (raft issue #222).
        jvmTest.dependencies {
            runtimeOnly(libs.logback)
        }
        androidUnitTest.dependencies {
            runtimeOnly(libs.logback)
        }
    }
}

// koverVerify is NOT bound to the check lifecycle — coverage verification is
// opt-in via: ./gradlew koverVerify koverHtmlReport
// onCheck = false keeps the threshold rules available for explicit invocation
// without paying the kover instrumentation cost on every CI build.
kover {
    reports {
        total {
            verify {
                onCheck = false
                rule("Minimum 70% line coverage") {
                    // Initial threshold: softer than :kuilt-crdt because commonMain here is
                    // a test-harness library exercised by consumer modules' test runs, not its own.
                    // Initial threshold: set from first measurement — raise via follow-up issues.
                    minBound(70)
                }
            }
        }
    }
}
