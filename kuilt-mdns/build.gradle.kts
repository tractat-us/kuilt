plugins {
    id("kuilt.kmp-library")
}

// Forward -Pmdns.multicast.tests=true to the JVM test process so that
// MDNSMulticastIntegrationTest can read it via System.getProperty().
tasks.withType<Test>().configureEach {
    val flag = providers.gradleProperty("mdns.multicast.tests").orNull
    if (flag != null) systemProperty("mdns.multicast.tests", flag)
}

// Forward -Pmdns.multicast.tests=true to the iOS K/N simulator test binary as
// the environment variable MDNS_MULTICAST_TESTS, readable via platform.posix.getenv.
// K/N test binaries don't support JVM system properties — env vars are the
// standard mechanism.
val mdnsFlag = providers.gradleProperty("mdns.multicast.tests").orNull
if (mdnsFlag != null) {
    tasks
        .withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>()
        .configureEach { environment("MDNS_MULTICAST_TESTS", mdnsFlag) }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core"))  // public API exposes PeerId/Tag/Loom — expose the contract transitively
            implementation(project(":kuilt-crdt"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(project(":kuilt-websocket"))
            implementation(libs.jmdns)
            implementation(libs.ktor.serverCore)
            implementation(libs.ktor.client.core)
        }
        jvmTest.dependencies {
            implementation(project(":kuilt-conformance"))
            implementation(libs.kotlin.testJunit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.serverCore)
            implementation(libs.ktor.serverWebsockets)
            implementation(libs.ktor.serverNetty)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.okhttp)
        }
    }
}
