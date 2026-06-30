import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.kover)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The tap's public surface returns Loom/Seam types and the exporter's
            // Rga<LogRecord>, so both the contract and the otel buffer are api deps.
            api(project(":kuilt-core"))
            api(project(":kuilt-otel"))
            implementation(project(":kuilt-quilter"))
            implementation(project(":kuilt-crdt"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.cbor)
            implementation(libs.kotlinx.io.bytestring)
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // Loopback-WebSocket integration test for simulator realism.
            implementation(project(":kuilt-websocket"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.serverTestHost)
            implementation(libs.ktor.serverWebsockets)
            implementation(libs.ktor.serverNetty)
            implementation(libs.ktor.client.websockets)
            runtimeOnly(libs.logback)
        }
        androidUnitTest.dependencies {
            runtimeOnly(libs.logback)
        }
    }
}

// kuilt-test ships kotlin-test-junit transitively; resolve the
// kotlin-test-framework-impl capability conflict to the JUnit4 variant so the
// default kotlin-test wiring and the websocket integration test don't clash.
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
                rule("Minimum 80% line coverage in commonMain") {
                    minBound(80)
                }
            }
        }
    }
}
