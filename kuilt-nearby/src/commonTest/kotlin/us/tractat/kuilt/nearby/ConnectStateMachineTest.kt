package us.tractat.kuilt.nearby

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectStateMachineTest {

    /**
     * A [NearbyApi] whose event flows are driven manually, recording the
     * side-effecting calls the state machine makes. Events are emitted from
     * [ConnectStateMachine.run]'s `trigger` lambda, which runs only after the
     * machine's collectors have subscribed (they launch UNDISPATCHED), so no
     * event is dropped.
     */
    private class ControllableNearbyApi : NearbyApi {
        val connInit = MutableSharedFlow<ConnectionInitiated>(extraBufferCapacity = 8)
        val connResult = MutableSharedFlow<ConnectionResult>(extraBufferCapacity = 8)
        val payloads = MutableSharedFlow<PayloadReceived>(extraBufferCapacity = 8)
        val found = MutableSharedFlow<EndpointFound>(extraBufferCapacity = 8)
        val disconnects = MutableSharedFlow<EndpointDisconnected>(extraBufferCapacity = 8)

        val accepted = mutableListOf<String>()
        val sent = mutableListOf<Pair<String, ByteArray>>()

        override fun availability(): FabricAvailability = FabricAvailability.Available
        override suspend fun startAdvertising(displayName: String, serviceId: String) {}
        override suspend fun stopAdvertising() {}
        override suspend fun startDiscovery(serviceId: String) {}
        override suspend fun stopDiscovery() {}
        override suspend fun requestConnection(displayName: String, endpointId: String) {}
        override suspend fun acceptConnection(endpointId: String) { accepted += endpointId }
        override suspend fun disconnect(endpointId: String) {}
        override suspend fun sendBytesPayload(endpointId: String, bytes: ByteArray) { sent += endpointId to bytes }

        override val endpointFound: Flow<EndpointFound> = found
        override val connectionInitiated: Flow<ConnectionInitiated> = connInit
        override val connectionResult: Flow<ConnectionResult> = connResult
        override val payloadReceived: Flow<PayloadReceived> = payloads
        override val endpointDisconnected: Flow<EndpointDisconnected> = disconnects
    }

    @Test
    fun happyPathResolvesWithRemoteIdentity() =
        runTest(UnconfinedTestDispatcher()) {
            val api = ControllableNearbyApi()
            val machine = ConnectStateMachine(
                PeerId("me"),
                api,
                endpointId = "ep1",
                serviceId = "svc",
                handshakeTimeout = NearbyLoom.DEFAULT_HANDSHAKE_TIMEOUT,
            )

            val link =
                machine.run(backgroundScope) {
                    api.connInit.emit(ConnectionInitiated("ep1", "host"))
                    api.connResult.emit(ConnectionResult("ep1", success = true))
                    api.payloads.emit(PayloadReceived("ep1", PeerId("remote").value.encodeToByteArray()))
                }

            assertEquals("ep1", link.endpointId)
            assertEquals(PeerId("remote"), link.remotePeerId)
            assertTrue("ep1" in api.accepted, "machine accepted the connection")
            assertEquals(
                PeerId("me").value,
                api.sent.single().second.decodeToString(),
                "machine sent its own identity after CONNECTED",
            )
        }

    @Test
    fun rejectionThrowsConnectionFailed() =
        runTest(UnconfinedTestDispatcher()) {
            val api = ControllableNearbyApi()
            val machine = ConnectStateMachine(
                PeerId("me"),
                api,
                endpointId = "ep1",
                serviceId = "svc",
                handshakeTimeout = NearbyLoom.DEFAULT_HANDSHAKE_TIMEOUT,
            )

            val outcome =
                runCatchingCancellable {
                    machine.run(backgroundScope) {
                        api.connInit.emit(ConnectionInitiated("ep1", "host"))
                        api.connResult.emit(ConnectionResult("ep1", success = false, reason = "rejected"))
                    }
                }

            assertTrue(
                outcome.exceptionOrNull() is ConnectionFailedException,
                "expected ConnectionFailedException, got ${outcome.exceptionOrNull()}",
            )
        }

    @Test
    fun timesOutWhenNoResultArrives() =
        runTest(UnconfinedTestDispatcher()) {
            val api = ControllableNearbyApi()
            val machine =
                ConnectStateMachine(
                    PeerId("me"),
                    api,
                    endpointId = "ep1",
                    serviceId = "svc",
                    handshakeTimeout = 1.seconds,
                )

            // trigger emits nothing → withTimeout fires on the virtual clock.
            // ALLOW-runCatching: the assertion below is ON the captured TimeoutCancellationException — a CancellationException the test must catch; runCatchingCancellable would rethrow it and the assertion could never run.
            val outcome = runCatching { machine.run(backgroundScope) {} }

            assertTrue(
                outcome.exceptionOrNull() is TimeoutCancellationException,
                "expected TimeoutCancellationException, got ${outcome.exceptionOrNull()}",
            )
        }

    // ── identity admission (#1821) ────────────────────────────────────────────
    //
    // The remote's [PeerId] arrives as the first BYTES payload off the radio — peer-supplied,
    // unvalidated bytes. Every arm below drives the SAME happy-path handshake as
    // [happyPathResolvesWithRemoteIdentity] and changes only those bytes, so what a failure names
    // is the identity rule and nothing else.
    //
    // The rig is the fake's own [ControllableNearbyApi.payloads] flow, which can carry any byte
    // sequence at all — including the ones a conforming peer never sends. That is what stops these
    // from being vacuous: the fake CAN emit a blank id, an id equal to `selfId`, and invalid UTF-8,
    // so "is it refused?" is a question with two possible answers. The two positive arms
    // ([happyPathResolvesWithRemoteIdentity] above and [validNonAsciiRemoteIdentityIsAccepted]
    // below) are what stops a guard that refused *everything* from passing.

    private suspend fun ControllableNearbyApi.driveHandshake(identityBytes: ByteArray) {
        connInit.emit(ConnectionInitiated("ep1", "host"))
        connResult.emit(ConnectionResult("ep1", success = true))
        payloads.emit(PayloadReceived("ep1", identityBytes))
    }

    private fun machineFor(api: ControllableNearbyApi) =
        ConnectStateMachine(
            PeerId("me"),
            api,
            endpointId = "ep1",
            serviceId = "svc",
            handshakeTimeout = NearbyLoom.DEFAULT_HANDSHAKE_TIMEOUT,
        )

    @Test
    fun blankRemoteIdentityIsRefused() =
        runTest(UnconfinedTestDispatcher()) {
            val api = ControllableNearbyApi()
            val outcome =
                runCatchingCancellable {
                    machineFor(api).run(backgroundScope) { api.driveHandshake(ByteArray(0)) }
                }

            assertTrue(
                outcome.isFailure,
                "an empty identity payload must fail the handshake, not resolve a link under the " +
                    "blank PeerId(\"\") — a blank id is unaddressable and two blank remotes collapse " +
                    "onto one roster entry. Resolved: ${outcome.getOrNull()}",
            )
        }

    @Test
    fun remoteIdentityEqualToSelfIsRefused() =
        runTest(UnconfinedTestDispatcher()) {
            val api = ControllableNearbyApi()
            val outcome =
                runCatchingCancellable {
                    machineFor(api).run(backgroundScope) {
                        api.driveHandshake(PeerId("me").value.encodeToByteArray())
                    }
                }

            assertTrue(
                outcome.isFailure,
                "a remote announcing THIS peer's own id must fail the handshake — admitting it makes " +
                    "the seam's own id a remote, and the eventual disconnect then evicts this peer " +
                    "from its own roster (the #1466 signature). Resolved: ${outcome.getOrNull()}",
            )
        }

    @Test
    fun invalidUtf8RemoteIdentityIsRefused() =
        runTest(UnconfinedTestDispatcher()) {
            val api = ControllableNearbyApi()
            // A lone 0xC3 opens a two-byte sequence that never completes; a lone 0x80 is a
            // continuation byte with no lead. Lossy decoding maps BOTH to U+FFFD, so two distinct
            // remotes would collapse onto one identical id.
            val truncatedLead =
                runCatchingCancellable {
                    machineFor(api).run(backgroundScope) { api.driveHandshake(byteArrayOf(0xC3.toByte())) }
                }
            val other = ControllableNearbyApi()
            val strayContinuation =
                runCatchingCancellable {
                    machineFor(other).run(backgroundScope) { other.driveHandshake(byteArrayOf(0x80.toByte())) }
                }

            assertAll(
                {
                    assertTrue(
                        truncatedLead.isFailure,
                        "an identity payload that is not valid UTF-8 must fail the handshake rather " +
                            "than be decoded lossily. Resolved: ${truncatedLead.getOrNull()}",
                    )
                },
                {
                    assertTrue(
                        strayContinuation.isFailure,
                        "a second, DIFFERENT invalid sequence must also fail — lossy decoding maps " +
                            "both to U+FFFD, silently merging two distinct devices onto one id. " +
                            "Resolved: ${strayContinuation.getOrNull()}",
                    )
                },
            )
        }

    /**
     * The positive arm the refusals rest on: a valid, distinct, non-ASCII id is still admitted
     * unchanged. Without this a guard that refused every identity payload would pass every test
     * above.
     */
    @Test
    fun validNonAsciiRemoteIdentityIsAccepted() =
        runTest(UnconfinedTestDispatcher()) {
            val api = ControllableNearbyApi()
            val link =
                machineFor(api).run(backgroundScope) {
                    api.driveHandshake("pêer-Ω-🧵".encodeToByteArray())
                }

            assertEquals(PeerId("pêer-Ω-🧵"), link.remotePeerId, "a valid UTF-8 id round-trips unchanged")
        }
}
