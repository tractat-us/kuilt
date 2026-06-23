package us.tractat.kuilt.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.core.runCatchingCancellable
import kotlin.time.Clock
import kotlin.time.Instant

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
 * @param pattern the session [Pattern] (display name) to advertise.
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
            } catch (e: CancellationException) {
                throw e
            } finally {
                runCatchingCancellable { room.leave(LeaveReason.Normal) }
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
