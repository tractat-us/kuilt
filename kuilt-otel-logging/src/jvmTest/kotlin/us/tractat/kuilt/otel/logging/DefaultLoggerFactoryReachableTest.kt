package us.tractat.kuilt.otel.logging

import io.github.oshai.kotlinlogging.DirectLoggerFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * The coverage gap #2289 found, closed on purpose: **`KotlinLogging.logger(…)` — the one entry
 * point every consumer of this module uses — must work on the JVM against the platform's default
 * logger factory.**
 *
 * Why 17 sibling test classes never noticed it was broken: on the JVM `kotlin-logging` resolves a
 * logger through an slf4j-backed factory, and slf4j-api was on neither `commonMain` nor `jvmTest`'s
 * runtime classpath. Every existing test either drives [LogCapture] / [CapturingAppender] directly,
 * never asking for a logger at all, or asks for one *inside* an active [installLogCapture] — which
 * has already swapped in [DirectLoggerFactory] and so never touches slf4j. `GateResolvesAtEdgeTest`
 * is the second shape: it does call `KotlinLogging.logger("com.example.Edge")`, but under an
 * installation. The default path was therefore untested, and `sampleWithActiveTrace` — which asks
 * for a logger with no capture installed, exactly as a consumer's own code does — died on its first
 * line with `NoClassDefFoundError: org/slf4j/LoggerFactory`.
 *
 * This is a JVM test because the missing binding is JVM-only; the fix is `runtimeOnly(libs.logback)`
 * on `jvmTest`/`androidUnitTest`, matching every sibling module in the repo.
 */
class DefaultLoggerFactoryReachableTest {

    /**
     * Assert the rig fired before trusting the result below. `installLogCapture` mutates
     * **global** state ([KotlinLoggingConfiguration.loggerFactory]), so a sibling test that leaked
     * [DirectLoggerFactory] would leave this class exercising the direct path — which needs no
     * slf4j and would make the real assertion pass vacuously. Fail loudly instead of silently
     * proving nothing.
     */
    @Test
    fun ambientFactoryIsThePlatformDefaultNotTheDirectOne() {
        assertNotSame(
            DirectLoggerFactory,
            KotlinLoggingConfiguration.loggerFactory,
            "a sibling test leaked DirectLoggerFactory into the global config — this class would " +
                "then exercise the direct path, which needs no slf4j, and prove nothing about the " +
                "default JVM factory. Find the test that installed capture without closing it.",
        )
    }

    /**
     * The gap itself: resolve a logger the way a consumer does — no capture installed — and emit a
     * line. Without an slf4j binding on the test runtime this throws
     * `NoClassDefFoundError: org/slf4j/LoggerFactory`, which is precisely how `sampleWithActiveTrace`
     * failed.
     */
    @Test
    fun consumerEntryPointResolvesAndLogsWithNoCaptureInstalled() {
        val log = KotlinLogging.logger("com.example.Checkout")
        log.info { "user checked out" }
        assertTrue(log.isInfoEnabled(), "a real backend is bound, so INFO is enabled")
    }

    /**
     * The same entry point while capture is installed still reaches the capturing appender — the
     * consumer-visible round trip, end to end through `KotlinLogging.logger(…)` rather than through
     * the core directly.
     */
    @Test
    fun consumerEntryPointReachesCaptureWhenInstalled() {
        val previousFactory = KotlinLoggingConfiguration.loggerFactory
        try {
            KotlinLoggingConfiguration.loggerFactory = DirectLoggerFactory
            val previousAppender = KotlinLoggingConfiguration.direct.appender
            val seen = mutableListOf<String>()
            KotlinLoggingConfiguration.direct.appender =
                object : io.github.oshai.kotlinlogging.Appender {
                    override fun log(loggingEvent: io.github.oshai.kotlinlogging.KLoggingEvent) {
                        seen += loggingEvent.message ?: ""
                    }
                }
            try {
                KotlinLogging.logger("com.example.Checkout").info { "charged the card" }
                assertTrue(
                    "charged the card" in seen,
                    "a line logged through KotlinLogging.logger(…) must reach the configured " +
                        "appender — that is the path installLogCapture hooks. Saw: $seen",
                )
            } finally {
                KotlinLoggingConfiguration.direct.appender = previousAppender
            }
        } finally {
            KotlinLoggingConfiguration.loggerFactory = previousFactory
        }
    }
}
