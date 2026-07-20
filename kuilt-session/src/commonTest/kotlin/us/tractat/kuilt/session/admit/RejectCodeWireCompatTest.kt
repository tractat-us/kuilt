package us.tractat.kuilt.session.admit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the **wire additivity** of the typed reject code (#1572).
 *
 * [GOLDEN_CODELESS_REJECT] is the exact byte string a build that predates [RejectCode] produces
 * for `Reject("resume-rejected")` — captured from the pre-change encoder, not re-derived from the
 * post-change one, so it is genuine evidence rather than a tautology. Two directions matter:
 *
 * - **old → new**: those bytes must still decode, and the missing code must surface as
 *   [RejectCode.Unknown] — never a decode failure, which the admit codec drops silently
 *   (there is no admit timeout on a resume, so a dropped frame hangs the handshake).
 * - **new → old**: a [Reject] carrying no code must still encode to those same bytes, so a new
 *   host talking to an old joiner puts nothing new on the wire at all.
 */
class RejectCodeWireCompatTest {

    /** `AdmitMessage.encode(Reject("resume-rejected"))` as produced before #1572 added the code. */
    private val goldenCodelessReject =
        "619f6672656a656374bf66726561736f6e6f726573756d652d72656a6563746564ffff"

    @Test
    fun `a reject minted before typed codes decodes with an Unknown code`() {
        val decoded = AdmitMessage.decode(goldenCodelessReject.hexToBytes())

        assertEquals(AdmitMessage.Reject("resume-rejected"), decoded)
        assertEquals(RejectCode.Unknown(""), (decoded as AdmitMessage.Reject).code)
    }

    @Test
    fun `a codeless reject still encodes to the pre-1572 bytes`() {
        assertEquals(goldenCodelessReject, AdmitMessage.encode(AdmitMessage.Reject("resume-rejected")).toHex())
    }

    @Test
    fun `a typed reject round-trips its code`() {
        val encoded = AdmitMessage.encode(
            AdmitMessage.Reject("resume-window-expired", RejectCode.ResumeWindowExpired),
        )

        val decoded = AdmitMessage.decode(encoded)
        assertEquals(AdmitMessage.Reject("resume-window-expired", RejectCode.ResumeWindowExpired), decoded)
        assertEquals(RejectCode.ResumeWindowExpired, (decoded as AdmitMessage.Reject).code)
    }

    @Test
    fun `a code this build does not know decodes as Unknown and stays retryable`() {
        val fromTheFuture = AdmitMessage.Reject("nope", RejectCode.Unknown("quota-exceeded"))

        val decoded = AdmitMessage.decode(AdmitMessage.encode(fromTheFuture)) as AdmitMessage.Reject

        assertEquals(RejectCode.Unknown("quota-exceeded"), decoded.code)
        assertTrue(
            decoded.code.retryable,
            "an unrecognised code must keep the pre-#1572 retry behaviour, never fail fast",
        )
    }

    @Test
    fun `terminality is declared per code`() {
        assertFalse(RejectCode.ResumeWindowExpired.retryable)
        assertFalse(RejectCode.ResumeTokenInvalid.retryable)
        assertFalse(RejectCode.RoomMismatch.retryable)
        assertTrue(RejectCode.ResumeWindowNotYetOpen.retryable)
        assertTrue(RejectCode.Unknown("").retryable)
    }

    private fun ByteArray.toHex(): String = joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
