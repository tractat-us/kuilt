package us.tractat.kuilt.webrtc

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.webrtc.internal.RtcPeerConnectionFacade
import us.tractat.kuilt.webrtc.internal.RtcPeerConnectionFacadeFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The constructor's `handshakeTimeout` reaches the clock `HandshakeRunner` already runs, through the
 * plain [us.tractat.kuilt.core.Loom.weave] entry point, for both roles (#2695).
 *
 * Every arm weaves against a [NeverOpensFacade] and no partner, so the data channel neither opens nor
 * fails and the only way `weave` can finish is the deadline. Each arm then pins the instant it
 * finishes: still pending one millisecond before the expected bound, finished at it, and finished by
 * a [TimeoutCancellationException]. The fixture's knobs, and what each switches off:
 * - the configured duration — omitted, the arm pins the 30 s default instead;
 * - `signalingDelayMs` — 0 except in the two arms that pin the clock's scope, where a 45 s relay dial
 *   would exhaust a 2 s deadline many times over if `signaling.open` were inside it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebRTCHandshakeTimeoutTest {
    @Test
    fun hostCanWaitPastDefaultAndExpiresAtConfiguredBound() =
        expiresAt(isHost = true, construct = configured(90.seconds), expectedMs = 90_000)

    @Test
    fun joinerCanWaitPastDefaultAndExpiresAtConfiguredBound() =
        expiresAt(isHost = false, construct = configured(90.seconds), expectedMs = 90_000)

    @Test
    fun hostDefaultRemainsThirtySeconds() = expiresAt(isHost = true, construct = defaulted, expectedMs = 30_000)

    @Test
    fun joinerDefaultRemainsThirtySeconds() = expiresAt(isHost = false, construct = defaulted, expectedMs = 30_000)

    @Test
    fun hostCanShortenDeadline() = expiresAt(isHost = true, construct = configured(2.seconds), expectedMs = 2_000)

    @Test
    fun joinerCanShortenDeadline() = expiresAt(isHost = false, construct = configured(2.seconds), expectedMs = 2_000)

    @Test
    fun subMillisecondDeadlineRoundsUpToOneMillisecond() =
        expiresAt(isHost = true, construct = configured(1.nanoseconds), expectedMs = 1)

    @Test
    fun fractionalMillisecondDeadlineRoundsUpRatherThanFiringEarly() =
        expiresAt(isHost = true, construct = configured(1_500.microseconds), expectedMs = 2)

    @Test
    fun hostSignalingOpenRemainsOutsideDeadline() =
        expiresAt(isHost = true, construct = configured(2.seconds), expectedMs = 2_000, signalingDelayMs = 45_000)

    @Test
    fun joinerSignalingOpenRemainsOutsideDeadline() =
        expiresAt(isHost = false, construct = configured(2.seconds), expectedMs = 2_000, signalingDelayMs = 45_000)

    @Test
    fun publicConstructorRejectsInvalidDurations() {
        val (signaling, _) = PairedSignalingChannels.pair()
        assertAll(
            *listOf(Duration.ZERO, (-1).seconds, Duration.INFINITE, -Duration.INFINITE)
                .map { invalid ->
                    {
                        assertFailsWith<IllegalArgumentException>("handshakeTimeout=$invalid") {
                            WebRTCPeerLinkFactory(signaling, "room", handshakeTimeout = invalid)
                        }
                        Unit
                    }
                }.toTypedArray(),
        )
    }

    @Test
    fun publicConstructorAcceptsPositiveFiniteDurations() {
        val (signaling, _) = PairedSignalingChannels.pair()
        // The KDoc's own example, compiled: the factory exists only on wasmJs, so it cannot live in
        // commonSamples.
        assertAll(
            { assertNotNull(WebRTCPeerLinkFactory(signaling, "room", handshakeTimeout = 90.seconds)) },
            { assertNotNull(WebRTCPeerLinkFactory(signaling, "room", handshakeTimeout = 1.nanoseconds)) },
        )
    }

    /** How an arm builds the factory under test, so "the default" is a constructor that omits the argument. */
    private fun interface Construct {
        fun build(
            signaling: SignalingChannel,
            facades: RtcPeerConnectionFacadeFactory,
        ): WebRTCPeerLinkFactory
    }

    private val defaulted = Construct { signaling, facades -> WebRTCPeerLinkFactory(signaling, "room", facadeFactory = facades) }

    private fun configured(timeout: Duration) =
        Construct { signaling, facades ->
            WebRTCPeerLinkFactory(signaling, "room", facadeFactory = facades, handshakeTimeout = timeout)
        }

    private fun expiresAt(
        isHost: Boolean,
        construct: Construct,
        expectedMs: Long,
        signalingDelayMs: Long = 0,
    ) = runTest {
        val (hostSignaling, joinerSignaling) = PairedSignalingChannels.pair()
        val paired = if (isHost) hostSignaling else joinerSignaling
        val signaling =
            object : SignalingChannel {
                override suspend fun open(room: String): SignalingSession {
                    delay(signalingDelayMs)
                    return paired.open(room)
                }
            }
        val createdRoles = mutableListOf<Boolean>()
        val facades =
            object : RtcPeerConnectionFacadeFactory {
                override fun create(
                    iceConfig: IceConfig,
                    hostInitiated: Boolean,
                ): RtcPeerConnectionFacade {
                    createdRoles += hostInitiated
                    return NeverOpensFacade()
                }
            }
        val factory = construct.build(signaling, facades)
        val rendezvous =
            if (isHost) {
                Rendezvous.New(Pattern(sessionName = "host"))
            } else {
                Rendezvous.Existing(
                    object : Tag {
                        override val sessionName = "joiner"
                        override val peerKey = "room"
                    },
                )
            }
        var expiry: TimeoutCancellationException? = null
        val pending =
            launch {
                try {
                    factory.weave(rendezvous).close()
                } catch (timeout: TimeoutCancellationException) {
                    expiry = timeout
                }
            }
        try {
            // Let signaling open; the handshake clock starts after it.
            runCurrent()
            advanceTimeBy(signalingDelayMs)
            runCurrent()
            val rolesAfterOpen = createdRoles.toList()
            // A checkpoint just past the 30 s default, where the arm's bound is later than that.
            val checkpointMs = minOf(30_001L, expectedMs - 1)
            advanceTimeBy(checkpointMs)
            runCurrent()
            val finishedAtCheckpoint = pending.isCompleted
            advanceTimeBy(expectedMs - 1 - checkpointMs)
            runCurrent()
            val finishedBeforeBound = pending.isCompleted
            advanceTimeBy(1)
            runCurrent()
            assertAll(
                { assertEquals(listOf(isHost), rolesAfterOpen, "weave must reach the requested handshake role once") },
                { assertFalse(finishedAtCheckpoint, "weave already finished at $checkpointMs ms, before the bound") },
                { assertFalse(finishedBeforeBound, "weave already finished 1 ms before the bound") },
                { assertTrue(pending.isCompleted, "handshake still pending at the configured bound, $expectedMs ms") },
                { assertNotNull(expiry, "weave must finish by the handshake deadline, not otherwise") },
            )
        } finally {
            pending.cancelAndJoin()
        }
    }
}
