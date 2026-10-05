plugins {
    id("kuilt.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-warp"))
            api(project(":kuilt-heddle"))
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(project(":kuilt-raft-test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.serialization.cbor)
        }
        jvmTest.dependencies {
            runtimeOnly(libs.logback)
        }
        androidUnitTest.dependencies {
            runtimeOnly(libs.logback)
        }
    }
}
