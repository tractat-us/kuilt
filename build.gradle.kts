plugins {
    alias(libs.plugins.kover)
    alias(libs.plugins.dokka)
}

// Root aggregation for both doc/coverage tools:
//   - Kover: `koverXmlReport`/`koverHtmlReport` → one merged coverage report.
//   - Dokka: `dokkaGenerate` → ONE browsable HTML API site at build/dokka/html/
//     listing all modules (without it the root task renders only the empty root).
// Each module gets the aggregation dependency only if it actually applies the
// tool — `plugins.withId` adds it lazily per applied plugin. This excludes
// `:kuilt-bom` (a Gradle `java-platform` with no sources/coverage and neither
// plugin) and any future platform module. A plain
// `subprojects.forEach { kover(it) }` instead fails to resolve the BOM's
// non-existent `kover`/`dokka` variant.
subprojects.forEach { sub ->
    sub.plugins.withId("org.jetbrains.kotlinx.kover") {
        dependencies { kover(sub) }
    }
    sub.plugins.withId("org.jetbrains.dokka") {
        dependencies { dokka(sub) }
    }
}

val kuiltVersionLine: String = providers.gradleProperty("kuiltVersionLine").get()

allprojects {
    // CI passes -Pversion=${kuiltVersionLine}.<run_number> (see publish.yml).
    // Local builds get a non-releasable -dev marker derived from the same line.
    group = "us.tractat.kuilt"
    version = (findProperty("version") as? String)
        ?.takeIf { it.isNotBlank() && it != "unspecified" } ?: "$kuiltVersionLine.0-dev"
}

// Categorical test backstops — applied to every JVM test task in every subproject.
//
// 1. Timeout: kill any hung JVM test process after 15 min so CI surfaces a failure
//    at the task level (with a named stack trace) rather than waiting for the
//    30-min job cancel that produces no actionable signal. 15 min is generous for
//    any single module's test task (kuilt-crdt:jvmTest peaks at ~9 min on a cold
//    build) while still catching a true hang well before the job-level 30-min cap.
//    See #329 for the incident.
//
// 2. kotlinx.coroutines.debug: names every coroutine with its launch call-site.
//    When a runTest timeout fires, the JVM dump (from the jstack watchdog in
//    ci.yml) shows "Coroutine …, created at …" instead of anonymous threads,
//    making the hung coroutine immediately identifiable.
subprojects {
    tasks.withType<Test>().configureEach {
        timeout.set(java.time.Duration.ofMinutes(15))
        systemProperty("kotlinx.coroutines.debug", "")
    }
}
