plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.kover)
}

// The JVM tests run a Netty server (`WarpNodeWebSocketTest`), and Netty's native-library loader calls
// `System.loadLibrary`, a restricted method: from JDK 24 the JVM prints a native-access warning on
// stderr the first time it is called (#2842). It refuses nothing today, but stderr cleanliness is
// evidence when a test here hangs or reds, so pre-approve the load rather than teach every reader
// to skim past it. `ALL-UNNAMED` because Netty sits on the test classpath, not the module path.
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
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
