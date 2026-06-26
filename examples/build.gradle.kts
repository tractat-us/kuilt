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
    testImplementation(project(":kuilt-cluster"))
    testImplementation(project(":kuilt-warp"))
    testImplementation(project(":kuilt-websocket"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.ktor.serverWebsockets)
    testImplementation(libs.ktor.serverNetty)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.ktor.client.okhttp)
    testRuntimeOnly(libs.logback)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("warp.fl.ws", (project.findProperty("warp.fl.ws") ?: "false").toString())
}
