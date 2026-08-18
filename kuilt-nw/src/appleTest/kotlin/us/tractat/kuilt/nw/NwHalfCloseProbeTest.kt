@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress("ForbiddenImport") // real-network loopback platform probe — a real Network.framework socket needs a real IO dispatcher; there is no virtual-time option here

package us.tractat.kuilt.nw

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real-network loopback platform probe — a real Network.framework socket needs a real IO dispatcher; there is no virtual-time option here
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.Network.NW_PARAMETERS_DEFAULT_CONFIGURATION
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_create
import platform.Network.nw_connection_send
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
import platform.Network.nw_content_context_create
import platform.Network.nw_content_context_set_is_final
import platform.Network.nw_endpoint_create_host
import platform.Network.nw_error_get_error_code
import platform.Network.nw_error_get_error_domain
import platform.Network.nw_listener_cancel
import platform.Network.nw_listener_create
import platform.Network.nw_listener_get_port
import platform.Network.nw_listener_set_new_connection_handler
import platform.Network.nw_listener_set_queue
import platform.Network.nw_listener_set_state_changed_handler
import platform.Network.nw_listener_start
import platform.Network.nw_listener_state_ready
import platform.Network.nw_listener_t
import platform.Network.nw_parameters_create_secure_tcp
import platform.Network.nw_parameters_set_include_peer_to_peer
import platform.Network.nw_protocol_options_t
import platform.Network.nw_tls_copy_sec_protocol_options
import platform.Security.sec_protocol_options_add_pre_shared_key
import platform.darwin.dispatch_data_create
import platform.darwin.dispatch_data_t
import platform.darwin.dispatch_queue_create
import us.tractat.kuilt.nw.cinterop.kuilt_nw_connection_receive
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
 * until remote EOF. D exists only if the platform preserves read-after-write-close *on this
 * binding*.
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
 *    acknowledged the data."* Hence every claim below is asserted from the **receiving** end.
 *
 * And from `Network.framework/Headers/content_context.h`:
 *  - **`:93-105`** — `nw_content_context_set_is_final` is the non-convenience spelling of the same
 *    write-close, and is what this probe uses: the `NW_CONNECTION_*_CONTEXT` globals mis-bridge
 *    under Kotlin/Native, as `RealNwApi.send` already records.
 *
 * So the **API** is a half-close API by construction. The doubt is the layer above TCP.
 *
 * ## Why TLS is the doubt, not TCP
 * This binding runs TLS-PSK (`RealNwApi.secureParams` →
 * `sec_protocol_options_add_pre_shared_key`), Apple's **TLS-1.2-era external PSK** path (see
 * [NwPskNulIdentityTest] for the RFC 4279 §5.1 analysis that pins it there). TLS 1.2
 * (RFC 5246 §7.2.1) requires a peer receiving `close_notify` to reply with its own and close
 * immediately — a *full* close, with pending writes discarded. Only TLS 1.3 (RFC 8446 §6.1) made
 * `close_notify` affect the write side alone. A stack that collapses write-close into session-close
 * would make the header's promise unreachable here.
 *
 * ## Rigging: the positive control is the load-bearing part
 * A probe that shows "bytes still arrived after I half-closed" proves nothing if the half-close
 * never happened. [writeCloseActuallyFiresOnThisBinding] is therefore the gate: it proves the
 * write-close took effect **from the far end** (the remote observes a read-close) and **locally**
 * (a subsequent send is refused). Every other assertion here is only meaningful because that one
 * holds.
 *
 * ## Scope
 * Loopback (`127.0.0.1`, `includePeerToPeer(false)`) is a valid oracle for the *framework + TLS*
 * question, because half-close is a property of the protocol stack rather than the link layer. It
 * does **not** cover the AWDL path a real two-device session uses.
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

        /**
         * Hard real-time ceiling on any single await. A probe that hangs is useless: every step
         * either produces an observation or times out into one.
         */
        val STEP_CEILING: Duration = 5.seconds

        /** Settle time for a one-way event on loopback — generous; loopback RTT is microseconds. */
        val SETTLE: Duration = 250.milliseconds
    }

    private val links = mutableListOf<ProbeLink>()

    @AfterTest
    fun tearDown() {
        links.forEach { it.close() }
        links.clear()
    }

    private suspend fun newLink(): ProbeLink {
        val link = ProbeLink.open(ROOM_KEY, SERVICE_TYPE)
        links += link
        assertTrue(link.formed, "probe link must reach ready on both ends — otherwise every result below is vacuous")
        return link
    }

    /**
     * **THE POSITIVE CONTROL.** Proves a write-close on this binding actually happens, in the only
     * two ways that are observable: the remote sees a **read-close** on its own receive, and the
     * local end can no longer send.
     *
     * Both arms matter. The remote-side arm rules out "the FIN never left" (which would make every
     * survival result below green by absence); the local-send arm rules out "the far end closed for
     * an unrelated reason".
     *
     * The remote's receive is armed **before** the half-close, so a read-close cannot be missed for
     * ordering reasons.
     */
    @Test
    fun writeCloseActuallyFiresOnThisBinding() = runBlocking {
        withContext(Dispatchers.Default) {
            val link = newLink()

            // Arm the remote's receive FIRST — a read-close delivered before we ask for it must not
            // be lost to ordering.
            val remoteRead = link.server.arm()

            // The half-close: a final context marked complete, carrying a marker so the remote can
            // distinguish "read-close with content" from "read-close alone".
            val marker = ByteArray(MARKER_BYTES) { 0x5A }
            val halfClose = link.client.sendFinal(marker, STEP_CEILING)

            val observed = link.server.await(remoteRead, STEP_CEILING)
            // A read-close may arrive as `isComplete` on the content-bearing completion, or as a
            // separate zero-byte completion right after it. Take a second look if the first carried
            // content without the flag.
            val followUp = if (!observed.isComplete && observed.byteCount > 0) {
                link.server.await(link.server.arm(), STEP_CEILING)
            } else {
                null
            }

            // Local arm: after a write-close the sending side is closed, so a further send must be
            // refused rather than silently accepted.
            val afterClose = link.client.sendData(ByteArray(4), STEP_CEILING)

            println(
                """
                |=== #2457 probe: writeCloseActuallyFires ===
                |half-close send        : $halfClose
                |remote first receive   : $observed
                |remote follow-up       : ${followUp ?: "<not needed>"}
                |local send after close : $afterClose
                |client states          : ${link.client.stateTrail()}
                |server states          : ${link.server.stateTrail()}
                """.trimMargin(),
            )

            val remoteSawReadClose = observed.isComplete || (followUp?.isComplete == true) ||
                (followUp?.errDomain != null)
            assertAll(
                { assertTrue(halfClose.ok, "the API must accept a final+complete send: $halfClose") },
                {
                    assertEquals(
                        MARKER_BYTES, observed.byteCount,
                        "the final message's content must reach the remote (delivery proven from the RECEIVING end)",
                    )
                },
                {
                    assertTrue(
                        remoteSawReadClose,
                        "POSITIVE CONTROL: the remote must observe a read-close (FIN) after our write-close — " +
                            "without it, every 'survived the half-close' result in this class is green by absence. " +
                            "first=$observed followUp=$followUp",
                    )
                },
                {
                    assertFalse(
                        afterClose.ok,
                        "POSITIVE CONTROL: the local sending direction must be closed — a send accepted " +
                            "after a write-close means the write-close did not take effect. got $afterClose",
                    )
                },
            )
        }
    }

    /**
     * **THE MONEY QUESTION for #2457.** The remote hands bytes to the transport; the local end then
     * write-closes *before* consuming them; do those bytes still arrive?
     *
     * This is the exact shape of the 182-byte loss in #2425 — a peer had already written into a
     * connection that the other end then tore down. Option D replaces that teardown with a
     * half-close, and only works if the answer here is yes.
     *
     * **The rig, and how it asserts it fired:**
     *  1. the remote sends [WINDOW_BYTES] and we await *its* send completion — the bytes are
     *     enqueued/sent (`connection.h:594-599`), and are no longer the writer's to retry;
     *  2. the local end has posted **zero** receives, asserted explicitly, so nothing has consumed
     *     them — the window is genuinely open;
     *  3. only then does the local end write-close, and only after that does it post its first
     *     receive.
     */
    @Test
    fun bytesTheRemoteAlreadySentSurviveOurWriteClose() = runBlocking {
        withContext(Dispatchers.Default) {
            val link = newLink()
            val payload = ByteArray(WINDOW_BYTES) { (it and 0x7F).toByte() }

            // (1) the remote's write is handed off.
            val remoteWrite = link.server.sendData(payload, STEP_CEILING)
            // Let the bytes reach the local transport's buffer without being consumed.
            delay(SETTLE)

            // (2) rig assertion — nothing has consumed them.
            val postedBefore = link.client.receivesPosted

            // (3) NOW write-close, then read.
            val halfClose = link.client.sendFinal(null, STEP_CEILING)
            val delivered = link.client.await(link.client.arm(), STEP_CEILING)

            println(
                """
                |=== #2457 probe: bytesInFlightSurviveWriteClose ===
                |remote write ($WINDOW_BYTES B) : $remoteWrite
                |receives posted before close   : $postedBefore
                |half-close send                : $halfClose
                |local receive after close      : $delivered
                |client states                  : ${link.client.stateTrail()}
                """.trimMargin(),
            )

            assertAll(
                { assertTrue(remoteWrite.ok, "the remote's write must be handed off: $remoteWrite") },
                {
                    assertEquals(
                        0, postedBefore,
                        "RIG: the in-flight window must be open — the local end must not have consumed " +
                            "the bytes before write-closing, or this test proves nothing",
                    )
                },
                { assertTrue(halfClose.ok, "the API must accept the write-close: $halfClose") },
                {
                    assertEquals(
                        WINDOW_BYTES, delivered.byteCount,
                        "THE #2457 QUESTION: bytes the remote had already handed to the transport must " +
                            "survive our write-close and still be readable. got $delivered",
                    )
                },
            )
        }
    }

    /**
     * The other half of a drain: after the local end write-closes, the remote must be able to keep
     * **writing**, and those writes must reach us. If receiving our `close_notify` failed the
     * remote's connection — the TLS 1.2 behaviour RFC 5246 §7.2.1 prescribes — D is dead, because
     * the peer still writing into the losing link would lose those frames instead.
     *
     * Asserted from the **receiving** end: a clean send completion on the remote proves only that
     * the call was accepted (`connection.h:594-599`).
     */
    @Test
    fun remoteCanStillWriteToUsAfterOurWriteClose() = runBlocking {
        withContext(Dispatchers.Default) {
            val link = newLink()

            val halfClose = link.client.sendFinal(null, STEP_CEILING)
            delay(SETTLE) // let the FIN land before the remote writes

            val payload = ByteArray(WINDOW_BYTES) { (it and 0x7F).toByte() }
            val remoteWrite = link.server.sendData(payload, STEP_CEILING)
            val delivered = link.client.await(link.client.arm(), STEP_CEILING)

            println(
                """
                |=== #2457 probe: remoteCanStillWriteAfterOurWriteClose ===
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
                        "a write the remote issues AFTER receiving our FIN must still reach us — " +
                            "otherwise the peer that has not yet run its own dedup loses those frames. got $delivered",
                    )
                },
            )
        }
    }
}

// ── raw Network.framework probe harness ────────────────────────────────────────────────────────

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
    val errDomain: Int?,
    val errCode: Int?,
) {
    override fun toString(): String = when {
        timedOut -> "TIMED-OUT (no completion within ceiling)"
        errDomain != null -> "ERROR domain=$errDomain code=$errCode bytes=$byteCount isComplete=$isComplete"
        else -> "DELIVERED bytes=$byteCount isComplete=$isComplete"
    }
}

private class RecvCtx(val sink: Channel<ReceiveOutcome>)

/**
 * The receive completion, routed through the pure-C block in `nwshim.def` exactly as
 * `RealNwApi.receiveLoop` does (#1516) — a Kotlin-lambda-bridged Obj-C block on the receive path
 * intermittently aborts the process under load.
 */
private val probeReceiveCompletion =
    staticCFunction<COpaquePointer?, COpaquePointer?, Int, Boolean, Boolean, Int, Int, Unit> {
        ctx, bytes, len, isComplete, hasError, errDomain, errCode ->
        val ref = ctx!!.asStableRef<RecvCtx>()
        val rc = ref.get()
        ref.dispose()
        val count = if (bytes != null && len > 0) bytes.reinterpret<ByteVar>().readBytes(len).size else 0
        rc.sink.trySend(
            ReceiveOutcome(
                timedOut = false,
                byteCount = count,
                isComplete = isComplete,
                errDomain = if (hasError) errDomain else null,
                errCode = if (hasError) errCode else null,
            ),
        )
    }

/** One end of the probe link — a raw `nw_connection_t` plus the observations taken through it. */
internal class ProbeSide(private val name: String, private val connection: nw_connection_t) {

    private val states = mutableListOf<String>()

    /** How many `nw_connection_receive` calls this side has posted — the in-flight-window rig counter. */
    var receivesPosted: Int = 0
        private set

    fun noteState(s: String) { states.add(s) }
    fun stateTrail(): String = states.joinToString(",").ifEmpty { "<none>" }

    /** A normal data frame on a **non-final** context — the shape `RealNwApi.send` uses today. */
    suspend fun sendData(bytes: ByteArray, ceiling: Duration): SendOutcome = send(bytes, final = false, ceiling)

    /**
     * The write-close: a **final** context marked complete (`connection.h:561-568`,
     * `content_context.h:93-105`). [content] may be null for a bare FIN.
     */
    suspend fun sendFinal(content: ByteArray?, ceiling: Duration): SendOutcome = send(content, final = true, ceiling)

    private suspend fun send(bytes: ByteArray?, final: Boolean, ceiling: Duration): SendOutcome {
        val sink = Channel<SendOutcome>(Channel.UNLIMITED)
        val context = nw_content_context_create(if (final) "kuilt-final" else "kuilt")
        if (final) nw_content_context_set_is_final(context, true)
        nw_connection_send(connection, bytes?.let { toDispatchData(it) }, context, true) { error ->
            if (error != null) {
                sink.trySend(
                    SendOutcome(
                        timedOut = false,
                        errDomain = nw_error_get_error_domain(error).toInt(),
                        errCode = nw_error_get_error_code(error),
                    ),
                )
            } else {
                sink.trySend(SendOutcome(timedOut = false, errDomain = null, errCode = null))
            }
        }
        return withTimeoutOrNull(ceiling) { sink.receive() }
            ?: SendOutcome(timedOut = true, errDomain = null, errCode = null)
    }

    /**
     * Post exactly one `nw_connection_receive` **now** and return its sink. Split from [await] so a
     * test can arm the far end's read before provoking the event it is watching for — a read-close
     * delivered before anyone asked for it must not be lost to ordering.
     */
    fun arm(): Channel<ReceiveOutcome> {
        val sink = Channel<ReceiveOutcome>(Channel.UNLIMITED)
        receivesPosted++
        val ctx = StableRef.create(RecvCtx(sink))
        kuilt_nw_connection_receive(connection, 1u, 65_536u, ctx.asCPointer(), probeReceiveCompletion)
        return sink
    }

    suspend fun await(sink: Channel<ReceiveOutcome>, ceiling: Duration): ReceiveOutcome =
        withTimeoutOrNull(ceiling) { sink.receive() }
            ?: ReceiveOutcome(timedOut = true, byteCount = 0, isComplete = false, errDomain = null, errCode = null)

    fun cancel() = nw_connection_cancel(connection)

    override fun toString(): String = name
}

/** A real `127.0.0.1` TLS-PSK link: an ephemeral listener plus the client that dialled it. */
internal class ProbeLink private constructor(
    private val listener: nw_listener_t,
    val client: ProbeSide,
    val server: ProbeSide,
    val formed: Boolean,
) {

    fun close() {
        client.cancel()
        server.cancel()
        nw_listener_cancel(listener)
    }

    companion object {
        /** Generous ceiling on link formation — a real TLS-PSK handshake on a loaded box. */
        private val FORM_CEILING = 20.seconds

        suspend fun open(roomKey: String, serviceType: String): ProbeLink {
            val psk = NwPsk.derive(roomKey, serviceType)
            val queue = dispatch_queue_create("us.tractat.kuilt.nw.probe", null)

            val portSink = Channel<Int>(Channel.UNLIMITED)
            val acceptedSink = Channel<nw_connection_t>(Channel.UNLIMITED)
            val serverReady = Channel<Boolean>(Channel.UNLIMITED)
            val clientReady = Channel<Boolean>(Channel.UNLIMITED)

            val listener = nw_listener_create(secureParams(psk)) ?: error("probe listener create failed")
            nw_listener_set_queue(listener, queue)
            nw_listener_set_state_changed_handler(listener) { state, _ ->
                if (state == nw_listener_state_ready) portSink.trySend(nw_listener_get_port(listener).toInt())
            }
            nw_listener_set_new_connection_handler(listener) { conn -> if (conn != null) acceptedSink.trySend(conn) }
            nw_listener_start(listener)

            val port = withTimeoutOrNull(FORM_CEILING) { portSink.receive() }
                ?: error("probe listener never reached ready")

            val endpoint = nw_endpoint_create_host(LOOPBACK_HOST, port.toString())
            val clientConn = nw_connection_create(endpoint, secureParams(psk))
                ?: error("probe client connection create failed")
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

            val accepted = withTimeoutOrNull(FORM_CEILING) { acceptedSink.receive() }
                ?: error("probe listener never accepted a connection")
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
            return ProbeLink(listener, clientSide, serverSide, formed = c && s)
        }

        private const val LOOPBACK_HOST = "127.0.0.1"

        private fun stateName(state: nw_connection_state_t?): String = when (state) {
            nw_connection_state_ready -> "ready"
            nw_connection_state_failed -> "failed"
            nw_connection_state_cancelled -> "cancelled"
            nw_connection_state_preparing -> "preparing"
            nw_connection_state_waiting -> "waiting"
            else -> "other($state)"
        }

        /** Byte-identical TLS-PSK configuration to `RealNwApi.secureParams`, loopback mode. */
        private fun secureParams(psk: NwPskMaterial) = nw_parameters_create_secure_tcp(
            configure_tls = { options: nw_protocol_options_t? ->
                val sec = nw_tls_copy_sec_protocol_options(options)
                sec_protocol_options_add_pre_shared_key(sec, toDispatchData(psk.psk), toDispatchData(psk.identity))
            },
            configure_tcp = NW_PARAMETERS_DEFAULT_CONFIGURATION,
        ).also { nw_parameters_set_include_peer_to_peer(it, false) }
    }
}

private fun toDispatchData(bytes: ByteArray): dispatch_data_t =
    bytes.usePinned { pinned ->
        dispatch_data_create(pinned.addressOf(0), bytes.size.convert(), null, null)
    }
