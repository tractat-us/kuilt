plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

// Forward the two opt-in gates to the JVM test process so the tests that read them via
// System.getProperty() can actually run — without this a `-P` flag never leaves the Gradle JVM and
// both suites self-skip while reporting green, which is dead coverage that looks live (#1748).
//   -Pserver.realnet.tests=true   → WebSocketPeerLinkRealNetworkTest (real Netty + real OkHttp on a
//                                   loopback socket; opt-in because it binds a real port).
//   -Pintegration.tests=true      → WebSocketPeerLinkFactoryTest (Ktor testApplication, wall-clock
//                                   awaits rather than virtual time).
// Mirrors :kuilt-cluster's cluster.realsocket.tests and :kuilt-mdns's mdns.multicast.tests.
tasks.withType<Test>().configureEach {
    listOf("server.realnet.tests", "integration.tests").forEach { name ->
        val flag = providers.gradleProperty(name).orNull
        if (flag != null) systemProperty(name, flag)
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core"))  // public API returns Loom/Seam — expose the contract transitively
            implementation(project(":kuilt-session"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.cbor)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.kotlin.logging)
        }

        // The #1725 connectivity-observer fold and its fake are common code, so their tests are
        // too — kuilt-test supplies assertAll for the multi-assert cases.
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
        }

        // jvmAndAndroidMain: Ktor server core ships only for JVM/Android targets —
        // there is no native (iOS/macOS) or wasmJs variant. KtorServerLoom lives here
        // so it compiles on both platforms without being visible to iOS/macOS/wasmJs
        // (which don't host a server).
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.ktor.serverCore)
                implementation(libs.ktor.serverWebsockets)
                // Shared by JVM and Android: the wss:// dev-cert helpers (tls/) use the OkHttp
                // client engine to pin a fingerprint, and Ktor's self-signed cert generator to
                // mint a DevTlsIdentity. Both APIs (KeyStore/X509TrustManager) exist on Android.
                implementation(libs.ktor.client.okhttp)
                implementation(libs.ktor.network.tls.certificates)
            }
        }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)

        // jvmAndAndroidTest: the tls-helper coverage that needs no server engine runs on
        // both the JVM and Android unit-test variants (the Netty round-trip stays in jvmTest).
        // Wired by hand for the same reason as jvmAndAndroidMain — the manual intermediate
        // disables the plugin's default test-hierarchy auto-wiring.
        val jvmAndAndroidTest by creating { dependsOn(commonTest.get()) }
        jvmTest.get().dependsOn(jvmAndAndroidTest)
        androidUnitTest.get().dependsOn(jvmAndAndroidTest)

        // iosMain: intermediate for both iOS K/N targets. Wired explicitly because
        // adding a manual jvmAndAndroidMain intermediate disables the KMP plugin's
        // default hierarchy auto-wiring for other platform intermediates.
        val iosMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }

        // macosMain: intermediate for macosArm64 K/N target.
        val macosMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
        val macosArm64Main by getting { dependsOn(macosMain) }

        jvmMain.dependencies {
            // Netty engine for the JVM server actual.
            implementation(libs.ktor.serverNetty)
        }
        androidMain.dependencies {
            // CIO engine for the Android server actual.
            implementation(libs.ktor.serverCio)
            implementation(libs.ktor.client.cio)
        }
        val wasmJsMain by getting {
            dependencies {
                implementation(libs.ktor.client.js)
            }
        }
        jvmTest.dependencies {
            implementation(project(":kuilt-conformance"))
            implementation(libs.kotlin.testJunit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.serverTestHost)
            implementation(libs.ktor.serverWebsockets)
            implementation(libs.ktor.serverNetty)
            implementation(libs.ktor.client.websockets)
            // CIO client engine — the half-open ping test needs an engine that honours the Ktor
            // client `pingInterval` (the OkHttp engine ignores it in favour of its own knob).
            implementation(libs.ktor.client.cio)
        }
    }
}
