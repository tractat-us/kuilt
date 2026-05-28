plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kuilt-core"))
            implementation(project(":kuilt-session"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.cbor)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.kotlin.logging)
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
            }
        }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)

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
            implementation(libs.ktor.client.okhttp)
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
        }
    }
}
