plugins {
    id("kuilt.kmp-library")
}

// Every `*ConcurrencyTest` in this module is a real-threaded probe (the name is the contract, not an
// enumeration — the same convention as `:kuilt-core` and `:kuilt-nw`). They run on real threads
// rather than virtual time, so their coroutines depend on the OS scheduling a `Dispatchers.Default`
// worker; when the machine is saturated by sibling test JVMs that dispatch can be delayed far past
// any budget the probe sets, and the probe reds — or is killed and writes no XML — for a reason that
// has nothing to do with the code under test (#1135 / #1158). So they are EXCLUDED from the normal
// run and only execute under -Pconcurrency.stress.tests=true, in a dedicated CI job on a runner with
// no co-scheduled test JVMs (see the `concurrency-probes` job in ci.yml).
//
// The cost, stated rather than discovered later: that job is deliberately NON-BLOCKING and is not
// aggregated into `ci-required`, so the merge gate does NOT pin #1879. Nothing cheaper is available
// — a single-threaded test cannot distinguish the check-then-act from the fix, because the
// check-then-act is *correct* when nothing runs between the read and the write, which is precisely
// what a test dispatcher guarantees. That is the same posture every sibling lost-terminal-`Torn`
// probe in `:kuilt-core` already has, for the same reason.
val runConcurrencyStress = providers.gradleProperty("concurrency.stress.tests").orNull == "true"
tasks.withType<Test>().configureEach {
    // Apply the exclusion only when the flag is OFF. With the flag ON the exclusion is absent, so a
    // command-line `--tests "*ConcurrencyTest"` include filter runs them (a build-defined exclude
    // would otherwise win over the include and match nothing).
    if (!runConcurrencyStress) {
        filter { excludeTestsMatching("*ConcurrencyTest") }
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core"))  // public API exposes Loom/PeerId — expose the contract transitively
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.atomicfu)
        }
        // Real Google Nearby Connections binding lives Android-only; the pure
        // adapter logic in commonMain stays GMS-free and JVM-testable.
        androidMain.dependencies {
            implementation(libs.play.services.nearby)
            implementation(libs.kotlinx.coroutines.playServices)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-conformance"))
            implementation(project(":kuilt-test")) // TEST_WEDGE_BACKSTOP
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.logback)
        }
        // kotlin-logging needs an SLF4J backend on the Android unit-test variant too. ChunkCodec
        // now logs every refused/evicted chunk, so the first logger USE is on the reassembly happy
        // path rather than an error-only path; without a backend that throws NoClassDefFoundError:
        // org/slf4j/LoggerFactory out of `feed` and poisons every ChunkCodec and NearbySeam test.
        // Mirrors :kuilt-nw and :kuilt-session, which hit the same thing for the same reason.
        androidUnitTest.dependencies {
            runtimeOnly(libs.logback)
        }
    }
}
