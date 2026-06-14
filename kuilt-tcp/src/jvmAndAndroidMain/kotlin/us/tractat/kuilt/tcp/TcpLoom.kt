package us.tractat.kuilt.tcp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.checkNotUnderTestDispatcher
import us.tractat.kuilt.core.fabric.handshaking
import kotlin.coroutines.CoroutineContext

/**
 * A [Loom] backed by a raw TCP socket — the headline of the pluggable fabric kit.
 *
 * A proprietary stream RPC becomes a kuilt fabric in a handful of transport lines:
 * obtain a connected [io.ktor.network.sockets.Socket], adapt it to a [Conn] with
 * [tcpConn] (Ktor channel → kotlinx-io Source/Sink → `framed()`), then hand that to
 * [handshaking] to negotiate identity in-band and yield a 2-peer [Seam].
 *
 * Construct with [host] (accepts one connection on a bound [ServerSocket]) or
 * [join] (dials a [TcpAddress]). The dispatcher that schedules the seam's read/write
 * loops is a real production dispatcher — TCP is real-network IO with no virtual clock;
 * the seam is thread-safe via atomics/locks, so the dispatcher is the scheduler, not a
 * mutex. Blocking socket reads run on [ioDispatcher] (see [tcpConn]), so the scheduling
 * dispatcher only serialises seam state, never blocks on the wire.
 *
 * [weave] guards against accidental virtual-time construction: building this real-IO seam
 * under a `kotlinx.coroutines.test.TestDispatcher` fails loudly (see
 * [checkNotUnderTestDispatcher]) — under virtual time the blocking socket IO would never
 * advance, deadlocking the test silently. Use an in-memory `connPair()`-backed seam (or
 * `handshaking`/`meshSeam` over in-memory conns) for virtual-time tests instead.
 */
public class TcpLoom private constructor(
    private val selfId: PeerId,
    private val selector: SelectorManager,
    private val seamDispatcher: CoroutineContext,
    private val ioDispatcher: CoroutineDispatcher,
    private val serverSocket: ServerSocket?,
) : Loom {
    override suspend fun weave(rendezvous: Rendezvous): Seam {
        // The real-IO TCP seam must never be built under a virtual TestDispatcher — its blocking
        // socket reads would never advance under virtual time, deadlocking the test silently. The
        // in-memory LinkSeam/MeshSeam are virtual-time-safe by design and deliberately omit this.
        checkNotUnderTestDispatcher(
            scope = CoroutineScope(seamDispatcher),
            typeName = "TcpLoom",
            substitute = "an in-memory connPair()/identified() seam",
            strict = true,
            expectVirtualTime = false,
        )
        val socket = when (rendezvous) {
            is Rendezvous.New -> requireNotNull(serverSocket) {
                "TcpLoom.host requires a bound ServerSocket"
            }.accept()
            is Rendezvous.Existing -> {
                val address = rendezvous.tag
                require(address is TcpAddress) { "TcpLoom only joins a TcpAddress, got ${address::class}" }
                aSocket(selector).tcp().connect(address.host, address.port)
            }
        }
        return handshaking(tcpConn(socket, ioDispatcher), selfId, seamDispatcher)
    }

    public companion object {
        /**
         * A host loom: [weave] of [Rendezvous.New] accepts one connection on the
         * already-bound [serverSocket]. Bind the socket with
         * `aSocket(selector).tcp().bind(host, port)` before calling [host].
         */
        public fun host(
            serverSocket: ServerSocket,
            selfId: PeerId,
            selector: SelectorManager,
            seamDispatcher: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): TcpLoom = TcpLoom(selfId, selector, seamDispatcher, ioDispatcher, serverSocket)

        /** A joiner loom: [weave] of [Rendezvous.Existing] dials the tag's [TcpAddress]. */
        public fun join(
            selfId: PeerId,
            selector: SelectorManager,
            seamDispatcher: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): TcpLoom = TcpLoom(selfId, selector, seamDispatcher, ioDispatcher, serverSocket = null)
    }
}
