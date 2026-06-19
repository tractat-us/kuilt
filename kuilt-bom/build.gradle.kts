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
        api(project(":kuilt-deal"))
        api(project(":kuilt-deal-test"))
        api(project(":kuilt-game"))
        api(project(":kuilt-raft"))
        api(project(":kuilt-raft-test"))
        api(project(":kuilt-session"))
        api(project(":kuilt-session-test"))
        api(project(":kuilt-websocket"))
        api(project(":kuilt-multipeer"))
        api(project(":kuilt-nearby"))
        api(project(":kuilt-webrtc"))
        api(project(":kuilt-mdns"))
        api(project(":kuilt-conformance"))
        api(project(":kuilt-test"))
    }
}
