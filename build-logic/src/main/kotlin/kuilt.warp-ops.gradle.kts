// kuilt.warp-ops — opt-in convention for a KMP module that declares @WarpOp ops.
//
// Apply AFTER kuilt.kmp-library:
//
//     plugins {
//         id("kuilt.kmp-library")
//         id("kuilt.warp-ops")
//     }
//
// Wires the :kuilt-warp-ksp symbol processor into the module's *common metadata*
// compilation. That is the one-generation-covers-all-targets path: KSP itself
// always executes on the JVM at build time, and because @WarpOp ops live in
// commonMain, processing the metadata compilation once yields ordinary commonMain
// Kotlin (the per-package `WarpOps` registrars) that every target — JVM, Android,
// iOS, macOS, wasmJs — then compiles like hand-written code. No per-target KSP
// runs, no per-target divergence, no runtime reflection anywhere.
//
// The two blocks below are the standard KMP+KSP metadata plumbing (KGP does not
// auto-wire generated metadata sources):
//   1. add the generated dir to commonMain;
//   2. make every other task that reads commonMain run after kspCommonMainKotlinMetadata.
//
// External consumers (outside this repo) reproduce this with the published
// processor: add("kspCommonMainMetadata", "us.tractat.kuilt:kuilt-warp-ksp:<v>")
// plus the same two blocks — see docs/warp-op-autoregistration.md.

import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    id("com.google.devtools.ksp")
}

dependencies {
    add("kspCommonMainMetadata", project(":kuilt-warp-ksp"))
}

// The name of the generator, used by every rule below. A `val` rather than five string
// literals: the literals were what let the enumeration below drift out of step with itself.
val generator = "kspCommonMainKotlinMetadata"

// A PLAIN PATH, deliberately — and not because nobody tried the provider.
//
// The structural fix for everything below would be to let the contribution carry its own
// producer, `kotlin.srcDir(files(dir).builtBy(generator))`, so Gradle infers the edge for
// every consumer including ones nobody has written yet. That is not expressible here, and
// the reason is in KSP's Gradle plugin rather than in this build: `KspAATask` sets
// `kspConfig.commonSourceRoots.from(kotlinCompilation.defaultSourceSet.kotlin)` — the WHOLE
// commonMain source set, as a real `@InputFiles` property — so the generator consumes the
// source set it writes into, and a `builtBy` on that set is a self-cycle:
//
//     Circular dependency between the following tasks:
//     :kuilt-warp-test:kspCommonMainKotlinMetadata
//     \--- :kuilt-warp-test:kspCommonMainKotlinMetadata
//
// KSP's own documented lever for this (`ksp { excludedSources.from(dir) }`, whose KDoc is
// literally "if you have a task that generates sources") does NOT lift it: it filters
// `sourceRoots`/`javaSourceRoots` and prunes the generator's explicit `dependsOn`, and leaves
// `commonSourceRoots` untouched. Measured on KSP 2.3.10 — same cycle, verbatim.
//
// So the edges have to be declared at the consumers, and the rules below are that list.
extensions.configure<KotlinMultiplatformExtension> {
    sourceSets.named("commonMain") {
        kotlin.srcDir(layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin"))
    }
}

// Every task in this module that reads commonMain sources, and therefore reads the generated
// directory above. Without an edge, Gradle 9 fails the task graph with an implicit-dependency
// validation error on kspCommonMainKotlinMetadata's output directory.
//
// This list is the fragile part of the design, and it has been wrong once in a way worth
// recording: it used to cover the per-target KSP tasks (`kspKotlinJvm` and friends) only by
// ACCIDENT, because under KSP 1 they happened to be `KotlinCompilationTask`s and so fell out
// of the first rule. Under KSP 2 they are `KspAATask`, a plain `DefaultTask`, and the cover
// silently lapsed; the bump to KSP 2.3.11 — which stopped skipping a per-target KSP task whose
// processor classpath is empty — then made them execute and turned the lapse into a red CI
// (#2014). Hence the second rule: the KSP tasks are matched by NAME, which is stable across
// both KSP generations, rather than inherited from a supertype that can be taken away.
//
// A NEW consumer type still has to be added here by hand. What makes that survivable is that
// the failure is loud rather than silent — Gradle refuses the graph — so the cost of an
// omission is a build error naming both tasks, not a wrong result.
tasks.withType<KotlinCompilationTask<*>>().configureEach {
    if (name != generator) dependsOn(generator)
}
tasks.matching { it.name.startsWith("ksp") && it.name != generator }.configureEach {
    dependsOn(generator)
}
// The KMP metadata publication's sources jar is named plain `sourcesJar`, the per-target ones
// `<target>SourcesJar`.
tasks.matching { it.name == "sourcesJar" || it.name.endsWith("SourcesJar") }.configureEach {
    dependsOn(generator)
}
tasks.withType<org.jetbrains.dokka.gradle.tasks.DokkaBaseTask>().configureEach {
    dependsOn(generator)
}
