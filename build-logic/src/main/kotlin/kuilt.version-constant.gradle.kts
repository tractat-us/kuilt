// kuilt.version-constant — generates `KuiltVersion.VERSION` into the module's commonMain (#3323).
//
// Applied AFTER kuilt.kmp-library, by the modules `GenerateKuiltVersion.CARRIERS` names, today
// `:kuilt-core` and `:kuilt-heddle-core`. Not every module depends on `:kuilt-core`: `:kuilt-crdt`,
// `:kuilt-store` and `:kuilt-seal` depend on no kuilt module, and `:kuilt-heddle-core` bans the
// coroutines `:kuilt-core` would bring, so a single carrier would leave its consumers without one.
// Each carrier generates its copy into its own package (the mapping gives it), because one class
// in one package from two jars is a split package. The modules below `:kuilt-core` (no kuilt
// dependency, or only such ones) carry none. The value is the version of the module that carries
// it; importing the BOM keeps every module at that version. Applying this
// plugin to a module missing from the mapping fails here, at configuration.
//
// It is a plain `val`, not a `const`: a `const` is inlined into consumers and would put the
// version in the carrier's ABI (see GenerateKuiltVersion).
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
// leaving `KuiltVersion` unresolved in the editor until the first build.

import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.gradle.process.CommandLineArgumentProvider

/** Hands a test JVM the published POM's path, keyed on its content rather than where it sits. */
class PublishedPomArgument(
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) val pom: Provider<RegularFile>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("-Dkuilt.publishedPom=${pom.get().asFile.absolutePath}")
}

val kuiltVersionPackage: String = GenerateKuiltVersion.CARRIERS[path] ?: throw GradleException(
    "kuilt.version-constant is applied to $path, which GenerateKuiltVersion.CARRIERS does not name. " +
        "Add it there with the package its KuiltVersion goes in; the generated KDoc lists every carrier from that map.",
)

val generateKuiltVersion = tasks.register<GenerateKuiltVersion>("generateKuiltVersion") {
    group = "build setup"
    description = "Writes KuiltVersion.kt, carrying the version this build publishes, into commonMain."
    packageName.set(kuiltVersionPackage)
    libraryVersion.set(project.version.toString())
    samples.from(fileTree("src/commonSamples/kotlin") { include("**/*.kt") })
    // NOT under `build/generated`: Dokka's `suppressGeneratedFiles` defaults to true and drops
    // everything there, so `KuiltVersion` was absent from the API reference, KDoc and `@sample`
    // included. Measured: the file sat in commonMain's Dokka `sourceRoots` and in its
    // `suppressedFiles`, and no page was rendered. Moving the output leaves that default in force for
    // anything else a module generates.
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

// Each carrier's `KuiltVersionTest` compares the generated `KuiltVersion.VERSION` with the version
// the published POM declares, so it needs that POM built and its path. The POM task is wired as an
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
