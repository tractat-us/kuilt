package us.tractat.kuilt.test.fabric

import kotlinx.coroutines.channels.Channel
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.ConnectionSource

/**
 * In-memory [ConnectionSource]: [offer] a hub-end [Connection] (typically from [connectionPair]);
 * [accept] receives it. The same accept path a real server uses, minus the wire — drives a hosted
 * overlay end-to-end under virtual time.
 */
public class InMemoryConnectionSource : ConnectionSource {
    private val channel = Channel<Connection>(capacity = Channel.UNLIMITED)
    public fun offer(connection: Connection) { channel.trySend(connection) }
    override suspend fun accept(): Connection = channel.receive()
}
