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

// ── Self-maintaining constraint list (#1044) ─────────────────────────────────
//
// Published ⟺ the module applies the `kuilt.publish` convention plugin
// (directly, or via `kuilt.kmp-library` which applies it). The BOM derives its
// constraints from that marker instead of a hand-maintained list, which drifted
// twice: #1004/#1036 found 10 modules missing, and by the time this landed four
// more (:kuilt-gossip-test, :kuilt-warp-runtime, :kuilt-warp-planning,
// :kuilt-warp-ml) had gone missing again.
//
// `evaluationDependsOn` is required before `hasPlugin`: :kuilt-bom is declared
// first in settings.gradle.kts, so siblings are still unconfigured when this
// script runs and the plugin check would be a false negative for all of them.
// (Same cross-project-at-configuration-time pattern as the root build script's
// forbidSourcelessKmpTarget guard; fine without isolated projects, which this
// build does not enable.)
val deliberatelyUnpublished = setOf(
    ":kuilt-scale", // JVM-only scaling/bench harness (plain kotlinJvm)
    ":examples", // test-only usage examples (plain kotlinJvm)
    ":demo-shared", // Patchwork demo app core (plain KMP jvm+wasmJs, never published)
    ":demo-relay", // Patchwork demo relay process (plain kotlinJvm application, never published)
    ":demo-cli", // Patchwork demo terminal peer (plain kotlinJvm application, never published)
)

val publishedSiblings = rootProject.subprojects
    .filter { it.path != project.path }
    .onEach { evaluationDependsOn(it.path) }
    .filter { it.plugins.hasPlugin("kuilt.publish") }

// Completeness backstop: every sibling must be either published or explicitly
// declared unpublished above. A new module can never be silently absent from
// the BOM — a module that forgets `kuilt.publish`/`kuilt.kmp-library` (and is
// not listed here as deliberately unpublished) fails configuration.
val unaccounted = rootProject.subprojects.map { it.path }
    .minus(publishedSiblings.map { it.path }.toSet())
    .minus(deliberatelyUnpublished)
    .minus(project.path)
check(unaccounted.isEmpty()) {
    "Modules neither published (apply kuilt.publish / kuilt.kmp-library) nor listed " +
        "as deliberatelyUnpublished in kuilt-bom/build.gradle.kts: $unaccounted"
}
val staleExclusions = deliberatelyUnpublished.filter { path ->
    rootProject.subprojects.none { it.path == path } || publishedSiblings.any { it.path == path }
}
check(staleExclusions.isEmpty()) {
    "deliberatelyUnpublished entries in kuilt-bom/build.gradle.kts that no longer " +
        "exist or are now published: $staleExclusions"
}

dependencies {
    constraints {
        publishedSiblings.forEach { api(project(it.path)) }
    }
}
