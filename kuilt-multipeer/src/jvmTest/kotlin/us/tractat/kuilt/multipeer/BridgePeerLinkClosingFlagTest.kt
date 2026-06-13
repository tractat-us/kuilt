package us.tractat.kuilt.multipeer

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.jna.Pointer
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.multipeer.internal.BridgePeerLink
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression guard: `mc.session.error` must only fire on unexpected peer
 * disconnects — not on `.notConnected` transitions that result from our
 * own [BridgePeerLink.close].
 *
 * Uses [CapturingFakeMultipeerNativeLib] to drive the peer-state callback
 * directly, mirroring how the real MC session delegate fires
 * `peer:didChangeState:notConnected` on the Apple side. The fix is a
 * `closing` flag that [BridgePeerLink.close] sets before calling
 * `mc_session_close`; the peer-state callback skips the error warn
 * when `closing` is true.
 */
class BridgePeerLinkClosingFlagTest {
    @Test
    fun `mc session error is emitted on unexpected disconnect but suppressed on clean close`() {
        val (logger, appender) = attachCapture()
        try {
            val selfId = PeerId("self")
            val sessionHandle = Pointer(0xDEADBEEFL)

            // --- Guard: unexpected disconnect DOES emit mc.session.error ---
            val fakeLibForDrop = CapturingFakeMultipeerNativeLib()
            BridgePeerLink(
                nativeLib = fakeLibForDrop,
                sessionHandle = sessionHandle,
                selfId = selfId,
            )
            fakeLibForDrop.firePeerState("remote-peer", isConnected = 0)

            val warnsAfterDrop = appender.warnMessages()

            // --- Subject: clean close does NOT emit mc.session.error ---
            val fakeLibForClose = CapturingFakeMultipeerNativeLib()
            val link =
                BridgePeerLink(
                    nativeLib = fakeLibForClose,
                    sessionHandle = sessionHandle,
                    selfId = selfId,
                )
            runBlocking { link.close(CloseReason.Normal) }
            // After close(), MC fires the notConnected callback for the remote peer.
            fakeLibForClose.firePeerState("remote-peer", isConnected = 0)

            val warnsAfterClose = appender.warnMessages()
            val newWarnsFromClose = warnsAfterClose.drop(warnsAfterDrop.size)

            val allAssertionFailures =
                buildList {
                    if (!warnsAfterDrop.any { it.contains("mc.session.error") }) {
                        add(
                            "Expected mc.session.error WARN on unexpected disconnect; " +
                                "captured warns: $warnsAfterDrop",
                        )
                    }
                    if (newWarnsFromClose.any { it.contains("mc.session.error") }) {
                        add(
                            "mc.session.error WARN must NOT fire after a clean close(); " +
                                "spurious warns: $newWarnsFromClose",
                        )
                    }
                }

            assertTrue(
                allAssertionFailures.isEmpty(),
                allAssertionFailures.joinToString("\n"),
            )
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private fun ListAppender<ILoggingEvent>.warnMessages(): List<String> =
        list
            .filter { it.level == Level.WARN }
            .map { it.formattedMessage }

    private companion object {
        fun attachCapture(): Pair<Logger, ListAppender<ILoggingEvent>> {
            @Suppress("CastNullableToNonNullableType") // SLF4J returns non-null; Logback is the bound implementation
            val logger =
                LoggerFactory.getLogger(
                    "us.tractat.kuilt.multipeer",
                ) as Logger
            logger.level = Level.DEBUG
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            return logger to appender
        }
    }
}

/**
 * Extension of [FakeMultipeerNativeLib] that captures the [MultipeerNativeLib.PeerStateCallback]
 * so tests can fire peer-state transitions directly.
 */
internal class CapturingFakeMultipeerNativeLib : MultipeerNativeLib by FakeMultipeerNativeLib() {
    private var capturedPeerStateCallback: MultipeerNativeLib.PeerStateCallback? = null

    fun firePeerState(
        peerId: String,
        isConnected: Int,
    ) {
        capturedPeerStateCallback?.invoke(peerId, isConnected)
    }

    override fun mc_session_set_peer_state_callback(
        session: Pointer?,
        cb: MultipeerNativeLib.PeerStateCallback,
    ) {
        capturedPeerStateCallback = cb
    }
}
