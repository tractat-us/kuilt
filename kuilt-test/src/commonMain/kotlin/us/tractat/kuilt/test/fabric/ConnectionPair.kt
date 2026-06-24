package us.tractat.kuilt.test.fabric

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.FrameOverflow
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Overflow
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.handshaking
import us.tractat.kuilt.core.fabric.identified
import kotlin.coroutines.ContinuationInterceptor

/**
 * Two [Connection]s whose sends cross to each other's [Connection.incoming]. In-memory, no network.
 *
 * Each channel is bounded by [policy] (default [DeliveryPolicy.Reliable]: 256-frame capacity,
 * backpressured). There is no [Channel.UNLIMITED] path — unbounded delivery is the defect this
 * parameter exists to make unrepresentable.
 */
public fun connectionPair(
    policy: DeliveryPolicy = DeliveryPolicy.Reliable,
): Pair<Connection, Connection> {
    val aToB = boundedChannel(policy)
    val bToA = boundedChannel(policy)
    return ChannelConnection(out = aToB, inn = bToA, policy = policy) to
        ChannelConnection(out = bToA, inn = aToB, policy = policy)
}

private fun boundedChannel(policy: DeliveryPolicy): Channel<ByteArray> =
    if (policy.overflow == Overflow.FAIL) {
        Channel(capacity = policy.capacity, onBufferOverflow = BufferOverflow.SUSPEND)
    } else {
        Channel(capacity = policy.capacity, onBufferOverflow = policy.overflow.toBufferOverflow())
    }

private fun Overflow.toBufferOverflow(): BufferOverflow = when (this) {
    Overflow.SUSPEND -> BufferOverflow.SUSPEND
    Overflow.DROP_OLDEST -> BufferOverflow.DROP_OLDEST
    Overflow.DROP_LATEST -> BufferOverflow.DROP_LATEST
    Overflow.FAIL -> error("FAIL is enforced in send(); toBufferOverflow() must not be called for it")
}

private class ChannelConnection(
    private val out: Channel<ByteArray>,
    private val inn: Channel<ByteArray>,
    private val policy: DeliveryPolicy,
) : Connection {
    override suspend fun send(frame: ByteArray) {
        when (policy.overflow) {
            Overflow.FAIL -> {
                val result = out.trySend(frame)
                if (result.isFailure && !result.isClosed) {
                    throw FrameOverflow("delivery buffer full (capacity=${policy.capacity})")
                }
            }
            else -> try {
                out.send(frame)
            } catch (_: ClosedSendChannelException) {
                // receiver closed concurrently — drop, matching best-effort fabric semantics
            }
        }
    }

    override val incoming: Flow<ByteArray> = inn.receiveAsFlow()

    override suspend fun close() { out.close() }
}

/**
 * A host/joiner Loom pair wired by one in-memory [connectionPair]: host weaves an
 * `identified` seam over one end, joiner over the other. For driving
 * `SeamConformanceSuite` against the LinkSeam primitive.
 */
public fun identifiedLoomPair(
    policy: DeliveryPolicy = DeliveryPolicy.Reliable,
): Pair<Loom, Loom> {
    val (hostConnection, joinerConnection) = connectionPair(policy)
    val host = ConnectionLoom(PeerId("host"), PeerId("joiner"), hostConnection)
    val joiner = ConnectionLoom(PeerId("joiner"), PeerId("host"), joinerConnection)
    return host to joiner
}

private class ConnectionLoom(
    private val self: PeerId,
    private val remote: PeerId,
    private val conn: Connection,
) : Loom {
    override suspend fun weave(rendezvous: Rendezvous): Seam =
        identified(conn, self, remote, currentCoroutineContext()[ContinuationInterceptor]!!)
}

/**
 * A host/joiner Loom pair wired by one in-memory [connectionPair]: each end weaves a
 * [handshaking] seam, exchanging [Hello] preambles so each side discovers the
 * other's [PeerId]. For driving [us.tractat.kuilt.conformance.SeamConformanceSuite]
 * against the handshaking seam.
 *
 * **Concurrency requirement:** the suite weaves host and joiner concurrently via
 * `async`, so both [handshaking] calls run in parallel and their preambles cross.
 * Serial weaving would deadlock (each side suspends waiting for the peer's Hello).
 */
public fun handshakingLoomPair(
    policy: DeliveryPolicy = DeliveryPolicy.Reliable,
): Pair<Loom, Loom> {
    val (hostConnection, joinerConnection) = connectionPair(policy)
    return HandshakeLoom(PeerId("host"), hostConnection) to
        HandshakeLoom(PeerId("joiner"), joinerConnection)
}

private class HandshakeLoom(private val self: PeerId, private val conn: Connection) : Loom {
    override suspend fun weave(rendezvous: Rendezvous): Seam =
        handshaking(conn, self, currentCoroutineContext()[ContinuationInterceptor]!!)
}
