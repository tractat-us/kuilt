// kuilt.version-constant — generates `KuiltVersion.VERSION` into the module's commonMain (#3323).
//
// Applied by `:kuilt-core` alone, AFTER kuilt.kmp-library. One module carries the value
// because every other module depends on `:kuilt-core`; generating it everywhere would put the same
// class in the same package into several klibs. It is a plain `val`, not a `const`: a `const` is
// inlined into consumers and would put the version in `:kuilt-core`'s ABI (see GenerateKuiltVersion).
//
// The value is `project.version`, which the root build sets for every project before any
// subproject evaluates — the same value the publications take their coordinates from. It is read
// here, at configuration time, into a task `@Input`: configuration-cache safe (no `project` at
// execution), and a `-Pversion=…` override is a configuration-cache input too, so it invalidates
// the cache entry and reruns the generator.
//
// Wiring: `kotlin.srcDir(generateKuiltVersion)` hands the source set a TaskProvider, so every
// consumer that reads commonMain as a FileCollection — every target's compile task, the sources
// jars — infers the edge to the generator. Dokka does not: kuilt.kmp-library feeds it
// `kotlin.srcDirs`, a `Set<File>` with nowhere to carry a producer, so its edge is declared by
// hand below (kuilt.warp-ops has the same rule for its KSP output). Omitting it is loud, not
// silent: Gradle refuses the graph with an implicit-dependency error naming both tasks.

import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

val generateKuiltVersion = tasks.register<GenerateKuiltVersion>("generateKuiltVersion") {
    group = "build setup"
    description = "Writes KuiltVersion.kt, carrying the version this build publishes, into commonMain."
    packageName.set("us.tractat.kuilt.core")
    libraryVersion.set(project.version.toString())
    outputDirectory.set(layout.buildDirectory.dir("generated/kuilt-version/commonMain/kotlin"))
}

extensions.configure<KotlinMultiplatformExtension> {
    sourceSets.named("commonMain") {
        kotlin.srcDir(generateKuiltVersion)
    }
}

tasks.withType<org.jetbrains.dokka.gradle.tasks.DokkaBaseTask>().configureEach {
    dependsOn(generateKuiltVersion)
}
