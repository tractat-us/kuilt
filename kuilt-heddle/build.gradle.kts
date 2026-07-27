plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-crdt"))
            api(project(":kuilt-core"))
            api(project(":kuilt-raft"))
            implementation(project(":kuilt-quilter"))
            implementation(project(":kuilt-liveness"))
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.cbor)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(project(":kuilt-raft-test"))
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            runtimeOnly(libs.logback)
        }
        androidUnitTest.dependencies {
            runtimeOnly(libs.logback)
        }
    }
}
