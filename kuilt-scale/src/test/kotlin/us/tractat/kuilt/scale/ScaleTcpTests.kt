package us.tractat.kuilt.scale

/**
 * Gate for real-TCP scaling tests.
 *
 * Tests that stand up real localhost TCP sockets must call [assumeEnabled] at the
 * top of the test to skip gracefully when the flag is absent. This prevents them from
 * running in the default CI gate.
 *
 * Enable with `-Pscale.tcp.tests=true` on the Gradle command line (see build.gradle.kts).
 * The build forwards the property as a JVM system property of the same name.
 */
internal object ScaleTcpTests {
    /** True when -Pscale.tcp.tests=true was passed to Gradle. */
    val enabled: Boolean
        get() = System.getProperty("scale.tcp.tests") == "true"

    /**
     * Skip the enclosing test if TCP scaling tests are not enabled.
     *
     * Uses [org.junit.jupiter.api.Assumptions.assumeTrue] (JUnit 5), which throws
     * [org.opentest4j.TestAbortedException] to signal a skipped test.
     */
    fun assumeEnabled() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            enabled,
            "Scale TCP tests are disabled. Run with -Pscale.tcp.tests=true to enable.",
        )
    }
}
