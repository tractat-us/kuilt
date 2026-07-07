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
//   2. make every other Kotlin compilation (and the source/lint tasks that read
//      commonMain) run after kspCommonMainKotlinMetadata.
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

extensions.configure<KotlinMultiplatformExtension> {
    sourceSets.named("commonMain") {
        kotlin.srcDir(layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin"))
    }
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    if (name != "kspCommonMainKotlinMetadata") dependsOn("kspCommonMainKotlinMetadata")
}

// Non-compilation consumers of commonMain sources: detekt (lint) and the
// per-target sources jars (publishing). Without these, Gradle 9 flags an
// implicit dependency on kspCommonMainKotlinMetadata's outputs.
tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    dependsOn("kspCommonMainKotlinMetadata")
}
tasks.matching { it.name.endsWith("SourcesJar") }.configureEach {
    dependsOn("kspCommonMainKotlinMetadata")
}
