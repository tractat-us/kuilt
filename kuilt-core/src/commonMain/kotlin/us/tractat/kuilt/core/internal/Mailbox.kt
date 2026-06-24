package us.tractat.kuilt.core.internal

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
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
            Overflow.FAIL ->
                if (!channel.trySend(frame).isSuccess) {
                    throw FrameOverflow("delivery buffer full (capacity=${policy.capacity})")
                }
            // SUSPEND suspends; DROP_* never suspend (the channel handles overflow).
            else -> channel.send(frame)
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
    Overflow.FAIL -> BufferOverflow.SUSPEND // unreachable; FAIL handled in deliver()
}
