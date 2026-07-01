package us.tractat.kuilt.otel.logging

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Regression guard for the Native self-capture leak (#1003).
 *
 * kuilt's log-capture self-exclusion drops any event whose `loggerName` starts
 * with `us.tractat.kuilt`. But `KotlinLogging.logger {}` (the empty-lambda form)
 * resolves to an EMPTY logger name on Kotlin/Native — `"".startsWith(...)` is
 * false, so those internal loggers leak into capture/extraction. Every internal
 * logger must therefore be given an explicit fully-qualified name string:
 * `KotlinLogging.logger("us.tractat.kuilt.…")`.
 *
 * This walks every main source set in the repo and fails if any reintroduces the
 * empty-lambda form. JVM-only (it scans the filesystem via [File]).
 */
class InternalLoggerNameGuardTest {

    @Test
    fun noEmptyLambdaLoggersInMainSources() {
        val repoRoot = findRepoRoot()
        // `KotlinLogging.logger {}` or `KotlinLogging.logger { }` — the name-less form.
        val emptyLambda = Regex("""KotlinLogging\.logger\s*\{\s*\}""")

        val offenders = repoRoot.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" }
            .filter { it.isFile && it.extension == "kt" }
            .filter { isMainSource(it) }
            .filter { emptyLambda.containsMatchIn(it.readText()) }
            .map { it.relativeTo(repoRoot).path }
            .toList()

        if (offenders.isNotEmpty()) {
            fail(
                "Found empty-name KotlinLogging.logger {} in main sources — internal loggers " +
                    "must be explicitly named `KotlinLogging.logger(\"us.tractat.kuilt.…\")` so the " +
                    "Native self-capture exclusion (#1003) works:\n" +
                    offenders.joinToString("\n") { "  - $it" },
            )
        }
    }

    /** A `*Main` source set that is not a sample directory. Excludes tests + commonSamples. */
    private fun isMainSource(file: File): Boolean {
        val path = file.invariantSeparatorsPath
        // Must live under src/<something>Main/ (commonMain, jvmMain, appleMain, wasmJsMain, …).
        val inMain = Regex("""/src/[^/]*Main/""").containsMatchIn(path)
        val isSamples = path.contains("commonSamples")
        return inMain && !isSamples
    }

    private fun findRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("Could not locate repo root (no settings.gradle.kts found walking up from user.dir)")
    }
}
