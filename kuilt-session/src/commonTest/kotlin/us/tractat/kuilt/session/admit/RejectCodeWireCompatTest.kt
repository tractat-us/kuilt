package us.tractat.kuilt.session.admit

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
        assertEquals(goldenCodelessReject, AdmitMessage.encode(AdmitMessage.Reject("resume-rejected")).toHexString())
    }

    @Test
    fun `a typed reject round-trips its code`() {
        val encoded = AdmitMessage.encode(
            AdmitMessage.Reject("room-mismatch: elsewhere", RejectCode.RoomMismatch, answers = null),
        )

        val decoded = AdmitMessage.decode(encoded)
        assertEquals(AdmitMessage.Reject("room-mismatch: elsewhere", RejectCode.RoomMismatch, answers = null), decoded)
        assertEquals(RejectCode.RoomMismatch, (decoded as AdmitMessage.Reject).code)
    }

    @Test
    fun `a code this build does not know decodes as Unknown and stays retryable`() {
        val fromTheFuture = AdmitMessage.Reject("nope", RejectCode.Unknown("quota-exceeded"), answers = null)

        val decoded = AdmitMessage.decode(AdmitMessage.encode(fromTheFuture)) as AdmitMessage.Reject

        assertEquals(RejectCode.Unknown("quota-exceeded"), decoded.code)
        assertTrue(
            decoded.code.retryable,
            "an unrecognised code must keep the pre-#1572 retry behaviour, never fail fast",
        )
    }

    @Test
    fun `terminality is declared per code`() {
        assertFalse(RejectCode.RoomMismatch.retryable)
        assertFalse(RejectCode.ProtocolMismatch.retryable)
        assertTrue(RejectCode.HostFailed.retryable)
        assertTrue(RejectCode.Unknown("").retryable)
    }

    /**
     * The resume codes went with the `Resume` frame (#3215; Decided 20). A peer that still sends one
     * reaches this build as [RejectCode.Unknown], retryable, like any code it does not know.
     */
    @Test
    fun `a retired resume code decodes as Unknown and stays retryable`() {
        for (id in listOf("resume-window-not-yet-open", "resume-window-expired", "resume-token-invalid")) {
            assertEquals(RejectCode.Unknown(id), RejectCode.fromId(id))
            assertTrue(RejectCode.fromId(id).retryable, id)
        }
    }

    /**
     * The key-proof codes (#3211) cross the wire as themselves and stay terminal: decoded as
     * [RejectCode.Unknown] they would be retryable, and a joiner refused for a bad proof would retry it.
     */
    @Test
    fun `the key-proof codes round-trip as themselves and are terminal`() {
        for (code in listOf(RejectCode.BadProof, RejectCode.KeyConflict)) {
            val decoded = AdmitMessage.decode(AdmitMessage.encode(AdmitMessage.Reject(code.id, code, answers = null))) as AdmitMessage.Reject
            assertEquals(code, decoded.code)
            assertFalse(decoded.code.retryable)
        }
    }

    /**
     * A host's own failure to sign or check (#3211) crosses the wire as itself, retryable, and is not
     * [RejectCode.Unspecified]: an app must be able to tell it from a `Reject` that carries no code.
     */
    @Test
    fun `the host failure code round-trips as itself and is retryable`() {
        val code = RejectCode.HostFailed
        val decoded = AdmitMessage.decode(AdmitMessage.encode(AdmitMessage.Reject(code.id, code, answers = null))) as AdmitMessage.Reject
        assertAll(
            { assertEquals("host-failed", code.id, "the wire id is fixed once shipped") },
            { assertEquals(code, decoded.code) },
            { assertEquals(code, RejectCode.fromId("host-failed")) },
            { assertTrue(decoded.code.retryable) },
            { assertNotEquals<RejectCode>(RejectCode.Unspecified, decoded.code) },
        )
    }

    /**
     * A policy that threw (#3220) crosses the wire as itself, retryable. Without its `fromId` branch it
     * would decode as [RejectCode.Unknown] with the same id, retryable too, so only the code itself
     * tells the two apart.
     */
    @Test
    fun `the policy failure code round-trips as itself and is retryable`() {
        val code = RejectCode.AdmissionPolicyFailed
        val decoded = AdmitMessage.decode(AdmitMessage.encode(AdmitMessage.Reject(code.id, code, answers = null))) as AdmitMessage.Reject
        assertAll(
            { assertEquals("admission-policy-failed", code.id, "the wire id is fixed once shipped") },
            { assertEquals<RejectCode>(code, decoded.code) },
            { assertEquals<RejectCode>(code, RejectCode.fromId("admission-policy-failed")) },
            { assertTrue(decoded.code.retryable) },
        )
    }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
