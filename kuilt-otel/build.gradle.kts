plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-crdt"))
            // api, not implementation: WarpTelemetry's constructor takes a DurableStore, so the
            // type is on this module's public surface and every consumer must be able to name it.
            api(project(":kuilt-store"))
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
            // TEST ONLY, and the direction is deliberate. `:kuilt-bolt` must stay ignorant of
            // telemetry — that is what lets one archiving decorator serve every op-log owner — so
            // the end-to-end test of an exporter feeding an archive lives on this side of the
            // edge, where the consumer is. Nothing in `:kuilt-otel`'s main sources depends on it.
            implementation(project(":kuilt-bolt"))
        }

        // No hand-wired source-set hierarchy here. The four platform `DurableStore`
        // implementations — the only reason this module ever had a `jvmAndAndroidMain`
        // intermediate and the explicit `appleMain`/`iosMain`/`macosMain`/`appleTest`
        // chain that creating one forces — now live in `:kuilt-store` (#2497), and
        // every source in this module is common. KMP's default hierarchy applies.

        jvmTest.dependencies {
            runtimeOnly(libs.logback)
        }
        androidUnitTest.dependencies {
            runtimeOnly(libs.logback)
        }
    }
}
