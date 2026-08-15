plugins { id("kuilt.kmp-library") }

tasks.withType<Test>().configureEach {
    val flag = providers.gradleProperty("multipeer.realnet.tests").orNull
    if (flag != null) systemProperty("multipeer.realnet.tests", flag)
}

kotlin {
    val macosLibName = "kuilt"
    macosArm64 { binaries.sharedLib { baseName = macosLibName } }

    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core"))  // public API returns Seam from weave() — expose the contract transitively
            implementation(project(":kuilt-session"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)  // single-shot terminal-teardown latch in MCSessionLink
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
            implementation(libs.kotlinx.coroutines.test)
        }
        // Mirror the manual appleMain wiring for the test compilations so the
        // apple-only unit tests (MCSessionLink / MultipeerPeerLinkFactory) share
        // one appleTest source set.
        val appleTest by creating {
            dependsOn(commonTest.get())
            // appleMain's MultipeerServiceBrowser is bound to DiscoverySourceConformanceSuite here
            // (kuilt #2410). It runs against the real MCNearbyServiceBrowser with its delegate driven by
            // hand — a real foundPeer needs a second physical device, so the delegate is the only
            // place this platform's arrivals and departures can be staged at all.
            dependencies { implementation(project(":kuilt-conformance")) }
        }
        val iosArm64Test by getting { dependsOn(appleTest) }
        val iosSimulatorArm64Test by getting { dependsOn(appleTest) }
        val macosArm64Test by getting { dependsOn(appleTest) }
        // The android stub is asserted directly rather than through
        // DiscoverySourceConformanceSuite: its discoveries() throws, so no honest causeArrival
        // exists and a binding could only pass by not running. See MultipeerAndroidStubTest.
        // kotlin-test resolves via a JUnit typealias on the Android unit-test variant, so that one
        // artifact has to be named here; coroutines-test already arrives through commonTest.
        androidUnitTest.dependencies { implementation(libs.kotlin.testJunit) }
        jvmTest.dependencies {
            implementation(project(":kuilt-conformance"))
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
