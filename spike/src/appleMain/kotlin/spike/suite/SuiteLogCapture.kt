@file:OptIn(ExperimentalForeignApi::class)

package spike.suite

import io.github.oshai.kotlinlogging.Appender
import io.github.oshai.kotlinlogging.DirectLoggerFactory
import io.github.oshai.kotlinlogging.KLoggerFactory
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSISO8601DateFormatWithFractionalSeconds
import platform.Foundation.NSISO8601DateFormatWithInternetDateTime
import platform.Foundation.NSISO8601DateFormatter
// `sysctlbyname` is exposed by the `darwin` platform library, NOT `posix`, on both iOS and macOS.
import platform.darwin.sysctlbyname
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.fsync
import platform.posix.size_tVar

/**
 * Durable on-device capture of a whole suite run (#1837 step 1).
 *
 * ## Why this exists
 *
 * Before this, the suite's trace lived only in memory: it reached a human through the on-screen log and
 * the **Share/Copy** report, and died with the app. Three concrete ways that lost evidence, all of them
 * hit in the field:
 *
 *  * **The Airplane Mode window.** Scenario 6 exists to take one phone's radio away — which is exactly
 *    when `devicectl --console` cannot reach that phone. The one window the scenario is *about* is the
 *    one window the cabled console goes dark for.
 *  * **A relaunch destroys the report.** `--terminate-existing` (how you attach a console) wipes the
 *    in-memory report of the run before it. That is not hypothetical; it destroyed a scenario-6 result.
 *  * **Two phones, compared by eye.** Scenario 6's PASS is an *asymmetry between the two devices*, so
 *    the artifact that proves it is the interleaved **pair** — which needs two files, not two texts.
 *
 * A file on disk fixes all three. `spike/collect-logs.sh` pulls it off both phones afterwards and merges
 * them into one causally-ordered timeline; nothing has to be running, and nothing had to be attached
 * while the radio was off.
 *
 * ## What lands in the file
 *
 *  * Every `hop(...)`/suite line, byte-for-byte the text the UI shows, each stamped with a UTC ISO-8601
 *    timestamp. The stamp is the merge key — lexicographic order *is* chronological order.
 *  * The fabric's own `kotlin-logging` output (`us.tractat.kuilt.nw.*`, `kuilt-session`), tee'd through
 *    [installFabricTee]. See its docs for the one behavioural change that implies.
 *  * The final report text, so the file is a superset of what **Share report** would have given you.
 *
 * ## Why not `installLogCapture` / `WarpLogRecordExporter`
 *
 * kuilt ships that machinery and dogfooding it was the stated preference, but it does not fit *step 1
 * on its own*, for three reasons:
 *
 *  1. **The artifact would be unreadable.** `WarpLogRecordExporter` persists a **CBOR-encoded `Rga`**.
 *     Decoding that on the Mac is precisely what #1837 step 2's `LogTapHost` is for — and step 2 is
 *     deliberately deferred (its `LogTapJoinToken` TTL question is unsettled, and #1820 is open). A
 *     shell collector cannot merge a CBOR blob, so persisting into one buys durability and gives up
 *     collectability — the half of the problem that actually hurts today.
 *  2. **The `hop(...)` lines do not go through `kotlin-logging` at all.** They are callbacks. So the
 *     capture edge alone would not satisfy "every hop line lands in the file" without first re-routing
 *     the suite's own trace through a logger — which changes its text.
 *  3. **Cost.** `WarpLogRecordExporter.export` re-encodes and rewrites the *entire* log on every record.
 *     Over a ~3-minute run that is quadratic, on an iPhone XS.
 *
 * So: a direct file-backed sink, in the same spirit as `SpikeNw.writeToFile`, but append-mode rather
 * than rewrite-the-world, and shared by the suite trace and the fabric log. When step 2 lands, this
 * class is what the tap drains — the file is the buffer.
 *
 * ## Failure is never a FAIL
 *
 * A capture problem must never turn a green scenario red. Every path here is total: [open] always
 * returns an instance, a sink that could not open its file is simply [enabled] `== false` and every
 * write is a no-op, and the reason is surfaced once through [warning] so it reaches the on-screen log
 * instead of vanishing.
 *
 * ## Thread safety
 *
 * Writes arrive from the suite's coroutine *and* from arbitrary `Network.framework` dispatch queues (via
 * the logging tee), so the file handle is guarded by an explicit `reentrantLock` — not by confining
 * writes to one dispatcher, which is banned repo policy. No suspending call is made inside the lock.
 */
public class SuiteLogCapture private constructor(
    /** Absolute path of this run's file, or `null` when capture could not be started. */
    public val path: String?,
    /** Non-null when capture could not be started: a one-line reason, meant for the on-screen log. */
    public val warning: String?,
    private var file: CPointer<FILE>?,
) {
    private val lock = reentrantLock()

    // Separate from [lock] on purpose: an observer runs on whichever Network.framework dispatch queue
    // logged, and holding the FILE lock across a foreign callback would couple two unrelated critical
    // sections. Nothing here touches the file handle.
    private val observerLock = reentrantLock()
    private val observers = mutableListOf<(String, String) -> Unit>()

    /** True when lines are actually being persisted. */
    public val enabled: Boolean get() = path != null

    /**
     * Watch the `kotlin-logging` stream *structurally* — [observer] is called with the event's
     * `loggerName` and `message`, not with the rendered file line — and returns the unregister action.
     *
     * **Why this exists.** Some library behaviour has no in-process surface a consumer can subscribe
     * to; its only externally visible trace is a log line. Scenario 7 is the case that forced it: the
     * #1637 no-op resume and an ordinary detector-observed recovery emit the *same*
     * `MembershipEvent.Recovered(hostId)`, so a verdict that needs to tell them apart has nowhere else
     * to look ([ResumeLaneProbe] explains the search that established that).
     *
     * Observers see events regardless of whether the file sink [enabled] — see [installFabricTee].
     * An observer must be cheap and must not throw: it runs inline on a fabric dispatch queue, ahead
     * of nothing and behind the delegate appender.
     */
    public fun observe(observer: (loggerName: String, message: String) -> Unit): () -> Unit {
        observerLock.withLock { observers.add(observer) }
        return { observerLock.withLock { observers.remove(observer) } }
    }

    /** Fan an event out to [observe]rs. Snapshot under the lock, call outside it. */
    internal fun notifyObservers(loggerName: String, message: String) {
        val snapshot = observerLock.withLock { if (observers.isEmpty()) return else observers.toList() }
        snapshot.forEach { it(loggerName, message) }
    }

    /**
     * Persist one line, stamped with the current UTC time. Total: any failure is swallowed, because a
     * scenario's verdict must never depend on whether a write succeeded.
     */
    public fun line(text: String) {
        val handle = file ?: return
        lock.withLock {
            // Re-read under the lock: close() nulls it, and the tee appender can fire from a dispatch
            // queue concurrently with teardown.
            if (file == null) return
            fputs("${stamp()} $text\n", handle)
            // flush, not fsync: the hazards this exists for are process death (`--terminate-existing`,
            // an iOS kill) and an unreachable console, and flushed bytes are already in the kernel's
            // buffer cache — visible to any later reader even if this process never runs again. fsync
            // per line would buy only power-loss durability, at a cost the soak's RTT measurement would
            // notice. close() fsyncs once, at the end.
            fflush(handle)
        }
    }

    /** Persist a multi-line block (the final report) verbatim, each line stamped. */
    public fun block(title: String, body: String) {
        line(title)
        body.lineSequence().forEach { line("| $it") }
    }

    /**
     * Tee `kotlin-logging` output into this file, so the fabric's own trace interleaves with the suite's
     * hops in one causally-ordered stream. Returns the uninstall action.
     *
     * **Behavioural note.** Like kuilt's own `installLogCapture`, this routes `kotlin-logging` through
     * `DirectLoggerFactory` — required for an appender to take effect at all on Darwin. That moves the
     * fabric's output from `os_log` to stdout, which `devicectl --console` *does* capture; the S4
     * diagnostic mode already did exactly this deliberately (see `CONNECTIVITY-SUITE.md`), so a cabled
     * run gains lines, it does not lose them. The configured **level is left alone** — this captures
     * whatever the run was already going to emit and never widens it, so the soak's RTT distribution is
     * measuring the same thing it measured yesterday.
     *
     * **Installed even when the file sink is off.** It used to early-return on `!`[enabled], which was
     * a pure optimisation while the tee's only job was writing bytes. It is not one now: [observe]rs
     * ride the same appender, and a scenario whose *verdict* depends on watching the log stream must
     * not silently go blind because a `fopen` failed. A disabled sink's [line] is already a no-op, so
     * the cost of installing anyway is one delegate call per event.
     */
    public fun installFabricTee(): () -> Unit {
        val previousFactory: KLoggerFactory = KotlinLoggingConfiguration.loggerFactory
        KotlinLoggingConfiguration.loggerFactory = DirectLoggerFactory
        val previousAppender: Appender = KotlinLoggingConfiguration.direct.appender
        KotlinLoggingConfiguration.direct.appender = TeeAppender(this, previousAppender)
        return {
            KotlinLoggingConfiguration.direct.appender = previousAppender
            KotlinLoggingConfiguration.loggerFactory = previousFactory
        }
    }

    /** Flush, fsync and close. Idempotent. */
    public fun close() {
        lock.withLock {
            val handle = file ?: return
            file = null
            fflush(handle)
            fsync(fileno(handle))
            fclose(handle)
        }
    }

    private fun stamp(): String = iso.stringFromDate(NSDate())

    public companion object {
        /** Where run files land inside the app container — flat, so `devicectl device copy from` gets them all. */
        public const val DIRECTORY: String = "Documents"

        /** Filename prefix the collector globs for. Distinct from `SpikeNw`'s `nw.log`. */
        public const val PREFIX: String = "suite-"

        private val iso = NSISO8601DateFormatter().apply {
            // Defaults to GMT, which is what we want: the merge key must not move under a timezone.
            formatOptions =
                NSISO8601DateFormatWithInternetDateTime or NSISO8601DateFormatWithFractionalSeconds
        }

        /**
         * Start capture for a run of [role]. **Never throws and never returns null** — a sink that could
         * not open its file reports [warning] and silently discards.
         *
         * One file per run, not one appended file forever. Two reasons: a run is the unit you reason
         * about (the collector can hand you "the newest run" without parsing anything), and an
         * append-forever file makes the very hazard this fixes worse — the relaunch that wiped a report
         * would instead bury it in the middle of an unbounded file. The name is
         * `suite-<UTC-stamp>-<hw-model>-<role>.log`: timestamp first so lexicographic order is
         * chronological across both phones, hardware model so two devices' files can never collide.
         */
        public fun open(role: String): SuiteLogCapture {
            val dir = "${NSHomeDirectory()}/$DIRECTORY"
            NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
            val name = "$PREFIX${fileStamp()}-${hardwareModel()}-${sanitize(role)}.log"
            val path = "$dir/$name"
            val handle = fopen(path, "a")
                ?: return SuiteLogCapture(null, "log capture OFF: cannot open $path", null)
            val capture = SuiteLogCapture(path, null, handle)
            capture.line("=== spike connectivity suite — run file (#1837) ===")
            capture.line("file=$name role=$role model=${hardwareModel()}")
            return capture
        }

        /** `2026-07-28T09:15:03.412Z` → `20260728T091503Z`: sortable, and legal in a filename. */
        private fun fileStamp(): String =
            iso.stringFromDate(NSDate()).take(FILE_STAMP_CHARS).filter { it != '-' && it != ':' } + "Z"

        private const val FILE_STAMP_CHARS = 19

        /**
         * `hw.machine` — `iPhone11,2` (XS), `iPhone18,1` (17 Pro), `arm64` on a Mac. Deliberately the
         * hardware identifier rather than the device *name*: it needs no entitlement, iOS 16's
         * device-name privacy change cannot blank it, and it is the same string `devicectl` reports as
         * `productType`, so a pulled file names the phone the Mac already named.
         */
        private fun hardwareModel(): String = memScoped {
            val len = alloc<size_tVar>()
            if (sysctlbyname(HW_MACHINE, null, len.ptr, null, 0.convert()) != 0) return@memScoped UNKNOWN_MODEL
            val size = len.value.toInt()
            if (size <= 0) return@memScoped UNKNOWN_MODEL
            val buffer = allocArray<ByteVar>(size)
            if (sysctlbyname(HW_MACHINE, buffer, len.ptr, null, 0.convert()) != 0) return@memScoped UNKNOWN_MODEL
            sanitize(buffer.toKString())
        }

        private const val HW_MACHINE = "hw.machine"
        private const val UNKNOWN_MODEL = "device"

        /** Filename-safe: letters, digits and `-` only, so nothing here can produce a path or a quote. */
        private fun sanitize(raw: String): String =
            raw.map { if (it.isLetterOrDigit() || it == '-') it else '-' }
                .joinToString("")
                .ifEmpty { UNKNOWN_MODEL }
    }
}

/**
 * Forwards every `kotlin-logging` event to the previously-installed appender (so console output is
 * preserved), into the run file, *and* to any [SuiteLogCapture.observe]r.
 *
 * Deliberately synchronous — no channel, no drain coroutine. `SuiteLogCapture.line` is a guarded
 * `fputs` + `fflush`, and the whole point of this class is that the bytes are on disk **before** the
 * process can be killed; handing them to a coroutine to write later reintroduces exactly the loss
 * window #1837 is about.
 *
 * [SuiteLogCapture.line] cannot throw and never itself logs through `kotlin-logging`, so this cannot
 * recurse or propagate a capture failure into the fabric's logging path.
 *
 * Observers get `loggerName` and `message` as their own values rather than the rendered line, so a
 * watcher matches on the *field* it means and never on this class's formatting.
 */
private class TeeAppender(
    private val sink: SuiteLogCapture,
    private val delegate: Appender,
) : Appender {
    override fun log(loggingEvent: KLoggingEvent) {
        delegate.log(loggingEvent)
        // Templated rather than read directly: `KLoggingEvent.message` is nullable, and a watcher
        // matching on prefixes wants a String either way.
        val loggerName = "${loggingEvent.loggerName}"
        val message = "${loggingEvent.message}"
        val cause = loggingEvent.cause
        val suffix = if (cause == null) "" else " | cause=${cause::class.simpleName}: ${cause.message}"
        sink.line("${loggingEvent.level.name} $loggerName — $message$suffix")
        sink.notifyObservers(loggerName, message)
    }
}
