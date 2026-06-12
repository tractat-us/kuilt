plugins {
    alias(libs.plugins.kover) apply false
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
// 1. Timeout: kill any hung JVM test process after 5 min so CI surfaces a failure
//    at the task level (with a named stack trace) rather than waiting for the
//    30-min job cancel that produces no actionable signal. A virtual-time hang
//    (while(true){delay()} in backgroundScope) is the primary driver — see #329.
//
// 2. kotlinx.coroutines.debug: names every coroutine with its launch call-site.
//    When a runTest timeout fires, the JVM dump (from the jstack watchdog in
//    ci.yml) shows "Coroutine …, created at …" instead of anonymous threads,
//    making the hung coroutine immediately identifiable.
subprojects {
    tasks.withType<Test>().configureEach {
        timeout.set(java.time.Duration.ofMinutes(5))
        systemProperty("kotlinx.coroutines.debug", "")
    }
}
