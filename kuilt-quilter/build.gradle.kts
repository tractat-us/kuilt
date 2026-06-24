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

// Give the test fork a generous heap (#655). QuilterConcurrencyTest is a deliberate
// concurrency flood — thousands of deltas in flight under a real multi-threaded dispatcher,
// buffered through the in-memory fabric's channel. The transient backlog is legitimate (not a
// leak — every Quilter buffer is bounded/GC'd), but it scales with consumer lag, and on a
// contended CI runner (the daemons hold ~6 GB) the default ~512 MB fork tipped into OOM
// intermittently. A controlled experiment confirmed the test is heap-bound: it OOMs
// deterministically at 16 MB and passes from 32 MB up. 1 GB gives headroom under contention.
// Bounding the harness channel itself is tracked separately (see #701).
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
