plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.kover)
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
        jvmMain.dependencies {
            // Chicory — pure-JVM wasm runtime (C3 substrate; JVM only, never touches commonMain).
            implementation(libs.chicory.runtime)
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

    // wasm3 cinterop — Apple Kotlin/Native targets, test compilation only.
    //
    // wasm3 (github.com/wasm3/wasm3 v0.5.0, MIT) is a pure C99 wasm interpreter
    // with no JIT; it works on iOS (which bans JIT) and macOS from one shared
    // source tree + one .def, making it the right choice for all three apple K/N
    // targets. The static libs in prebuilt/<target>/libwasm3.a were compiled on
    // macOS with the Xcode sysroot and committed so CI (ubuntu) can link without
    // needing xcrun/Xcode.
    //
    // Only the test compilation is wired — this is a correctness proof (C3 gate),
    // not a production runtime API. No WarpNode.kt edits, no commonMain changes.
    val wasm3DefFile = layout.projectDirectory.file("src/nativeInterop/cinterop/wasm3.def")
    val wasm3IncludeDir = layout.projectDirectory.dir("src/nativeInterop/wasm3/source")
    val wasm3PrebuiltDir = layout.projectDirectory.dir("src/nativeInterop/wasm3/prebuilt")

    macosArm64 {
        compilations.named("test") {
            cinterops.create("wasm3") {
                defFile(wasm3DefFile)
                includeDirs(wasm3IncludeDir)
                extraOpts("-libraryPath", wasm3PrebuiltDir.dir("macosArm64").asFile.absolutePath)
            }
        }
    }
    iosArm64 {
        compilations.named("test") {
            cinterops.create("wasm3") {
                defFile(wasm3DefFile)
                includeDirs(wasm3IncludeDir)
                extraOpts("-libraryPath", wasm3PrebuiltDir.dir("iosArm64").asFile.absolutePath)
            }
        }
    }
    iosSimulatorArm64 {
        compilations.named("test") {
            cinterops.create("wasm3") {
                defFile(wasm3DefFile)
                includeDirs(wasm3IncludeDir)
                extraOpts("-libraryPath", wasm3PrebuiltDir.dir("iosSimulatorArm64").asFile.absolutePath)
            }
        }
    }
}
