package spike.suite

/**
 * Connectivity-suite result model (#1467). Deliberately plain data types so they bridge cleanly to
 * Swift via the `SpikeKit` framework — the SwiftUI layer renders the [ScenarioResult] list as a
 * pass/fail matrix and shares [SuiteReport.text].
 */

/** Per-scenario outcome. */
public enum class Verdict { PASS, FAIL, SKIP }

/**
 * One scenario's result. [hops] is the hop-by-hop trace (advertise → discover → weave → …) so a
 * FAIL names the failing hop; [detail] is the one-line summary shown in the matrix.
 */
public data class ScenarioResult(
    public val id: Int,
    public val name: String,
    public val verdict: Verdict,
    public val elapsedMs: Long,
    public val detail: String,
    public val hops: List<String>,
) {
    /** Matrix glyph — kept ASCII so it copies cleanly into a text message. */
    public val glyph: String
        get() = when (verdict) {
            Verdict.PASS -> "PASS"
            Verdict.FAIL -> "FAIL"
            Verdict.SKIP -> "SKIP"
        }
}

/**
 * A finished (or in-progress) suite run: the captured [env], the [role] the device played, and the
 * accumulated [results]. [text] is the copy/shareable report the field user texts back.
 */
public data class SuiteReport(
    public val role: String,
    public val startedAtEpoch: Double,
    public val env: EnvSnapshot,
    public val device: String,
    public val results: List<ScenarioResult>,
) {
    public val allRan: Boolean get() = results.size >= TOTAL_SCENARIOS
    public val passed: Int get() = results.count { it.verdict == Verdict.PASS }

    /** The shareable plaintext report. Fixed-width so it reads in a Messages bubble. */
    public val text: String
        get() = buildString {
            appendLine("kuilt-nw connectivity suite")
            appendLine("role=$role  passed=$passed/${results.size}")
            appendLine(env.line)
            appendLine("device: $device")
            appendLine("-".repeat(34))
            results.forEach { r ->
                appendLine("[${r.id}] ${r.name.padEnd(22)} ${r.glyph}  ${fmtMs(r.elapsedMs).padStart(6)}  ${r.detail}")
            }
            appendLine("-".repeat(34))
            // Hop traces last, so the matrix is the first screen but a debugger has the detail.
            results.forEach { r ->
                if (r.hops.isNotEmpty()) {
                    appendLine("· [${r.id}] ${r.name}")
                    r.hops.forEach { appendLine("    $it") }
                }
            }
        }.trimEnd()

    public companion object {
        public const val TOTAL_SCENARIOS: Int = 5
    }
}

internal fun fmtMs(ms: Long): String = if (ms >= 1000) "${(ms / 100) / 10.0}s" else "${ms}ms"
