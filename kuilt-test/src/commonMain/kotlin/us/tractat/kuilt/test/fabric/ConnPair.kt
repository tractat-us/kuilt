package us.tractat.kuilt.test.fabric

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import us.tractat.kuilt.core.fabric.Conn

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
