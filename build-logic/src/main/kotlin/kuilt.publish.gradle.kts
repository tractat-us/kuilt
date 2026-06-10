import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    `maven-publish`
    // base = no auto-added Sonatype repo, no auto-signing. We keep full control:
    // the TigrisStaging repo (below) and the per-merge Tigris push stay as-is;
    // GPG signing and the Central Portal upload are wired in later, gated steps.
    id("com.vanniktech.maven.publish.base")
}

// Attach a (stub) javadoc jar to every target — a hard Maven Central requirement
// that KMP does not emit by default. Sources jars are on by default. `configure`
// requires the Kotlin Multiplatform plugin to already be applied; kuilt.kmp-library
// applies this convention *before* the KMP plugin, so defer until KMP is on rather
// than depend on plugin-application order. Coordinates default to
// project.group/name/version, i.e. us.tractat.kuilt:<module>:<version>.
plugins.withId("org.jetbrains.kotlin.multiplatform") {
    mavenPublishing {
        configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty()))
    }
}

mavenPublishing {
    pom {
        name.set(project.name)
        description.set(moduleDescription(project.name))
        url.set("https://github.com/tractat-us/kuilt")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("keddie")
                name.set("Iain Keddie")
                url.set("https://github.com/keddie")
            }
        }
        scm {
            url.set("https://github.com/tractat-us/kuilt")
            connection.set("scm:git:https://github.com/tractat-us/kuilt.git")
            developerConnection.set("scm:git:ssh://git@github.com/tractat-us/kuilt.git")
        }
    }

    // GPG-sign every publication — but ONLY when an in-memory signing key is
    // present. vanniktech reads `signingInMemoryKey` / `signingInMemoryKeyPassword`
    // (set in CI from the SIGNING_KEY / SIGNING_PASSWORD secrets via the
    // ORG_GRADLE_PROJECT_* env convention). Gating on key presence keeps the
    // unsigned paths green: local `./gradlew build` and the per-merge TigrisStaging
    // push have no key and must not fail on a missing signature. Maven Central
    // requires signatures, so the release workflow (#303) supplies the key.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    // Maven Central (Central Portal) — configured ONLY when a Central username is
    // present (CI maps the MAVEN_CENTRAL_USERNAME/PASSWORD secrets to
    // ORG_GRADLE_PROJECT_mavenCentralUsername/Password). Gating on credential
    // presence means local builds and the per-merge Tigris path never configure a
    // Central repo — so they can't publish to Central even by accident; that
    // absence is itself a release-control interlock. `automaticRelease = false`:
    // even a triggered release uploads to the Portal as a *validated, pending*
    // deployment that must be released by hand — deliberate control of every
    // version, especially the first. The release path is gated again in the
    // workflow (tags / manual dispatch only).
    if (providers.gradleProperty("mavenCentralUsername").isPresent) {
        publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = false)
    }
}

publishing {
    repositories {
        // TigrisStaging: a *local file://* maven repo that the publish workflow
        // stages publications into before `aws s3 sync`-ing the whole tree up
        // to Tigris. We don't use Gradle's native s3:// transport because it
        // sets a header (an ACL or storage-class) that Tigris rejects with
        // HTTP 400 — vanilla AWS CLI writes work to the same bucket. Stage-and-
        // sync sidesteps Gradle's transport entirely and reuses the AWS CLI path.
        maven {
            name = "TigrisStaging"
            url = rootProject.layout.buildDirectory.dir("staged-maven-repo").get().asFile.toURI()
        }
    }
}

// Per-module POM description. Maven Central rejects publications without one, so
// every module maps to a one-line summary here (kept in sync with the module
// table in CLAUDE.md). The fallback keeps any future module Central-valid until
// it earns a bespoke line.
fun moduleDescription(module: String): String = when (module) {
    "kuilt-core" -> "kuilt contract (Loom/Seam/Swatch) and the InMemoryLoom reference implementation."
    "kuilt-crdt" -> "Delta-state CRDT zoo plus SeamReplicator and RoutingSeam for kuilt."
    "kuilt-raft" -> "Raft consensus (election, log replication, snapshots, membership) over a kuilt Seam."
    "kuilt-session" -> "Membership-aware Room over a kuilt Loom (SeamRoom): handshake, roster, reconnect, partition detection."
    "kuilt-websocket" -> "Ktor WebSocket fabric for kuilt."
    "kuilt-multipeer" -> "Apple Multipeer Connectivity fabric for kuilt."
    "kuilt-nearby" -> "Google Nearby Connections fabric for kuilt."
    "kuilt-webrtc" -> "WebRTC data-channel fabric for kuilt."
    "kuilt-mdns" -> "Bonjour/mDNS discovery for kuilt."
    "kuilt-conformance" -> "Conformance TCKs (SeamConformanceSuite, RoomConformanceSuite) for kuilt fabrics and rooms."
    "kuilt-test" -> "Shared test utilities and fakes for kuilt, built on kuilt-core."
    "kuilt-session-test" -> "Session test support (FakeRoomFactory, …) for kuilt."
    "kuilt-raft-test" -> "Raft test harness (FakeRaftNode, …) for kuilt."
    else -> "kuilt — peer-symmetric, multiplatform networking. Module $module."
}
