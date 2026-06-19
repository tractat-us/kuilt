plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core"))
            api(project(":kuilt-raft"))
            implementation(project(":kuilt-liveness"))
            implementation(project(":kuilt-quilter"))
            implementation(project(":kuilt-crdt"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.cbor)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-raft-test"))
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
