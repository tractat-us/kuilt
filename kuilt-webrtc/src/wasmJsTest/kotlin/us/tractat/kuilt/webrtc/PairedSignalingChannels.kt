package us.tractat.kuilt.webrtc

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * In-memory paired signaling channels. Messages [send]'d on one side
 * appear in [incoming] on the other.
 *
 * Each [open] call pre-seeds the inbound channel with a [SignalingMessage.Role]
 * frame matching the server-side B0 role assignment: the "host" side gets
 * `Role(host = true)`, the "joiner" side gets `Role(host = false)`.
 *
 * Use [PairedSignalingChannels.pair] to create the two ends.
 */
class PairedSignalingChannels private constructor(
    private val outbound: Channel<SignalingMessage>,
    private val inbound: Channel<SignalingMessage>,
    private val isHost: Boolean,
) : SignalingChannel {
    override suspend fun open(room: String): SignalingSession {
        // Inject the server-assigned role frame as the first inbound message,
        // mirroring what the real relay sends in SignalingRoutes.
        inbound.send(SignalingMessage.Role(host = isHost))
        return FakeSession(outbound, inbound)
    }

    companion object {
        /**
         * Create a paired host/joiner channel pair. The first element is the
         * host channel ([Role.host] = true), the second is the joiner channel.
         */
        fun pair(): Pair<PairedSignalingChannels, PairedSignalingChannels> {
            val aToB = Channel<SignalingMessage>(Channel.UNLIMITED)
            val bToA = Channel<SignalingMessage>(Channel.UNLIMITED)
            return PairedSignalingChannels(aToB, bToA, isHost = true) to
                PairedSignalingChannels(bToA, aToB, isHost = false)
        }
    }
}

private class FakeSession(
    private val outbound: Channel<SignalingMessage>,
    private val inbound: Channel<SignalingMessage>,
) : SignalingSession {
    override val incoming: Flow<SignalingMessage> = inbound.receiveAsFlow()

    /**
     * Send a message. Silently no-ops if the outbound channel is already closed.
     *
     * Both sides call [HandshakeRunner]'s `finally` block concurrently after the
     * data channel opens — each calls [close], which closes its own [outbound].
     * There is a race window where one side's ICE-candidate sender (cancelled in
     * `finally` just before `close()`) loses the race and tries to send after the
     * partner has already closed the channel. Swallowing here keeps that race benign
     * in the fake; the real relay handles it via normal WebSocket close semantics.
     */
    override suspend fun send(message: SignalingMessage) {
        try {
            outbound.send(message)
        } catch (_: ClosedSendChannelException) {
            // Partner closed before we could send; safe to drop in the fake.
        }
    }

    /**
     * Close both directions so the paired fake mirrors real close semantics:
     * - Closing [outbound] terminates the remote's [incoming] collector.
     * - Closing [inbound] terminates the local [incoming] collector in
     *   [HandshakeRunner]'s `inbound` coroutine, allowing [coroutineScope] to
     *   complete cleanly regardless of whether the remote has sent a reply.
     *
     * This is correct for any test shape: with a concurrent joiner (the hardened
     * suite) the joiner's own [close] call closes the other direction; closing
     * both here is idempotent (Channel.close() is safe to call multiple times).
     */
    override suspend fun close() {
        outbound.close()
        inbound.close()
    }
}
