package us.tractat.kuilt.websocket

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.session.LeaveReason
import us.tractat.kuilt.session.Principal
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.RoomHost
import us.tractat.kuilt.session.SeamRoomFactory

private val log = KotlinLogging.logger("us.tractat.kuilt.websocket.KtorRoomHost")

/**
 * Ktor-bound WebSocket [Room] host. Mounts a server-side WS route on
 * [application] at [path] via an internal [KtorServerLoom] and forwards every
 * accepted connection to [start]'s `onRoom` callback as its own [Room].
 *
 * Lives in `:kuilt-websocket/jvmAndAndroidMain`, next to [KtorServerLoom]:
 * the Ktor server engine (Netty on JVM, CIO on Android) is provided by the
 * embedded-server caller, not by this class.
 *
 * **Multi-room lifecycle.** Unlike [us.tractat.kuilt.multipeer.MultipeerRoomHost]
 * (one MC session = one [Room]), each accepted WebSocket connection is its own
 * two-peer [Room]. The accept loop drains as many rooms as clients arrive,
 * dispatching each to `onRoom` in a child coroutine so concurrent connections
 * don't serialize.
 *
 */
public class KtorRoomHost internal constructor(
    private val path: String,
    private val pattern: Pattern,
    private val loom: Loom,
) : RoomHost {
    /**
     * Production constructor. Pre-constructs a [KtorServerLoom] synchronously
     * so the WebSocket route is mounted on [application] before any client
     * tries to connect. Deferring into [start]'s launched coroutine would
     * race-condition route registration against early connecting clients.
     */
    public constructor(
        application: Application,
        path: String,
        serverPeerId: PeerId,
        pattern: Pattern,
        principalExtractor: (ApplicationCall) -> Principal? = { null },
    ) : this(
        path = path,
        pattern = pattern,
        loom = KtorServerLoom(application, path, serverPeerId, principalExtractor = principalExtractor),
    )

    private val startMutex = Mutex()
    private var started = false

    /**
     * Run the accept loop. Each call to [SeamRoomFactory.host] suspends until
     * the next WebSocket connection arrives, then yields a fully-built [Room].
     * Each room is dispatched to [onRoom] in a child coroutine; the loop
     * continues accepting until the calling scope is cancelled or the underlying
     * accept fails.
     *
     * **Error signalling.** A non-cancellation failure from [SeamRoomFactory.host]
     * (e.g. the server loom stops accepting) is logged and rethrown — [start]
     * propagates the exception to the caller. Callers can wrap [start] in
     * `runCatching` or a `try/catch` to observe the failure and decide whether
     * to restart or surface the error.
     *
     * [CancellationException] (structured-concurrency cancellation) propagates
     * unchanged, as required.
     *
     * @throws IllegalStateException if called while already running.
     */
    override suspend fun start(onRoom: suspend (Room) -> Unit) {
        startMutex.withLock {
            check(!started) { "KtorRoomHost.start already running" }
            started = true
        }
        log.info { "ws.room.start path=$path displayName=${pattern.displayName}" }
        coroutineScope {
            val factory = SeamRoomFactory.systemClock(loom = loom, scope = this)
            while (true) {
                val room: Room =
                    try {
                        factory.host(pattern)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        log.warn(e) { "ws.room.accept failure: ${e.message}" }
                        throw e
                    }
                launch {
                    try {
                        onRoom(room)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        log.warn(e) { "ws.room.onRoom failure: ${e.message}" }
                    } finally {
                        runCatchingCancellable { room.leave(LeaveReason.Normal) }
                    }
                }
            }
        }
    }

    /**
     * No-op. Lifecycle is owned by the calling [kotlinx.coroutines.CoroutineScope]:
     * cancelling the scope tears [start] down via structured concurrency.
     */
    override fun close(): Unit = Unit
}
