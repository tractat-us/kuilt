plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-crdt"))
            // kuilt-core provides runCatchingCancellable and will be needed for
            // WarpOtlpBridge (A5) once that is built. Keep as an implementation dep
            // (not api) — the kuilt-otel surface does not expose Seam/Swatch types.
            implementation(project(":kuilt-core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.cbor)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlin.logging)
            // ByteString — value-based equals/hashCode for trace/span IDs stored in ORSet.
            // kotlinx-io-bytestring is a transitive of kotlinx-io-core but we declare it
            // explicitly because we use it directly in the public API.
            api(libs.kotlinx.io.bytestring)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            runtimeOnly(libs.logback)
        }
        androidUnitTest.dependencies {
            runtimeOnly(libs.logback)
        }
    }
}
