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
            api(project(":kuilt-quilter"))
            api(project(":kuilt-session"))
            api(project(":kuilt-liveness"))
            api(project(":kuilt-raft"))
            implementation(libs.kotlincrypto.hash.sha2)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.cbor)
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(project(":kuilt-raft-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            // Chicory — pure-JVM wasm runtime (C3 substrate; JVM only, never touches commonMain).
            implementation(libs.chicory.runtime)
        }
        jvmTest.dependencies {
            implementation(project(":kuilt-websocket"))
            implementation(libs.kotlin.testJunit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.serverNetty)
            implementation(libs.ktor.serverWebsockets)
            runtimeOnly(libs.logback)
        }
        androidUnitTest.dependencies {
            runtimeOnly(libs.logback)
        }
    }
}
