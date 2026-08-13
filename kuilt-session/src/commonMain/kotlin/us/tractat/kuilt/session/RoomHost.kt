package us.tractat.kuilt.session

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.liveness.HeartbeatConfig
import kotlin.time.Clock
import kotlin.time.Instant

private val logger = KotlinLogging.logger("us.tractat.kuilt.session.RoomHost")

/**
 * Accepts incoming connections and surfaces each as an admitted [Room].
 *
 * A host is the entry point a fabric adapter wraps so consumers can run a session
 * without knowing the transport: a Ktor WebSocket host, an Apple-Multipeer host,
 * or the transport-agnostic [LoomRoomHost] over any [Loom].
 *
 * **Why this interface exists.** It lets a consumer drive the full admit handshake
 * (Hello → Welcome → onPeer) deterministically under `runTest` virtual time by
 * substituting a [LoomRoomHost] backed by an [us.tractat.kuilt.core.InMemoryLoom]
 * for the production host — exercising the real host code path without binding a
 * socket or standing up a server engine.
 *
 * **Cardinality.** [start]'s `onRoom` is invoked **once per admitted connection**.
 * A single-room host ([LoomRoomHost], a Multipeer host) invokes it exactly once and
 * then suspends; a multi-room accept-loop host (the Ktor WebSocket host) invokes it
 * for every connection that arrives.
 */
public interface RoomHost : AutoCloseable {
    /**
     * Begin hosting. Each admitted connection is surfaced to [onRoom] as a live
     * [Room]. Suspends until the calling [kotlinx.coroutines.CoroutineScope] is
     * cancelled; [kotlinx.coroutines.CancellationException] propagates unchanged.
     *
     * @throws IllegalStateException if called while already running.
     */
    public suspend fun start(onRoom: suspend (Room) -> Unit)
}

/**
 * Transport-agnostic single-room [RoomHost] over any [Loom]. Opens one host session
 * via [SeamRoomFactory.host], hands the live [Room] to `onRoom` exactly once, then
 * suspends until cancelled (leaving the room cleanly on the way out).
 *
 * Constructed with an [us.tractat.kuilt.core.InMemoryLoom] it is the in-memory host
 * double for admit-handshake tests: pair it with a [SeamRoomFactory.join] over the
 * same loom and the Hello → Welcome → onPeer exchange runs entirely under virtual
 * time, no socket bound.
 *
 * The fabric-specific hosts delegate their session lifecycle here; only the
 * multi-connection accept loop (Ktor) and platform cleanup live in those adapters.
 *
 * @param loom the [Loom] to host over.
 * @param pattern the session [Pattern] (session name) to advertise.
 * @param clock time source for partition detection; defaults to wall-clock
 *   [kotlin.time.Clock.System]. Inject a virtual clock in tests.
 * @param heartbeatConfig partition-detection timing.
 */
public class LoomRoomHost(
    private val loom: Loom,
    private val pattern: Pattern,
    private val clock: () -> Instant = { Clock.System.now() },
    private val heartbeatConfig: HeartbeatConfig = HeartbeatConfig(),
) : RoomHost {
    private val startMutex = Mutex()
    private var started = false

    override suspend fun start(onRoom: suspend (Room) -> Unit) {
        startMutex.withLock {
            check(!started) { "LoomRoomHost.start already running" }
            started = true
        }
        coroutineScope {
            val factory = SeamRoomFactory(loom = loom, scope = this, clock = clock, heartbeatConfig = heartbeatConfig)
            val room: Room = factory.host(pattern)
            try {
                onRoom(room)
                awaitCancellation()
            } finally {
                leaveShielded(room)
            }
        }
    }

    /**
     * Leave [room] on the way out of [start] — shielded, because otherwise the leave never happens at all.
     *
     * **`NonCancellable` is load-bearing here, not belt-and-braces.** Scope cancellation *is* this host's
     * documented lifecycle ([close] is a no-op and says so) and [start]'s body ends in
     * [awaitCancellation], so by the time this runs the coroutine is **always already cancelled**.
     * [Room.leave] is a suspending call into a transport — a WebSocket close handshake, a Multipeer
     * disconnect, an `NwConnection` cancel all suspend — so unshielded it throws at that suspension point
     * and every host that shuts down the documented way silently vanishes off the fabric instead of
     * departing: no `LeaveReason.Normal`, no `Seam.close`, peers left to time it out as a transport drop
     * (#2286). The same idiom, for the same reason, is in `NwLoom.discardUnreturnedSeam` and
     * `CompositeSeam.discardOrphanedPly`.
     *
     * `try` / `catch (Throwable)` rather than `runCatchingCancellable`: inside the shield this block's Job
     * is parented to [NonCancellable], so there is no cancellation of ours left to preserve and every
     * throwable arriving here — including a [kotlinx.coroutines.CancellationException] a
     * `withTimeout`-bounded `leave` minted itself, which [Room.leave]'s contract forbids but a library
     * cannot assume a consumer honours — is this one leave's failure. `runCatchingCancellable` would
     * rethrow exactly that case, aborting the cleanup the shield exists to guarantee.
     */
    private suspend fun leaveShielded(room: Room) {
        withContext(NonCancellable) {
            try {
                room.leave(LeaveReason.Normal)
            } catch (failure: Throwable) {
                logger.debug { "roomHost.leave-failed session=${pattern.sessionName} failure=$failure" }
            }
        }
    }

    /**
     * No-op. Lifecycle is owned by the calling [kotlinx.coroutines.CoroutineScope]:
     * cancelling the scope tears [start] down via structured concurrency. The [loom]
     * is the caller's to close.
     */
    override fun close(): Unit = Unit
}
