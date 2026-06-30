package us.tractat.kuilt.otel.logging

/**
 * A platform-independent log level, carrying its OTLP severity mapping.
 *
 * Each level maps to the base of its OTLP `SeverityNumber` range (TRACE→1,
 * DEBUG→5, INFO→9, WARN→13, ERROR→17) and a human-readable severity text. The
 * capture core copies both onto each `LogRecord` so the buffer is OTLP-shaped
 * from the moment of capture.
 *
 * Levels are declared least-to-most severe; [ordinal] is therefore a valid
 * severity comparison and is what [CaptureConfig.minLevel] filters against.
 */
public enum class LogLevel(
    /** OTLP `SeverityNumber` (1–24) for this level — the base of its range. */
    public val severityNumber: Int,
    /** Human-readable severity text (e.g. "INFO", "WARN"). */
    public val severityText: String,
) {
    TRACE(severityNumber = 1, severityText = "TRACE"),
    DEBUG(severityNumber = 5, severityText = "DEBUG"),
    INFO(severityNumber = 9, severityText = "INFO"),
    WARN(severityNumber = 13, severityText = "WARN"),
    ERROR(severityNumber = 17, severityText = "ERROR"),
}
