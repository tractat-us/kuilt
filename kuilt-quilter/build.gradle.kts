import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.kover)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core"))
            api(project(":kuilt-crdt"))
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.cbor)
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(project(":kuilt-conformance"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            runtimeOnly(libs.logback)
        }
        androidUnitTest.dependencies {
            runtimeOnly(libs.logback)
        }
    }
}

// Generous test-fork heap (#655). QuilterConcurrencyTest is a deliberate concurrency flood —
// thousands of deltas in flight under a real *multi-threaded* dispatcher. Delivery is now bounded
// (#701/#741: the in-memory fabric buffers through a bounded Spool), which caps the inbound backlog,
// but the flood's delta allocation + GC pressure under a contended CI runner still needs real
// headroom: a 256 MB attempt (#776) OOM'd intermittently on contended runners (it passed locally
// uncontended and on idle CI, but OOM'd at 256 MB during #783's build — and 256 MB is below the
// ~512 MB default that #655 already found OOM-prone). Bounded delivery removes the *unbounded*
// backlog; it does not remove the need for contention headroom. Keep 1 GB.
tasks.withType<Test>().configureEach {
    maxHeapSize = "1g"
}

// kuilt-conformance ships kotlin-test-junit in commonMain; resolve the
// kotlin-test-framework-impl capability conflict to the JUnit4 variant so
// kuilt-conformance and the default kotlin-test wiring don't clash.
configurations.configureEach {
    resolutionStrategy.capabilitiesResolution.withCapability(
        "org.jetbrains.kotlin:kotlin-test-framework-impl",
    ) {
        candidates.firstOrNull { (it.id as? ModuleComponentIdentifier)?.module == "kotlin-test-junit" }
            ?.let { select(it) }
    }
}

kover {
    reports {
        total {
            verify {
                onCheck = false
                rule("Minimum 90% line coverage in commonMain") {
                    minBound(90)
                }
            }
        }
    }
}
