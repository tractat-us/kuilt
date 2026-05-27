package us.tractat.kuilt.webrtc

import kotlinx.coroutines.channels.Channel
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

    override suspend fun send(message: SignalingMessage) {
        outbound.send(message)
    }

    override suspend fun close() {
        outbound.close()
    }
}
