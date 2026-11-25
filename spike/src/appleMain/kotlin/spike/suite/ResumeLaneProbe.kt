package spike.suite

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

/**
 * Watches the **joiner resume lane itself** — the machinery [#1637](https://github.com/tractat-us/kuilt/issues/1637)
 * is a defect of — instead of inferring from membership events that it must have worked.
 *
 * ## Why a log-line watcher, of all things
 *
 * Scenario 7's PASS has to mean *"this episode ran the reconnect lane and the host handed the seat
 * back"*. Every in-process surface a `Room` consumer can subscribe to was checked first, and none of
 * them can say all of that:
 *
 *  * **`MembershipEvent.Recovered(hostId)` cannot.** An ordinary detector-observed recovery emits it
 *    without the reconnect lane ever running. The 2026-07-28 hardware run is the receipt: it reported
 *    a green `Recovered(441485b2)` 8.5 s after the radio died while the resume machine had not
 *    resolved anything at all, and the event stream could not tell.
 *  * **`Partitioned` / `WindowOpened` cannot either**, and this is the subtler trap: the joiner emits
 *    both from `markPartitioned` on a plain heartbeat `Timeout`, with the resume machine never
 *    entered and its host-liveness detector still running. "The episode opened" is therefore evidence
 *    about the *detector*, not about the resume lane.
 *  * **`Room` exposes nothing else** — `roster`, `events`, `localFabric`, `role`. There is no
 *    `reconnecting` flow, no reconnect-outcome callback. Adding one is a library change, which this
 *    scenario is explicitly not allowed to make in order to pass itself.
 *
 * What *is* unambiguous is the resume machine's own evidence-capture logging, added for #1618 and
 * kept deliberately as identities-and-state rather than counts. So the discriminator is those lines.
 *
 * ## Keeping the brittleness bounded
 *
 * Matching log text is brittle, so the match surface is kept as small as it can be:
 *
 *  * The tee hands over `loggerName` and `message` as separate values ([SuiteLogCapture.observe]), so
 *    nothing here depends on how a line is *rendered*.
 *  * Only the **stable prefix** is matched — `resume.ok`, `resume.terminal`, `resume.refused` — never
 *    the whole line, so the identities and durations those lines carry can change freely. The one
 *    field read is `resume.ok`'s `restored=true|false`, which says whether the host's answer named the
 *    membership this room already held.
 *  * The logger name is recorded for the report but deliberately **not** required: a rename of the
 *    class would otherwise blind the probe silently, and the prefixes are already unique.
 *  * A probe that saw nothing is never read as a PASS. Going blind produces an explicit
 *    "NOT EXERCISED" verdict, which is the whole point — see [ResumeLane].
 */
internal class ResumeLaneProbe {
    private val lock = reentrantLock()
    private var handedOff: String? = null
    private var refused: String? = null
    private var ok: String? = null
    private var restored: String? = null
    private var afresh: String? = null
    private var terminal: String? = null
    private var witness: String? = null
    private val lines = mutableListOf<String>()

    /**
     * Feed one `kotlin-logging` event in. Called inline on whichever `Network.framework` dispatch
     * queue logged it, so it does nothing but classify and store under the lock — and it must never
     * throw, or it would propagate into the fabric's logging path.
     */
    fun record(loggerName: String, message: String) {
        val kind = classify(message)
        lock.withLock {
            // Recorded for EVERY event, matched or not — see [ResumeLane.witness]. Silence from a
            // probe that was never wired up looks exactly like silence from a machine that never ran,
            // and those two must not share a verdict.
            if (witness == null) witness = loggerName
            if (kind == null) return
            lines.add("$loggerName: $message")
            // First occurrence wins for each kind: a second episode's line must not overwrite the one
            // that decided the verdict, and every line is kept in [lines] anyway.
            when (kind) {
                Kind.HANDED_OFF -> handedOff = handedOff ?: message
                Kind.REFUSED -> refused = refused ?: message
                Kind.OK -> {
                    ok = ok ?: message
                    if (message.contains(RESTORED)) restored = restored ?: message
                    if (message.contains(AFRESH)) afresh = afresh ?: message
                }
                Kind.TERMINAL -> terminal = terminal ?: message
            }
        }
    }

    /** A consistent read of everything seen so far. */
    fun snapshot(): ResumeLane = lock.withLock {
        ResumeLane(
            handedOff = handedOff,
            refused = refused,
            ok = ok,
            restored = restored,
            afresh = afresh,
            terminal = terminal,
            witness = witness,
            lines = lines.toList(),
        )
    }

    private fun classify(message: String): Kind? = when {
        message.startsWith(OK) -> Kind.OK
        message.startsWith(TERMINAL) -> Kind.TERMINAL
        message.startsWith(REFUSED) -> Kind.REFUSED
        message.startsWith(UNRESPONSIVE) && message.contains(RESUME_BRANCH) -> Kind.HANDED_OFF
        else -> null
    }

    private enum class Kind { HANDED_OFF, REFUSED, OK, TERMINAL }

    private companion object {
        /**
         * `JoinerResumeMachine`: the episode closed on the host's `Welcome` to this room's Hello. Since
         * #3215 that is every successful return; [RESTORED] and [AFRESH] say which kind.
         */
        const val OK = "resume.ok"

        /** On a [OK] line: the `Welcome` named the membership this room already held — the seat came back. */
        const val RESTORED = "restored=true"

        /** On a [OK] line: the `Welcome` named another membership — the seat was gone, admitted afresh. */
        const val AFRESH = "restored=false"

        /** `JoinerResumeMachine`: the machine bailed before retrying (no reweave, or no known host). */
        const val TERMINAL = "resume.terminal"

        /**
         * `JoinerResumeMachine`: one host `Reject` inside the retry loop, carrying the code, the
         * attempt number and the window's progress — direct evidence that the reconnect lane was
         * entered and is running (#1969).
         */
        const val REFUSED = "resume.refused"

        /**
         * `SeamRoom`: which branch a peer going quiet took. The weaker stand-in for [REFUSED] on a
         * build without #1969 — it proves the room *handed off* to the resume machine, not that the
         * machine got as far as being refused. Still enough to separate "entered and did not finish"
         * from "never entered at all", which is the difference between a stalled lane and an
         * untested one.
         */
        const val UNRESPONSIVE = "membership.unresponsive"
        const val RESUME_BRANCH = "branch=resume"
    }
}

/**
 * What [ResumeLaneProbe] observed, as one immutable read.
 *
 * Two independent questions, which the 2026-07-28 run could not tell apart and which scenario 7 must
 * never collapse — **was the lane ENTERED**, and **did it RESOLVE**:
 *
 *  * [entered] — the episode reached the resume machine at all ([refused], or [handedOff] as the
 *    weaker stand-in).
 *  * [restored] non-null — it resolved on a `Welcome` naming the membership this room held: the host
 *    handed the seat back.
 *  * [afresh] non-null — it resolved on a `Welcome` naming another membership: the host had let the
 *    seat go, so it had noticed the outage. Recovery, but out of band.
 *  * entered and neither — the lane ran and never concluded. An anomaly of its own.
 *  * not entered — the blip never reached the lane. Nothing was tested, however healthy the room looks.
 */
internal data class ResumeLane(
    /** The `membership.unresponsive … branch=resume` line, if the room ever handed off to the machine. */
    val handedOff: String?,
    /**
     * The first `resume.refused` line (#1969) — a host `Reject` inside the retry loop. Direct evidence
     * the reconnect lane was entered *and* running.
     */
    val refused: String?,
    /** The first `resume.ok` line of any kind — the episode closed on the host's `Welcome`. */
    val ok: String?,
    /** The first `resume.ok … restored=true` line — the host handed this room's own seat back. */
    val restored: String?,
    /** The first `resume.ok … restored=false` line — the seat was gone, and this room was admitted afresh. */
    val afresh: String?,
    /** The `resume.terminal` line — the machine gave up on a null gate before ever retrying. */
    val terminal: String?,
    /**
     * The logger that produced the **first event of any kind** the probe saw — proof the log stream
     * reached it at all.
     *
     * Null means the probe was **blind**: the tee never installed, or the level was raised above
     * INFO, or the factory was swapped out from under it. That is not the same finding as "the
     * machine ran and logged nothing", and a report that conflates them sends a reader hunting a
     * library bug that is really a wiring bug in this file.
     */
    val witness: String?,
    /** Every matched line in arrival order, for the shared report. */
    val lines: List<String>,
) {
    /**
     * True when the episode reached the resume machine at all — the question that is *not* the same
     * as [resolved], and whose conflation with it is what let a premature verdict look green.
     */
    val entered: Boolean get() = refused != null || handedOff != null

    /** The strongest available evidence that the lane was entered, for quoting in a verdict. */
    val entryEvidence: String? get() = refused ?: handedOff

    /** True when the lane reached a conclusion of its own, either way. */
    val resolved: Boolean get() = ok != null

    /** True when the probe never saw a single log event — its silence means nothing. */
    val blind: Boolean get() = witness == null

    /** Identities and text, never counts — the report is the only artifact a later debugger gets. */
    fun render(): String = when {
        blind -> "BLIND (the probe saw no log events at all — its silence proves nothing)"
        lines.isEmpty() -> "SILENT (stream live, first witness $witness; the resume machine logged nothing)"
        else -> "[${lines.joinToString(" ; ")}]"
    }
}
