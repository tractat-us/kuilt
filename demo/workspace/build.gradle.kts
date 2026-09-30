// Agent-workspace spike (epic #2869, M0/M1). Deliberately a PLAIN KMP module, as
// demo/shared is: no explicitApi, no publishing, JVM only. Headless: results here are
// JVM-only evidence and make no cross-target claim. Listed in kuilt-bom's
// `deliberatelyUnpublished` set.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kuilt-quilter")) // api-exposes :kuilt-core + :kuilt-crdt
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies { runtimeOnly(libs.logback) }
    }
}
