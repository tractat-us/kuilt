package us.tractat.kuilt.session.admit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the **wire additivity** of the protocol-version field on [AdmitMessage.Hello] (#1569).
 *
 * [goldenVersionlessHello] is the exact byte string a build that predates the version field
 * produces for `Hello("Ada", "s-1", targetRoom = "room-7")` — captured from the pre-change
 * encoder, not re-derived from the post-change one, so it is genuine evidence rather than a
 * tautology. Two directions matter, exactly as [RejectCodeWireCompatTest] pins for the reject code:
 *
 * - **old → new**: those bytes must still decode, and the missing version must surface as `null`
 *   (legacy/unknown) — never a decode failure, which the admit codec drops silently (there is no
 *   admit timeout on a `Hello`, so a dropped frame hangs the handshake).
 * - **new → old**: a [AdmitMessage.Hello] carrying no version must still encode to those same
 *   bytes, so a new joiner that omits the field puts nothing new on the wire at all.
 */
class HelloVersionWireCompatTest {

    /** `AdmitMessage.encode(Hello("Ada", "s-1", targetRoom = "room-7"))` before #1569 added the version. */
    private val goldenVersionlessHello =
        "619f6568656c6c6fbf6b646973706c61794e616d65634164616973657373696f6e496463732d316a746172676574526f6f6d66726f6f6d2d37ffff"

    @Test
    fun `a hello minted before the version field decodes with a null version`() {
        val decoded = AdmitMessage.decode(goldenVersionlessHello.hexToBytes())

        assertEquals(
            AdmitMessage.Hello(displayName = "Ada", sessionId = "s-1", targetRoom = "room-7"),
            decoded,
        )
        assertNull((decoded as AdmitMessage.Hello).protocolVersion, "a legacy hello carries no version")
    }

    @Test
    fun `a versionless hello still encodes to the pre-1569 bytes`() {
        assertEquals(
            goldenVersionlessHello,
            AdmitMessage.encode(
                AdmitMessage.Hello(displayName = "Ada", sessionId = "s-1", targetRoom = "room-7"),
            ).toHex(),
        )
    }

    @Test
    fun `a versioned hello round-trips its version`() {
        val encoded = AdmitMessage.encode(
            AdmitMessage.Hello(
                displayName = "Ada",
                sessionId = "s-1",
                targetRoom = "room-7",
                protocolVersion = ProtocolVersion.CURRENT,
            ),
        )

        val decoded = AdmitMessage.decode(encoded) as AdmitMessage.Hello
        assertEquals(ProtocolVersion.CURRENT, decoded.protocolVersion)
    }

    private fun ByteArray.toHex(): String = joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
