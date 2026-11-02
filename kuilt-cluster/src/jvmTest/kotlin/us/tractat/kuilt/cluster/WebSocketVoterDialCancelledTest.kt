package us.tractat.kuilt.cluster

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: a real socket and a real client, so real time
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/**
 * `voterMeshOverWebSockets`' dial keeps the promise `assembleVoterMesh` asks of it: cancelled at any
 * point, it leaves no socket behind (#3044).
 *
 * The far end is a plain loopback socket that holds the upgrade answer, so the cancel lands with the
 * handshake in flight, and only then does the far end let it finish. `withTimeout(formationTimeout)`
 * and `VoterMesh.close` both deliver this cancel in production; here the test cancels directly so the
 * window is forced rather than raced.
 *
 * Unlike the `-Pcluster.realsocket.tests` lane, nothing here waits on an upgrade a saturated box could
 * lose. The assertion is on what the far end's own socket did, and its bound is only waited out on a
 * red, so it runs in the normal build.
 */
class WebSocketVoterDialCancelledTest {

    private val rigScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val farEnd = HeldUpgrade()
    private val httpClient = HttpClient(CIO) { install(ClientWebSockets) }

    private val raftConfig = RaftConfig(
        electionTimeoutMin = 100.milliseconds,
        electionTimeoutMax = 200.milliseconds,
        heartbeatInterval = 20.milliseconds,
        random = Random(3044),
    )

    @AfterTest
    fun tearDown() {
        rigScope.cancel()
        farEnd.close()
        httpClient.close()
    }

    @Test
    fun aVoterDialCancelledBeforeTheUpgradeAnswersLeavesNoSocket() = runBlocking {
        withTimeout(TEST_BOUND) {
            // Lower id dials higher, so voter-a dials the held far end, and voter-b dials nothing.
            val voters = listOf(
                WebSocketVoter(NodeId("voter-a"), NeverYieldingConnectionSource(), farEnd.url),
                WebSocketVoter(NodeId("voter-b"), NeverYieldingConnectionSource(), farEnd.url),
            )
            val formation = rigScope.launch {
                voterMeshOverWebSockets(voters, httpClient, Dispatchers.Default, raftConfig, random = Random(3044))
            }
            farEnd.requestSeen.await()
            assertFalse(formation.isCompleted, "rig: formation ended before the far end answered")

            formation.cancelAndJoin()
            farEnd.finishHandshake()

            assertTrue(farEnd.socketEnded.await(), "nothing closed the socket within $OBSERVATION, so nobody owns it")
        }
    }

    /** One upgrade on a plain socket, answered when the test says so, then read until the socket ends. */
    private class HeldUpgrade : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private val answer = CountDownLatch(1)

        val requestSeen = CompletableDeferred<Unit>()
        val socketEnded = CompletableDeferred<Boolean>()
        val url: String get() = "ws://127.0.0.1:${listener.localPort}/voter"

        init {
            thread(isDaemon = true, name = "held-upgrade-${listener.localPort}") { serve() }
        }

        fun finishHandshake() = answer.countDown()

        override fun close() {
            answer.countDown()
            listener.close()
        }

        private fun serve() {
            try {
                listener.accept().use { socket ->
                    val input = socket.getInputStream()
                    val request = StringBuilder()
                    while (!request.endsWith("\r\n\r\n")) {
                        val next = input.read()
                        if (next < 0) throw IOException("the client went away mid-request")
                        request.append(next.toChar())
                    }
                    requestSeen.complete(Unit)
                    answer.await()
                    socket.soTimeout = OBSERVATION.inWholeMilliseconds.toInt()
                    try {
                        socket.getOutputStream().apply {
                            write(upgradeResponse(request).toByteArray())
                            flush()
                        }
                        while (input.read() >= 0) {
                            // Frames behind the answer, a Close among them. Only the end of the socket counts.
                        }
                        socketEnded.complete(true)
                    } catch (_: SocketTimeoutException) {
                        socketEnded.complete(false)
                    } catch (_: IOException) {
                        socketEnded.complete(true)
                    }
                }
            } catch (_: IOException) {
                // The listener closed in teardown, with nobody left to report to.
            }
        }

        private fun upgradeResponse(request: CharSequence): String {
            val key = request.lineSequence()
                .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                .substringAfter(':')
                .trim()
            val accept = Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).toByteArray()))
            return "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n"
        }
    }

    private companion object {
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /** Waited out only on a red. */
        val OBSERVATION = 15.seconds
        val TEST_BOUND = 45.seconds
    }
}
