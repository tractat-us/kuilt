package us.tractat.kuilt.core.internal

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.FrameOverflow
import us.tractat.kuilt.core.Overflow
import us.tractat.kuilt.core.Swatch

/**
 * The one sanctioned per-receiver inbound buffer for in-process fabrics. Always bounded; its
 * overflow behaviour is the injected [DeliveryPolicy]. `SUSPEND`/`DROP_*` delegate to the
 * channel's native [BufferOverflow]; `FAIL` is enforced explicitly. Single-collection FIFO
 * (collect [incoming] once).
 */
internal class Mailbox(private val policy: DeliveryPolicy) {
    private val channel: Channel<Swatch> =
        if (policy.overflow == Overflow.FAIL) {
            Channel(capacity = policy.capacity, onBufferOverflow = BufferOverflow.SUSPEND)
        } else {
            Channel(capacity = policy.capacity, onBufferOverflow = policy.overflow.toBufferOverflow())
        }

    val incoming: Flow<Swatch> = channel.receiveAsFlow()

    suspend fun deliver(frame: Swatch) {
        when (policy.overflow) {
            Overflow.FAIL -> {
                val result = channel.trySend(frame)
                // A closed channel means the receiver went away (left the mesh) — a drop, like
                // any departed peer. Only a genuinely full buffer is the FAIL signal.
                if (result.isFailure && !result.isClosed) {
                    throw FrameOverflow("delivery buffer full (capacity=${policy.capacity})")
                }
            }
            // SUSPEND suspends; DROP_* never suspend (the channel handles overflow). A receiver
            // that closes concurrently races the send — it is gone, so the frame is dropped (as
            // the pre-bounded `trySend` did) rather than surfaced to the broadcasting caller.
            else -> try {
                channel.send(frame)
            } catch (e: ClosedSendChannelException) {
                // receiver closed concurrently — drop
            }
        }
    }

    fun close() {
        channel.close()
    }
}

private fun Overflow.toBufferOverflow(): BufferOverflow = when (this) {
    Overflow.SUSPEND -> BufferOverflow.SUSPEND
    Overflow.DROP_OLDEST -> BufferOverflow.DROP_OLDEST
    Overflow.DROP_LATEST -> BufferOverflow.DROP_LATEST
    Overflow.FAIL -> error("FAIL is enforced in deliver(); toBufferOverflow() must not be called for it")
}
