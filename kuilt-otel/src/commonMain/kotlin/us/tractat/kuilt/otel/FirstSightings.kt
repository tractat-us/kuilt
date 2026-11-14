package us.tractat.kuilt.otel

import io.github.oshai.kotlinlogging.KLogger
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

/**
 * The series a lasting condition has already been reported for, so it is logged at WARN once per
 * series and at DEBUG after that (#3318).
 *
 * A sum that has passed [Long.MAX_VALUE] stays past it, so each drain, collapse or export meets it
 * again. Logged at WARN every time, it is a line per drain for as long as the series lives, and
 * under `installLogCapture` each of those lines is itself a durable log record the next drain
 * ships.
 *
 * Bounded by the live series: each sighting first forgets every key not in the live set it is
 * given, so the set holds at most those series plus the one being sighted. A series that is evicted
 * and later returns is reported again, which is a new series as far as anyone reading the log can
 * tell.
 *
 * Guarded by a lock rather than by whichever dispatcher the caller runs on: two drains may run at
 * once, and exactly one of them reports a given series first.
 */
internal class FirstSightings {
    private val lock = reentrantLock()
    private val seen = HashSet<MetricKey>()

    /**
     * Whether this is [key]'s first sighting since it was last outside [live]. Keys not in [live]
     * are forgotten first.
     */
    fun firstSighting(key: MetricKey, live: Set<MetricKey>): Boolean = lock.withLock {
        seen.retainAll(live)
        seen.add(key)
    }

    /** Log [message] about [key] at WARN on its [firstSighting], and at DEBUG after that. */
    fun report(logger: KLogger, key: MetricKey, live: Set<MetricKey>, message: () -> String) {
        if (firstSighting(key, live)) logger.warn(message) else logger.debug(message)
    }
}
