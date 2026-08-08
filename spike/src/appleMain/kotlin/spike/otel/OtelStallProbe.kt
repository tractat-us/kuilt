@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package spike.otel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.NSFileManagerDurableStore
import us.tractat.kuilt.otel.WarpLogRecordExporter
import kotlin.time.TimeSource

/**
 * On-device measurement for [#1860](https://github.com/tractat-us/kuilt/issues/1860) — the field
 * failure where a device with a 3.9 MB / 7,917-record telemetry store stopped writing records
 * **entirely**, silently, with `mtime` never advancing again.
 *
 * ## Why this is a measurement and not a fix
 *
 * Every fix attempted on #1860 so far has been aimed at a mechanism nobody had timed. Three named
 * suspects have since been refuted with receipts — `evictOldest` (unreachable below the cap),
 * #2126's Θ(N²) whole-buffer rewrite (the failure survived it), and #2127's windowing/retirement
 * (dormant below the cap). The one reachable suspect left is `Rga.insertAfter`, which rebuilds two
 * persistent collections per call:
 *
 * ```
 * val newOps = ops + op                        // Set.plus  — allocates a fresh LinkedHashSet, copies all N
 * insertsById = insertsById + (id to op),      // Map.plus  — allocates a fresh LinkedHashMap, copies all N
 * ```
 *
 * That is Θ(ops) allocations per exported record — order 16k at N ≈ 7,917 — and it runs under the
 * exporter's lock, before any store call, which is exactly the region the device evidence bounds
 * the stall to. **It is a hypothesis, not a finding**, and this probe exists to kill or confirm it
 * with a number rather than another argument.
 *
 * ## Why it measures from outside the exporter rather than inside it
 *
 * The obvious probe is a timing log inside `WarpLogRecordExporter.exportTurn`. This does not do
 * that, for two reasons. It would ship a temporary into production code for a question that a
 * throwaway harness answers; and — more usefully — timing `insertAfter` *alone* ([arm A][armA])
 * next to the *whole* `export()` ([arm B][armB]) decomposes the cost without instrumenting the
 * private path at all. `B − A` is everything the exporter does that is not the CRDT append.
 *
 * ## Reading the result
 *
 * * **Arm A climbing into the tens of ms** ⇒ hypothesis confirmed; the fix is a persistent data
 *   structure (or an incremental builder) for `ops`/`insertsById` in `:kuilt-crdt`. That is a real
 *   design change and belongs in its own issue, not inlined here.
 * * **Arm A sub-millisecond and flat, arm B climbing** ⇒ hypothesis refuted, and the cost is in
 *   the rest of the write turn. `B − A` per bucket says how much, and the search moves to
 *   `appendToActiveSegment` / `pendingWrites`.
 * * **Both flat** ⇒ the stall is not in the export path at all, and the search moves to the
 *   caller — `CapturingAppender`'s drain, or `admit()` returning early.
 *
 * ## Fidelity to the field configuration
 *
 * The device this runs on is an iPhone XS (`iPhone11,2`) on iOS 18.7.9 — the same model and OS as
 * the field report. The exporter is constructed the way both consumer call sites construct it:
 * `replica` and `store` only, so `maxRecords` takes `DEFAULT_MAX_LOG_RECORDS` (10,000). The store
 * is a real [NSFileManagerDurableStore] over a real directory in the app container, not a fake.
 *
 * Two things are deliberately *not* matched, and both make this a **weaker** stall than the field:
 * the log is grown from empty rather than recovered from a 3.9 MB blob (so early records are
 * cheap, which is what draws the curve), and the run does one export at a time with no concurrent
 * app doing anything else.
 *
 * ## Reporting shape
 *
 * Per-call timings are aggregated into buckets of [BUCKET] and reported as
 * `n=<records so far> meanMs=<mean over the bucket> maxMs=<worst in the bucket>`. A bare
 * millisecond figure with no `n` beside it is uninterpretable — the whole question is the shape of
 * the curve against the op count, not any single value. `max` is carried because a stall is a tail
 * phenomenon: a mean can stay respectable while individual calls go to seconds.
 */
public class OtelStallProbe {

    private companion object {
        /** How many records each arm appends. The field device sat at 7,917; overshoot it. */
        const val RECORDS = 9_000

        /** Per-call timings are averaged over this many calls before a line is emitted. */
        const val BUCKET = 250

        /** How many records [armC] exports after recovering. Small on purpose — see [armC]. */
        const val RECOVER_EXPORTS = 20

        /**
         * Body size in bytes, matching the field store's ~490 B/record average
         * (3,888,587 bytes / 7,917 records) once CBOR framing and the id are accounted for.
         *
         * It matters for arm B (bytes encoded per segment write) and is nearly irrelevant to
         * arm A, whose cost is per-*element* copying, not per-byte.
         */
        const val BODY_BYTES = 420
    }

    /**
     * Run both arms and stream every line to [onLine].
     *
     * Fire-and-forget on [Dispatchers.Default]: the caller is a SwiftUI button/launch-arg handler
     * that must not block the main thread for the minutes this takes.
     */
    public fun start(onLine: (String) -> Unit) {
        CoroutineScope(Dispatchers.Default).launch {
            runCatching {
                onLine("===PROBE-BEGIN=== issue=1860 mode=grow records=$RECORDS bucket=$BUCKET")
                onLine(deviceLine())
                armA(onLine)
                armB(onLine)
                onLine("===PROBE-END===")
            }.onFailure { failure ->
                // A throw here is itself a finding — the field failure is silent, and an
                // exception thrown at N records would be a far better explanation than slowness.
                onLine("===PROBE-FAILED=== $failure")
                onLine("===PROBE-END===")
            }
        }
    }

    /**
     * Arm C — **recover** from the store [start] left behind, then export into it.
     *
     * This is the arm that matches the field reproduction, and it exists because arms A and B
     * disagree with the field report in a way that names the real variable. The thread's
     * on-device result is *0 records ⇒ captures, 7,917 records recovered ⇒ dead indefinitely*,
     * and it was read as a statement about **how many** records the log holds. Arm B grows a log
     * past that count on the same phone and stays healthy — so the count is not what distinguishes
     * the two. The other thing that differs is that the dead log was **recovered from disk** and
     * the healthy one was **built in memory**.
     *
     * A recovered [Rga] is not merely a large one. It is reconstructed by `Rga.fromOps`, whose
     * cache is cold, and it is assembled by unioning one [Rga] per sealed segment — at
     * `DEFAULT_LOG_SEGMENT_OPS = 256`, that is a sequential `store.read` and a set union per 256
     * records. Neither of those happens on the grow path at all.
     *
     * So this arm runs the export loop against a log in the state the field device was actually
     * in, and times [WarpLogRecordExporter.recover] separately from the exports that follow it —
     * because "recovery is slow" and "exports after recovery are slow" are different bugs with
     * different fixes, and a single end-to-end number cannot tell them apart.
     */
    public fun startRecover(onLine: (String) -> Unit) {
        CoroutineScope(Dispatchers.Default).launch {
            runCatching {
                onLine("===PROBE-BEGIN=== issue=1860 mode=recover bucket=$BUCKET")
                onLine(deviceLine())
                armC(onLine)
                onLine("===PROBE-END===")
            }.onFailure { failure ->
                onLine("===PROBE-FAILED=== $failure")
                onLine("===PROBE-END===")
            }
        }
    }

    /**
     * Arm A — [Rga.insertAfter] with nothing else in the frame.
     *
     * No store, no exporter, no encoding: this is the named suspect on its own, so a climb here
     * cannot be attributed to anything downstream of it.
     */
    private fun armA(onLine: (String) -> Unit) {
        onLine("--- arm A: Rga.insertAfter alone (no store, no exporter) ---")
        var log = Rga.empty<LogRecord>()
        var tail = RgaId.HEAD
        val replica = ReplicaId("probe")
        val timer = Bucketed(BUCKET, "A.insertAfter", onLine)

        repeat(RECORDS) { i ->
            val record = record(i)
            val started = TimeSource.Monotonic.markNow()
            val (next, op) = log.insertAfter(replica = replica, after = tail, value = record)
            timer.record(started.elapsedNow().inWholeMicroseconds, i + 1)
            log = next
            tail = op.id
        }
        timer.flush()
        onLine("arm A done: opCount=${log.opCount}")
    }

    /**
     * Arm B — the whole `export()` write turn against a real file-backed store.
     *
     * This is the in-situ number: same exporter construction as both consumer call sites, same
     * default 10,000 cap, a real [NSFileManagerDurableStore] over a real directory in the app
     * container. `B − A` at the same bucket is everything the exporter does besides the append.
     */
    private suspend fun armB(onLine: (String) -> Unit) {
        val dir = probeDirectory()
        onLine("--- arm B: full WarpLogRecordExporter.export() to a real store ---")
        onLine("arm B store dir=$dir")

        val exporter = WarpLogRecordExporter(replica = ReplicaId("probe"), store = NSFileManagerDurableStore(dir))
        val timer = Bucketed(BUCKET, "B.export", onLine)

        repeat(RECORDS) { i ->
            val record = record(i)
            val started = TimeSource.Monotonic.markNow()
            val result = exporter.export(record)
            timer.record(started.elapsedNow().inWholeMicroseconds, i + 1)
            // A Failure is the other way this ends: the field store accepted nothing, and an
            // exporter that reports failure per record is a completely different diagnosis from
            // one that reports success slowly. Name it the first time it happens, then every
            // bucket, rather than letting a silent failure masquerade as a fast success.
            if (result !is us.tractat.kuilt.otel.ExportResult.Success) {
                timer.noteFailure(i + 1, result.toString())
            }
        }
        timer.flush()
        onLine("arm B done: visible=${exporter.snapshot().size} opCount=${exporter.snapshot().opCount}")
        onLine("arm B store bytes=${directoryBytes(dir)}")
    }

    /**
     * Arm C — recover the store arm B left, then keep exporting into it.
     *
     * Deliberately reuses the directory rather than creating one: the whole point is to start
     * from a store that already exists on disk, which is the one condition the field failure and
     * arm B do not share. Exports far fewer records than the other arms — if this is where the
     * stall lives, it shows up in the first handful, and if it does not, a thousand more will not
     * change the verdict.
     */
    private suspend fun armC(onLine: (String) -> Unit) {
        val dir = existingProbeDirectory()
        onLine("--- arm C: recover() from the store on disk, then export ---")
        onLine("arm C store dir=$dir bytesBefore=${directoryBytes(dir)} files=${fileCount(dir)}")

        val exporter = WarpLogRecordExporter(replica = ReplicaId("probe"), store = NSFileManagerDurableStore(dir))

        val recoverStarted = TimeSource.Monotonic.markNow()
        exporter.recover()
        val recoverMs = recoverStarted.elapsedNow().inWholeMilliseconds
        val snapshot = exporter.snapshot()
        onLine("arm C recover() elapsedMs=$recoverMs visible=${snapshot.size} opCount=${snapshot.opCount}")

        // Each export is reported individually, not bucketed. At this point the interesting
        // outcome is a single call that never returns, and a bucket of 250 would swallow the
        // first 249 timings before printing anything at all.
        repeat(RECOVER_EXPORTS) { i ->
            val started = TimeSource.Monotonic.markNow()
            val result = exporter.export(record(100_000 + i))
            val elapsed = started.elapsedNow().inWholeMilliseconds
            onLine("C.export i=$i elapsedMs=$elapsed result=$result")
        }
        onLine("arm C done: visible=${exporter.snapshot().size} bytesAfter=${directoryBytes(dir)} files=${fileCount(dir)}")
    }

    /** The directory [armB] left behind, without touching its contents. */
    private fun existingProbeDirectory(): String {
        val documents = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory,
            NSUserDomainMask,
            true,
        ).first() as String
        return "$documents/otel-probe-1860"
    }

    private fun fileCount(dir: String): Int =
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null).orEmpty().size

    /** A synthetic record shaped like a real captured log line. */
    private fun record(i: Int): LogRecord = LogRecord(
        recordId = ByteString(ByteArray(8) { b -> (i shr (b * 4)).toByte() }),
        body = "probe record $i " + "x".repeat(BODY_BYTES),
        severityNumber = 9,
        severityText = "INFO",
        observedEpochNanos = i.toLong(),
    )

    /**
     * A fresh directory per run under the app container's Documents.
     *
     * Fresh because a second run inheriting the first run's 9,000 records would start its curve at
     * the far end and measure something nobody asked about. Under `Documents` so
     * `devicectl device copy from --domain-type appDataContainer` can pull it if the printed
     * numbers ever need to be re-derived from the store itself.
     */
    private fun probeDirectory(): String {
        val documents = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory,
            NSUserDomainMask,
            true,
        ).first() as String
        val dir = "$documents/otel-probe-1860"
        val manager = NSFileManager.defaultManager
        if (manager.fileExistsAtPath(dir)) manager.removeItemAtPath(dir, null)
        manager.createDirectoryAtPath(dir, true, null, null)
        return dir
    }

    /** Total bytes the store occupies on disk — the field failure's other observable. */
    private fun directoryBytes(dir: String): Long {
        val manager = NSFileManager.defaultManager
        val names = manager.contentsOfDirectoryAtPath(dir, null).orEmpty()
        return names.fold(0L) { total, name ->
            val attributes = manager.attributesOfItemAtPath("$dir/$name", null)
            total + ((attributes?.get("NSFileSize") as? NSNumber)?.longLongValue ?: 0L)
        }
    }

    /**
     * Identifies the hardware the numbers came off.
     *
     * Deliberately `NSProcessInfo` and not `UIDevice`: this module also builds for `macosArm64`
     * (the on-Mac compile check), where UIKit does not exist. A probe whose provenance line only
     * compiles on one of its two targets is a build break waiting for whoever runs it next.
     */
    private fun deviceLine(): String {
        val info = NSProcessInfo.processInfo
        return "host os=${info.operatingSystemVersionString} cores=${info.processorCount} " +
            "memoryGB=${info.physicalMemory / (1024uL * 1024uL * 1024uL)}"
    }
}

/**
 * Accumulates per-call microsecond timings and emits one line per [size] calls.
 *
 * Per-call lines would be 9,000 lines an arm and would themselves perturb the measurement — the
 * console channel is a `print` per line over USB. The bucket mean is what draws the curve; the
 * bucket **max** is what catches a stall, because a tail that goes to seconds can hide inside a
 * respectable mean.
 */
private class Bucketed(
    private val size: Int,
    private val label: String,
    private val onLine: (String) -> Unit,
) {
    private var sumMicros = 0L
    private var maxMicros = 0L
    private var count = 0
    private var failures = 0
    private var firstFailure: String? = null

    fun record(micros: Long, n: Int) {
        sumMicros += micros
        if (micros > maxMicros) maxMicros = micros
        count++
        if (count == size) emit(n)
    }

    fun noteFailure(n: Int, detail: String) {
        if (firstFailure == null) {
            firstFailure = detail
            onLine("$label FIRST FAILURE at n=$n: $detail")
        }
        failures++
    }

    fun flush() {
        if (count > 0) emit(-1)
    }

    private fun emit(n: Int) {
        val mean = sumMicros.toDouble() / count / 1000.0
        val max = maxMicros / 1000.0
        onLine("$label n=$n meanMs=${fmt(mean)} maxMs=${fmt(max)} failures=$failures")
        sumMicros = 0
        maxMicros = 0
        count = 0
    }

    private fun fmt(value: Double): String {
        val scaled = (value * 1000).toLong()
        return "${scaled / 1000}.${(scaled % 1000).toString().padStart(3, '0')}"
    }
}
