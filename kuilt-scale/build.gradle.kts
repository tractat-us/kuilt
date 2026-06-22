// :kuilt-scale — JVM-only scaling test / bench harness.
//
// NOT published: uses the plain kotlinJvm plugin (no kuilt.kmp-library, no kuilt.publish).
// Excluded from kuilt-bom. CI runs it as part of the regular JVM test suite (./gradlew build),
// but the real-TCP layer is opt-in via -Pscale.tcp.tests=true (never in the default gate).

plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    implementation(project(":kuilt-core"))
    implementation(project(":kuilt-test"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.atomicfu)

    testImplementation(project(":kuilt-core"))
    testImplementation(project(":kuilt-test"))
    testImplementation(project(":kuilt-raft"))
    testImplementation(project(":kuilt-raft-test"))
    testImplementation(project(":kuilt-tcp"))
    testImplementation(project(":kuilt-stream"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.network)
    testRuntimeOnly(libs.logback)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()

    // Forward -Pscale.tcp.tests=true to the test process as a system property.
    // Tests guarded by ScaleTcpTests.assumeEnabled() only run when this is present,
    // matching the -Pmdns.multicast.tests pattern from :kuilt-mdns.
    val flag = providers.gradleProperty("scale.tcp.tests").orNull
    if (flag != null) systemProperty("scale.tcp.tests", flag)
}
