package us.tractat.kuilt.test.fabric

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Spool
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.handshaking
import us.tractat.kuilt.core.fabric.identified
import kotlin.coroutines.ContinuationInterceptor

/**
 * Two Connections whose sends cross to each other's `incoming`. In-memory, no network.
 *
 * Each direction is a bounded [Spool] (default [DeliveryPolicy.Reliable]); there is no
 * `Channel.UNLIMITED` path. `Spool<ByteArray>` because a [Connection] carries raw byte frames,
 * a layer below the Swatch/Seam abstraction.
 *
 * @param maxFrameBytes what each end reports as [Connection.maxFrameBytes] — the frame ceiling a
 *   length-prefixed transport would have. Defaults to `null` (this in-memory pair has no ceiling of
 *   its own); pass a value to drive the payload-budget arithmetic the seams above derive from it.
 *   **Reported, not enforced by default** — an oversize frame still crosses, so a test can assert on
 *   the budget without also having to fake a rejection. Pass [enforcesFrameCeiling] when the
 *   rejection is the thing under test.
 * @param enforcesFrameCeiling make [Connection.send] actually *refuse* a frame over [maxFrameBytes],
 *   the way a length-prefixed transport does (`:kuilt-stream`'s `framed()` throws
 *   `FrameTooLargeException`). Off by default so existing budget-arithmetic tests are unaffected.
 *
 *   A test that asserts a seam **pre-checks** a payload needs this: without it the fake carries the
 *   oversize frame happily, so the seam's own pre-check is the only thing that could refuse it and
 *   the test passes whether or not the fabric error it exists to prevent would have fired. With it,
 *   the test is red before the pre-check exists — and stays honest about *which* layer refused
 *   (a seam that pre-checks raises [us.tractat.kuilt.core.PayloadTooLarge]; one that does not lets
 *   this fake's error through, into whatever the seam does with a failed `send`).
 */
public fun connectionPair(
    policy: DeliveryPolicy = DeliveryPolicy.Reliable,
    maxFrameBytes: Int? = null,
    enforcesFrameCeiling: Boolean = false,
): Pair<Connection, Connection> {
    val aToB = Spool<ByteArray>(policy)
    val bToA = Spool<ByteArray>(policy)
    val ceiling = maxFrameBytes.takeIf { enforcesFrameCeiling }
    return ChannelConnection(out = aToB, inn = bToA, maxFrameBytes = maxFrameBytes, enforced = ceiling) to
        ChannelConnection(out = bToA, inn = aToB, maxFrameBytes = maxFrameBytes, enforced = ceiling)
}

private class ChannelConnection(
    private val out: Spool<ByteArray>,
    private val inn: Spool<ByteArray>,
    override val maxFrameBytes: Int?,
    private val enforced: Int?,
) : Connection {
    override suspend fun send(frame: ByteArray) {
        // Stand in for a real framed transport's FrameTooLargeException. The type does not matter to
        // the seams above — every one of them treats any `conn.send` failure identically — only that
        // the refusal happens at the wire, after the seam has already accepted the call.
        enforced?.let {
            if (frame.size > it) error("frame of ${frame.size} B exceeds the $it B frame ceiling")
        }
        out.deliver(frame)
    }
    override val incoming: Flow<ByteArray> = inn.incoming
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
    /** Established by construction — the [Connection] is already live (#1746). */
    override fun capability(): TransportCapability =
        TransportCapability(roles = emptySet(), availability = FabricAvailability.Available)

    override suspend fun weave(rendezvous: Rendezvous): Seam =
        identified(conn, self, remote, requireNotNull(currentCoroutineContext()[ContinuationInterceptor]) {
            "weave/handshake: no dispatcher (ContinuationInterceptor) in coroutine context"
        })
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
    /** Established by construction — the [Connection] is already live (#1746). */
    override fun capability(): TransportCapability =
        TransportCapability(roles = emptySet(), availability = FabricAvailability.Available)

    override suspend fun weave(rendezvous: Rendezvous): Seam =
        handshaking(conn, self, requireNotNull(currentCoroutineContext()[ContinuationInterceptor]) {
            "weave/handshake: no dispatcher (ContinuationInterceptor) in coroutine context"
        })
}
