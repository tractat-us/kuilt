import com.vanniktech.maven.publish.JavaPlatform

plugins {
    `java-platform`
    id("kuilt.publish")
}

// kuilt.publish wires POM metadata and the TigrisStaging + Central repos.
// It skips KMP-specific configure() because org.jetbrains.kotlin.multiplatform
// is not applied here, so we call it explicitly for the java-platform publication.
mavenPublishing {
    configure(JavaPlatform())
    pom {
        name.set("kuilt-bom")
        description.set("Bill of Materials for kuilt — import once to align all module versions.")
    }
}

dependencies {
    constraints {
        api(project(":kuilt-core"))
        api(project(":kuilt-liveness"))
        api(project(":kuilt-crdt"))
        api(project(":kuilt-quilter"))
        api(project(":kuilt-gossip"))
        api(project(":kuilt-deal"))
        api(project(":kuilt-deal-test"))
        api(project(":kuilt-game"))
        api(project(":kuilt-raft"))
        api(project(":kuilt-raft-test"))
        api(project(":kuilt-session"))
        api(project(":kuilt-session-test"))
        api(project(":kuilt-websocket"))
        api(project(":kuilt-stream"))
        api(project(":kuilt-tcp"))
        api(project(":kuilt-cluster"))
        api(project(":kuilt-multipeer"))
        api(project(":kuilt-nearby"))
        api(project(":kuilt-webrtc"))
        api(project(":kuilt-mdns"))
        api(project(":kuilt-conformance"))
        api(project(":kuilt-test"))
        api(project(":kuilt-otel"))
        api(project(":kuilt-otel-tap"))
        api(project(":kuilt-otel-tap-test"))
        api(project(":kuilt-otel-logging"))
        api(project(":kuilt-otel-logback"))
        api(project(":kuilt-otel-log4j2"))
        api(project(":kuilt-otel-sdk"))
        api(project(":kuilt-warp"))
        api(project(":kuilt-warp-otel"))
    }
}
