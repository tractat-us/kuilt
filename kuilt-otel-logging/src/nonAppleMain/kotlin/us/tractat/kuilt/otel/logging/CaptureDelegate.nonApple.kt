package us.tractat.kuilt.otel.logging

import io.github.oshai.kotlinlogging.Appender

/**
 * Non-Apple passthrough — forward to whatever appender was already installed
 * (the console appender), preserving existing log output on JVM, Android and
 * wasmJs.
 */
internal actual fun captureDelegate(previous: Appender): Appender = previous
