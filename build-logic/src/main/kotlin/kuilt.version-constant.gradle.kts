// kuilt.version-constant — generates the string behind `us.tractat.kuilt.KuiltVersion.VERSION` into
// the module's commonMain (#3323). `KuiltVersion` itself is hand-written; see GenerateKuiltVersion.
//
// Applied AFTER kuilt.kmp-library, by `:kuilt-base` alone (`GenerateKuiltVersion.CARRIER`), and
// applying it anywhere else fails here, at configuration. `:kuilt-base` depends on nothing, so the
// modules that need the value `api` it rather than carrying a copy each: `:kuilt-heddle-core` can
// take it without the coroutines `:kuilt-core` would bring, and one class in one package from two
// jars, a split package, cannot arise. The value is the version of `:kuilt-base`; importing the BOM
// keeps every module at that version.
//
// `VERSION` is a plain `val` over an `internal const`: a public `const` is inlined into consumers
// and would put the version in the carrier's ABI (see GenerateKuiltVersion).
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
// silent: Gradle refuses the graph with an implicit-dependency error naming both tasks. The IDE's
// `prepareKotlinIdeaImport` gets the same edge, so a Gradle sync generates the file instead of
// leaving `GENERATED_VERSION` unresolved in the editor until the first build.

import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.gradle.process.CommandLineArgumentProvider

/** Hands a test JVM the published POM's path, keyed on its content rather than where it sits. */
class PublishedPomArgument(
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) val pom: Provider<RegularFile>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("-Dkuilt.publishedPom=${pom.get().asFile.absolutePath}")
}

if (path != GenerateKuiltVersion.CARRIER) {
    throw GradleException(
        "kuilt.version-constant is applied to $path, but only ${GenerateKuiltVersion.CARRIER} carries " +
            "KuiltVersion. Depend on it with `api(project(\"${GenerateKuiltVersion.CARRIER}\"))` instead: " +
            "a second copy would be a second class to keep in step, or a split package.",
    )
}

val generateKuiltVersion = tasks.register<GenerateKuiltVersion>("generateKuiltVersion") {
    group = "build setup"
    description = "Writes GeneratedVersion.kt, the version this build publishes, into commonMain."
    libraryVersion.set(project.version.toString())
    // Not under `build/generated`, where Dokka's `suppressGeneratedFiles` (default true) drops
    // everything. That once hid the whole generated `KuiltVersion` from the API reference; now the
    // documented object is hand-written and only an `internal` constant is generated, so the location
    // no longer decides what Dokka shows, and it stays here rather than move for no gain.
    outputDirectory.set(layout.buildDirectory.dir("kuilt-version/commonMain/kotlin"))
}

extensions.configure<KotlinMultiplatformExtension> {
    sourceSets.named("commonMain") {
        kotlin.srcDir(generateKuiltVersion)
    }
}

tasks.withType<org.jetbrains.dokka.gradle.tasks.DokkaBaseTask>().configureEach {
    dependsOn(generateKuiltVersion)
}

tasks.matching { it.name == "prepareKotlinIdeaImport" }.configureEach {
    dependsOn(generateKuiltVersion)
}

// `KuiltVersionTest` compares `KuiltVersion.VERSION` with the version the published POM
// declares, so it needs that POM built and its path. The POM task is wired as an
// input, which gives both the edge and a rerun when the version changes. Resolved inside the lazy
// `jvmTest` configuration, by which point KGP and maven-publish have registered the publication.
tasks.withType<Test>().configureEach {
    if (name == "jvmTest") {
        val pomTask = tasks.named<GenerateMavenPom>("generatePomFileForKotlinMultiplatformPublication")
        // The path travels through an argument provider whose POM is a path-insensitive `@InputFile`,
        // so the checkout's absolute path is not a `jvmTest` cache key, while the provider still
        // carries the producing task's edge.
        jvmArgumentProviders.add(PublishedPomArgument(layout.file(pomTask.map { it.destination })))
    }
}
