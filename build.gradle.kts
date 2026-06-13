plugins {
    alias(libs.plugins.kover)
}

// Aggregate every module's JVM coverage into one root report. `koverXmlReport`
// emits build/reports/kover/report.xml (uploaded to Codecov from CI);
// `koverHtmlReport` writes a browsable report for local inspection. Each module
// applies the Kover plugin via the kmp-library convention, so a project
// dependency here is all the aggregation needs.
dependencies {
    subprojects.forEach { kover(it) }
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
