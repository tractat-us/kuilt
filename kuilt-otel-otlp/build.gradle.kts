plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

// The JVM tests run a Netty collector (`OtlpHttpEdgeIntegrationTest`), and Netty's native-library
// loader calls `System.loadLibrary`, a restricted method: from JDK 24 the JVM prints a native-access
// warning on stderr the first time it is called (#2842). It refuses nothing today, but stderr
// cleanliness is evidence when a test here hangs or reds, so pre-approve the load rather than teach
// every reader to skim past it. `ALL-UNNAMED` because Netty sits on the test classpath.
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Public surface consumes kuilt-otel types (OtlpEdge, records, digests).
            api(project(":kuilt-otel"))
            // OtlpHttpEdge's constructor takes a DurableStore for its sent-set persistence,
            // so the type is on this module's own public surface — declared here rather than
            // leaned on transitively through :kuilt-otel's api edge.
            api(project(":kuilt-store"))
            // runCatchingCancellable — cancellation-safe sends and digest reads.
            implementation(project(":kuilt-core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.protobuf) // OTLP/protobuf wire (selectable alongside JSON)
            implementation(libs.kotlinx.serialization.cbor) // producer-local sent-set persistence
            // SHA-256 over the collector base URL, so the sent-set key's length is bounded and
            // independent of the endpoint (#2513). KMP-uniform on every target this module builds
            // for; :kuilt-deal's FairRandom uses the same artifact from commonMain.
            implementation(libs.kotlincrypto.hash.sha2)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlin.logging)
            api(libs.kotlinx.io.bytestring)
        }

        // Per-target Ktor engines. The client runs on all targets, so this module is
        // all-target (no server piece, no jvmAndAndroidMain intermediate — the default
        // KMP hierarchy auto-wiring stays on). Engines attach to the leaf source sets,
        // which always exist for the declared targets.
        jvmMain.dependencies { implementation(libs.ktor.client.okhttp) }
        androidMain.dependencies { implementation(libs.ktor.client.cio) }
        val iosArm64Main by getting { dependencies { implementation(libs.ktor.client.darwin) } }
        val iosSimulatorArm64Main by getting { dependencies { implementation(libs.ktor.client.darwin) } }
        val macosArm64Main by getting { dependencies { implementation(libs.ktor.client.darwin) } }
        val wasmJsMain by getting { dependencies { implementation(libs.ktor.client.js) } }

        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.testJunit)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.serverTestHost)
            implementation(libs.ktor.serverNetty)
        }
    }
}
