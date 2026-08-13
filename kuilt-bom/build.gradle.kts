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
// The mutation-receipt probe (#2272), if one was asked for. This check is the reason the probe
// needs an entry at all: it runs at CONFIGURATION time, so without an exemption it fails before any
// task executes and pre-empts the very guard the probe exists to prove. Keeping it here rather than
// making the receipt-taker edit `deliberatelyUnpublished` by hand is what removes the follow-on trap
// — a hand-added entry that survives the revert then trips `staleExclusions` on the NEXT run, a
// second red about a second unrelated thing.
//
// The two halves live in two files, so verify the other one is still there. If the property is set
// and the module is absent, `settings.gradle.kts`'s probe block has been deleted or broken and the
// documented receipt has silently reverted to the invalid shape.
val guardProbeModule: String? = providers.gradleProperty("guardProbeModule").orNull
if (guardProbeModule != null) {
    check(rootProject.subprojects.any { it.path == guardProbeModule }) {
        "-PguardProbeModule=$guardProbeModule was passed but no such module is in the build. " +
            "The probe block in settings.gradle.kts is missing or broken, so the receipt shape " +
            "documented in the root build script's \"Guard plumbing\" section no longer works (#2272)."
    }
}

val deliberatelyUnpublished = setOf(
    ":kuilt-scale", // JVM-only scaling/bench harness (plain kotlinJvm)
    ":examples", // test-only usage examples (plain kotlinJvm)
    ":demo-shared", // Patchwork demo app core (plain KMP jvm+wasmJs, never published)
    ":demo-relay", // Patchwork demo relay process (plain kotlinJvm application, never published)
    ":demo-cli", // Patchwork demo terminal peer (plain kotlinJvm application, never published)
    ":demo-web", // Patchwork demo browser page (plain KMP wasmJs executable, never published)
    ":demo-tap", // Patchwork demo reach-in harness (plain kotlinJvm application, never published)
) + if (providers.gradleProperty("includeSpike").isPresent) {
    setOf(":spike") // Phase-0 kuilt-nw connectivity spike (#1403), opt-in via -PincludeSpike; throwaway
} else {
    emptySet()
} + guardProbeModule?.let { setOf(it) }.orEmpty() // mutation-receipt probe (#2272), opt-in; see below

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
        "as deliberatelyUnpublished in kuilt-bom/build.gradle.kts: $unaccounted\n" +
        "  If you added this module as a PROBE, to prove some OTHER guard notices a new module: " +
        "this failure is CONFIGURATION-time, so it pre-empted that guard and this red says " +
        "nothing about it (#2272). Revert the settings.gradle.kts edit and use the probe flag " +
        "instead — `./gradlew <theGuardUnderTest> -PguardProbeModule=${unaccounted.first()}` — which is " +
        "accounted for here and so reaches the guard. See the root build script's \"Guard " +
        "plumbing\" section."
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
