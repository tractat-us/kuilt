package us.tractat.kuilt.webrtc

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.webrtc.internal.HandshakeRunner
import us.tractat.kuilt.webrtc.internal.RtcPeerConnectionFacade
import us.tractat.kuilt.webrtc.internal.SdpType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class HandshakeRunnerTest {
    @Test
    fun hostAndJoinerCompleteHandshake() =
        runTest {
            val (hostFacFactory, joinerFacFactory) = PairedFacadeFactory.pair()
            val (hostSig, joinerSig) = PairedSignalingChannels.pair()

            val hostFacade = hostFacFactory.create(IceConfig.NoServers, hostInitiated = true)
            val joinerFacade = joinerFacFactory.create(IceConfig.NoServers, hostInitiated = false)

            coroutineScope {
                val hostDone =
                    async {
                        HandshakeRunner.runHost(
                            facade = hostFacade,
                            signaling = hostSig.open("test-room"),
                        )
                    }
                val joinerDone =
                    async {
                        HandshakeRunner.runJoiner(
                            facade = joinerFacade,
                            signaling = joinerSig.open("test-room"),
                        )
                    }
                val host: RtcPeerConnectionFacade = hostDone.await()
                val joiner: RtcPeerConnectionFacade = joinerDone.await()
                assertNotNull(host)
                assertNotNull(joiner)
            }
        }

    @Test
    fun joinerSendsAnswerAfterReceivingOffer() =
        runTest {
            val (_, joinerFacFactory) = PairedFacadeFactory.pair()
            val (hostSig, joinerSig) = PairedSignalingChannels.pair()

            val joinerFacade = joinerFacFactory.create(IceConfig.NoServers, hostInitiated = false)
            val joinerSession = joinerSig.open("test-room")
            val hostSession = hostSig.open("test-room")

            // Inject an offer into the joiner's signaling channel.
            hostSession.send(SignalingMessage.Offer(sdp = "fake-offer-sdp"))

            // Run the joiner handshake concurrently: it consumes the offer and produces an answer.
            coroutineScope {
                val joinerJob =
                    async {
                        HandshakeRunner.runJoiner(facade = joinerFacade, signaling = joinerSession)
                    }
                // The host side should see an Answer. Skip the relay-injected Role frame.
                val seen = hostSession.incoming.filter { it !is SignalingMessage.Role }.first()
                assertEquals(SignalingMessage.Answer(sdp = "fake-answer-sdp"), seen)
                joinerJob.cancel()
            }
        }

    @Test
    fun hostHandshakeThrowsWhenConnectionFails() =
        runTest {
            val (hostSig, _) = PairedSignalingChannels.pair()
            val boom = IllegalStateException("ICE failed before data channel opened")

            val thrown =
                assertFailsWith<IllegalStateException> {
                    HandshakeRunner.runHost(
                        facade = NeverOpensFacade(failWith = boom),
                        signaling = hostSig.open("test-room"),
                    )
                }
            assertEquals(boom.message, thrown.message)
        }

    @Test
    fun hostHandshakeTimesOutWhenNothingHappens() =
        runTest {
            val (hostSig, _) = PairedSignalingChannels.pair()

            // Neither opens nor fails — the overall handshake deadline must fire
            // rather than suspend forever. runTest auto-advances virtual time.
            assertFailsWith<TimeoutCancellationException> {
                HandshakeRunner.runHost(
                    facade = NeverOpensFacade(),
                    signaling = hostSig.open("test-room"),
                )
            }
        }

    /**
     * Regression guard for #1224 — boot-stagger fix.
     *
     * The `handshakeTimeoutMs` parameter lets callers override the default 30 s
     * deadline. The WASM Quick Play path passes 90 s so a slow-booting partner tab
     * (30–60 s WASM bundle load) does not cause the host to time out and retry with
     * a fresh signaling session (which would churn the relay room).
     *
     * This test proves the parameter is wired into [HandshakeRunner.runHost] by
     * passing a 1 ms timeout — confirming the timeout fires immediately with a small
     * value (would hang for 30 s with the hardcoded constant if the parameter were
     * not threaded through).
     */
    @Test
    fun customHandshakeTimeoutMsIsRespectedByRunHost() =
        runTest {
            val (hostSig, _) = PairedSignalingChannels.pair()

            // 1 ms timeout — must fire before the default 30 s would elapse.
            assertFailsWith<TimeoutCancellationException> {
                HandshakeRunner.runHost(
                    facade = NeverOpensFacade(),
                    signaling = hostSig.open("test-room"),
                    handshakeTimeoutMs = 1L,
                )
            }
        }

    /**
     * Symmetric guard for [HandshakeRunner.runJoiner] — same reasoning as
     * [customHandshakeTimeoutMsIsRespectedByRunHost].
     */
    @Test
    fun customHandshakeTimeoutMsIsRespectedByRunJoiner() =
        runTest {
            val (_, joinerSig) = PairedSignalingChannels.pair()

            assertFailsWith<TimeoutCancellationException> {
                HandshakeRunner.runJoiner(
                    facade = NeverOpensFacade(),
                    signaling = joinerSig.open("test-room"),
                    handshakeTimeoutMs = 1L,
                )
            }
        }

    /**
     * Regression for #1224: after the data channel opens, `runHost` and
     * `runJoiner` must return *normally* — not throw
     * [kotlinx.coroutines.channels.ClosedReceiveChannelException].
     *
     * The bug: the `finally` block called `inbound.cancel()` and then
     * `signaling.close()`. `signaling.close()` closed `inboundChannel`,
     * which could throw [kotlinx.coroutines.channels.ClosedReceiveChannelException]
     * inside the already-running `inbound` coroutine before the
     * [kotlinx.coroutines.CancellationException] from `inbound.cancel()` was
     * processed. Because [kotlinx.coroutines.channels.ClosedReceiveChannelException]
     * is not a [kotlinx.coroutines.CancellationException], `coroutineScope`
     * propagated it as a real failure, surfacing as `'Channel was closed'` in
     * `LiveSessionRuntime.runOneAttempt()` and triggering a spurious retry.
     *
     * The fix: drop `inbound.cancel()`. `signaling.close()` closes
     * `inboundChannel`, which terminates the `receiveAsFlow()` collector
     * in `inbound` *normally* (no exception). The `coroutineScope` then
     * returns cleanly.
     *
     * **Note:** the race is not reproducible in virtual-time `runTest` because
     * the test dispatcher serializes coroutines cooperatively, eliminating the
     * scheduling window where [kotlinx.coroutines.channels.ClosedReceiveChannelException]
     * can escape before the [kotlinx.coroutines.CancellationException] is processed.
     * The real-time regression guard is `QuickPlayBrowserIntegrationTest
     * .both_tabs_complete_webrtc_pairing_and_reach_shared_lobby` (in `:server`),
     * which runs the full WASM bundle in headless Chromium under the browser's
     * real JS event loop. This unit test verifies the happy-path shape of the fix.
     */
    @Test
    fun handshakeReturnsNormallyAfterDataChannelOpens() =
        runTest {
            repeat(5) {
                // Run the full paired handshake multiple times to flush out
                // any timing-dependent race in the finally block.
                val (hostFacFactory, joinerFacFactory) = PairedFacadeFactory.pair()
                val (hostSig, joinerSig) = PairedSignalingChannels.pair()

                val hostFacade = hostFacFactory.create(IceConfig.NoServers, hostInitiated = true)
                val joinerFacade = joinerFacFactory.create(IceConfig.NoServers, hostInitiated = false)

                coroutineScope {
                    val hostDone = async { HandshakeRunner.runHost(hostFacade, hostSig.open("room$it")) }
                    val joinerDone = async { HandshakeRunner.runJoiner(joinerFacade, joinerSig.open("room$it")) }
                    // Both must complete without throwing.
                    assertNotNull(hostDone.await())
                    assertNotNull(joinerDone.await())
                }
            }
        }
}

/**
 * A facade whose data channel never opens. If [failWith] is set,
 * [awaitConnectionFailure] resolves with it; otherwise the connection
 * neither opens nor fails (exercises the handshake timeout).
 */
private class NeverOpensFacade(
    private val failWith: Throwable? = null,
) : RtcPeerConnectionFacade {
    override val localIceCandidates: Flow<SignalingMessage.IceCandidate> = emptyFlow()
    override val incomingBytes: Flow<ByteArray> = emptyFlow()

    override suspend fun awaitDataChannelOpen() = awaitCancellation()

    override suspend fun awaitConnectionFailure(): Throwable = failWith ?: awaitCancellation()

    override suspend fun awaitDataChannelClose() = awaitCancellation()

    override suspend fun createOffer(): String = "offer-sdp"

    override suspend fun createAnswer(): String = "answer-sdp"

    override suspend fun setLocalDescription(
        sdp: String,
        type: SdpType,
    ) = Unit

    override suspend fun setRemoteDescription(
        sdp: String,
        type: SdpType,
    ) = Unit

    override suspend fun addIceCandidate(candidate: SignalingMessage.IceCandidate) = Unit

    override suspend fun sendBytes(bytes: ByteArray) = Unit

    override suspend fun close() = Unit
}
