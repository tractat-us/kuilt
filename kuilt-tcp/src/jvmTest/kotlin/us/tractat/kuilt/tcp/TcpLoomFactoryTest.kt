package us.tractat.kuilt.tcp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real-network loopback test — a TCP socket needs real production dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The uniform-shape factories [tcpLoomHost] / [tcpLoomJoin] (#1430).
 *
 * Every knob they expose is asserted to *reach* the loom it configures — a factory that quietly
 * dropped an argument would compile and pass a construction smoke test. And because the whole
 * point of the convention is to expose only knobs this fabric can honour, the two it deliberately
 * omits (`policy`, `weaveTimeout`) are pinned as absent by
 * [theSignatureExposesOnlyTheKnobsThisFabricHonours].
 *
 * `runBlocking`, never `runTest`: [TcpLoom] is real-IO only and its `weave` refuses a virtual
 * clock outright, so a `withTimeout` here has to be a real-time one.
 */
class TcpLoomFactoryTest {

    private val selector = SelectorManager(Dispatchers.IO)
    private lateinit var serverSocket: ServerSocket
    private var port: Int = 0

    @BeforeTest
    fun setUp() = runBlocking {
        // Bind 0 and read the port back off the socket we hold — probing a free port and re-binding
        // the number is a TOCTOU another process can win in the window (#1590).
        serverSocket = aSocket(selector).tcp().bind("127.0.0.1", 0)
        port = (serverSocket.localAddress as InetSocketAddress).port
    }

    @AfterTest
    fun tearDown() {
        serverSocket.close()
        selector.close()
    }

    @Test
    fun defaultSelfIdMintsADistinctIdentityPerCall() = weavePair(
        host = tcpLoomHost(serverSocket, selector),
        joiner = tcpLoomJoin(selector),
    ) { hostSeam, joinerSeam ->
        assertTrue(hostSeam.selfId.value.isNotBlank(), "a defaulted selfId must be non-blank")
        assertNotEquals(
            hostSeam.selfId,
            joinerSeam.selfId,
            "two factory calls must mint distinct identities (freshPeerId per call, #1405)",
        )
        assertEquals(
            setOf(hostSeam.selfId, joinerSeam.selfId),
            hostSeam.peers.value,
            "both defaulted identities must appear in the roster",
        )
    }

    @Test
    fun forwardsAnExplicitSelfIdToTheWovenSeam() {
        val hostId = PeerId("factory-host")
        val joinerId = PeerId("factory-joiner")
        weavePair(
            host = tcpLoomHost(serverSocket, selector, selfId = hostId),
            joiner = tcpLoomJoin(selector, selfId = joinerId),
        ) { hostSeam, joinerSeam ->
            assertEquals(hostId, hostSeam.selfId, "host factory must honour the supplied selfId")
            assertEquals(joinerId, joinerSeam.selfId, "joiner factory must honour the supplied selfId")
            assertEquals(
                setOf(hostId, joinerId),
                joinerSeam.peers.value,
                "the supplied id must cross the in-band handshake, not just sit on the loom",
            )
        }
    }

    /**
     * The canonical `dispatcher` slot is the seam's *scheduling* dispatcher — what [TcpLoom] calls
     * its `seamDispatcher`. `weave` builds a scope from exactly that one and refuses a virtual
     * `TestDispatcher`, so tripping the guard proves the argument arrived: a factory that dropped
     * it would fall back to the real default, clear the guard, and fail dialling port 1 with an
     * `IOException` instead.
     */
    @Test
    fun theDispatcherSlotBecomesTheSeamDispatcher() = runTest {
        val loom = tcpLoomJoin(selector, dispatcher = StandardTestDispatcher(testScheduler))
        val failure = assertFailsWith<IllegalStateException> {
            loom.weave(Rendezvous.Existing(TcpAddress("127.0.0.1", 1)))
        }
        assertTrue("TcpLoom" in failure.message!!, "diagnostic must name the type: ${failure.message}")
    }

    /**
     * `ioDispatcher` is a second, fabric-specific knob and not interchangeable with `dispatcher`:
     * the blocking socket reads are pinned to it by `tcpConnection`'s `flowOn`. Reading the peer's
     * handshake preamble is already such a read, so a dispatcher that never saw a dispatch was
     * never wired in.
     */
    @Test
    fun ioDispatcherRunsTheBlockingSocketReads() {
        val counting = CountingDispatcher(Dispatchers.IO)
        weavePair(
            host = tcpLoomHost(serverSocket, selector),
            joiner = tcpLoomJoin(selector, ioDispatcher = counting),
        ) { hostSeam, joinerSeam ->
            hostSeam.broadcast("ping".encodeToByteArray())
            assertEquals("ping", joinerSeam.incoming.first().decodeToString())
            assertTrue(
                counting.dispatches.get() > 0,
                "the supplied ioDispatcher must run the joiner's socket reads, saw no dispatch",
            )
        }
    }

    /**
     * The omissions are a decision, not an oversight, so they are pinned.
     *
     * `weaveTimeout` is absent because `weave` bounds neither `accept()` nor `connect()`, and
     * `policy` because `handshaking` — the identity negotiation this fabric goes through — hands
     * `identified` no [us.tractat.kuilt.core.DeliveryPolicy] (#2323). Either one added here would
     * be accepted and then silently dropped, which is what got a shared config bag rejected on
     * #1430 in the first place.
     *
     * Asserted over the JVM signature because absence is not otherwise observable at runtime, and
     * over the *whole ordered list* because erasure hides a type-by-type check: `PeerId` and
     * `Duration` are both value classes, so `selfId` appears here as `String` and a re-added
     * `weaveTimeout` would appear as `long`. The list also pins canonical order — the fabric's own
     * required arguments first, then the universal knobs.
     */
    @Test
    fun theSignatureExposesOnlyTheKnobsThisFabricHonours() {
        // selfId erases to String (PeerId is a value class over it).
        val universalKnobs = listOf(String::class.java, CoroutineContext::class.java, CoroutineDispatcher::class.java)
        assertEquals(
            listOf(ServerSocket::class.java, SelectorManager::class.java) + universalKnobs,
            factoryParameterTypes("tcpLoomHost"),
            "tcpLoomHost: (serverSocket, selector, selfId, dispatcher, ioDispatcher)",
        )
        assertEquals(
            listOf(SelectorManager::class.java) + universalKnobs,
            factoryParameterTypes("tcpLoomJoin"),
            "tcpLoomJoin: (selector, selfId, dispatcher, ioDispatcher)",
        )
    }

    // A `@JvmInline` value class in the signature makes Kotlin mangle the JVM method name
    // (`tcpLoomHost-<hash>`), so match on the prefix; the `$default` bridge carries the same prefix
    // and is excluded by name.
    private fun factoryParameterTypes(name: String): List<Class<*>> {
        val matches = Class.forName("us.tractat.kuilt.tcp.TcpFabricKt")
            .declaredMethods
            .filter { it.name.startsWith(name) && !it.name.endsWith("\$default") }
        assertEquals(1, matches.size, "expected one $name, found ${matches.map { it.name }}")
        return matches.single().parameterTypes.toList()
    }

    /**
     * Weave a real loopback pair, run [assertions] against both seams, then close them.
     *
     * `runBlocking` + a real-time [withTimeout]: this drives real sockets, and an inner
     * `withTimeout` under `runTest` would be *virtual* time, which never advances against a
     * blocking read.
     */
    private fun weavePair(
        host: TcpLoom,
        joiner: TcpLoom,
        assertions: suspend (hostSeam: Seam, joinerSeam: Seam) -> Unit,
    ) = runBlocking {
        withTimeout(10.seconds) {
            coroutineScope {
                val accepted = async { host.host(Pattern("factory")) }
                val joinerSeam = joiner.join(TcpAddress("127.0.0.1", port))
                val hostSeam = accepted.await()
                try {
                    assertions(hostSeam, joinerSeam)
                } finally {
                    hostSeam.close(CloseReason.Normal)
                    joinerSeam.close(CloseReason.Normal)
                }
            }
        }
    }

    /** Counts dispatches so a test can prove a dispatcher argument was actually wired in. */
    private class CountingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        val dispatches: AtomicInteger = AtomicInteger()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches.incrementAndGet()
            delegate.dispatch(context, block)
        }
    }
}
