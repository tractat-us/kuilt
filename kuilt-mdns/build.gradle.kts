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
        androidMain.dependencies {
            implementation(libs.kotlin.logging)
        }
        // Android's MDNSServiceDiscoverer is bound to DiscoverySourceConformanceSuite here (#1903).
        // It runs as a plain JVM unit test against a fake NsdBrowser — NsdManager is a final class
        // with a package-private constructor, so the seam is the only way to reach this code at all.
        //
        // logback is the SLF4J backend kotlin-logging needs on the Android unit-test variant: the
        // discoverer holds a file-level logger, so class-init would otherwise throw
        // NoClassDefFoundError: org/slf4j/LoggerFactory. Mirrors :kuilt-liveness / :kuilt-session.
        androidUnitTest.dependencies {
            implementation(project(":kuilt-conformance"))
            implementation(libs.kotlin.testJunit)
            implementation(libs.kotlinx.coroutines.test)
            runtimeOnly(libs.logback)
        }
        iosMain.dependencies {
            implementation(libs.kotlin.logging)
        }
        // iosMain's MDNSServiceDiscoverer is bound to the same suite (#2400), against a fake
        // Bonjour browser — NSNetServiceBrowser only delivers callbacks while the main run loop is
        // pumped, and a runTest body on Kotlin/Native occupies the thread that would pump it.
        iosTest.dependencies {
            implementation(project(":kuilt-conformance"))
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
