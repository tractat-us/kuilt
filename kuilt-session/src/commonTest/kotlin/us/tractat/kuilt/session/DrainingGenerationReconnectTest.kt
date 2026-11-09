@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.MuxClientLoom
import us.tractat.kuilt.core.MuxServerLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.RoomAuthorizer
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.peerMesh
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.partition.RoomId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A joiner whose first re-weave lands on a **draining** generation — one whose roster already shows
 * the host gone while its state still reads `Woven` — dials again the moment that generation tears,
 * not one `resumeTimeout` plus one `interval` later (#3151).
 *
 * The window is real: `MeshSeam.removePeer` publishes the drained roster before `tearDown` latches
 * `Torn`, and the joiner's detector starts a reconnect on the roster drop alone. `MuxClientLoom`
 * re-dials only a base that already reads `Torn`, so a re-weave inside that window hands back the
 * dying generation. Under real threads the window is a few instructions wide, so this test widens it
 * on purpose: [DrainingSeam] passes the roster drop through at once but holds the base's `Torn` until
 * the test releases it. That makes the race an ordering the test chooses rather than a scheduling
 * accident.
 *
 * Before the fix the Resume sent on the dying generation went nowhere, the flight waited out
 * `resumeTimeout`, the loop slept one `interval`, and only then did the second re-weave dial — 20 s at
 * the defaults, which is what tractat-us/fireworks-compose#7129 measured in CI.
 */
class DrainingGenerationReconnectTest {

    private companion object {
        /** How many re-weaves have their fresh generation killed in the tight-loop test. */
        const val KILLED_GENERATIONS = 8
    }

    private val config = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 200.milliseconds,
        reconnectWindow = 2.seconds,
    )

    private val nameOf: (Rendezvous) -> String = { rv ->
        when (rv) {
            is Rendezvous.New -> rv.pattern.sessionName
            is Rendezvous.Existing -> rv.tag.sessionName
        }
    }

    @Test
    fun `a re-weave handed a draining generation dials again as soon as it tears`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val h = harness()

            h.hostRoom.roster.first { it.size == 1 }
            h.joinerRoom.roster.first { it.isNotEmpty() }
            assertNotNull(h.joinerRoom.resumeToken)
            assertEquals(1, h.dials(), "precondition: one base dial to join")

            val resumed = async {
                h.joinerRoom.events.first { it is MembershipEvent.Resumed || it is MembershipEvent.HostLost }
            }

            // The link closes from the remote end. The joiner's roster loses the host at once; its base
            // still reads Woven, because DrainingSeam holds the Torn back.
            h.cutFirstLink()
            runCurrent()

            // The rig fired: the joiner re-wove, and that re-weave was handed the dying generation
            // rather than dialling a fresh one.
            assertTrue(h.reweaves() >= 1, "the roster drop must have started a re-weave (re-weaves: ${h.reweaves()})")
            assertEquals(1, h.dials(), "the first re-weave must have reused the draining generation")
            assertFalse(resumed.isCompleted, "nothing can have resumed over the dying generation")

            // The dying generation now tears.
            h.firstBase().releaseTorn()
            advanceTimeBy(config.interval.inWholeMilliseconds / 2)
            runCurrent()

            // Half an interval later: well inside one interval, and far short of the
            // resumeTimeout + interval the unfixed loop spends before its next dial.
            assertEquals(2, h.dials(), "the joiner must dial again as soon as its generation tears")
            assertTrue(resumed.isCompleted, "the resume over the fresh generation must have landed")
            assertIs<MembershipEvent.Resumed>(resumed.await())
        }

    /**
     * The other interleaving (#3151): the dying generation tears just **after** the re-weave hands it
     * back, before the machine has seen it `Woven`. Nothing but a weave re-points the handle, so a
     * wait for `Woven` alone would sit out the whole reconnect window. The fireworks-compose CI run
     * that read `dials=1` 45 s after the cut fits this shape.
     */
    @Test
    fun `a generation that tears while the re-weave waits for Woven is dialled again at once`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val h = harness(releaseTornOnFirstReweave = true)

            h.hostRoom.roster.first { it.size == 1 }
            h.joinerRoom.roster.first { it.isNotEmpty() }
            assertNotNull(h.joinerRoom.resumeToken)
            assertEquals(1, h.dials(), "precondition: one base dial to join")

            val resumed = async {
                h.joinerRoom.events.first { it is MembershipEvent.Resumed || it is MembershipEvent.HostLost }
            }

            h.cutFirstLink()
            runCurrent()

            assertEquals(2, h.reweaves(), "the stale generation's re-weave, then one that dials (re-weaves: ${h.reweaves()})")
            assertEquals(2, h.dials(), "the joiner must dial again as soon as its generation tears")
            assertTrue(resumed.isCompleted, "the resume over the fresh generation must have landed")
            assertIs<MembershipEvent.Resumed>(resumed.await())
        }

    /**
     * The immediate retry is bounded: a base that dies on **every** dial is retried at most once at
     * once per tear, then at the normal interval, rather than in a tight loop that redials as fast as
     * the network allows for the whole reconnect window.
     *
     * Every fresh generation through re-weave [KILLED_GENERATIONS] has its link cut the moment it is
     * handed back. An unbounded retry would run all of them in zero virtual time; the bounded one
     * re-weaves twice, the stale generation and one immediate dial, and then waits an interval.
     */
    @Test
    fun `a base that dies on every dial is retried at the interval and not in a tight loop`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val h = harness(releaseTornOnFirstReweave = true, killFreshGenerationsThrough = KILLED_GENERATIONS)

            h.hostRoom.roster.first { it.size == 1 }
            h.joinerRoom.roster.first { it.isNotEmpty() }
            assertNotNull(h.joinerRoom.resumeToken)

            val resumed = async {
                h.joinerRoom.events.first { it is MembershipEvent.Resumed || it is MembershipEvent.HostLost }
            }

            h.cutFirstLink()
            runCurrent()

            assertEquals(
                2,
                h.reweaves(),
                "with no virtual time passed, only the stale generation and one immediate dial may have run",
            )
            assertFalse(resumed.isCompleted, "every generation so far has died")

            // At two re-weaves per interval the last killed generation is passed well inside the
            // window, and the next dial survives.
            repeat(KILLED_GENERATIONS) { advanceTimeBy(config.interval.inWholeMilliseconds) }
            runCurrent()
            assertTrue(h.reweaves() > KILLED_GENERATIONS, "the loop must carry on past the killed generations")
            assertIs<MembershipEvent.Resumed>(resumed.await())
        }

    // ── Harness ──────────────────────────────────────────────────────────────

    private class Harness(
        val hostRoom: SeamRoom,
        val joinerRoom: SeamRoom,
        val dials: () -> Int,
        val reweaves: () -> Int,
        val firstBase: () -> DrainingSeam,
        val cutFirstLink: suspend () -> Unit,
    )

    /**
     * A host room over a [MuxServerLoom] hub and a joiner over a [MuxClientLoom] whose base is a
     * [peerMesh] spoke — the topology `KtorMeshClientLoom` produces. Only the **first** base is wrapped
     * in a [DrainingSeam]; every later dial is a plain spoke.
     *
     * @param releaseTornOnFirstReweave release the draining base's `Torn` the moment the first
     *   re-weave hands that base back, so the handle tears after the machine has its seam but before
     *   it has seen `Woven`.
     * @param killFreshGenerationsThrough cut the link of every base a re-weave dials, up to and
     *   including this re-weave number, so each fresh generation dies as soon as it is handed back.
     *   Zero (the default) leaves every fresh generation alone.
     */
    private suspend fun TestScope.harness(
        releaseTornOnFirstReweave: Boolean = false,
        killFreshGenerationsThrough: Int = 0,
    ): Harness {
        val dispatcher = coroutineContext[ContinuationInterceptor]
        checkNotNull(dispatcher) { "the test dispatcher must be present in the test coroutine context" }
        val source = InMemoryConnectionSource()
        val serverLoom = MuxServerLoom(
            source = source,
            scope = backgroundScope,
            selfId = PeerId("server"),
            authorizer = RoomAuthorizer.AllowAll,
            dispatcher = dispatcher,
            random = Random(13L),
        )
        val hostRoom = SeamRoom(
            seam = serverLoom.host(Pattern("table-7")),
            role = SessionRole.Host,
            memberName = "table-7",
            scope = backgroundScope,
            clock = { Instant.fromEpochMilliseconds(0L) },
            heartbeatConfig = config,
            roomId = RoomId("room-1"),
        ).also { it.start() }

        val clientId = PeerId("client")
        var dials = 0
        var first: DrainingSeam? = null
        var firstLink: Pair<Connection, Connection>? = null
        var latestLink: Pair<Connection, Connection>? = null
        val base = object : Loom {
            override suspend fun weave(rendezvous: Rendezvous): Seam {
                dials++
                val (serverConn, clientConn) = connectionPair()
                source.offer(serverConn)
                latestLink = serverConn to clientConn
                val spoke = peerMesh(clientId, listOf(clientConn), dispatcher, Random(dials.toLong()))
                if (dials > 1) return spoke
                firstLink = serverConn to clientConn
                return DrainingSeam(spoke, backgroundScope).also { first = it }
            }
        }
        val muxClient = MuxClientLoom(base, Rendezvous.New(Pattern("base")), backgroundScope, nameOf)
        val tag = InMemoryTag("table-7")
        var reweaves = 0
        val joinerRoom = SeamRoom(
            seam = muxClient.join(tag),
            role = SessionRole.Joiner,
            memberName = "client",
            scope = backgroundScope,
            clock = { Instant.fromEpochMilliseconds(0L) },
            heartbeatConfig = config,
            roomId = null,
            reweave = {
                reweaves++
                val dialsBefore = dials
                muxClient.join(tag).also {
                    if (releaseTornOnFirstReweave && reweaves == 1) checkNotNull(first).releaseTorn()
                    if (dials > dialsBefore && reweaves <= killFreshGenerationsThrough) {
                        val (serverConn, clientConn) = checkNotNull(latestLink)
                        serverConn.close()
                        clientConn.close()
                    }
                }
            },
        ).also { it.start() }

        return Harness(
            hostRoom = hostRoom,
            joinerRoom = joinerRoom,
            dials = { dials },
            reweaves = { reweaves },
            firstBase = { checkNotNull(first) { "the first base has not been dialled" } },
            cutFirstLink = {
                val (serverConn, clientConn) = checkNotNull(firstLink) { "the first base has not been dialled" }
                // Remote end first: the spoke learns of the close from its inbound completing. The
                // client end is closed too so the hub sees the drop and opens its resume window.
                serverConn.close()
                clientConn.close()
            },
        )
    }
}

/**
 * A base [Seam] caught **draining**: its [peers] follow the delegate at once, but its [state] holds
 * the delegate's [SeamState.Torn] back until [releaseTorn]. While held it is a seam that reads
 * `Woven` with nobody to send to, exactly as a `MeshSeam` with an empty link map does: a broadcast
 * is accepted and goes nowhere, and a directed send finds no such peer. Its [incoming] likewise completes only once the `Torn` is released.
 */
private class DrainingSeam(
    private val delegate: Seam,
    scope: CoroutineScope,
) : Seam {
    private val released = CompletableDeferred<Unit>()
    private val _peers = MutableStateFlow(delegate.peers.value)
    private val _state = MutableStateFlow(delegate.state.value)

    init {
        scope.launch { delegate.peers.collect { _peers.value = it } }
        scope.launch {
            delegate.state.collect { next ->
                if (next is SeamState.Torn) released.await()
                _state.value = next
            }
        }
    }

    /**
     * Let the delegate's held `Torn` through: the draining generation is now visibly dead.
     *
     * Published here, synchronously, rather than left to the mirroring coroutine: one dispatch hop
     * fewer between the release and the channel view above this seam tearing. The second test in
     * [DrainingGenerationReconnectTest] depends on that hop count to land the tear before the
     * machine's wait for `Woven` sees its first value.
     */
    fun releaseTorn() {
        released.complete(Unit)
        delegate.state.value.let { if (it is SeamState.Torn) _state.value = it }
    }

    override val selfId: PeerId get() = delegate.selfId
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()
    override val state: StateFlow<SeamState> = _state.asStateFlow()
    override val incoming: Flow<Swatch> = delegate.incoming.onCompletion { released.await() }

    override suspend fun broadcast(payload: ByteArray) {
        if (delegate.state.value is SeamState.Torn) return
        delegate.broadcast(payload)
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        if (delegate.state.value is SeamState.Torn) throw PeerNotConnected(peer)
        delegate.sendTo(peer, payload)
    }

    override suspend fun close(reason: CloseReason): Unit = delegate.close(reason)
}
