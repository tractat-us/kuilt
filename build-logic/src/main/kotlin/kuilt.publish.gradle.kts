import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform

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
        publishToMavenCentral(automaticRelease = false)
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

// Per-module POM description. Maven Central rejects publications without one, and
// this text is what a reader sees in Central's search results and on aggregators
// like klibs.io — so every module earns a line saying what it actually does, in
// plain language, kept in sync with the module table in CLAUDE.md.
//
// There is deliberately no fallback. A generic `else` branch is invisible when it
// fires: 29 of 44 published modules once shipped "…Module kuilt-otel." to Maven
// Central and nothing failed. `moduleDescription` is evaluated at configuration
// time for exactly the modules that apply this plugin, so `error` here is an exact
// completeness check — no allowlist to drift, nothing lexical to evade. Same shape
// as kuilt-bom's `hasPlugin("kuilt.publish")` backstop. A new module fails
// configuration until it says what it is.
fun moduleDescription(module: String): String = when (module) {
    // ── Contract & core ──────────────────────────────────────────────────────
    "kuilt-core" ->
        "The kuilt contract — Loom, Seam and Swatch — with the InMemoryLoom reference fabric, " +
            "MuxSeam channel multiplexing and CompositeLoom multipath bonding."

    // ── Libraries layered on the contract ────────────────────────────────────
    "kuilt-crdt" ->
        "Delta-state CRDTs — counters, sets, registers, maps, sequences and JSON — as plain " +
            "serializable values, usable with any transport or none at all."
    "kuilt-quilter" ->
        "Live CRDT replication over a kuilt Seam: delta exchange, causal garbage collection " +
            "and anti-entropy."
    "kuilt-store" ->
        "Durable key-to-bytes storage for Kotlin Multiplatform: a write returns only once the " +
            "bytes will survive a crash, with a crash-safe implementation per platform."
    "kuilt-bolt" ->
        "Write-only history archive kept beside a live CRDT replica, so a server can keep a " +
            "year of edits while the phone feeding it keeps an hour."
    "kuilt-raft" ->
        "Raft consensus over a kuilt Seam: leader election, log replication, snapshots, " +
            "dynamic membership, linearizable reads and leadership transfer."
    "kuilt-cluster" ->
        "Server-cluster facade over kuilt-raft: a voter core with a learner periphery, " +
            "learner-to-leader forwarding, and cross-relay failover."
    "kuilt-game" ->
        "Turn-based game sessions over kuilt-raft: propose and commit typed actions, apply " +
            "them optimistically, and roll back deterministically."
    "kuilt-deal" ->
        "Cryptographically fair card dealing and dealer-less random seed agreement over a " +
            "kuilt Seam."
    "kuilt-gossip" ->
        "Partial-mesh overlay for a kuilt Seam: broadcast floods to a few neighbours rather " +
            "than everyone, so large sessions scale."
    "kuilt-heddle" ->
        "Fair-share scheduling of one pooled resource across peers with no central referee, " +
            "and it keeps working while the network is partitioned."
    "kuilt-liveness" ->
        "Peer-liveness detection over a kuilt Seam: heartbeats, partition events and reachability."
    "kuilt-session" ->
        "Membership-aware Room over a kuilt Loom: admit/identify handshake, roster, reconnect " +
            "tokens and partition detection."
    "kuilt-stream" ->
        "Wraps a byte stream as a kuilt Connection, with length-prefix framing and oversize " +
            "protection."

    // ── Fabrics & discovery ──────────────────────────────────────────────────
    "kuilt-websocket" -> "Ktor WebSocket fabric for kuilt."
    "kuilt-tcp" -> "Raw TCP fabric for kuilt, on JVM and Android."
    "kuilt-nw" ->
        "Apple Network.framework peer-to-peer fabric for kuilt: nearby devices find each other " +
            "and connect directly, with no server."
    "kuilt-multipeer" -> "Apple Multipeer Connectivity fabric for kuilt. Superseded by kuilt-nw."
    "kuilt-nearby" -> "Google Nearby Connections fabric for kuilt."
    "kuilt-webrtc" -> "WebRTC data-channel fabric for kuilt."
    "kuilt-mdns" -> "Bonjour/mDNS discovery for kuilt."

    // ── Observability ────────────────────────────────────────────────────────
    "kuilt-otel" ->
        "Offline-first OpenTelemetry: record traces, metrics and logs on any device and have " +
            "them reconcile when connectivity returns, with no duplicates and no data loss."
    "kuilt-otel-tap" ->
        "Pull the logs off a running device by joining it as a peer and reading the " +
            "offline-first buffer it already keeps."
    "kuilt-otel-logging" ->
        "Routes an app's kotlin-logging output into the kuilt offline-first telemetry buffer, " +
            "through one call on every platform."
    "kuilt-otel-logback" ->
        "Captures Logback output — including other libraries' — into the kuilt offline-first " +
            "telemetry buffer."
    "kuilt-otel-log4j2" ->
        "Captures Log4j2 output — including other libraries' — into the kuilt offline-first " +
            "telemetry buffer."
    "kuilt-otel-sdk" ->
        "Bridges an existing OpenTelemetry SDK setup into the kuilt offline-first telemetry " +
            "buffer, trace-context propagation included."
    "kuilt-otel-otlp" ->
        "Forwards buffered spans, logs and metrics to any OTLP/HTTP collector when the network " +
            "returns, sending only what the endpoint has not already received."

    // ── Warp — the coordination-free distributed scheduler ───────────────────
    "kuilt-warp" ->
        "Spreads a pile of work across whoever is connected, with no central scheduler and no " +
            "peer doing the same job twice."
    "kuilt-warp-runtime" ->
        "The sandbox that runs code another peer sent you: a walled-off WASM engine with no " +
            "files, no network, no clock, and no way to run forever."
    "kuilt-warp-compiler" ->
        "Real Binaryen wasm-opt optimizer for warp compiler nodes (bundled, extract-and-exec)."
    "kuilt-warp-ksp" ->
        "KSP symbol processor for warp: generates OpRegistrar wiring for @WarpOp-annotated ops."
    "kuilt-warp-planning" ->
        "Coordination-cost model and planner for warp: scores what a pipeline would pay and " +
            "rewrites it to minimise that."
    "kuilt-warp-ml" ->
        "Federated learning over warp: train one shared model from everybody's data without " +
            "anybody handing over their data."
    "kuilt-warp-otel" ->
        "Records warp execution metrics — executions, duplicates, failovers — into a kuilt-otel " +
            "exporter. Idempotent under retry."
    "kuilt-warp-heddle" ->
        "Fair share for warp: express that interactive work gets more of the grid than batch " +
            "work, without changing how warp picks who runs what."

    // ── Conformance & test support ───────────────────────────────────────────
    "kuilt-conformance" ->
        "Conformance TCKs (SeamConformanceSuite, RoomConformanceSuite) for kuilt fabrics and rooms."
    "kuilt-test" -> "Shared test utilities and fakes for kuilt, built on kuilt-core."
    "kuilt-session-test" -> "Session test support (FakeRoomFactory, …) for kuilt."
    "kuilt-raft-test" -> "Raft test harness (FakeRaftNode, …) for kuilt."
    "kuilt-deal-test" -> "Conformance TCK for kuilt CommutativeScheme implementations."
    "kuilt-gossip-test" ->
        "Gossip test support for kuilt: a started in-memory star of GossipSeams, with a handle " +
            "for admitting a fresh client mid-test."
    "kuilt-warp-test" ->
        "Warp test infrastructure: the sandboxed-WASM conformance TCK and a deterministic " +
            "virtual-time multi-node simulator."
    "kuilt-otel-tap-test" ->
        "Test and CI support for live-tailing the logs of a tapped device over kuilt-otel-tap."

    // ── Packaging ────────────────────────────────────────────────────────────
    "kuilt-bom" -> "Bill of Materials for kuilt — import once to align all module versions."

    else -> error(
        "No POM description for '$module'. Add a line to moduleDescription() in " +
            "build-logic/src/main/kotlin/kuilt.publish.gradle.kts saying what the module does — " +
            "this text is what Maven Central search and klibs.io show a reader."
    )
}
