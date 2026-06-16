plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    testImplementation(project(":kuilt-game"))
    testImplementation(project(":kuilt-raft-test"))
    testImplementation(project(":kuilt-raft"))
    testImplementation(project(":kuilt-crdt"))
    testImplementation(project(":kuilt-core"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.logback)
}

tasks.test {
    useJUnitPlatform()
}
