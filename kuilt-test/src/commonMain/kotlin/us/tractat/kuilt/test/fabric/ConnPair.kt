package us.tractat.kuilt.test.fabric

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.Conn
import us.tractat.kuilt.core.fabric.handshaking
import us.tractat.kuilt.core.fabric.identified
import kotlin.coroutines.ContinuationInterceptor

/** Two Conns whose sends cross to each other's `incoming`. In-memory, no network. */
public fun connPair(): Pair<Conn, Conn> {
    val aToB = Channel<ByteArray>(Channel.UNLIMITED)
    val bToA = Channel<ByteArray>(Channel.UNLIMITED)
    return ChannelConn(out = aToB, inn = bToA) to ChannelConn(out = bToA, inn = aToB)
}

private class ChannelConn(
    private val out: Channel<ByteArray>,
    private val inn: Channel<ByteArray>,
) : Conn {
    override suspend fun send(frame: ByteArray) { out.send(frame) }
    override val incoming: Flow<ByteArray> = inn.receiveAsFlow()
    override suspend fun close() { out.close() }
}

/**
 * A host/joiner Loom pair wired by one in-memory [connPair]: host weaves an
 * `identified` seam over one end, joiner over the other. For driving
 * `SeamConformanceSuite` against the LinkSeam primitive.
 */
public fun identifiedLoomPair(): Pair<Loom, Loom> {
    val (hostConn, joinerConn) = connPair()
    val host = ConnLoom(PeerId("host"), PeerId("joiner"), hostConn)
    val joiner = ConnLoom(PeerId("joiner"), PeerId("host"), joinerConn)
    return host to joiner
}

private class ConnLoom(
    private val self: PeerId,
    private val remote: PeerId,
    private val conn: Conn,
) : Loom {
    override suspend fun weave(rendezvous: Rendezvous): Seam =
        identified(conn, self, remote, currentCoroutineContext()[ContinuationInterceptor]!!)
}

/**
 * A host/joiner Loom pair wired by one in-memory [connPair]: each end weaves a
 * [handshaking] seam, exchanging [Hello] preambles so each side discovers the
 * other's [PeerId]. For driving [us.tractat.kuilt.conformance.SeamConformanceSuite]
 * against the handshaking seam.
 *
 * **Concurrency requirement:** the suite weaves host and joiner concurrently via
 * `async`, so both [handshaking] calls run in parallel and their preambles cross.
 * Serial weaving would deadlock (each side suspends waiting for the peer's Hello).
 */
public fun handshakingLoomPair(): Pair<Loom, Loom> {
    val (hostConn, joinerConn) = connPair()
    return HandshakeLoom(PeerId("host"), hostConn) to HandshakeLoom(PeerId("joiner"), joinerConn)
}

private class HandshakeLoom(private val self: PeerId, private val conn: Conn) : Loom {
    override suspend fun weave(rendezvous: Rendezvous): Seam =
        handshaking(conn, self, currentCoroutineContext()[ContinuationInterceptor]!!)
}
