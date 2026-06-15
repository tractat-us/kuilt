package us.tractat.kuilt.websocket

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.session.LeaveReason
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.SeamRoomFactory

private val log = KotlinLogging.logger {}

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
public class KtorRoomHost(
    application: Application,
    private val path: String,
    serverPeerId: PeerId,
    private val pattern: Pattern,
) : AutoCloseable {
    private val startMutex = Mutex()
    private var started = false

    // Pre-construct the Loom synchronously so the WebSocket route is mounted
    // on `application` before any client tries to connect. Deferring into
    // `start()`'s launched coroutine race-conditions route registration
    // against early connecting clients.
    private val loom: KtorServerLoom =
        KtorServerLoom(application, path, serverPeerId)

    /**
     * Run the accept loop. Each call to [SeamRoomFactory.host] suspends until
     * the next WebSocket connection arrives, then yields a fully-built [Room].
     * Each room is dispatched to [onRoom] in a child coroutine; the loop
     * continues accepting until the calling scope is cancelled.
     *
     * @throws IllegalStateException if called while already running.
     */
    public suspend fun start(onRoom: suspend (Room) -> Unit) {
        startMutex.withLock {
            check(!started) { "KtorRoomHost.start already running" }
            started = true
        }
        log.info { "ws.room.start path=$path displayName=${pattern.displayName}" }
        coroutineScope {
            val factory = SeamRoomFactory(loom = loom, scope = this)
            while (true) {
                val room: Room =
                    try {
                        factory.host(pattern)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        log.warn(e) { "ws.room.accept failure: ${e.message}" }
                        break
                    }
                launch {
                    try {
                        onRoom(room)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        log.warn(e) { "ws.room.onRoom failure: ${e.message}" }
                    } finally {
                        runCatching { room.leave(LeaveReason.Normal) }
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
