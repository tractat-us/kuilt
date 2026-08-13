package us.tractat.kuilt.websocket

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
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
        log.info { "ws.room.start path=$path sessionName=${pattern.sessionName}" }
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
                        // Load-bearing, unlike the same arm in [us.tractat.kuilt.session.LoomRoomHost]
                        // (which had no swallowing arm and so removed it as a no-op). Here the
                        // `catch (Throwable)` below WOULD swallow it — CancellationException is an
                        // IllegalStateException on the JVM — turning a structured-concurrency cancel
                        // into a logged warning. This arm is the only thing keeping that from happening.
                        throw e
                    } catch (e: Throwable) {
                        log.warn(e) { "ws.room.onRoom failure: ${e.message}" }
                    } finally {
                        leaveShielded(room)
                    }
                }
            }
        }
    }

    /**
     * Leave [room] on the way out of its accept-loop child — shielded, because otherwise the leave
     * never happens at all.
     *
     * **`NonCancellable` is load-bearing here, not belt-and-braces.** Two paths reach this `finally`
     * on an **already-cancelled** coroutine, and both are ordinary: cancelling the calling scope, which
     * is this host's entire documented shutdown ([close] is a no-op and says so); and a non-cancellation
     * accept failure, which [start] rethrows, failing the enclosing `coroutineScope` and cancelling every
     * sibling room handler with it. [Room.leave] is a suspending call into the transport — the WebSocket
     * close handshake suspends — so unshielded it throws at that suspension point and the leave never
     * completes: no `LeaveReason.Normal`, no `Seam.close`, and every room the server was hosting silently
     * vanishes off the fabric instead of departing, leaving peers to time it out as a transport drop
     * (#2286). The same idiom, for the same reason, is in `NwLoom.discardUnreturnedSeam` and
     * `CompositeSeam.discardOrphanedPly`.
     *
     * The bug is specific to those two paths: `onRoom` returning normally, or throwing into the
     * swallowing `catch` above, both reach this `finally` uncancelled, where the leave already worked.
     * That is what made the site look safe on inspection.
     *
     * `try` / `catch (Throwable)` rather than `runCatchingCancellable`: inside the shield this block's Job
     * is [NonCancellable], so there is no cancellation of ours left to preserve, and every throwable
     * arriving here — including a [CancellationException] a `withTimeout`-bounded `leave` minted itself,
     * which [Room.leave]'s contract forbids but a library cannot assume a consumer honours — is this one
     * leave's failure. `runCatchingCancellable` would rethrow exactly that case, aborting the cleanup the
     * shield exists to guarantee.
     */
    private suspend fun leaveShielded(room: Room) {
        withContext(NonCancellable) {
            try {
                room.leave(LeaveReason.Normal)
            } catch (failure: Throwable) {
                log.debug { "ws.room.leave-failed path=$path sessionName=${pattern.sessionName} failure=$failure" }
            }
        }
    }

    /**
     * No-op. Lifecycle is owned by the calling [kotlinx.coroutines.CoroutineScope]:
     * cancelling the scope tears [start] down via structured concurrency.
     */
    override fun close(): Unit = Unit
}
