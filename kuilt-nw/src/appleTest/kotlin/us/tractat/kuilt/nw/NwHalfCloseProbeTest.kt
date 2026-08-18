@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress("ForbiddenImport") // real-network loopback platform probe — a real Network.framework socket needs a real IO dispatcher; there is no virtual-time option here

package us.tractat.kuilt.nw

import kotlinx.atomicfu.atomic
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real-network loopback platform probe — a real Network.framework socket needs a real IO dispatcher; there is no virtual-time option here
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_create
import platform.Network.nw_connection_set_queue
import platform.Network.nw_connection_set_state_changed_handler
import platform.Network.nw_connection_start
import platform.Network.nw_connection_state_cancelled
import platform.Network.nw_connection_state_failed
import platform.Network.nw_connection_state_preparing
import platform.Network.nw_connection_state_ready
import platform.Network.nw_connection_state_t
import platform.Network.nw_connection_state_waiting
import platform.Network.nw_connection_t
import platform.Network.nw_endpoint_create_host
import platform.Network.nw_listener_cancel
import platform.Network.nw_listener_create
import platform.Network.nw_listener_get_port
import platform.Network.nw_listener_set_new_connection_handler
import platform.Network.nw_listener_set_queue
import platform.Network.nw_listener_set_state_changed_handler
import platform.Network.nw_listener_start
import platform.Network.nw_listener_state_failed
import platform.Network.nw_listener_state_ready
import platform.Network.nw_listener_t
import platform.Network.nw_parameters_t
import platform.darwin.dispatch_queue_create
import us.tractat.kuilt.nw.probe.cinterop.kuilt_nw_probe_params
import us.tractat.kuilt.nw.probe.cinterop.kuilt_nw_probe_receive
import us.tractat.kuilt.nw.probe.cinterop.kuilt_nw_probe_send
import us.tractat.kuilt.test.assertAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * **Platform probe for [#2457](https://github.com/tractat-us/kuilt/issues/2457) — can an
 * `nw_connection_t` on kuilt-nw's TLS-PSK binding be half-closed?** i.e. write-close the sending
 * direction (send a FIN) while the *receiving* direction stays open, so that bytes the remote had
 * already handed to the transport still arrive.
 *
 * The answer decides #2457's option **D** ("graceful loser drain"): on losing the double-dial
 * dedup, rebind writes to the winning connection, **half-close** the loser and keep **reading** it
 * until remote EOF. D exists only if the platform preserves read-after-write-close on this binding.
 *
 * ## Verdict: NO — the write-close is accepted and then ignored
 * Measured identically on `macosArm64` and `iosSimulatorArm64`, 6/6 runs each. Over **plain TCP**
 * the write-close works exactly as documented: the remote observes a read-close (a zero-byte,
 * `isFinal && isComplete` completion) and a subsequent local send fails with `EPIPE`. Over the
 * **TLS-PSK** parameters kuilt-nw actually ships, the *identical* call is accepted with no error
 * and then has no effect whatsoever — the remote never receives a read-close, and bytes written
 * afterwards are still delivered to it. The write direction simply stays open.
 *
 * So D's termination condition ("until remote FIN/EOF") can never fire on this fabric, and the peer
 * on the other end of a losing connection is never told to stop writing to it.
 *
 * ## What the headers promise (macOS 26.5 SDK)
 * From `Network.framework/Headers/connection.h`:
 *  - **`:561-568`** — *"In order to close a connection on the sending side (a \"write close\"), send
 *    a context that is marked as \"final\" and mark is_complete."*
 *  - **`:488-497`** (`NW_CONNECTION_FINAL_MESSAGE_CONTEXT`) — *"Marking a send as complete when
 *    using this context will close the sending side of the underlying connection. This is the
 *    equivalent of sending a FIN on a TCP stream."*
 *  - **`:352-356`** (receive completion) — a stream connection's context is marked final, and
 *    *"Once a final context is marked as complete, the recipient may assume that the connection has
 *    been closed in the receiving direction (a \"read close\"). For TCP, this represents receiving a
 *    FIN."*
 *  - **`:594-599`** (send completion) — *"This callback does not indicate that the remote side has
 *    acknowledged the data."* So every claim below is asserted from the **receiving** end.
 *
 * And `Network.framework/Headers/content_context.h:80-90` — `nw_content_context_get_is_final`, the
 * receiving-side companion the probe reads to tell a read-close from an ordinary message.
 *
 * So the **API** is a half-close API by construction. The doubt is the layer above TCP.
 *
 * ## Why TLS is the doubt, not TCP
 * This binding runs TLS-PSK (`RealNwApi.secureParams` →
 * `sec_protocol_options_add_pre_shared_key`), Apple's **TLS-1.2-era external PSK** path — see
 * [NwPskNulIdentityTest] for the RFC 4279 §5.1 analysis that pins it there. TLS 1.2
 * (RFC 5246 §7.2.1) requires a peer receiving `close_notify` to reply with its own and close
 * immediately: a *full* close, pending writes discarded. Only TLS 1.3 (RFC 8446 §6.1) made
 * `close_notify` affect the write side alone. A stack sitting on the 1.2 semantics cannot offer
 * the header's half-close.
 *
 * ## Why the call is made from C
 * A negative verdict is only worth having if it cannot be blamed on the caller. Two load-bearing
 * values here are `#define`s over `extern const` globals that Kotlin/Native does not reliably
 * bridge: `NW_CONNECTION_FINAL_MESSAGE_CONTEXT` (`RealNwApi.send` already carries the note that
 * "the `NW_CONNECTION_*_CONTEXT` constants mis-bridge under K/N") and `NW_PARAMETERS_DISABLE_
 * PROTOCOL` (spelled from Kotlin it produced parameters over which no connection would form at
 * all). Both are therefore spelled in `nwprobe.def`, a **test-only** cinterop, using Apple's own
 * macros.
 *
 * ## The control arm is the load-bearing part
 * [writeCloseOverPlainTcpIsTheDiscriminator] runs the identical code path with TLS disabled. TCP
 * half-close is well-defined (RFC 793's FIN), so it separates the two explanations a failed
 * write-close is otherwise ambiguous between: a plain-TCP link that half-closes proves the call is
 * right and indicts TLS; one that does not indicts the call or the framework instead. Read the two
 * TLS/plain results together — neither means much alone.
 *
 * ## Scope
 * Loopback (`127.0.0.1`, `includePeerToPeer(false)`) is a valid oracle for the *framework + TLS*
 * question, because half-close is a property of the protocol stack rather than of the link layer.
 * It does **not** cover the AWDL path a real two-device session uses.
 *
 * The probe drives raw `nw_connection_t`s rather than [NwSeam]/[RealNwApi], because `RealNwApi.send`
 * deliberately sends every frame on a **non-final** context — no production path can express a
 * half-close today, and adding one is the fix, not the probe.
 */
class NwHalfCloseProbeTest {

    private companion object {
        const val ROOM_KEY = "loopback-secret"
        const val SERVICE_TYPE = "_kuilt._tcp"

        /** The field-capture payload size from #2425 — the write lost in the publish-swap window. */
        const val WINDOW_BYTES = 182

        /** A short marker the far end can count, distinct from [WINDOW_BYTES]. */
        const val MARKER_BYTES = 8

        /** A second marker, distinct again, for the write issued *after* the write-close. */
        const val POST_CLOSE_BYTES = 5

        /**
         * Hard real-time ceiling on any single await. A probe that hangs is useless: every step
         * either produces an observation or times out into one.
         */
        val STEP_CEILING: Duration = 5.seconds

        /**
         * Ceiling for an await whose *timeout* is the expected finding — "no read-close ever came",
         * "the post-close write never landed". Loopback RTT is microseconds, so this is already four
         * orders of magnitude of headroom; it is short only relative to [STEP_CEILING], and exists so
         * the suite does not spend 5 s per absence.
         */
        val ABSENCE_CEILING: Duration = 2.seconds

        /** Settle time for a one-way event on loopback — generous; loopback RTT is microseconds. */
        val SETTLE: Duration = 250.milliseconds
    }

    private val links = mutableListOf<ProbeLink>()

    @AfterTest
    fun tearDown() {
        links.forEach { it.close() }
        links.clear()
    }

    private suspend fun newLink(tls: Boolean): ProbeLink {
        val link = ProbeLink.open(ROOM_KEY, SERVICE_TYPE, tls)
        links += link
        assertTrue(
            link.formed,
            "probe link must reach ready on both ends — otherwise every result below is vacuous. " +
                "tls=$tls trail=${link.formationTrail}",
        )
        return link
    }

    /**
     * One write-close attempt over a link built with or without TLS, recording: whether the API
     * accepted it, what the remote observed, and whether the local sending direction actually
     * closed.
     *
     * The remote's receive is armed **before** the write-close, so a read-close cannot be lost to
     * ordering.
     */
    private suspend fun observeWriteClose(tls: Boolean): WriteCloseObservation {
        val link = newLink(tls)

        // (1) start the remote's read pump BEFORE the write-close, so a read-close cannot be lost to
        // ordering. One outstanding receive throughout — see ProbeSide.startReceivePump.
        link.server.startReceivePump()
        val marker = ByteArray(MARKER_BYTES) { 0x5A }
        val halfClose = link.client.sendFinal(marker, STEP_CEILING)
        val observed = link.server.next(STEP_CEILING)

        // (2) a read-close may ride the content-bearing completion, or arrive as a separate one right
        // after it. Look again when the first was not itself a read-close. A timeout here IS the
        // finding on a binding that ignores the write-close, so it uses the absence ceiling.
        val followUp = if (!observed.readClose) link.server.next(ABSENCE_CEILING) else null

        // (3) did the local sending direction really close? Two independent readings: what the send
        // completion says, and — the one that actually counts — whether the bytes REACH the remote.
        // A send completion is not an acknowledgement (connection.h:594-599), so the completion alone
        // could never establish either answer.
        val postClose = ByteArray(POST_CLOSE_BYTES) { 0x3C }
        val afterClose = link.client.sendData(postClose, STEP_CEILING)
        val postCloseDelivery = link.server.next(STEP_CEILING)

        return WriteCloseObservation(
            tls = tls,
            halfClose = halfClose,
            remoteFirst = observed,
            remoteFollowUp = followUp,
            localSendAfterClose = afterClose,
            postCloseDelivery = postCloseDelivery,
            clientStates = link.client.stateTrail(),
            serverStates = link.server.stateTrail(),
        ).also { println(it) }
    }

    /**
     * **The discriminator — read this one first.** The identical code path over **plain TCP** (TLS
     * disabled, everything else the same), where half-close is well-defined (RFC 793 FIN).
     *
     * If this passes, the probe's call is correct and any failure under TLS is attributable to the
     * TLS layer. If this fails, the negative verdict in [writeCloseIsIgnoredOnTheTlsPskBinding] is
     * **not safe to rely on** — the fault would be in the call or the framework binding, and #2457
     * would need re-probing before option D is rejected.
     */
    @Test
    fun writeCloseOverPlainTcpIsTheDiscriminator() = runBlocking {
        withContext(Dispatchers.Default) {
            val o = observeWriteClose(tls = false)
            assertAll(
                { assertTrue(o.halfClose.ok, "the API must accept a final+complete send: ${o.halfClose}") },
                {
                    assertEquals(
                        MARKER_BYTES, o.remoteFirst.byteCount,
                        "the final message's content must reach the remote (proven from the RECEIVING end)",
                    )
                },
                {
                    assertTrue(
                        o.remoteSawReadClose,
                        "DISCRIMINATOR (#2457): over plain TCP the remote MUST observe a read-close (FIN), " +
                            "or the probe's own call — not TLS — is what is wrong, and the TLS-PSK verdict " +
                            "is unsafe. got first=${o.remoteFirst} followUp=${o.remoteFollowUp}",
                    )
                },
                {
                    assertFalse(
                        o.writeDirectionStillOpen,
                        "over plain TCP the local sending direction must be closed after a write-close — " +
                            "asserted from the REMOTE, which must receive nothing. " +
                            "completion=${o.localSendAfterClose} remote=${o.postCloseDelivery}",
                    )
                },
            )
        }
    }

    /**
     * The same write-close over the **TLS-PSK** binding kuilt-nw actually ships. Pins whatever the
     * platform does, so #2457's verdict has a durable receipt either way. Read together with
     * [writeCloseOverPlainTcpIsTheDiscriminator], whose result is what makes this one interpretable.
     */
    @Test
    fun writeCloseIsIgnoredOnTheTlsPskBinding() = runBlocking {
        withContext(Dispatchers.Default) {
            val o = observeWriteClose(tls = true)
            assertAll(
                { assertTrue(o.halfClose.ok, "the API accepts the call: ${o.halfClose}") },
                {
                    assertEquals(
                        MARKER_BYTES, o.remoteFirst.byteCount,
                        "the final message's CONTENT still reaches the remote (proven from the receiving end)",
                    )
                },
                {
                    assertFalse(
                        o.remoteSawReadClose,
                        "PINNED PLATFORM BEHAVIOUR (#2457): over TLS-PSK the remote does NOT observe a " +
                            "read-close. If this assertion starts failing, Apple has begun honouring the " +
                            "write-close on this binding and option D becomes viable — re-open #2457. " +
                            "got first=${o.remoteFirst} followUp=${o.remoteFollowUp}",
                    )
                },
                {
                    assertEquals(
                        POST_CLOSE_BYTES, o.postCloseDelivery.byteCount,
                        "PINNED PLATFORM BEHAVIOUR (#2457): the local sending direction stays OPEN — bytes " +
                            "written AFTER the write-close still REACH the remote, which is what proves the " +
                            "write-close was a no-op rather than merely unreported. " +
                            "completion=${o.localSendAfterClose} remote=${o.postCloseDelivery}",
                    )
                },
            )
        }
    }

    /**
     * **#2457's money question**, asked directly: the remote hands bytes to the transport, the local
     * end write-closes *before* consuming them, and we look for those bytes.
     *
     * This is the shape of the 182-byte loss in #2425 — a peer had already written into a connection
     * the other end then tore down. Option D replaces that teardown with a half-close.
     *
     * **What a pass here does and does not mean.** It shows the bytes are still readable, but on the
     * TLS-PSK binding the write-close is a no-op (pinned above), so what it really measures is that
     * an *untouched* connection still delivers — which was never in doubt. It is recorded because it
     * is the assertion #2457 asks for, and because on any binding where the write-close does fire it
     * becomes the load-bearing one.
     *
     * **The rig, and how it asserts it fired:**
     *  1. the remote sends [WINDOW_BYTES] and we await *its* send completion — the bytes are
     *     enqueued/sent (`connection.h:594-599`) and no longer the writer's to retry;
     *  2. the local end has posted **zero** receives, asserted explicitly, so nothing has consumed
     *     them — the window is genuinely open;
     *  3. only then does the local end write-close, and only after that does it post its first read.
     */
    @Test
    fun bytesTheRemoteAlreadySentSurviveOurWriteClose() = runBlocking {
        withContext(Dispatchers.Default) {
            val link = newLink(tls = true)
            val payload = ByteArray(WINDOW_BYTES) { (it and 0x7F).toByte() }

            val remoteWrite = link.server.sendData(payload, STEP_CEILING)
            delay(SETTLE) // let the bytes reach the local transport without being consumed

            val postedBefore = link.client.receivesPosted
            val halfClose = link.client.sendFinal(null, STEP_CEILING)
            link.client.startReceivePump()
            val delivered = link.client.next(STEP_CEILING)

            println(
                """
                |=== #2457 probe: bytes already sent survive our write-close (TLS-PSK) ===
                |remote write ($WINDOW_BYTES B)  : $remoteWrite
                |receives posted before close : $postedBefore
                |half-close send              : $halfClose
                |local receive after close    : $delivered
                |client states                : ${link.client.stateTrail()}
                """.trimMargin(),
            )

            assertAll(
                { assertTrue(remoteWrite.ok, "the remote's write must be handed off: $remoteWrite") },
                {
                    assertEquals(
                        0, postedBefore,
                        "RIG: the in-flight window must be open — the local end must not have consumed the " +
                            "bytes before write-closing, or this test proves nothing",
                    )
                },
                { assertTrue(halfClose.ok, "the API must accept the write-close: $halfClose") },
                {
                    assertEquals(
                        WINDOW_BYTES, delivered.byteCount,
                        "bytes the remote had already handed to the transport must still be readable " +
                            "after our write-close. got $delivered",
                    )
                },
            )
        }
    }

    /**
     * The other half of a drain: after the local end write-closes, the remote must be able to keep
     * **writing** to us, and those writes must land. If receiving our `close_notify` failed the
     * remote's connection — the TLS 1.2 behaviour RFC 5246 §7.2.1 prescribes — option D would be
     * dead even with a working half-close, because the peer that has not yet run its own dedup
     * would lose exactly the frames D exists to save.
     *
     * Asserted from the **receiving** end; a clean send completion on the remote proves only that
     * the call was accepted (`connection.h:594-599`).
     *
     * As with [bytesTheRemoteAlreadySentSurviveOurWriteClose], on this binding the write-close is a
     * no-op, so what this currently measures is an untouched connection — recorded because it is the
     * question #2457 asks, and because it becomes load-bearing on any binding that honours the
     * write-close.
     */
    @Test
    fun remoteCanStillWriteToUsAfterOurWriteClose() = runBlocking {
        withContext(Dispatchers.Default) {
            val link = newLink(tls = true)

            val halfClose = link.client.sendFinal(null, STEP_CEILING)
            delay(SETTLE) // let the FIN land, if there is one, before the remote writes

            val payload = ByteArray(WINDOW_BYTES) { (it and 0x7F).toByte() }
            link.client.startReceivePump()
            val remoteWrite = link.server.sendData(payload, STEP_CEILING)
            val delivered = link.client.next(STEP_CEILING)

            println(
                """
                |=== #2457 probe: remote keeps writing after our write-close (TLS-PSK) ===
                |half-close send        : $halfClose
                |remote write AFTER FIN : $remoteWrite
                |local receive          : $delivered
                |client states          : ${link.client.stateTrail()}
                |server states          : ${link.server.stateTrail()}
                """.trimMargin(),
            )

            assertAll(
                { assertTrue(halfClose.ok, "the API must accept the write-close: $halfClose") },
                {
                    assertEquals(
                        WINDOW_BYTES, delivered.byteCount,
                        "a write the remote issues after our write-close must still reach us. got $delivered",
                    )
                },
            )
        }
    }
}

// ── observations ───────────────────────────────────────────────────────────────────────────────

/** Everything one write-close attempt revealed, over one link. */
internal data class WriteCloseObservation(
    val tls: Boolean,
    val halfClose: SendOutcome,
    val remoteFirst: ReceiveOutcome,
    val remoteFollowUp: ReceiveOutcome?,
    val localSendAfterClose: SendOutcome,
    /** What the REMOTE received for the post-write-close send — the outcome, not the instrument. */
    val postCloseDelivery: ReceiveOutcome,
    val clientStates: String,
    val serverStates: String,
) {
    /**
     * Is the local sending direction still open? Decided by delivery at the far end, not by the send
     * completion — `connection.h:594-599` is explicit that a completion "does not indicate that the
     * remote side has acknowledged the data".
     */
    val writeDirectionStillOpen: Boolean
        get() = postCloseDelivery.byteCount > 0
    /**
     * Did the remote see its read direction close? Either the content-bearing completion was itself
     * final+complete, or the follow-up was, or the follow-up came back with a terminal error (an EOF
     * surfaced as an error is still an EOF). A follow-up that merely *timed out* is the opposite
     * finding — the connection is simply still open.
     */
    val remoteSawReadClose: Boolean
        get() = remoteFirst.readClose || remoteFollowUp?.readClose == true || remoteFollowUp?.errDomain != null

    override fun toString(): String = """
        |=== #2457 write-close observation (${if (tls) "TLS-PSK" else "PLAIN TCP"}) ===
        |half-close send accepted : $halfClose
        |remote first receive     : $remoteFirst
        |remote follow-up receive : ${remoteFollowUp ?: "<not needed — first completion was already final>"}
        |remote saw read-close    : $remoteSawReadClose
        |local send AFTER close   : $localSendAfterClose
        |  …as seen by the REMOTE : $postCloseDelivery
        |write direction still open: $writeDirectionStillOpen
        |client states            : $clientStates
        |server states            : $serverStates
    """.trimMargin()
}

/** One observed `nw_connection_send` completion. */
internal data class SendOutcome(val timedOut: Boolean, val errDomain: Int?, val errCode: Int?) {
    val ok: Boolean get() = !timedOut && errDomain == null
    override fun toString(): String = when {
        timedOut -> "TIMED-OUT (no completion within ceiling)"
        errDomain == null -> "OK (completion, no error)"
        else -> "ERROR domain=$errDomain code=$errCode"
    }
}

/** One observed `nw_connection_receive` completion, unpacked to primitives by the C shim. */
internal data class ReceiveOutcome(
    val timedOut: Boolean,
    val byteCount: Int,
    val isComplete: Boolean,
    val isFinal: Boolean,
    val errDomain: Int?,
    val errCode: Int?,
) {
    /**
     * A read close, per `connection.h:352-356`: the context is the connection's **final** one and it
     * is now **complete**. Both flags, not just `isComplete` — on a stream every ordinary message
     * completion also carries `isComplete`.
     */
    val readClose: Boolean get() = isFinal && isComplete

    override fun toString(): String = when {
        timedOut -> "TIMED-OUT (no completion within ceiling)"
        errDomain != null -> "ERROR domain=$errDomain code=$errCode bytes=$byteCount " +
            "isComplete=$isComplete isFinal=$isFinal"
        else -> "DELIVERED bytes=$byteCount isComplete=$isComplete isFinal=$isFinal readClose=$readClose"
    }
}

// ── raw Network.framework probe harness ────────────────────────────────────────────────────────

private class SendCtx(val sink: Channel<SendOutcome>)
private class RecvCtx(val side: ProbeSide)

/** Routed through a plain C function pointer — no Obj-C object crosses the Kotlin boundary (#1516). */
private val probeSendCompletion =
    staticCFunction<COpaquePointer?, Boolean, Int, Int, Unit> { ctx, hasError, errDomain, errCode ->
        val ref = ctx!!.asStableRef<SendCtx>()
        val sink = ref.get().sink
        ref.dispose()
        sink.trySend(
            SendOutcome(
                timedOut = false,
                errDomain = if (hasError) errDomain else null,
                errCode = if (hasError) errCode else null,
            ),
        )
    }

private val probeReceiveCompletion =
    staticCFunction<COpaquePointer?, Int, Boolean, Boolean, Boolean, Int, Int, Unit> {
        ctx, len, isComplete, isFinal, hasError, errDomain, errCode ->
        val ref = ctx!!.asStableRef<RecvCtx>()
        val side = ref.get().side
        ref.dispose()
        side.onReceiveCompletion(
            ReceiveOutcome(
                timedOut = false,
                byteCount = len,
                isComplete = isComplete,
                isFinal = isFinal,
                errDomain = if (hasError) errDomain else null,
                errCode = if (hasError) errCode else null,
            ),
        )
    }

/** One end of the probe link — a raw `nw_connection_t` plus the observations taken through it. */
internal class ProbeSide(private val name: String, private val connection: nw_connection_t) {

    private val states = mutableListOf<String>()
    private val events = Channel<ReceiveOutcome>(Channel.UNLIMITED)
    private val posted = atomic(0)

    /** How many `nw_connection_receive` calls this side has posted — the in-flight-window rig counter. */
    val receivesPosted: Int get() = posted.value

    fun noteState(s: String) { states.add(s) }
    fun stateTrail(): String = states.joinToString(",").ifEmpty { "<none>" }

    /** A normal data frame on `NW_CONNECTION_DEFAULT_MESSAGE_CONTEXT` — the shape `RealNwApi.send` uses. */
    suspend fun sendData(bytes: ByteArray, ceiling: Duration): SendOutcome = send(bytes, final = false, ceiling)

    /**
     * The write-close: `NW_CONNECTION_FINAL_MESSAGE_CONTEXT` with `is_complete = true`
     * (`connection.h:561-568`). [content] may be null for a bare FIN.
     */
    suspend fun sendFinal(content: ByteArray?, ceiling: Duration): SendOutcome = send(content, final = true, ceiling)

    private suspend fun send(bytes: ByteArray?, final: Boolean, ceiling: Duration): SendOutcome {
        val sink = Channel<SendOutcome>(Channel.UNLIMITED)
        val ctx = StableRef.create(SendCtx(sink))
        if (bytes == null || bytes.isEmpty()) {
            kuilt_nw_probe_send(connection, null, 0, final, ctx.asCPointer(), probeSendCompletion)
        } else {
            // The C side copies into a dispatch_data immediately, so the pin need only span the call.
            bytes.usePinned { pinned ->
                kuilt_nw_probe_send(
                    connection, pinned.addressOf(0), bytes.size, final, ctx.asCPointer(), probeSendCompletion,
                )
            }
        }
        return withTimeoutOrNull(ceiling) { sink.receive() }
            ?: SendOutcome(timedOut = true, errDomain = null, errCode = null)
    }

    /**
     * Start the receive pump: one `nw_connection_receive` outstanding at a time, each completion
     * queued into [events] and (unless it was terminal) immediately re-armed.
     *
     * **One outstanding receive is not a stylistic choice — a probe that arms a second one while the
     * first is still pending mis-attributes its own results.** Network.framework hands each arriving
     * chunk to the *oldest* outstanding receive, so an earlier call that the test has already given
     * up waiting on still consumes the next bytes; a later call then waits for data that has already
     * been delivered elsewhere and times out. That produced a false "the write direction is closed"
     * reading in an earlier revision of this probe. `RealNwApi` keeps the same single-outstanding
     * discipline for the same reason.
     *
     * Started explicitly rather than at link formation, because
     * [bytesTheRemoteAlreadySentSurviveOurWriteClose] needs a side with **zero** receives posted.
     */
    fun startReceivePump() = armOne()

    private fun armOne() {
        posted.incrementAndGet()
        val ctx = StableRef.create(RecvCtx(this))
        kuilt_nw_probe_receive(connection, 1u, 65_536u, ctx.asCPointer(), probeReceiveCompletion)
    }

    /** Runs on the GCD queue from a pure-C block: queue the event, then re-arm unless it was terminal. */
    internal fun onReceiveCompletion(outcome: ReceiveOutcome) {
        events.trySend(outcome)
        if (outcome.errDomain == null && !outcome.readClose) armOne()
    }

    /** The next receive completion, or a timed-out marker. A timeout is itself an observation. */
    suspend fun next(ceiling: Duration): ReceiveOutcome =
        withTimeoutOrNull(ceiling) { events.receive() } ?: ReceiveOutcome(
            timedOut = true, byteCount = 0, isComplete = false, isFinal = false, errDomain = null, errCode = null,
        )

    fun cancel() = nw_connection_cancel(connection)

    override fun toString(): String = name
}

/** A real `127.0.0.1` link — TLS-PSK or plain TCP — as an ephemeral listener plus the client that dialled it. */
internal class ProbeLink private constructor(
    private val listener: nw_listener_t,
    val client: ProbeSide,
    val server: ProbeSide,
    val formed: Boolean,
    /** Every formation step, so a link that never comes up diagnoses itself instead of just failing. */
    val formationTrail: String,
) {

    fun close() {
        client.cancel()
        server.cancel()
        nw_listener_cancel(listener)
    }

    companion object {
        /** Generous ceiling on link formation — a real TLS-PSK handshake on a loaded box. */
        private val FORM_CEILING = 20.seconds
        private const val LOOPBACK_HOST = "127.0.0.1"

        /**
         * @param tls when false, builds the same parameters with the TLS protocol disabled — the
         *   plain-TCP control arm (see [NwHalfCloseProbeTest]).
         */
        suspend fun open(roomKey: String, serviceType: String, tls: Boolean): ProbeLink {
            val psk = NwPsk.derive(roomKey, serviceType)
            val queue = dispatch_queue_create("us.tractat.kuilt.nw.probe", null)
            val trail = mutableListOf<String>()

            val portSink = Channel<Int>(Channel.UNLIMITED)
            val acceptedSink = Channel<nw_connection_t>(Channel.UNLIMITED)
            val serverReady = Channel<Boolean>(Channel.UNLIMITED)
            val clientReady = Channel<Boolean>(Channel.UNLIMITED)

            val listener = nw_listener_create(params(psk, tls)) ?: error("probe listener create failed (tls=$tls)")
            nw_listener_set_queue(listener, queue)
            nw_listener_set_state_changed_handler(listener) { state, _ ->
                when (state) {
                    nw_listener_state_ready -> portSink.trySend(nw_listener_get_port(listener).toInt())
                    nw_listener_state_failed -> { trail.add("listener-failed"); portSink.trySend(-1) }
                    else -> Unit
                }
            }
            nw_listener_set_new_connection_handler(listener) { conn -> if (conn != null) acceptedSink.trySend(conn) }
            nw_listener_start(listener)

            val port = withTimeoutOrNull(FORM_CEILING) { portSink.receive() }
            if (port == null || port <= 0) {
                trail.add("no-port(port=$port)")
                nw_listener_cancel(listener)
                error("probe listener never bound (tls=$tls): ${trail.joinToString(",")}")
            }
            trail.add("listener-ready:$port")

            val endpoint = nw_endpoint_create_host(LOOPBACK_HOST, port.toString())
            val clientConn = nw_connection_create(endpoint, params(psk, tls))
                ?: error("probe client connection create failed (tls=$tls)")
            val clientSide = ProbeSide("client", clientConn)
            nw_connection_set_queue(clientConn, queue)
            nw_connection_set_state_changed_handler(clientConn) { state, _ ->
                clientSide.noteState(stateName(state))
                when (state) {
                    nw_connection_state_ready -> clientReady.trySend(true)
                    nw_connection_state_failed -> clientReady.trySend(false)
                    else -> Unit
                }
            }
            nw_connection_start(clientConn)
            trail.add("client-started")

            val accepted = withTimeoutOrNull(FORM_CEILING) { acceptedSink.receive() }
            if (accepted == null) {
                trail.add("never-accepted/client=${clientSide.stateTrail()}")
                nw_connection_cancel(clientConn)
                nw_listener_cancel(listener)
                error("probe listener never accepted (tls=$tls): ${trail.joinToString(",")}")
            }
            trail.add("accepted")
            val serverSide = ProbeSide("server", accepted)
            nw_connection_set_queue(accepted, queue)
            nw_connection_set_state_changed_handler(accepted) { state, _ ->
                serverSide.noteState(stateName(state))
                when (state) {
                    nw_connection_state_ready -> serverReady.trySend(true)
                    nw_connection_state_failed -> serverReady.trySend(false)
                    else -> Unit
                }
            }
            nw_connection_start(accepted)

            val c = withTimeoutOrNull(FORM_CEILING) { clientReady.receive() } ?: false
            val s = withTimeoutOrNull(FORM_CEILING) { serverReady.receive() } ?: false
            trail.add("client-ready=$c/${clientSide.stateTrail()}")
            trail.add("server-ready=$s/${serverSide.stateTrail()}")
            return ProbeLink(listener, clientSide, serverSide, formed = c && s, formationTrail = trail.joinToString(","))
        }

        private fun stateName(state: nw_connection_state_t?): String = when (state) {
            nw_connection_state_ready -> "ready"
            nw_connection_state_failed -> "failed"
            nw_connection_state_cancelled -> "cancelled"
            nw_connection_state_preparing -> "preparing"
            nw_connection_state_waiting -> "waiting"
            else -> "other($state)"
        }

        /** Built in C (`nwprobe.def`) so the TLS-disable and final-context macros are Apple's own. */
        private fun params(psk: NwPskMaterial, tls: Boolean): nw_parameters_t =
            psk.psk.usePinned { key ->
                psk.identity.usePinned { id ->
                    kuilt_nw_probe_params(
                        key.addressOf(0), psk.psk.size, id.addressOf(0), psk.identity.size, tls,
                    )
                }
            }
    }
}
