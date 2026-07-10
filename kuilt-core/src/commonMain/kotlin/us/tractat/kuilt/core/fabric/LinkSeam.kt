package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.SeamStateGate
import us.tractat.kuilt.core.Spool
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.runCatchingCancellable
import kotlin.coroutines.CoroutineContext

/**
 * A byte-transparent 2-peer [Seam] over a [Connection] whose two identities are known.
 * `broadcast` == `sendTo(remoteId)`. Woven at construction; Torn on conn EOF/error
 * or [close]. Concurrent sends are serialized through an internal channel + single
 * writer so wire order matches call order.
 *
 * **Thread-safety.** This type is correct under a *multi-threaded* dispatcher — the
 * injected [dispatcher] is only the scope for the read/write loops (scheduling); it is
 * **not** a mutual-exclusion mechanism. Teardown is latched by a [SeamStateGate]: `close`
 * and `readLoop`'s `finally` both call `tear()`, which is single-shot, so teardown runs
 * exactly once regardless of which thread arrives first and no late write can move the
 * state off `Torn`. `readLoop`/`broadcast`/`sendTo` read `state.value`; because the
 * suspending `outbox.send()` is an inherent check-then-send TOCTOU against a concurrent
 * teardown that closes the channel, they convert a `ClosedSendChannelException` into the same
 * clean closed-seam [IllegalStateException] the pre-check produces — a closed seam never
 * leaks a raw channel exception.
 *
 * **Inbox backpressure.** Inbound frames are delivered through a [Spool] whose capacity
 * and overflow behaviour are governed by [policy] (default [DeliveryPolicy.Reliable],
 * capacity [DeliveryPolicy.DEFAULT_CAPACITY], overflow [us.tractat.kuilt.core.Overflow.SUSPEND]).
 * The old [Channel.UNLIMITED] inbox is gone: unbounded inbound queues are structurally
 * unrepresentable per the fabric-backpressure epic.
 *
 * **Outbox.** The outbound queue is a bounded [Channel] (capacity [DeliveryPolicy.DEFAULT_CAPACITY],
 * overflow [BufferOverflow.SUSPEND]). SUSPEND is the right strategy for an outbound queue:
 * `broadcast`/`sendTo` callers are backpressured when the wire cannot keep up, preserving FIFO
 * order without dropping frames. The outbox is distinct from the inbox policy because it is a
 * wire-output buffer, not an inbound delivery buffer; a per-call override of outbound capacity
 * is not useful at this primitive's level.
 *
 * @param dispatcher The scope for the seam's read/write loops. Production callers pass
 *   `Dispatchers.Default.limitedParallelism(1)`; test callers pass a
 *   `TestCoroutineDispatcher` derived from the test scheduler so that the seam's
 *   read/write loops share the same virtual clock as the test's `withTimeout`.
 * @param policy Governs the inbox [Spool]'s capacity and overflow behaviour.
 *   Defaults to [DeliveryPolicy.Reliable] (bounded, backpressured, lossless).
 */
public fun identified(
    conn: Connection,
    selfId: PeerId,
    remoteId: PeerId,
    dispatcher: CoroutineContext,
    policy: DeliveryPolicy = DeliveryPolicy.Reliable,
): Seam = LinkSeam(conn, selfId, remoteId, dispatcher, policy)

internal class LinkSeam(
    private val conn: Connection,
    override val selfId: PeerId,
    private val remoteId: PeerId,
    dispatcher: CoroutineContext,
    policy: DeliveryPolicy = DeliveryPolicy.Reliable,
) : Seam {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _peers = MutableStateFlow(setOf(selfId, remoteId))
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    private val stateGate = SeamStateGate(SeamState.Woven)
    override val state: StateFlow<SeamState> = stateGate.state

    private val inbox = Spool<Swatch>(policy)
    override val incoming: Flow<Swatch> = inbox.incoming

    // Single-writer outbound queue: concurrent broadcast/sendTo enqueue here;
    // one coroutine drains in FIFO order to conn.send. Bounded with SUSPEND overflow so
    // callers are backpressured rather than producing unbounded frame accumulation.
    private val outbox = Channel<ByteArray>(
        capacity = DeliveryPolicy.DEFAULT_CAPACITY,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    // Confined to readLoop's single collector; not shared across threads.
    private var seq = 0L

    init {
        scope.launch { writeLoop() }
        scope.launch { readLoop() }
    }

    private val closedMessage get() = "Seam for $selfId is closed"

    override suspend fun broadcast(payload: ByteArray) {
        check(state.value !is SeamState.Torn) { closedMessage }
        enqueue(payload)
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        check(state.value !is SeamState.Torn) { closedMessage }
        if (peer !in _peers.value) throw PeerNotConnected(peer)
        enqueue(payload)
    }

    // `outbox.send()` stays outside any guard (it suspends), so a concurrent teardown can close
    // the channel between the pre-check and the send. Convert that into the same clean closed-seam
    // error the pre-check produces; never leak a raw ClosedSendChannelException.
    private suspend fun enqueue(payload: ByteArray) {
        runCatchingCancellable { outbox.send(payload) }
            .onFailure { if (it is ClosedSendChannelException) throw IllegalStateException(closedMessage) else throw it }
    }

    override suspend fun close(reason: CloseReason) {
        tearDown(reason)
        conn.close()
    }

    private suspend fun writeLoop() {
        for (frame in outbox) {
            // A frame can be enqueued moments before the wire tears (a broadcast racing a remote
            // drop). `conn.send` then throws (e.g. "Sink is closed"); left uncaught it escapes into
            // this seam's scope and leaks across into a later coroutine. Treat a send failure as a
            // remote drop — tear down (single-shot) and stop — exactly as `readLoop` handles a conn
            // error. Cancellation is rethrown by runCatchingCancellable, never swallowed.
            val sent = runCatchingCancellable { conn.send(frame) }
            if (sent.isFailure) {
                tearDown(CloseReason.RemoteRequested)
                return
            }
        }
    }

    private suspend fun readLoop() {
        try {
            conn.incoming.collect { bytes ->
                if (state.value !is SeamState.Torn) {
                    // deliver suspends under SUSPEND-overflow policy — this is intentional: the
                    // readLoop pauses until the consumer drains, propagating backpressure from the
                    // inbox spool back to the wire. No lock is held here, so suspension is safe.
                    inbox.deliver(Swatch(payload = bytes, sender = remoteId, sequence = ++seq))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // remote dropped — fall through to teardown
        } finally {
            tearDown(CloseReason.RemoteRequested)
        }
    }

    // Collapse the roster BEFORE publishing Torn (peers-before-state: a consumer observing Torn must
    // already see the collapsed roster). The `_peers.update` is idempotent (always setOf(selfId)), so
    // running it on a losing caller is harmless; `stateGate.tear` is the single-shot latch that gates
    // the one-time channel closes.
    private fun tearDown(reason: CloseReason) {
        _peers.update { setOf(selfId) }
        if (!stateGate.tear(reason)) return
        outbox.close()
        inbox.close()
    }
}
