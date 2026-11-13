package us.tractat.kuilt.session.admit

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    /**
     * `AdmitMessage.encode(Hello("Ada", "s-1", targetRoom = "room-7", protocolVersion = 2))` — the wire
     * a v2 joiner sends, captured from the encoder **before** v3 added the device key and the joiner
     * nonce (#3210).
     */
    private val goldenV2Hello =
        "619f6568656c6c6fbf6b646973706c61794e616d65634164616973657373696f6e496463732d316a746172676574526f6f6d66726f6f6d2d376f70726f746f636f6c56657273696f6e02ffff"

    /**
     * The capture receipt for [goldenV2Hello]: a Hello with no key and no nonce still encodes to the
     * v2 bytes, so a v3 build that omitted them would put nothing new on the wire.
     */
    @Test
    fun `a keyless v2 hello still encodes to the v2 bytes`() {
        assertEquals(
            goldenV2Hello,
            AdmitMessage.encode(
                AdmitMessage.Hello(displayName = "Ada", sessionId = "s-1", targetRoom = "room-7", protocolVersion = 2),
            ).toHex(),
        )
    }

    /**
     * The hazard v3 must not walk into (#3210): a v2 Hello carries no key and no joiner nonce, and it
     * must still **decode**, with both `null`. If either field were required on the wire, the CBOR
     * decode would fail, [AdmitMessage.decode] would turn that into `null`, the host would drop the
     * frame without a word, and the version gate — which needs a decoded Hello to read the version
     * from — would never fire. The v2 joiner would wait out its admit deadline instead of being told
     * [RejectCode.ProtocolMismatch].
     */
    @Test
    fun `a v2 hello decodes with no key and no nonce so the version gate can refuse it`() {
        val decoded = AdmitMessage.decode(goldenV2Hello.hexToBytes())

        assertNotNull(decoded, "a v2 hello must decode, or the version gate never sees it")
        val hello = decoded as AdmitMessage.Hello
        assertAll(
            { assertEquals(2, hello.protocolVersion) },
            { assertNull(hello.key, "a v2 hello names no key") },
            { assertNull(hello.joinerNonce, "a v2 hello carries no nonce") },
            { assertFalse(ProtocolVersion.isSupported(hello.protocolVersion), "and the gate refuses v2") },
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
