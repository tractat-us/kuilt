package us.tractat.kuilt.multipeer.bridge

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import platform.MultipeerConnectivity.MCEncryptionRequired
import platform.MultipeerConnectivity.MCPeerID
import platform.MultipeerConnectivity.MCSession
import platform.MultipeerConnectivity.MCSessionState
import platform.posix.usleep
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.multipeer.internal.MCSessionLink
import us.tractat.kuilt.multipeer.internal.toNSData
import us.tractat.kuilt.test.assertAll
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.concurrent.FutureState
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.native.setUnhandledExceptionHook
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The lifetime half of `mc_session_close` and of replacing a session callback, against the real exports,
 * a real [MCSessionLink] and a real `MCSession`. The link's delegate is driven by hand, as in
 * `MCSessionLinkTearCollapseTest`, because MC fires it only with a second device. Pumps run on the
 * bridge's production `Dispatchers.Default`.
 *
 * The rig is `BridgeBrowserQuiesceTest`'s: a window forced inside a `staticCFunction`, a wedge bounded on
 * a [Worker] into a named red, and an unhandled-exception hook that records what would abort a shipped
 * process. The same limit applies too: these callbacks have no JNA trampoline, so what is proven is the
 * premise the JVM relies on.
 *
 * The arms named `…FromInside…` are what a plain join would break. They pass on the code before any join,
 * and they are here because `BridgePeerLink` can reach them: `BridgePeerLinkTeardownTest` runs that route.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class, ObsoleteWorkersApi::class)
class BridgeSessionQuiesceTest {
    private val uncaughtLock = reentrantLock()
    private val uncaught = mutableListOf<Throwable>()
    private var previousHook: ReportUnhandledExceptionHook? = null
    private val caller = Worker.start(name = "session-quiesce-caller")

    private fun uncaughtSoFar(): List<Throwable> = uncaughtLock.withLock { uncaught.toList() }

    @BeforeTest
    fun recordInsteadOfAborting() {
        SessionProbe.reset()
        previousHook = setUnhandledExceptionHook { failure -> uncaughtLock.withLock { uncaught += failure } }
    }

    @AfterTest
    fun restoreTheHook() {
        setUnhandledExceptionHook(previousHook)
        // Not awaited: after a wedge the worker never gets as far as terminating.
        caller.requestTermination(processScheduledJobs = false)
    }

    @Test
    fun closeWaitsForADataCallbackAlreadyInFlight() = withLink { link, session ->
        mc_session_set_data_callback(session, SessionProbe.dataHeld)
        link.deliverFrame()
        await("the data callback is entered") { SessionProbe.entered.value == 1 }
        val exitedBeforeClose = SessionProbe.exited.value

        SessionProbe.release.value = true
        val exitedAtReturn = close(session)

        await("the held callback finishes before cleanup") { SessionProbe.exited.value == 1 }
        assertAll(
            { assertEquals(0, exitedBeforeClose, "the rig must hold the callback across the close") },
            { assertEquals(1, exitedAtReturn, "a data callback in flight must finish before close returns") },
            { assertEquals(emptyList(), uncaughtSoFar()) },
        )
    }

    @Test
    fun closeWaitsForAPeerStateCallbackAlreadyInFlight() = withLink { link, session ->
        mc_session_set_peer_state_callback(session, SessionProbe.peerHeld)
        link.connect("guest")
        await("the peer-state callback is entered") { SessionProbe.entered.value == 1 }
        val exitedBeforeClose = SessionProbe.exited.value

        SessionProbe.release.value = true
        val exitedAtReturn = close(session)

        await("the held callback finishes before cleanup") { SessionProbe.exited.value == 1 }
        assertAll(
            { assertEquals(0, exitedBeforeClose, "the rig must hold the callback across the close") },
            { assertEquals(1, exitedAtReturn, "a peer-state callback in flight must finish before close returns") },
            { assertEquals(emptyList(), uncaughtSoFar()) },
        )
    }

    /** What a join cannot do: a pump waiting for itself never returns, and this callback is that pump. */
    @Test
    fun closeFromInsideACallbackOfThatSessionReturns() = withLink { link, session ->
        SessionProbe.session.value = session
        mc_session_set_peer_state_callback(session, SessionProbe.peerClosesTheSession)
        link.connect("guest")

        await("mc_session_close returns inside the callback") { SessionProbe.closedInside.value == 1 }

        val torn = link.state.value
        assertAll(
            { assertIs<SeamState.Torn>(torn, "the close must still have closed the link") },
            { assertEquals(emptyList(), uncaughtSoFar()) },
        )
    }

    /**
     * A close from inside one callback gives up only on waiting for that callback. The other pump can be
     * inside its own, and the guarantee still covers it.
     */
    @Test
    fun closeFromInsideACallbackStillWaitsForTheOtherPump() = withLink { link, session ->
        SessionProbe.session.value = session
        mc_session_set_data_callback(session, SessionProbe.dataHeld)
        mc_session_set_peer_state_callback(session, SessionProbe.peerClosesTheSession)
        link.deliverFrame()
        await("the data callback is entered") { SessionProbe.entered.value == 1 }
        val exitedBeforeClose = SessionProbe.exited.value

        SessionProbe.release.value = true
        link.connect("guest")
        await("mc_session_close returns inside the callback") { SessionProbe.closedInside.value == 1 }

        await("the held callback finishes before cleanup") { SessionProbe.exited.value == 1 }
        assertAll(
            { assertEquals(0, exitedBeforeClose, "the rig must hold the callback across the close") },
            {
                assertEquals(
                    1,
                    SessionProbe.exitedWhenClosedInside.value,
                    "a close from inside one callback must still wait for the other pump's",
                )
            },
            { assertEquals(emptyList(), uncaughtSoFar()) },
        )
    }

    /**
     * A second frame is already queued when the replacement starts. With no wait the next callback takes it
     * at once, while the old one is still held: the overlap is forced rather than raced. Either callback may
     * legally get that frame, since the replacement has not returned, so the frames are counted across both.
     * The third frame comes after the return, so a replacement that left no pump running never reaches three.
     */
    @Test
    fun replacingTheDataCallbackWaitsForTheOldOneAndLosesNoFrame() = withLink { link, session ->
        mc_session_set_data_callback(session, SessionProbe.dataHeld)
        link.deliverFrame()
        await("the old callback is entered") { SessionProbe.entered.value == 1 }
        link.deliverFrame()
        val exitedBeforeReplace = SessionProbe.exited.value

        SessionProbe.release.value = true
        val runningAtReturn = replaceData(session)
        link.deliverFrame()
        await("all three frames arrive") { SessionProbe.entered.value + SessionProbe.nextEntries.value == 3 }
        val runningAtFirstNext = SessionProbe.oldRunningAtFirstNext.value

        close(session)
        assertAll(
            { assertEquals(0, exitedBeforeReplace, "the rig must hold the callback across the replacement") },
            { assertEquals(0, runningAtReturn, "the old callback must finish before the replacement returns") },
            { assertEquals(0, runningAtFirstNext, "two data callbacks must never overlap") },
            { assertEquals(emptyList(), uncaughtSoFar()) },
        )
    }

    @Test
    fun replacingThePeerStateCallbackWaitsForTheOldOne() = withLink { link, session ->
        mc_session_set_peer_state_callback(session, SessionProbe.peerHeld)
        link.connect("guest")
        await("the old callback is entered") { SessionProbe.entered.value == 1 }
        val exitedBeforeReplace = SessionProbe.exited.value

        SessionProbe.release.value = true
        val runningAtReturn = replacePeerState(session)
        await("the next callback delivers") { SessionProbe.nextEntries.value >= 1 }
        val runningAtFirstNext = SessionProbe.oldRunningAtFirstNext.value

        close(session)
        assertAll(
            { assertEquals(0, exitedBeforeReplace, "the rig must hold the callback across the replacement") },
            { assertEquals(0, runningAtReturn, "the old callback must finish before the replacement returns") },
            { assertEquals(0, runningAtFirstNext, "two peer-state callbacks must never overlap") },
            { assertEquals(emptyList(), uncaughtSoFar()) },
        )
    }

    /** The replacement has to wait for the callback making the call, and so cannot do that before returning. */
    @Test
    fun replacingACallbackFromInsideItselfReturnsAndDoesNotOverlap() = withLink { link, session ->
        SessionProbe.session.value = session
        mc_session_set_data_callback(session, SessionProbe.dataReplacesItself)
        link.deliverFrame()
        link.deliverFrame()
        await("the replacement returns inside the callback") { SessionProbe.replacedInside.value == 1 }
        await("the next callback delivers") { SessionProbe.nextEntries.value >= 1 }
        val runningAtFirstNext = SessionProbe.oldRunningAtFirstNext.value

        close(session)
        assertAll(
            { assertEquals(0, runningAtFirstNext, "the next callback must wait for the one that installed it") },
            { assertEquals(emptyList(), uncaughtSoFar()) },
        )
    }

    /**
     * A close right behind a self-replacement. The new pump is waiting for this callback, and this
     * callback is about to wait for the pumps, so a close that could not end that wait would wedge here.
     */
    @Test
    fun closeFromInsideACallbackThatJustReplacedItselfReturns() = withLink { link, session ->
        SessionProbe.session.value = session
        mc_session_set_data_callback(session, SessionProbe.dataReplacesItselfThenCloses)
        link.deliverFrame()

        await("mc_session_close returns inside the callback") { SessionProbe.closedInside.value == 1 }

        val torn = link.state.value
        assertAll(
            { assertIs<SeamState.Torn>(torn, "the close must still have closed the link") },
            { assertEquals(emptyList(), uncaughtSoFar()) },
        )
    }

    /**
     * A throw from `link.incoming` under a `SupervisorJob` is the Kotlin/Native abort route. The hook turns
     * that abort into a list entry, and [FailingIncomingSeam] counts the throw, so the rig cannot pass by
     * never having collected.
     */
    @Test
    fun aFailingIncomingFlowDoesNotReachTheUnhandledExceptionRoute() {
        val link = FailingIncomingSeam()
        val state = BridgeSessionState(link)
        val ref = StableRef.create(state)
        mc_session_set_data_callback(ref.asCPointer(), SessionProbe.dataHeld)

        await("the flow fails") { link.collections.value == 1 }
        await("the pump ends") { state.scope.coroutineContext.job.children.none() }

        val uncaughtAtEnd = uncaughtSoFar()
        mc_session_close(ref.asCPointer())
        assertEquals(emptyList(), uncaughtAtEnd, "a failed flow must end its pump, not the process")
    }

    /** `mc_session_close`, bounded from outside. Returns the exit count read on the closing thread. */
    private fun close(session: COpaquePointer): Int {
        val returned = caller.execute(TransferMode.SAFE, { session }) { handle ->
            mc_session_close(handle)
            SessionProbe.exited.value
        }
        await("mc_session_close returns") { returned.state != FutureState.SCHEDULED }
        return returned.result
    }

    private fun replaceData(session: COpaquePointer): Int {
        val returned = caller.execute(TransferMode.SAFE, { session }) { handle ->
            mc_session_set_data_callback(handle, SessionProbe.dataNext)
            SessionProbe.entered.value - SessionProbe.exited.value
        }
        await("mc_session_set_data_callback returns") { returned.state != FutureState.SCHEDULED }
        return returned.result
    }

    private fun replacePeerState(session: COpaquePointer): Int {
        val returned = caller.execute(TransferMode.SAFE, { session }) { handle ->
            mc_session_set_peer_state_callback(handle, SessionProbe.peerNext)
            SessionProbe.entered.value - SessionProbe.exited.value
        }
        await("mc_session_set_peer_state_callback returns") { returned.state != FutureState.SCHEDULED }
        return returned.result
    }

    private fun withLink(block: (MCSessionLink, COpaquePointer) -> Unit) {
        val self = MCPeerID(displayName = "self")
        val link = MCSessionLink(
            self,
            MCSession(peer = self, securityIdentity = null, encryptionPreference = MCEncryptionRequired),
        )
        block(link, StableRef.create(BridgeSessionState(link)).asCPointer())
    }

    private fun MCSessionLink.deliverFrame() {
        delegate.session(session, didReceiveData = byteArrayOf(1, 2, 3).toNSData(), fromPeer = MCPeerID(displayName = "guest"))
    }

    private fun MCSessionLink.connect(name: String) {
        delegate.session(session, MCPeerID(displayName = name), MCSessionState.MCSessionStateConnected)
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = TimeSource.Monotonic.markNow() + AWAIT_BOUND
        while (!condition()) {
            if (deadline.hasPassedNow()) fail("timed out waiting until $what; uncaught=${uncaughtSoFar()}")
            usleep(POLL_MICROS)
        }
    }

    private companion object {
        const val POLL_MICROS: UInt = 500u
        val AWAIT_BOUND = 60.seconds
    }
}

private const val SESSION_HOLD_MICROS: UInt = 200_000u

/** Only its `incoming` matters: a collection throws, and is counted. */
private class FailingIncomingSeam : Seam {
    val collections = atomic(0)
    override val selfId: PeerId = PeerId("self")
    private val roster = MutableStateFlow(setOf(selfId))
    private val lifecycle = MutableStateFlow<SeamState>(SeamState.Woven)
    override val peers: StateFlow<Set<PeerId>> = roster.asStateFlow()
    override val state: StateFlow<SeamState> = lifecycle.asStateFlow()
    override val incoming: Flow<Swatch> = flow {
        collections.incrementAndGet()
        error("rigged upstream failure")
    }

    override suspend fun broadcast(payload: ByteArray) = Unit

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) = Unit

    override suspend fun close(reason: CloseReason) = Unit
}

/** Global because a `staticCFunction` can capture nothing. */
@OptIn(ExperimentalForeignApi::class)
private object SessionProbe {
    val entered = atomic(0)
    val exited = atomic(0)
    val nextEntries = atomic(0)
    val oldRunningAtFirstNext = atomic(-1)
    val closedInside = atomic(0)
    val exitedWhenClosedInside = atomic(-1)
    val replacedInside = atomic(0)
    val release = atomic(false)
    val session = atomic<COpaquePointer?>(null)

    val dataHeld = staticCFunction { _: CPointer<ByteVar>?, _: CPointer<ByteVar>?, _: Int -> hold() }
    val peerHeld = staticCFunction { _: CPointer<ByteVar>?, _: Int -> hold() }
    val dataNext = staticCFunction { _: CPointer<ByteVar>?, _: CPointer<ByteVar>?, _: Int -> noteNext() }
    val peerNext = staticCFunction { _: CPointer<ByteVar>?, _: Int -> noteNext() }

    val peerClosesTheSession = staticCFunction { _: CPointer<ByteVar>?, _: Int ->
        mc_session_close(session.value)
        exitedWhenClosedInside.value = exited.value
        closedInside.value = 1
    }

    val dataReplacesItself = staticCFunction { _: CPointer<ByteVar>?, _: CPointer<ByteVar>?, _: Int ->
        // The second frame of the rig lands on this callback's own pump only if the replacement lost it.
        if (replacedInside.compareAndSet(0, 1)) {
            entered.incrementAndGet()
            mc_session_set_data_callback(session.value, dataNext)
            usleep(SESSION_HOLD_MICROS)
            exited.incrementAndGet()
        }
        Unit
    }

    val dataReplacesItselfThenCloses = staticCFunction { _: CPointer<ByteVar>?, _: CPointer<ByteVar>?, _: Int ->
        mc_session_set_data_callback(session.value, dataNext)
        mc_session_close(session.value)
        closedInside.value = 1
    }

    fun reset() {
        entered.value = 0
        exited.value = 0
        nextEntries.value = 0
        oldRunningAtFirstNext.value = -1
        closedInside.value = 0
        exitedWhenClosedInside.value = -1
        replacedInside.value = 0
        release.value = false
        session.value = null
    }

    private fun noteNext() {
        oldRunningAtFirstNext.compareAndSet(-1, entered.value - exited.value)
        nextEntries.incrementAndGet()
    }

    private fun hold() {
        entered.incrementAndGet()
        while (!release.value) usleep(100u)
        usleep(SESSION_HOLD_MICROS)
        exited.incrementAndGet()
    }
}
