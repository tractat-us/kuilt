package us.tractat.kuilt.scale

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch

/**
 * A [Seam] wrapper that counts broadcasts, sendTos, and bytes in/out.
 *
 * Thread-safe: counters use atomicfu atomics. The [incoming] flow is
 * wrapped with [Flow.onEach] to count received frames transparently.
 * All [Seam] contract guarantees of [delegate] are preserved.
 *
 * Call [snapshot] at any point to read the current [SeamMetrics].
 */
public class MeteredSeam(private val delegate: Seam) : Seam {

    private val _broadcasts = atomic(0L)
    private val _sendTos = atomic(0L)
    private val _bytesOut = atomic(0L)
    private val _framesIn = atomic(0L)
    private val _bytesIn = atomic(0L)

    override val selfId: PeerId get() = delegate.selfId
    override val peers: StateFlow<Set<PeerId>> get() = delegate.peers
    override val state: StateFlow<SeamState> get() = delegate.state
    override val plies: StateFlow<Map<PlyId, SeamState>> get() = delegate.plies

    override val incoming: Flow<Swatch> = delegate.incoming.onEach { swatch ->
        _framesIn.incrementAndGet()
        _bytesIn.addAndGet(swatch.payloadSize.toLong())
    }

    override suspend fun broadcast(payload: ByteArray) {
        _broadcasts.incrementAndGet()
        _bytesOut.addAndGet(payload.size.toLong())
        delegate.broadcast(payload)
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        _sendTos.incrementAndGet()
        _bytesOut.addAndGet(payload.size.toLong())
        delegate.sendTo(peer, payload)
    }

    override suspend fun close(reason: CloseReason): Unit = delegate.close(reason)

    /** Current metrics snapshot. Consistent on each individual counter read (not cross-counter atomic). */
    public fun snapshot(): SeamMetrics = SeamMetrics(
        broadcasts = _broadcasts.value,
        sendTos = _sendTos.value,
        bytesOut = _bytesOut.value,
        framesIn = _framesIn.value,
        bytesIn = _bytesIn.value,
    )
}
