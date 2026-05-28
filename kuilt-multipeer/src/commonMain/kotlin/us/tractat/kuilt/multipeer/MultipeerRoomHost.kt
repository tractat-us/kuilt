package us.tractat.kuilt.multipeer

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.session.LeaveReason
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.SeamRoomFactory

private val log = KotlinLogging.logger {}

/**
 * Multipeer-Connectivity room host. Wraps a [Loom] and a [Pattern], opens a
 * host session via [SeamRoomFactory], and exposes it as a single [Room].
 *
 * Single-room lifecycle: one [MultipeerRoomHost] hosts one session.
 *
 * Lifecycle:
 * - [start] builds a [Room] via [SeamRoomFactory.host] and invokes [onRoom]
 *   once. Suspends until the calling scope is cancelled; on cancellation the
 *   [Room] is left cleanly.
 * - Calling [start] a second time on the same instance throws
 *   [IllegalStateException].
 *
 * Frame routing, per-peer addressing, and membership tracking are owned by
 * [Room] — the manual demux that `MCLeaderListener` carried before
 * `:kuilt-session` existed is gone.
 *
 * **JNA dylib note:** this class is pure-commonMain Kotlin over [Loom]/[Room].
 * It has no native-code path. The [MultipeerPeerLinkFactory] dylib basenames and
 * JNA constants are unchanged and outside the scope of this class.
 */
public class MultipeerRoomHost(
    private val loom: Loom,
    private val sessionConfig: Pattern,
) : AutoCloseable {
    private val startMutex = Mutex()
    private var started = false

    /**
     * Start hosting a room. Builds the [Room] via [SeamRoomFactory.host], invokes
     * [onRoom] once with the live room, then suspends until the calling scope
     * is cancelled.
     *
     * On cancellation, [Room.leave] is called with [LeaveReason.Normal].
     *
     * @throws IllegalStateException if called while already running.
     */
    public suspend fun start(onRoom: suspend (Room) -> Unit) {
        startMutex.withLock {
            check(!started) { "MultipeerRoomHost.start already running" }
            started = true
        }
        log.info { "mp.room.start displayName=${sessionConfig.displayName}" }
        coroutineScope {
            val factory = SeamRoomFactory(loom = loom, scope = this)
            val room: Room = factory.host(sessionConfig)
            try {
                onRoom(room)
                awaitCancellation()
            } catch (e: CancellationException) {
                throw e
            } finally {
                log.info { "mp.room.close displayName=${sessionConfig.displayName}" }
                runCatching { room.leave(LeaveReason.Normal) }
            }
        }
    }

    /**
     * No-op. Lifecycle is owned by the calling [kotlinx.coroutines.CoroutineScope]:
     * cancelling the scope tears [start] down via structured concurrency.
     *
     * The [loom] is owned by the caller and its platform-specific cleanup
     * (e.g. [MultipeerPeerLinkFactory.close]) is the caller's responsibility.
     */
    override fun close(): Unit = Unit
}
