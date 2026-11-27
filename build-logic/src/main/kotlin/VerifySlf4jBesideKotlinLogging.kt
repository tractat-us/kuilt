import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Checks the outcome of the slf4j-api edge in `kuilt.kmp-library` (#3744): on the Android release
 * and the JVM runtime classpath, a module that depends on kotlin-logging directly also depends on
 * slf4j-api directly. That is what the published POM carries, since both are the module's own
 * `implementation` dependencies.
 *
 * The edge decides from the source-set hierarchy, so a hierarchy read too early, a renamed source
 * set or a selector that matches nothing would drop slf4j-api from every POM while every test,
 * running with Logback, stayed green. This task reads Gradle's resolved graph instead, which knows
 * nothing of that walk, so it reds on any of them. It is a check on build metadata, not a type:
 * the property is about what a published POM lists, which no Kotlin type can see.
 *
 * Direct dependencies only, so a sibling kuilt module or a third-party library that brings
 * slf4j-api along cannot mask a module that lost its own.
 */
abstract class VerifySlf4jBesideKotlinLogging : DefaultTask() {

    /** `group:name` of the kotlin-logging catalog entry; a direct dependency whose name starts with it counts. */
    @get:Input
    abstract val kotlinLogging: Property<String>

    /** `group:name` of slf4j-api. */
    @get:Input
    abstract val slf4jApi: Property<String>

    @get:Input
    abstract val androidRuntime: Property<ResolvedComponentResult>

    @get:Input
    abstract val jvmRuntime: Property<ResolvedComponentResult>

    @get:OutputFile
    abstract val marker: RegularFileProperty

    @TaskAction
    fun verify() {
        val logging = kotlinLogging.get()
        val slf4j = slf4jApi.get()
        val verdicts = mapOf(
            "releaseRuntimeClasspath" to androidRuntime.get(),
            "jvmRuntimeClasspath" to jvmRuntime.get(),
        ).mapValues { (_, root) ->
            val direct = root.dependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .map { it.selected.moduleVersion?.let { id -> "${id.group}:${id.name}" }.orEmpty() }
            direct.any { it.startsWith(logging) } to direct.any { it == slf4j }
        }
        val missing = verdicts.filterValues { (logs, hasSlf4j) -> logs && !hasSlf4j }.keys
        if (missing.isNotEmpty()) {
            throw GradleException(
                "$path: $missing depend on $logging directly but not on $slf4j, so an app with no SLF4J " +
                    "crashes on this module's first logger (#3744). The slf4j-api edge in " +
                    "kuilt.kmp-library.gradle.kts selected nothing here.",
            )
        }
        marker.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                verdicts.entries.joinToString("\n", postfix = "\n") { (name, verdict) ->
                    "$name: kotlin-logging=${verdict.first} slf4j-api=${verdict.second}"
                },
            )
        }
    }
}
