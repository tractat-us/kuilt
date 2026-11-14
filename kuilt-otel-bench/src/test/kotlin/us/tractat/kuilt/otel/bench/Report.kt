package us.tractat.kuilt.otel.bench

import java.io.File
import java.lang.management.ManagementFactory
import java.util.Locale

/**
 * Writes one arm's rows as a markdown table to stdout and to `build/reports/otel-bench/<arm>.md`.
 *
 * Stdout rather than a logger because the table IS the output — the benchmark task turns on
 * `showStandardStreams` so it lands in the Gradle console. The header carries the JDK and the
 * one-minute load average the JVM can see; quote the five-minute figure from `uptime` beside any
 * number you lift from here, since a loaded box moves absolute times by multiples.
 */
fun report(arm: String, columns: List<String>, rows: List<List<String>>) {
    val load = ManagementFactory.getOperatingSystemMXBean().systemLoadAverage
    val text = buildString {
        appendLine("## $arm")
        appendLine()
        appendLine("JDK ${System.getProperty("java.version")}, 1-min load ${"%.2f".format(Locale.ROOT, load)}")
        appendLine()
        appendLine(columns.joinToString(" | ", "| ", " |"))
        appendLine(columns.joinToString(" | ", "| ", " |") { "---" })
        rows.forEach { appendLine(it.joinToString(" | ", "| ", " |")) }
    }
    println(text)
    val dir = File(System.getProperty("user.dir"), "build/reports/otel-bench")
    dir.mkdirs()
    File(dir, "$arm.md").writeText(text)
}

fun micros(nanos: Double): String = "%.1f".format(Locale.ROOT, nanos / 1_000.0)

fun millis(nanos: Double): String = "%.2f".format(Locale.ROOT, nanos / 1_000_000.0)

fun kib(bytes: Double): String = "%.1f".format(Locale.ROOT, bytes / 1024.0)

fun ratio(value: Double): String = "%.2f".format(Locale.ROOT, value)

fun percent(part: Double, whole: Double): String = "%.0f%%".format(Locale.ROOT, 100.0 * part / whole)
