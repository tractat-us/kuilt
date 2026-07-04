import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.kover)
}

kotlin {
    sourceSets {
        // MANUAL appleMain — the default hierarchy template's auto-created appleMain
        // works fine for compilation, but Dokka's Kotlin-source-set auto-discovery loses
        // track of it once it holds real files (as of the Multipeer entry points added
        // here): it registers coarse "ios"/"macos" groupings that depend on "appleMain"
        // for their actual source, but never registers "appleMain" itself, so
        // dokkaGenerateModuleHtml fails with "There is no source module for
        // :kuilt-otel-tap/appleMain". Manually creating it (same pattern as
        // :kuilt-multipeer/:kuilt-websocket, which already have real appleMain content
        // and build Dokka fine) sidesteps the auto-discovery path entirely.
        val appleMain by creating { dependsOn(commonMain.get()) }
        val iosArm64Main by getting { dependsOn(appleMain) }
        val iosSimulatorArm64Main by getting { dependsOn(appleMain) }
        val macosArm64Main by getting { dependsOn(appleMain) }

        val appleTest by creating { dependsOn(commonTest.get()) }
        val iosArm64Test by getting { dependsOn(appleTest) }
        val iosSimulatorArm64Test by getting { dependsOn(appleTest) }
        val macosArm64Test by getting { dependsOn(appleTest) }

        commonMain.dependencies {
            // The tap's public surface returns Loom/Seam types and the exporter's
            // Rga<LogRecord>, so both the contract and the otel buffer are api deps.
            api(project(":kuilt-core"))
            api(project(":kuilt-otel"))
            implementation(project(":kuilt-quilter"))
            // StampedLogRecord exposes RgaId on the public surface (pullStamped), so
            // kuilt-crdt is an api dep, not implementation-only.
            api(project(":kuilt-crdt"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.cbor)
            implementation(libs.kotlinx.io.bytestring)
            implementation(libs.kotlin.logging)
            // Join-token admission gate: HMAC-SHA256 (KMP-uniform) + reentrant lock + CSPRNG.
            implementation(libs.kotlincrypto.macs.hmac.sha2)
            implementation(libs.kotlincrypto.random.crypto.rand)
            implementation(libs.kotlinx.atomicfu)
        }
        // Apple-only encrypted reach: the Multipeer fabric (DTLS out of the box) is the
        // iOS/macOS complement to the plaintext mDNS+WS path. :kuilt-multipeer's real impl
        // is Apple-native, so the Multipeer tap entry points live in appleMain only.
        appleMain.dependencies {
            implementation(project(":kuilt-multipeer"))
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // The Apple wiring test references MultipeerPeerLinkFactory to prove the fabric links
        // into the tap module on the Apple variants.
        appleTest.dependencies {
            implementation(project(":kuilt-multipeer"))
        }
        jvmTest.dependencies {
            // Loopback-WebSocket integration test for simulator realism.
            implementation(project(":kuilt-websocket"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.serverTestHost)
            implementation(libs.ktor.serverWebsockets)
            implementation(libs.ktor.serverNetty)
            implementation(libs.ktor.client.websockets)
            // Compile access (not just runtime) so the join-code-not-logged test can attach a
            // logback ListAppender and assert the secret never reaches any log sink.
            implementation(libs.logback)
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
