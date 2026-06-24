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

// Modest test-fork heap for QuilterConcurrencyTest — a deliberate concurrency flood (thousands of
// deltas in flight under a real multi-threaded dispatcher). It was pinned to 1 GB (#655) because
// the in-memory fabric's UNLIMITED inbound channel let the transient backlog scale with consumer
// lag and tip a contended CI fork into OOM. #701/#741 made delivery bounded (the fabric now buffers
// through a bounded Spool), so peak memory no longer scales with lag. Controlled experiment:
// QuilterConcurrencyTest passes at 128 MB under bounded delivery; 256 MB is ample CI headroom.
tasks.withType<Test>().configureEach {
    maxHeapSize = "256m"
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
