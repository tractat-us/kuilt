package us.tractat.kuilt.nw

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.PeerId

/**
 * Drives the per-connection identity handshake for one already-open [NwApi]
 * connection. Suspends until both peers' stable [PeerId]s have been exchanged,
 * then returns the resolved [NwLink] — or throws on close-before-identity or
 * timeout.
 *
 * Unlike the Nearby fabric's request→initiate→accept→result dance, an [NwApi]
 * connection is **already open** when this machine runs (Network.framework has
 * completed its own transport handshake and emitted `connectionOpened`). So this
 * machine has no kickoff step of its own — it only exchanges identity over the
 * open connection: send our [PeerId] as the first framed message, and resolve
 * once the remote's [PeerId] arrives as the first decoded frame.
 *
 * ## Subscribe-before-trigger
 * The event flows on [NwApi] are hot with no replay. All collectors are launched
 * with [CoroutineStart.UNDISPATCHED] so they have subscribed **before** [run]
 * sends the local identity and invokes [trigger]. Without this, the remote's
 * identity frame (or a racing close) could be emitted before the collector
 * subscribes and be lost — the classic `MutableSharedFlow` emit-before-subscribe
 * race that hangs the handshake under `runTest`'s `StandardTestDispatcher`.
 *
 * ## Handoff to [NwSeam] (Task 2.5)
 * By the transport's handoff invariant, **no data frames are sent on a connection
 * until BOTH peers' identities have resolved** — a `Seam` only broadcasts once it
 * is Woven. So the first frame [decode]d off this connection is unambiguously the
 * remote identity, and the shared [framer] (carrying any buffered remainder) can
 * be handed to [NwSeam]'s long-lived per-connection receive loop with no data
 * loss. This machine drives only the identity phase; [NwSeam] owns the durable
 * receive loop.
 *
 * @param selfId this peer's stable identity, sent as the first framed message.
 * @param api the transport moving raw bytes over the open connection.
 * @param framer the single-reader decoder for this connection; its buffered
 *   remainder is retained for [NwSeam] after the handshake resolves.
 * @param timeoutMs upper bound on the whole identity exchange.
 */
internal class NwConnectMachine(
    private val selfId: PeerId,
    private val api: NwApi,
    private val framer: NwFramer,
    private val timeoutMs: Long = 30_000L,
) {

    /**
     * Subscribe the handshake collectors (synchronously, via UNDISPATCHED), send
     * our identity as the first framed message, invoke [trigger], then suspend
     * until the remote identity resolves the link or it fails / times out.
     *
     * [trigger] is a no-op extension point mirroring the Nearby machine's
     * role-specific kickoff — an [NwApi] connection is already open, so nothing
     * needs to be kicked off here, but the hook is kept for symmetry and future use.
     */
    suspend fun run(
        scope: CoroutineScope,
        connectionId: NwConnectionId,
        trigger: suspend () -> Unit = {},
    ): NwLink =
        withTimeout(timeoutMs) {
            val resolved = CompletableDeferred<NwLink>()
            val jobs = launchListeners(scope, connectionId, resolved)
            try {
                api.send(connectionId, encodeFrame(selfId.value.encodeToByteArray()))
                trigger()
                resolved.await()
            } finally {
                jobs.forEach { it.cancel() }
            }
        }

    private fun launchListeners(
        scope: CoroutineScope,
        connectionId: NwConnectionId,
        resolved: CompletableDeferred<NwLink>,
    ): List<Job> =
        listOf(
            // A close before identity exchange completes fails the handshake.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                api.connectionClosed.collect { event ->
                    if (event.connectionId != connectionId) return@collect
                    if (resolved.isCompleted) return@collect
                    resolved.completeExceptionally(
                        NwHandshakeException(
                            "connection ${connectionId.value} closed before identity exchange",
                        ),
                    )
                }
            },
            // The FIRST decoded frame on our connection is the remote identity.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                api.bytesReceived.collect { event ->
                    if (event.connectionId != connectionId) return@collect
                    if (resolved.isCompleted) return@collect
                    val firstFrame = framer.decode(event.bytes).firstOrNull() ?: return@collect
                    resolved.complete(NwLink(connectionId, PeerId(firstFrame.decodeToString())))
                }
            },
        )
}

/**
 * A resolved identity handshake: [connectionId] now carries a known peer whose
 * stable identity is [remotePeerId]. `NwSeam` (Task 2.5) uses [remotePeerId] to
 * run the duplicate-connection dedup tie-break.
 */
internal data class NwLink(
    val connectionId: NwConnectionId,
    val remotePeerId: PeerId,
)

/** Thrown when the identity handshake fails (e.g. the connection closes first) or times out. */
internal class NwHandshakeException(message: String) : Exception(message)
