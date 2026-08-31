@file:Suppress("ForbiddenImport") // real-network loopback test — a TCP socket needs real production dispatchers

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
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.Overflow
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
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
 * point of the convention is to expose only knobs this fabric can honour, the one it deliberately
 * omits (`weaveTimeout`) is pinned as absent by
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
     * The omission is a decision, not an oversight, so it is pinned.
     *
     * `weaveTimeout` is absent because `weave` bounds neither `accept()` nor `connect()`; added
     * here it would be accepted and then silently dropped, which is what got a shared config bag
     * rejected on #1430 in the first place. `policy` was absent for that same reason until #2323
     * taught `handshaking` to carry a [DeliveryPolicy] through to `identified`; it is present now
     * and [aLossyPolicyReachesTheWovenSeam] proves it is honoured rather than merely accepted.
     *
     * Asserted over the JVM signature because absence is not otherwise observable at runtime, and
     * over the *whole ordered list* because erasure hides a type-by-type check: `PeerId` and
     * `Duration` are both value classes, so `selfId` appears here as `String` and a re-added
     * `weaveTimeout` would appear as `long`. The list also pins canonical order — the fabric's own
     * required arguments first, then the universal knobs.
     */
    @Test
    fun theSignatureExposesOnlyTheKnobsThisFabricHonours() {
        // selfId erases to String (PeerId is a value class over it); DeliveryPolicy is a plain
        // data class, so it survives erasure as itself.
        val universalKnobs = listOf(
            String::class.java,
            DeliveryPolicy::class.java,
            CoroutineContext::class.java,
            CoroutineDispatcher::class.java,
        )
        assertEquals(
            listOf(ServerSocket::class.java, SelectorManager::class.java) + universalKnobs,
            factoryParameterTypes("tcpLoomHost"),
            "tcpLoomHost: (serverSocket, selector, selfId, policy, dispatcher, ioDispatcher)",
        )
        assertEquals(
            listOf(SelectorManager::class.java) + universalKnobs,
            factoryParameterTypes("tcpLoomJoin"),
            "tcpLoomJoin: (selector, selfId, policy, dispatcher, ioDispatcher)",
        )
    }

    /**
     * `policy` must *reach the woven seam*, not merely appear on the signature (#2323).
     *
     * The whole reason this knob was withheld from these factories is that an accepted-then-dropped
     * argument is worse than an absent one, so presence on the parameter list proves nothing on its
     * own — the three hops `tcpLoomJoin` → `TcpLoom` → `weave` → `handshaking` each had to be wired.
     *
     * The joiner asks for a **capacity-1 `FAIL`** inbox and then never collects `incoming`. The
     * host sends four frames: the first fills the joiner's spool, and the second makes
     * `Spool.deliver` raise `FrameOverflow` inside `LinkSeam`'s read loop, which treats any read
     * failure as a lost wire and tears the seam down. So an overflow that reaches the seam is
     * observable at the [Seam] contract level, as [SeamState.Torn].
     *
     * Under the default [DeliveryPolicy.Reliable] the same four frames sit in a 256-deep buffer and
     * the seam stays `Woven` forever — which is exactly what a dropped `policy` argument produces,
     * and why the wait below expires rather than passing on a technicality. Real time, not virtual:
     * `TcpLoom` refuses a `TestDispatcher`, and the ceiling is a generous wedge backstop — a live
     * overflow latches in milliseconds. `withTimeoutOrNull`, not `withTimeout`, so the *assertion*
     * reports the failure with the seam's actual state; a thrown
     * `TimeoutCancellationException` would preempt it and say only that ten seconds passed.
     */
    @Test
    fun aLossyPolicyReachesTheWovenSeam() = weavePair(
        host = tcpLoomHost(serverSocket, selector),
        joiner = tcpLoomJoin(selector, policy = DeliveryPolicy(capacity = 1, overflow = Overflow.FAIL)),
    ) { hostSeam, joinerSeam ->
        repeat(4) { hostSeam.broadcast(byteArrayOf(it.toByte())) }
        val torn = withTimeoutOrNull(5.seconds) { joinerSeam.state.first { it is SeamState.Torn } }
        assertTrue(
            torn is SeamState.Torn,
            "a capacity-1 FAIL inbox must overflow on the second frame; the seam is still " +
                "${joinerSeam.state.value}, so the policy never reached it",
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
