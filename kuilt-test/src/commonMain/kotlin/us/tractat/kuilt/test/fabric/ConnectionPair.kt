package us.tractat.kuilt.test.fabric

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.handshaking
import us.tractat.kuilt.core.fabric.identified
import kotlin.coroutines.ContinuationInterceptor

/** Two Connections whose sends cross to each other's `incoming`. In-memory, no network. */
public fun connectionPair(): Pair<Connection, Connection> {
    val aToB = Channel<ByteArray>(Channel.UNLIMITED)
    val bToA = Channel<ByteArray>(Channel.UNLIMITED)
    return ChannelConnection(out = aToB, inn = bToA) to ChannelConnection(out = bToA, inn = aToB)
}

private class ChannelConnection(
    private val out: Channel<ByteArray>,
    private val inn: Channel<ByteArray>,
) : Connection {
    override suspend fun send(frame: ByteArray) { out.send(frame) }
    override val incoming: Flow<ByteArray> = inn.receiveAsFlow()
    override suspend fun close() { out.close() }
}

/**
 * A host/joiner Loom pair wired by one in-memory [connectionPair]: host weaves an
 * `identified` seam over one end, joiner over the other. For driving
 * `SeamConformanceSuite` against the LinkSeam primitive.
 */
public fun identifiedLoomPair(): Pair<Loom, Loom> {
    val (hostConnection, joinerConnection) = connectionPair()
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
public fun handshakingLoomPair(): Pair<Loom, Loom> {
    val (hostConnection, joinerConnection) = connectionPair()
    return HandshakeLoom(PeerId("host"), hostConnection) to HandshakeLoom(PeerId("joiner"), joinerConnection)
}

private class HandshakeLoom(private val self: PeerId, private val conn: Connection) : Loom {
    override suspend fun weave(rendezvous: Rendezvous): Seam =
        handshaking(conn, self, currentCoroutineContext()[ContinuationInterceptor]!!)
}
