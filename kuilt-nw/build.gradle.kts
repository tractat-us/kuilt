plugins { id("kuilt.kmp-library") }

kotlin {
    val macosLibName = "kuilt"
    macosArm64 { binaries.sharedLib { baseName = macosLibName } }

    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core"))  // public API returns Seam from weave() — expose the contract transitively
            implementation(project(":kuilt-session"))
            implementation(project(":kuilt-stream"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlin.logging)
        }
        // MANUAL appleMain → disables default-hierarchy auto-wiring → hand-wire ALL intermediates:
        val appleMain by creating { dependsOn(commonMain.get()) }
        val iosArm64Main by getting { dependsOn(appleMain) }
        val iosSimulatorArm64Main by getting { dependsOn(appleMain) }
        val macosMain by creating { dependsOn(appleMain) }
        val macosArm64Main by getting { dependsOn(macosMain) }
        jvmMain.dependencies { implementation(libs.jna) }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(project(":kuilt-conformance"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // Mirror the manual appleMain wiring for the test compilations so any
        // apple-only unit tests share one appleTest source set.
        val appleTest by creating { dependsOn(commonTest.get()) }
        val iosArm64Test by getting { dependsOn(appleTest) }
        val iosSimulatorArm64Test by getting { dependsOn(appleTest) }
        val macosArm64Test by getting { dependsOn(appleTest) }
        jvmTest.dependencies {
            implementation(libs.kotlin.testJunit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.logback)
        }
    }
}

val nativeBinariesDir = layout.buildDirectory.dir("native-binaries-jvm")
val packageMacosNatives = tasks.register<Copy>("packageMacosNatives") {
    group = "build"
    from(layout.buildDirectory.dir("bin/macosArm64/releaseShared")) {
        include("libkuilt.dylib"); into("darwin-aarch64")
    }
    into(nativeBinariesDir); dependsOn("linkReleaseSharedMacosArm64")
}
kotlin.sourceSets.named("jvmMain") { resources.srcDir(packageMacosNatives.map { it.destinationDir }) }
