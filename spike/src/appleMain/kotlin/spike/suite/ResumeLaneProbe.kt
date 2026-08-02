package spike.suite

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

/**
 * Watches the **joiner resume lane itself** — the machinery [#1637](https://github.com/tractat-us/kuilt/issues/1637)
 * is a defect of — instead of inferring from membership events that it must have worked.
 *
 * ## Why a log-line watcher, of all things
 *
 * Scenario 7's PASS has to mean *"the #1637 fix ran and resolved this episode"*. Every in-process
 * surface a `Room` consumer can subscribe to was checked first, and none of them can say that:
 *
 *  * **`MembershipEvent.Recovered(hostId)` cannot.** The #1637 dwell path
 *    (`JoinerResumeHost.onNoOpResume` → the room's `markRecovered`) and an ordinary
 *    detector-observed recovery (`PartitionEvent.PeerRecovered` → the *same* `markRecovered`) emit a
 *    byte-identical event. The 2026-07-28 hardware run is the receipt: it reported a green
 *    `Recovered(441485b2)` 8.5 s after the radio died while the resume machine had not resolved
 *    anything at all — the fix under test was never exercised, and the event stream could not tell.
 *  * **`Partitioned` / `WindowOpened` cannot either**, and this is the subtler trap: the joiner emits
 *    both from `markPartitioned` on a plain heartbeat `Timeout`, with the resume machine never
 *    entered and its host-liveness detector still running. "The episode opened" is therefore evidence
 *    about the *detector*, not about the resume lane.
 *  * **`Room` exposes nothing else** — `roster`, `events`, `localFabric`, `role`, `resumeToken`. There
 *    is no `reconnecting` flow, no resume-outcome callback. Adding one is a library change, which this
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
 *  * Only the **stable prefix** is matched — `resume.no-op`, `resume.ok`, `resume.terminal` — never
 *    the whole line, so the identities and durations those lines carry can change freely.
 *  * The logger name is recorded for the report but deliberately **not** required: a rename of the
 *    class would otherwise blind the probe silently, and the prefixes are already unique.
 *  * A probe that saw nothing is never read as a PASS. Going blind produces an explicit
 *    "NOT EXERCISED" verdict, which is the whole point — see [ResumeLane].
 */
internal class ResumeLaneProbe {
    private val lock = reentrantLock()
    private var entered: String? = null
    private var noOp: String? = null
    private var ok: String? = null
    private var terminal: String? = null
    private var witness: String? = null
    private val lines = mutableListOf<String>()

    /**
     * Feed one `kotlin-logging` event in. Called inline on whichever `Network.framework` dispatch
     * queue logged it, so it does nothing but classify and store under the lock — and it must never
     * throw, or it would propagate into the fabric's logging path.
     */
    fun record(loggerName: String, message: String) {
        // Recorded for EVERY event, matched or not — see [ResumeLane.witness]. Silence from a probe
        // that was never wired up looks exactly like silence from a machine that never ran, and those
        // two must not share a verdict.
        val kind = lock.withLock {
            if (witness == null) witness = loggerName
            classify(message)
        } ?: return
        lock.withLock {
            lines.add("$loggerName: $message")
            // First occurrence wins for each kind: a second episode's line must not overwrite the one
            // that decided the verdict, and every line is kept in [lines] anyway.
            when (kind) {
                Kind.ENTERED -> entered = entered ?: message
                Kind.NO_OP -> noOp = noOp ?: message
                Kind.OK -> ok = ok ?: message
                Kind.TERMINAL -> terminal = terminal ?: message
            }
        }
    }

    /** A consistent read of everything seen so far. */
    fun snapshot(): ResumeLane = lock.withLock {
        ResumeLane(
            entered = entered,
            noOp = noOp,
            ok = ok,
            terminal = terminal,
            witness = witness,
            lines = lines.toList(),
        )
    }

    private fun classify(message: String): Kind? = when {
        message.startsWith(NO_OP) -> Kind.NO_OP
        message.startsWith(OK) -> Kind.OK
        message.startsWith(TERMINAL) -> Kind.TERMINAL
        message.startsWith(UNRESPONSIVE) && message.contains(RESUME_BRANCH) -> Kind.ENTERED
        else -> null
    }

    private enum class Kind { ENTERED, NO_OP, OK, TERMINAL }

    private companion object {
        /** `JoinerResumeMachine`: the episode completed on the #1637 dwell — the fixed behaviour. */
        const val NO_OP = "resume.no-op"

        /** `JoinerResumeMachine`: the episode completed on a real `ResumeAck` — the ordinary lane. */
        const val OK = "resume.ok"

        /** `JoinerResumeMachine`: the machine bailed before retrying (no reweave / token / host). */
        const val TERMINAL = "resume.terminal"

        /**
         * `SeamRoom`: which branch a peer going quiet took. Context, not a verdict — it separates
         * "the resume machine was entered and did not finish" from "it was never entered at all",
         * which is the difference between a slow lane and an untested one.
         */
        const val UNRESPONSIVE = "membership.unresponsive"
        const val RESUME_BRANCH = "branch=resume"
    }
}

/**
 * What [ResumeLaneProbe] observed, as one immutable read.
 *
 * The three states a *surviving* room can be in, which scenario 7 must never collapse:
 *
 *  * [noOp] non-null — the resume lane resolved on the #1637 dwell. The fix demonstrably ran.
 *  * [ok] non-null — it resolved on a real `ResumeAck`, so the host *had* a window open and this was
 *    the ordinary resume lane, not the sub-timeout one. Recovery, but out of band.
 *  * both null — the lane never resolved. The room may look perfectly healthy; nothing was tested.
 */
internal data class ResumeLane(
    /** The `membership.unresponsive … branch=resume` line, if the room ever handed off to the machine. */
    val entered: String?,
    /** The `resume.no-op` line — the #1637 dwell fired. */
    val noOp: String?,
    /** The `resume.ok` line — a real resume was ACKed. */
    val ok: String?,
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
    /** True when the lane reached a conclusion of its own, either way. */
    val resolved: Boolean get() = noOp != null || ok != null

    /** True when the probe never saw a single log event — its silence means nothing. */
    val blind: Boolean get() = witness == null

    /** Identities and text, never counts — the report is the only artifact a later debugger gets. */
    fun render(): String = when {
        blind -> "BLIND (the probe saw no log events at all — its silence proves nothing)"
        lines.isEmpty() -> "SILENT (stream live, first witness $witness; the resume machine logged nothing)"
        else -> "[${lines.joinToString(" ; ")}]"
    }
}
