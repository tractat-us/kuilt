plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    testImplementation(project(":kuilt-game"))
    testImplementation(project(":kuilt-raft-test"))
    testImplementation(project(":kuilt-raft"))
    testImplementation(project(":kuilt-crdt"))
    testImplementation(project(":kuilt-quilter"))
    testImplementation(project(":kuilt-core"))
    testImplementation(project(":kuilt-session"))
    testImplementation(project(":kuilt-websocket"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.ktor.serverWebsockets)
    testImplementation(libs.ktor.serverNetty)
    testImplementation(libs.ktor.client.websockets)
    testRuntimeOnly(libs.logback)
}

tasks.test {
    useJUnitPlatform()
}
