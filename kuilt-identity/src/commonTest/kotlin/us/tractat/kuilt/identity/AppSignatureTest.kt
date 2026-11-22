package us.tractat.kuilt.identity

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An app signs its own bytes with [signApp], and no bytes it is handed can come out as one of kuilt's
 * own statements (#3600).
 *
 * The threat: an app that signs whatever a peer sends it, with the device's key. With a raw `sign`,
 * a peer could send the layout of an admission proof naming the victim's key and receive the
 * victim's signature on it. [aPeerCannotObtainASignedKuiltStatementThroughSignApp] makes that request
 * with the real P-256 key and checks the proof it assembles does not verify.
 */
class AppSignatureTest {
    @Test
    fun anAppSignatureVerifiesUnderItsOwnContextOnly() = runTest {
        val key = DeviceKey.generate()
        val message = "move 12: e4".encodeToByteArray()
        val signature = key.signApp("chess/move", message)
        val verdicts = listOf(
            key.id.verifyApp("chess/move", message, signature),
            key.id.verifyApp("chess/resign", message, signature),
            key.id.verifyApp("chess/move", "move 12: d4".encodeToByteArray(), signature),
            DeviceKey.generate().id.verifyApp("chess/move", message, signature),
            key.id.verify(message, signature),
        )
        assertAll(
            { assertEquals(Layout.SIGNATURE_SIZE, signature.size, "a raw r‖s signature") },
            { assertTrue(verdicts[0], "verifies under the context it was signed in") },
            { assertFalse(verdicts[1], "refused under another context") },
            { assertFalse(verdicts[2], "refused over another message") },
            { assertFalse(verdicts[3], "refused under another key") },
            { assertFalse(verdicts[4], "not a raw signature over the message itself") },
        )
    }

    @Test
    fun theSignedBytesAreTheAppTagThenTheMessage() = runTest {
        val key = FixedDeviceKey(GENERATOR_KEY, PLACEHOLDER_SIGNATURE)
        key.signApp("chat", "hi".encodeToByteArray())
        // u16 tag length 8 ‖ "app/chat" ‖ version 1 ‖ the message, untouched.
        assertEquals("0008" + "6170702f63686174" + "01" + "6869", key.signed.single().toHexString())
    }

    @Test
    fun aKuiltContextIsRefusedBeforeAnythingIsSigned() = runTest {
        val key = FixedDeviceKey(GENERATOR_KEY, PLACEHOLDER_SIGNATURE)
        val refused = listOf("kuilt", "kuilt/", "kuilt/admit-proof", "kuilt/grant", "", "café")
        val signRefusals = refused.map { context ->
            refusal { key.signApp(context, MESSAGE) }
        }
        val verifyRefusals = refused.map { context ->
            refusal { GENERATOR_KEY.verifyApp(context, MESSAGE, PLACEHOLDER_SIGNATURE) }
        }
        // Names that only look like kuilt's are an app's to use.
        val allowed = listOf("kuilt-chat", "my-kuilt/x", "KUILT/x", "app/kuilt")
        allowed.forEach { key.signApp(it, MESSAGE) }
        assertAll(
            *refused.indices.map { i ->
                { assertTrue(signRefusals[i] != null, "signApp refuses \"${refused[i]}\": ${signRefusals[i]}") }
            }.toTypedArray(),
            *refused.indices.map { i ->
                { assertTrue(verifyRefusals[i] != null, "verifyApp refuses \"${refused[i]}\": ${verifyRefusals[i]}") }
            }.toTypedArray(),
            { assertEquals(allowed.size, key.signed.size, "only the allowed contexts reached the key") },
        )
    }

    @Test
    fun aPeerCannotObtainASignedKuiltStatementThroughSignApp() = runTest {
        val victim = DeviceKey.generate()
        // The layout of a proof that the victim answers the peer's challenge: what the peer wants signed.
        val honest = AdmissionProof.sign(victim, "room", GENERATOR_KEY, "host", "joiner", "device", countingBytes(0, Challenge.NONCE_SIZE))
        val layout = honest.wire.copyOfRange(0, honest.wire.size - Layout.SIGNATURE_SIZE)
        // Contexts a peer might talk the app into, each trying to make the signed bytes open with kuilt's header.
        val header = layout.copyOfRange(0, 2 + "kuilt/admit-proof".length + 1).decodeToString()
        val attempts = listOf(
            "x" to layout,
            "chat" to layout,
            header to layout.copyOfRange(header.length, layout.size),
            "\u0000\u0011kuilt/admit-proof" to layout.copyOfRange(header.length - 1, layout.size),
        )
        val rows = attempts.map { (context, message) ->
            val signature = victim.signApp(context, message)
            val forged = AdmissionProof.decode(layout + signature)
            Triple(victim.id.verifyApp(context, message, signature), forged, forged?.isSignedBy(victim.id))
        }
        assertAll(
            { assertTrue(honest.isSignedBy(victim.id), "precondition: a proof the victim really signed verifies") },
            *rows.mapIndexed { i, (appVerifies, forged, forgedVerifies) ->
                listOf(
                    { assertTrue(appVerifies, "attempt $i: the app signature itself is sound") },
                    { assertNotNull(forged, "attempt $i: precondition, the assembled bytes decode as a proof") },
                    { assertEquals(false, forgedVerifies, "attempt $i: the assembled proof is not the victim's") },
                )
            }.flatten().toTypedArray(),
        )
    }

    /** The [IllegalArgumentException] [block] threw, or `null` when it returned. Nothing else is caught. */
    private suspend fun refusal(block: suspend () -> Unit): IllegalArgumentException? = try {
        block()
        null
    } catch (e: IllegalArgumentException) {
        e
    }

    private companion object {
        val MESSAGE = "an app's own bytes".encodeToByteArray()
    }
}
