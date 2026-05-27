@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("FunctionName")

package us.tractat.kuilt.webrtc.internal

import kotlin.JsFun
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.Promise

// ── External interface declarations ───────────────────────────────────────────

internal external interface RTCConfiguration : JsAny {
    var iceServers: JsArray<RTCIceServer>
    var iceTransportPolicy: String // "all" | "relay"
}

internal external interface RTCIceServer : JsAny {
    var urls: String
    var username: String?
    var credential: String?
}

internal external interface RTCSessionDescriptionInit : JsAny {
    var type: String // "offer" | "answer"
    var sdp: String
}

internal external interface RTCIceCandidateInit : JsAny {
    var candidate: String
    var sdpMid: String?
    var sdpMLineIndex: Int?
}

internal external interface RTCDataChannelInit : JsAny {
    var ordered: Boolean?
    var maxRetransmits: Int?
    var maxPacketLifeTime: Int?
    var negotiated: Boolean?
    var protocol: String?
}

internal external class RTCDataChannel : JsAny {
    val readyState: String // "connecting" | "open" | "closing" | "closed"
    var binaryType: String // set to "arraybuffer"

    fun send(data: JsAny)

    fun close()
}

internal external class RTCIceCandidate(
    init: RTCIceCandidateInit,
) : JsAny

internal external class RTCSessionDescription(
    init: RTCSessionDescriptionInit,
) : JsAny

internal external class RTCPeerConnection(
    configuration: RTCConfiguration,
) : JsAny {
    val connectionState: String
    val iceConnectionState: String

    fun createDataChannel(
        label: String,
        init: RTCDataChannelInit,
    ): RTCDataChannel

    fun createOffer(): Promise<RTCSessionDescription>

    fun createAnswer(): Promise<RTCSessionDescription>

    fun setLocalDescription(desc: RTCSessionDescription): Promise<JsAny?>

    fun setRemoteDescription(desc: RTCSessionDescription): Promise<JsAny?>

    fun addIceCandidate(candidate: RTCIceCandidate): Promise<JsAny?>

    fun close()
}

// ── JS object literal factory helpers ─────────────────────────────────────────
// These construct plain JS object literals that satisfy the external interface shapes.
// js() must be the only expression in a top-level function body.

internal fun rtcConfiguration(): RTCConfiguration = js("({ iceServers: [], iceTransportPolicy: 'all' })")

internal fun rtcIceServer(): RTCIceServer = js("({ urls: '', username: null, credential: null })")

internal fun rtcSessionDescriptionInit(): RTCSessionDescriptionInit = js("({ type: '', sdp: '' })")

internal fun rtcIceCandidateInit(): RTCIceCandidateInit = js("({ candidate: '', sdpMid: null, sdpMLineIndex: null })")

internal fun rtcDataChannelInit(): RTCDataChannelInit = js("({ ordered: true })")

// ── ArrayBuffer <-> ByteArray helpers ─────────────────────────────────────────
// Kotlin/Wasm JS interop only accepts external, primitive, string, and function
// types as parameters — not ByteArray. We bridge through a JS Uint8Array that
// Kotlin allocates by size, then fills byte-by-byte via primitive setters.

/** Allocate a new JS Uint8Array of [length] bytes. */
@JsFun("(length) => new Uint8Array(length)")
internal external fun newUint8Array(length: Int): JsAny

/** Write [byte] at [index] into a Uint8Array [view]. */
@JsFun("(view, index, byte) => { view[index] = byte; }")
internal external fun uint8ArraySet(
    view: JsAny,
    index: Int,
    byte: Byte,
)

/** Extract the ArrayBuffer from a Uint8Array [view]. */
@JsFun("(view) => view.buffer")
internal external fun uint8ArrayBuffer(view: JsAny): JsAny

/** Length of an ArrayBuffer [buffer] in bytes. */
@JsFun("(buffer) => buffer.byteLength")
internal external fun arrayBufferByteLength(buffer: JsAny): Int

/** Read a single byte from an ArrayBuffer [buffer] at [index]. */
@JsFun("(buffer, index) => new Int8Array(buffer)[index]")
internal external fun arrayBufferGetByte(
    buffer: JsAny,
    index: Int,
): Byte

/**
 * Convert a [ByteArray] to a JS ArrayBuffer ([JsAny]) suitable for [RTCDataChannel.send].
 *
 * Allocates a JS Uint8Array, fills it byte-by-byte via primitives (no ByteArray-to-JS
 * direct cast — that is not supported in Kotlin/Wasm), then returns the underlying buffer.
 */
internal fun ByteArray.toArrayBuffer(): JsAny {
    val view = newUint8Array(size)
    for (i in indices) uint8ArraySet(view, i, this[i])
    return uint8ArrayBuffer(view)
}

/**
 * Convert an ArrayBuffer (as [JsAny]) received via [RTCDataChannel] back to a [ByteArray].
 */
internal fun JsAny.toByteArray(): ByteArray {
    val length = arrayBufferByteLength(this)
    return ByteArray(length) { i -> arrayBufferGetByte(this, i) }
}

// ── RTCIceCandidate field accessors ───────────────────────────────────────────
// js() is not supported in functions with extension receivers in Kotlin/Wasm.
// @JsFun on free (non-extension) external functions passes the receiver as a parameter.

@JsFun("(cand) => cand.candidate")
internal external fun candidateString(cand: RTCIceCandidate): String

@JsFun("(cand) => (cand.sdpMid == null ? null : cand.sdpMid)")
internal external fun sdpMidString(cand: RTCIceCandidate): String?

@JsFun("(cand) => (cand.sdpMLineIndex == null ? null : cand.sdpMLineIndex)")
internal external fun sdpMLineIndexInt(cand: RTCIceCandidate): Int?

// ── RTCSessionDescription field accessor ──────────────────────────────────────

@JsFun("(desc) => desc.sdp")
internal external fun sdpString(desc: RTCSessionDescription): String

// ── RTCPeerConnection event-handler bridges ───────────────────────────────────
// Direct assignment of Kotlin lambdas to external-class event-handler vars is
// unreliable in Kotlin/Wasm: the external-interface parameter (e.g.
// RTCPeerConnectionIceEvent) is a JS object and the Kotlin/Wasm → JS boundary
// does not guarantee a stable function adapter for nullable function-typed vars.
//
// The proven pattern (used by WebSocketSignalingChannel) is to go through
// @JsFun-annotated external bridge functions: the JS lambda receives the raw
// event, extracts whatever is needed as primitives / JsAny, and calls the
// Kotlin handler. The handler only receives types that cross the boundary cleanly.

/**
 * Installs [handler] as the `onicecandidate` callback on [pc].
 *
 * When a candidate is available the handler receives the raw `RTCIceCandidate`
 * JS object (or `null` when gathering is complete). When the candidate is null
 * the handler is not called — end-of-candidates is signaled by passing
 * `null` and the bridge drops it.
 */
@JsFun(
    "(pc, handler) => { pc.onicecandidate = (e) => { if (e.candidate) handler(e.candidate); }; }",
)
internal external fun pcSetOnIceCandidate(
    pc: RTCPeerConnection,
    handler: (RTCIceCandidate) -> Unit,
)

/**
 * Installs [handler] as the `oniceconnectionstatechange` callback on [pc].
 * The handler receives the connection state string directly.
 */
@JsFun(
    "(pc, handler) => { pc.oniceconnectionstatechange = () => handler(pc.iceConnectionState); }",
)
internal external fun pcSetOnIceConnectionStateChange(
    pc: RTCPeerConnection,
    handler: (String) -> Unit,
)

/**
 * Installs [handler] as the `ondatachannel` callback on [pc].
 * The handler receives the new [RTCDataChannel] directly.
 */
@JsFun("(pc, handler) => { pc.ondatachannel = (e) => handler(e.channel); }")
internal external fun pcSetOnDataChannel(
    pc: RTCPeerConnection,
    handler: (RTCDataChannel) -> Unit,
)

// ── RTCDataChannel event-handler bridges ─────────────────────────────────────

/** Installs [handler] as the `onopen` callback on [dc]. No event data needed. */
@JsFun("(dc, handler) => { dc.onopen = () => handler(); }")
internal external fun dcSetOnOpen(
    dc: RTCDataChannel,
    handler: () -> Unit,
)

/** Installs [handler] as the `onclose` callback on [dc]. No event data needed. */
@JsFun("(dc, handler) => { dc.onclose = () => handler(); }")
internal external fun dcSetOnClose(
    dc: RTCDataChannel,
    handler: () -> Unit,
)

/**
 * Installs [handler] as the `onerror` callback on [dc].
 * The handler receives the error message string (or "unknown" if unparseable).
 */
@JsFun("(dc, handler) => { dc.onerror = (e) => handler(e && e.message ? e.message : 'unknown'); }")
internal external fun dcSetOnError(
    dc: RTCDataChannel,
    handler: (String) -> Unit,
)

/**
 * Installs [handler] as the `onmessage` callback on [dc].
 * The handler receives the raw `ArrayBuffer` payload as [JsAny].
 */
@JsFun("(dc, handler) => { dc.onmessage = (e) => handler(e.data); }")
internal external fun dcSetOnMessage(
    dc: RTCDataChannel,
    handler: (JsAny) -> Unit,
)
