package us.tractat.kuilt.core.fabric

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext
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
 * **The seam closes [conn] itself when the link ends (#2898)** — it does not wait for the
 * application to call [Seam.close]. A clean end of [Connection.incoming] tears the seam with
 * [CloseReason.RemoteRequested]; an `incoming` that throws (a stream cut mid-frame, an oversize
 * length prefix) tears it with [CloseReason.Error] carrying that exception, so a consumer can tell
 * the two apart. Either way the frames the writer had already queued are flushed first, and then
 * [conn] is closed — exactly once across every path, a later [close] included.
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

    /**
     * The conn's own frame ceiling, unchanged: this seam writes the caller's `payload` to
     * [Connection.send] byte for byte — there is no per-frame header on this hop — so the payload
     * budget and the frame budget are the same number (#2047).
     *
     * **Enforced, not merely published** (#2069): [sendTo] refuses an over-budget payload with
     * [us.tractat.kuilt.core.PayloadTooLarge] and [broadcast] drops it, both before the frame can
     * reach [Connection.send]. See the note above the two methods for what used to happen instead.
     */
    override val maxPayloadBytes: Int? get() = conn.maxFrameBytes

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

    // Single-shot latch for `conn.close()`: whichever path flips it closes the conn, and completes
    // `connClosed` so a concurrent `close()` that lost the race can still wait for the close to land.
    private val connCloseClaimed = atomic(false)
    private val connClosed = CompletableDeferred<Unit>()

    private val writer = scope.launch { writeLoop() }

    init {
        scope.launch { readLoop() }
    }

    private val closedMessage get() = "Seam for $selfId is closed"

    // Both sends pre-check against the conn's own ceiling (#2069). Enqueuing an over-budget frame
    // instead is destructive out of all proportion to the mistake: this call returns SUCCESS, and
    // the frame then fails in `writeLoop`, which cannot tell an oversize frame from a dead wire and
    // tears the whole seam down — a whole session lost, asynchronously, to one bad payload the
    // caller was told had been accepted. The wire is fine; `framed().send` checks before writing.

    override suspend fun broadcast(payload: ByteArray) {
        check(state.value !is SeamState.Torn) { closedMessage }
        // Best-effort by contract: an over-budget payload is dropped, not reported. Its most common
        // caller is a timer-driven replication loop a throw would kill.
        if (conn.oversizeOrNull(payload) != null) return
        enqueue(payload)
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        check(state.value !is SeamState.Torn) { closedMessage }
        // A 2-peer link's roster is { selfId, remoteId } and its wire has exactly one addressee, so
        // without this the frame went to the REMOTE — a misdelivery, reported as success (#2428).
        require(peer != selfId) { "Cannot send to self — use broadcast if you intend to loop back" }
        if (peer !in _peers.value) throw PeerNotConnected(peer)
        conn.oversizeOrNull(payload)?.let { throw it }
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
        // A local close does not drain the outbox: closing the conn now is also what unsticks a writer
        // suspended in `conn.send` on a peer that has stopped reading. If the link already closed
        // itself, wait for that close rather than closing twice.
        if (!connCloseClaimed.compareAndSet(expect = false, update = true)) return connClosed.await()
        closeConn()?.let { throw it }
    }

    /**
     * Close [conn] for a path the remote or the wire ended, once. Best effort: there is no caller to
     * report a failure to, so it is absorbed. Shielded, so it runs even when the path ending is a
     * cancellation; inside the shield every cancellation is one `conn.close()` minted, so the catch in
     * [closeConn] is plain (#1803/#1824).
     */
    private suspend fun closeConnOnce() {
        if (connCloseClaimed.compareAndSet(expect = false, update = true)) closeConn()
    }

    /** Close [conn] under [NonCancellable]; returns the failure, if any, and always settles [connClosed]. */
    private suspend fun closeConn(): Throwable? = withContext(NonCancellable) {
        try {
            conn.close()
            null
        } catch (failure: Throwable) {
            failure
        } finally {
            connClosed.complete(Unit)
        }
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
                closeConnOnce()
                return
            }
        }
    }

    private suspend fun readLoop() {
        var reason: CloseReason = CloseReason.RemoteRequested
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
            // The read failed rather than ended: a cut or refused stream, not a clean close (#2898).
            reason = CloseReason.Error(e)
        } finally {
            tearDown(reason)
            // The remote is gone, so this side closes too (`docs/tcp-wire.md` § 5) — but only after
            // the writer drains what was already queued: `tearDown` closed the outbox, so the writer
            // ends once it has flushed it. Shielded so the close still lands if this loop is cancelled.
            withContext(NonCancellable) {
                writer.join()
                closeConnOnce()
            }
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
