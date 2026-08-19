package us.tractat.kuilt.core.fabric

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.takeWhile
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.Spool

/**
 * [MeshDisplacementDrainConformanceSuite] against a connection that flushes **nothing**.
 *
 * The reference binding for the suite, and the reason it can be non-nullable: abruptness is a
 * property of the fixture, and any transport can be wrapped into one.
 */
internal class AbruptCloseMeshDisplacementDrainTest : MeshDisplacementDrainConformanceSuite() {
    override fun newAbruptClosingConnectionPair(): Pair<Connection, Connection> = abruptConnectionPair()
}

/**
 * Two connected [Connection]s whose `close` **discards**: closing one end completes that end's
 * [incoming] immediately, dropping anything the peer had already sent and it had not yet collected.
 *
 * This is deliberately *worse* than any transport in the tree, and worse than a `singleCollection`
 * wrapper, whose pump may already have republished a frame before it is cancelled. That is the point:
 * a fixture that keeps some of the tail cannot distinguish a seam that drains from a transport that
 * happens to flush. Here nothing survives a close, so every delivered frame is one the seam read
 * before it closed anything.
 *
 * The discard is expressed as a `takeWhile` gate on the *receiving* side rather than by closing the
 * spool, because closing a channel still drains its buffer — which is exactly the partial flush this
 * fixture must not have.
 */
internal fun abruptConnectionPair(): Pair<Connection, Connection> {
    val aToB = Spool<ByteArray>(DeliveryPolicy.Reliable)
    val bToA = Spool<ByteArray>(DeliveryPolicy.Reliable)
    return AbruptConnection(out = aToB, inn = bToA) to AbruptConnection(out = bToA, inn = aToB)
}

private class AbruptConnection(
    private val out: Spool<ByteArray>,
    inn: Spool<ByteArray>,
) : Connection {
    private val closed = atomic(false)

    override suspend fun send(frame: ByteArray) {
        if (closed.value) return
        out.deliver(frame)
    }

    // The gate is re-read per element, so a close mid-stream ends the flow at that element rather
    // than letting the rest of the buffer through.
    override val incoming: Flow<ByteArray> = inn.incoming.takeWhile { !closed.value }

    override suspend fun close() {
        closed.value = true
        out.close()
    }
}
