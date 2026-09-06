@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.fabric

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * A mesh handshake abandoned by **cancellation** must still close the connection it was handed (#2587).
 *
 * `handshakeLink` is the only frame that holds a raw [Connection] across both of the preamble's
 * suspension points (`send`, then the `firstFrame` read). Until #2587 it closed the conn on **no** exit
 * but the successful one, and every caller's compensating close is keyed on a *throw*:
 * [acceptPump]'s `runCatchingCancellable { … }.onFailure { conn.close() }` and the voter supervisor's
 * identical shape both **rethrow** a `CancellationException` by construction, so `onFailure` never runs.
 * The two paths are disjoint — throw-or-timeout is covered, cancellation is not — and cancellation is
 * the *ordinary* path: it is what a formation timeout, a `meshScope.cancel()` and a plain
 * `VoterMesh.close()` all deliver.
 *
 * The conn a leak strands is not inert. Over a WebSocket fabric it is a live client session the peer
 * still sees as ESTABLISHED, held until the caller-owned `HttpClient` is closed — a zombie from a
 * voter that has given up.
 *
 * ## What each arm's rig switches off
 *
 * Both arms hang the handshake the same way: the far end of a [connectionPair] is **never driven**, so
 * the preamble `send` completes and the `firstFrame` read suspends forever. Two knobs decide whether
 * the arm is measuring the leak or something else:
 *
 * - **Reading the far end's first frame is the rig-fired precondition, not decoration.** It proves the
 *   handshake reached `firstFrame` — i.e. a conn really exists and really is mid-preamble. Without it a
 *   green would be indistinguishable from a rig in which no conn was ever created (the shape that makes
 *   the leak *unreachable* on `SeverableInMemoryVoterFabric`, whose severed `openLink` suspends before
 *   `connectionPair()` and so never mints a conn to leak).
 * - **[HANDSHAKE_CEILING] is deliberately enormous in [cancellingTheAcceptPumpClosesAConnLeftMidHandshake].**
 *   `acceptPump` *already* closes on its own `handshakeTimeout` (`completed == null`), which is a
 *   different obligation entirely. A ceiling the test could advance past would let that arm produce the
 *   green and the cancellation leak would go on unmeasured.
 */
class MeshHandshakeCancellationCloseTest {

    /**
     * Construction-time handshake (`buildMesh`): cancelling the caller mid-preamble must close the conn.
     *
     * `buildMesh` closes nothing on any failing exit, and the conn is not reachable from anywhere else —
     * no seam was ever returned, and the conn belongs to the caller's transport rather than to any scope
     * the mesh owns. This is the reachable half of that site: every in-tree production caller hands
     * `buildMesh` exactly one connection (`KtorMeshClientLoom`, `MuxServerLoom`), so cancelling an
     * ordinary `withTimeout { loom.join(tag) }` orphans it.
     */
    @Test
    fun aConstructionHandshakeCancelledMidPreambleClosesItsConn() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val (mine, theirs) = connectionPair()
            val conn = CloseCountingConnection(mine)

            val build = async { hubMesh(PeerId("self"), listOf(conn), dispatcher, Random(7)) }

            // Rig-fired precondition: our preamble crossed the wire, so the handshake is suspended in
            // `firstFrame` awaiting a reply the far end will never send.
            val preamble = theirs.incoming.first()
            assertAll(
                { assertEquals(PeerId("self"), meshHelloOf(preamble).peerId, "the far end saw our MeshHello") },
                { assertFalse(build.isCompleted, "construction is still mid-handshake") },
                { assertEquals(0, conn.closeCalls, "nothing has closed the conn yet") },
            )

            build.cancelAndJoin()

            assertTrue(
                conn.closed.isCompleted,
                "a construction handshake abandoned by cancellation must close its connection: nothing " +
                    "else can — no seam was returned and the conn is the caller transport's, not a scope's",
            )
        }

    /**
     * Accept-pump path: cancelling the pump job while a conn is mid-preamble must close that conn.
     *
     * This is the site #2587's own status block never named, and it sits on the very path the
     * formation-failure teardown exercises — `assembleVoterMesh`'s `catch` calls `meshScope.cancel()`,
     * which cancels the pumps, immediately adjacent to the code that carefully closes the *dialed*
     * conns. `acceptPump` closes on a `handle` throw and on its own handshake timeout; on cancellation
     * `runCatchingCancellable` rethrows and both of its closes are skipped.
     */
    @Test
    fun cancellingTheAcceptPumpClosesAConnLeftMidHandshake() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val hub = hubMesh(PeerId("hub"), emptyList(), dispatcher, Random(7))
            val (theirs, mine) = connectionPair()
            val accepted = CloseCountingConnection(mine)
            val source = OneShotConnectionSource(accepted)

            val pump = acceptPump(source, handshakeTimeout = HANDSHAKE_CEILING) { conn -> hub.addLink(conn) }
            runCurrent()

            // Rig-fired precondition, same shape as the arm above: the hub's own MeshHello reached the
            // far end, so the pump's child coroutine is suspended inside `firstFrame`.
            val preamble = theirs.incoming.first()
            assertAll(
                { assertEquals(PeerId("hub"), meshHelloOf(preamble).peerId, "the far end saw the hub's MeshHello") },
                { assertTrue(source.accepted.isCompleted, "the pump really accepted the conn") },
                { assertEquals(setOf(PeerId("hub")), hub.peers.value, "the link was never published") },
                { assertEquals(0, accepted.closeCalls, "nothing has closed the conn yet") },
            )

            pump.cancelAndJoin()

            assertAll(
                {
                    assertTrue(
                        accepted.closed.isCompleted,
                        "a pump cancelled while a conn is mid-handshake must close it; the pump's own " +
                            "throw/timeout closes are keyed on failure modes a cancellation never reaches",
                    )
                },
                {
                    assertEquals(
                        1,
                        accepted.closeCalls,
                        "exactly once: the handshake frame closes it and the pump's own closes stay " +
                            "unreached on this exit, so the fix adds an obligation rather than a second close",
                    )
                },
            )
        }

    /**
     * The admission-rejection route's close count is **unchanged** by the #2587 fix — it stays two.
     *
     * Measured rather than reasoned about, because the audit that produced this change predicted a
     * *third* close here and that prediction is wrong: `admitLink` runs **after** `handshakeLink` has
     * already returned a `Link`, so the new failure-close is never on this path. The two are
     * `admitLink`'s own rejection close and [acceptPump]'s `onFailure` close on the resulting
     * `LinkRejectedException`, and both predate this change.
     *
     * Worth pinning rather than deleting once measured: it is the one route where a *successful*
     * handshake is followed by a close, so it is where a future "close in `handshakeLink`'s `finally`
     * instead of its `catch`" refactor would first show up as an over-reach.
     */
    @Test
    fun anAdmissionRejectionClosesTheConnExactlyTwice() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val hub = hubMesh(
                selfId = PeerId("hub"),
                connections = emptyList(),
                dispatcher = dispatcher,
                random = Random(7),
                admission = LinkAdmission { _, _ -> false },
            )
            val (theirs, mine) = connectionPair()
            val accepted = CloseCountingConnection(mine)
            val rejections = mutableListOf<Throwable>()

            val pump = acceptPump(
                source = OneShotConnectionSource(accepted),
                handshakeTimeout = HANDSHAKE_CEILING,
                onFailure = { rejections += it },
            ) { conn -> hub.addLink(conn) }
            runCurrent()

            // Complete the far end's half of the preamble so the handshake SUCCEEDS and admission is
            // reached — the arms above never get here, which is exactly why they cannot see this route.
            theirs.incoming.first()
            theirs.send(MeshWire.encodeHello(PeerId("spoke"), meshNonce(1)))
            runCurrent()

            assertAll(
                {
                    assertTrue(
                        rejections.any { it is LinkRejectedException },
                        "rig: the handshake completed and admission rejected it — reached: $rejections",
                    )
                },
                { assertEquals(setOf(PeerId("hub")), hub.peers.value, "a rejected link is never published") },
                {
                    assertEquals(
                        2,
                        accepted.closeCalls,
                        "admitLink closes the rejected link and the pump closes it again on the " +
                            "LinkRejectedException — both predate #2587, which touches only exits that " +
                            "produce no Link at all",
                    )
                },
            )

            pump.cancelAndJoin()
        }

    private companion object {
        /**
         * `acceptPump`'s handshake ceiling for the arm above — far past anything the test advances, so
         * the pump's `completed == null` close provably cannot be what produces the green.
         */
        val HANDSHAKE_CEILING = 1.hours
    }
}

/**
 * A [ConnectionSource] that yields [first] once and then suspends forever, so exactly one pump child
 * exists. [accepted] completes when the pump has actually taken it — the "the rig fired" half of a
 * close assertion that would otherwise be green by absence.
 */
internal class OneShotConnectionSource(private val first: Connection) : ConnectionSource {
    private val handedOut = atomic(false)
    val accepted: CompletableDeferred<Unit> = CompletableDeferred()

    override suspend fun accept(): Connection =
        if (handedOut.compareAndSet(expect = false, update = true)) {
            accepted.complete(Unit)
            first
        } else {
            CompletableDeferred<Connection>().await()
        }
}

/**
 * One link end that records what was done to it: [closed] completes on the first [close] and
 * [closeCalls] counts every one.
 *
 * The count is what separates "closed once" from "closed again by an over-reaching teardown" — a
 * [CompletableDeferred] latches, so [closed] alone cannot see a second close.
 *
 * ## Its [close] **suspends** first, and that is what pins the `NonCancellable` shield
 *
 * Every real transport close suspends; [connectionPair]'s does not (`Spool.close` is a plain channel
 * close), and neither does the [singleCollection] wrapper's own bookkeeping. Measured on this branch:
 * with a non-suspending recorder both arms above stayed **green after `withContext(NonCancellable)`
 * was deleted from `handshakeLink`** — the close ran to completion on an already-cancelled job simply
 * because it never reached a suspension point. That would have left the fix's whole design decision
 * (a shield, rather than `closeBestEffort`'s live `ensureActive`) unmeasured, and it is the "fixture
 * configured at exactly the value where the property cannot fail" shape.
 *
 * The [yield] is placed **before** the recording deliberately: on a cancelled job an unshielded close
 * throws there, so nothing is recorded and the arm reds. It is not a delay — no virtual time is
 * involved — so it adds no timing sensitivity.
 */
internal class CloseCountingConnection(private val raw: Connection) : Connection {
    private val calls = atomic(0)

    /** Completes when [close] is **called** — the obligation being pinned. */
    val closed: CompletableDeferred<Unit> = CompletableDeferred()

    /** How many times [close] has been called on this end. */
    val closeCalls: Int get() = calls.value

    override val maxFrameBytes: Int? get() = raw.maxFrameBytes
    override val incoming: Flow<ByteArray> get() = raw.incoming
    override suspend fun send(frame: ByteArray) = raw.send(frame)

    /** Records the call **before** delegating (the obligation is that we were closed, not that the
     *  underlying spool accepted it) but **after** one suspension point — see the class KDoc. */
    override suspend fun close() {
        yield()
        calls.incrementAndGet()
        closed.complete(Unit)
        raw.close()
    }
}
