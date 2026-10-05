@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package us.tractat.kuilt.store

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.create
// NSData.writeToFile:options:error: is an NSExtendedData category method, exposed as an extension.
import platform.Foundation.writeToFile
import platform.posix.rename
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * What the `F_FULLFSYNC` flush costs per [NSFileManagerDurableStore.write] (#2141), measured
 * against the unflushed path it replaced, in one process so the comparison survives a loaded box.
 *
 * **Opt-in, gated at the task level:** `:kuilt-store`'s build excludes this class from every native
 * test task unless `-Pkuilt.benchmark.tests=true`, so an un-run probe is *absent* from the results XML
 * rather than a green row that did nothing. Run it with
 *
 * ```
 * ./gradlew :kuilt-store:macosArm64Test --tests "*FlushCostProbe" -Pkuilt.benchmark.tests=true
 * ```
 *
 * and read the medians from the `system-out` of its results XML. It asserts nothing about time —
 * the absolute numbers belong to the box and its load — only that every sample was taken, since on
 * Kotlin/Native the clock in the XML is not a witness that the body ran.
 *
 * The two paths are interleaved sample by sample, so a burst of load lands on both rather than on
 * whichever happened to be running. Payloads are the two sizes the telemetry exporters write: a
 * ~1 KB index record and a ~123 KB log segment.
 */
class NSFileManagerDurableStoreFlushCostProbe {

    @Test
    fun flushCost() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val report = PAYLOAD_SIZES.map { size -> measure(size) }
        report.forEach { println(it) }
        assertEquals(PAYLOAD_SIZES.size * 2 * SAMPLES, report.sumOf { it.samples }, "every sample was taken")
    }

    private suspend fun measure(size: Int): Row {
        val bytes = ByteArray(size) { it.toByte() }
        val dir = freshTempDir("kuilt-store-flush-probe")
        val store = NSFileManagerDurableStore(dir)
        val key = StoreKey("probe")
        val baselineDest = dir + "baseline"
        repeat(WARMUP) {
            unflushedWrite(baselineDest, bytes)
            store.write(key, bytes)
        }
        val unflushed = mutableListOf<Duration>()
        val flushed = mutableListOf<Duration>()
        repeat(SAMPLES) {
            unflushed += timed { unflushedWrite(baselineDest, bytes) }
            flushed += timed { store.write(key, bytes) }
        }
        return Row(size, Stats(unflushed), Stats(flushed))
    }

    private inline fun timed(block: () -> Unit): Duration {
        val mark = TimeSource.Monotonic.markNow()
        block()
        return mark.elapsedNow()
    }

    /** The pre-#2141 write path, verbatim in substance: `NSData.writeToFile` with no flush, then `rename(2)`. */
    private fun unflushedWrite(dest: String, bytes: ByteArray) {
        val tmp = "$dest.tmp"
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        check(data.writeToFile(tmp, options = 0uL, error = null)) { "baseline write failed" }
        check(rename(tmp, dest) == 0) { "baseline rename failed" }
    }

    private class Stats(samples: List<Duration>) {
        val count = samples.size
        private val sorted = samples.sorted()
        val median: Duration = sorted[count / 2]
        val p95: Duration = sorted[(count * 95 / 100).coerceAtMost(count - 1)]
        override fun toString() = "median=$median p95=$p95 n=$count"
    }

    private class Row(val size: Int, val unflushed: Stats, val flushed: Stats) {
        val samples = unflushed.count + flushed.count
        override fun toString() = "FLUSH-COST bytes=$size unflushed[$unflushed] fullfsync[$flushed]"
    }
}

private val PAYLOAD_SIZES = listOf(1_024, 123 * 1_024)
private const val WARMUP = 5
private const val SAMPLES = 60
