package us.tractat.kuilt.session.admit

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdmitMessageTest {
    @Test
    fun `encode and decode round-trips Hello`() {
        val original = AdmitMessage.Hello(
            displayName = "Alice",
            sessionId = "session-123",
            deviceId = "device-abc",
        )
        val bytes = AdmitMessage.encode(original)
        val decoded = AdmitMessage.decode(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun `encode and decode round-trips Welcome`() = runTest {
        val original = AdmitMessage.Welcome(
            assignedPeerId = "peer-1",
            displayName = "Alice",
            sessionId = "session-123",
            key = DidKey.parse(KEY_A),
            deviceId = "device-abc",
        )
        val bytes = AdmitMessage.encode(original)
        val decoded = AdmitMessage.decode(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun `encode and decode round-trips Reject`() {
        val original = AdmitMessage.Reject(reason = "already admitted")
        val bytes = AdmitMessage.encode(original)
        val decoded = AdmitMessage.decode(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun `Hello without deviceId round-trips`() {
        val original = AdmitMessage.Hello(displayName = "Bob", sessionId = "s-1")
        val decoded = AdmitMessage.decode(AdmitMessage.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `encoded admit frame starts with PREFIX_BYTE`() {
        val bytes = AdmitMessage.encode(AdmitMessage.Hello("Alice", "s-1"))
        assertEquals(AdmitMessage.PREFIX_BYTE, bytes[0])
    }

    @Test
    fun `isAdmitFrame returns true for encoded frame`() {
        val bytes = AdmitMessage.encode(AdmitMessage.Hello("Alice", "s-1"))
        assertTrue(AdmitMessage.isAdmitFrame(bytes))
    }

    @Test
    fun `isAdmitFrame returns false for application frame`() {
        val appFrame = byteArrayOf(0x01, 0x02, 0x03)
        assertTrue(!AdmitMessage.isAdmitFrame(appFrame))
    }

    @Test
    fun `decode returns null for non-admit bytes`() {
        val appFrame = "hello world".encodeToByteArray()
        assertNull(AdmitMessage.decode(appFrame))
    }

    @Test
    fun `decode returns null for empty bytes`() {
        assertNull(AdmitMessage.decode(ByteArray(0)))
    }

    @Test
    fun `decode returns null for malformed CBOR after prefix`() {
        val corrupt = byteArrayOf(AdmitMessage.PREFIX_BYTE, 0x01, 0x02)
        assertNull(AdmitMessage.decode(corrupt))
    }

    @Test
    fun `decoded Hello is type Hello not Welcome`() {
        val hello = AdmitMessage.Hello("Alice", "s-1")
        val decoded = AdmitMessage.decode(AdmitMessage.encode(hello))
        assertNotNull(decoded)
        assertTrue(decoded is AdmitMessage.Hello)
    }

    /**
     * #3215: the direct Welcome names the Hello it answers and the membership it admits. Both
     * round-trip, `answers` by content, and the roster copy every other member receives — the same
     * Welcome with neither — writes neither key.
     */
    @Test
    fun `Welcome round-trips its answer and membership and the roster copy writes neither`() = runTest {
        val nonce = ByteArray(AdmitMessage.Hello.JOINER_NONCE_SIZE) { it.toByte() }
        val welcome = AdmitMessage.Welcome(
            "peer-1", "Bob", "s-1", DidKey.parse(KEY_A), roomId = "room-9",
            returnKind = ReturnKind.Restored, answers = nonce, membership = "membership-1",
        )
        val decoded = AdmitMessage.decode(AdmitMessage.encode(welcome))
        val rosterCopy = AdmitMessage.encode(welcome.copy(returnKind = null, answers = null, membership = null)).decodeToString()
        assertAll(
            { assertEquals(welcome, decoded) },
            { assertNotEquals(welcome, welcome.copy(answers = ByteArray(nonce.size))) },
            // Positive control for the two absences below: the keys ARE visible when written.
            { assertTrue(AdmitMessage.encode(welcome).decodeToString().let { "answers" in it && "membership" in it }) },
            { assertFalse("answers" in rosterCopy, "the roster copy wrote answers") },
            { assertFalse("membership" in rosterCopy, "the roster copy wrote membership") },
        )
    }

    /** #3215: a Reject names the Hello it refuses, by content; one refusing a nonce-less Hello names none. */
    @Test
    fun `Reject round-trips the Hello it answers`() {
        val nonce = ByteArray(AdmitMessage.Hello.JOINER_NONCE_SIZE) { (it * 3).toByte() }
        val reject = AdmitMessage.Reject("key-conflict: held", RejectCode.KeyConflict, nonce)
        val unnamed = AdmitMessage.Reject("protocol-mismatch: v2", RejectCode.ProtocolMismatch, answers = null)
        assertAll(
            { assertEquals(reject, AdmitMessage.decode(AdmitMessage.encode(reject))) },
            { assertEquals(unnamed, AdmitMessage.decode(AdmitMessage.encode(unnamed))) },
            { assertNotEquals(reject, reject.copy(answers = ByteArray(nonce.size))) },
            { assertEquals(reject.hashCode(), reject.copy(answers = nonce.copyOf()).hashCode()) },
        )
    }

    /**
     * Decided 32 (#3695): a superseded refusal crosses the wire as itself, terminal, with the Hello it
     * answers and the successor it names. The successor is part of its identity.
     */
    @Test
    fun `a superseded Reject round-trips its successor and is terminal`() {
        val nonce = ByteArray(AdmitMessage.Hello.JOINER_NONCE_SIZE) { 7 }
        val reject = AdmitMessage.Reject.superseded("bob-2", nonce)
        val decoded = AdmitMessage.decode(AdmitMessage.encode(reject)) as AdmitMessage.Reject
        assertAll(
            { assertEquals(reject, decoded) },
            { assertEquals("bob-2", decoded.supersededBy) },
            { assertTrue(decoded.answers.contentEquals(nonce)) },
            { assertEquals(RejectCode.Superseded, decoded.code) },
            { assertEquals("superseded", RejectCode.Superseded.id, "the wire id is fixed once shipped") },
            { assertEquals(RejectCode.Superseded, RejectCode.fromId("superseded")) },
            { assertFalse(decoded.code.retryable) },
            { assertNotEquals(reject, reject.copy(supersededBy = "bob-3")) },
        )
    }

    /**
     * A successor and the superseded code go together, and a superseded refusal names its Hello: each
     * half refused in the constructor, and so on decode, where a frame breaking one is dropped.
     */
    @Test
    fun `a Reject's successor and its superseded code go together`() {
        val nonce = ByteArray(AdmitMessage.Hello.JOINER_NONCE_SIZE) { 7 }
        // The same frame as a valid one but for one code byte, so the decode arm differs only in that.
        val lookalike = AdmitMessage.encode(AdmitMessage.Reject("r", "xuperseded", nonce))
        val needle = "xuperseded".encodeToByteArray()
        val codeAt = (0..lookalike.size - needle.size).firstOrNull { at -> needle.indices.all { lookalike[at + it] == needle[it] } } ?: -1
        val codeOnly = lookalike.copyOf().also { it[codeAt] = 's'.code.toByte() }
        assertAll(
            { assertTrue(codeAt > 0, "rig: the code is on the wire") },
            { assertNotNull(AdmitMessage.decode(lookalike), "rig: the lookalike decodes") },
            { assertFailsWith<IllegalArgumentException>("no successor") { AdmitMessage.Reject("r", RejectCode.Superseded, nonce) } },
            { assertFailsWith<IllegalArgumentException>("another code") { AdmitMessage.Reject("r", RejectCode.KeyConflict.id, nonce, supersededBy = "bob-2") } },
            { assertFailsWith<IllegalArgumentException>("no Hello") { AdmitMessage.Reject("r", RejectCode.Superseded.id, answers = null, supersededBy = "bob-2") } },
            { assertNull(AdmitMessage.decode(codeOnly), "a superseded code with no successor is dropped on decode") },
        )
    }

    /**
     * v8 (#3221): the direct Welcome carries the member's pass, by content, and a roster copy writes
     * none. A [AdmitMessage.GrantRenewal] round-trips and compares by content too.
     */
    @Test
    fun `Welcome and GrantRenewal round-trip a pass by content`() = runTest {
        val pass = byteArrayOf(9, 8, 7, 6)
        val welcome = AdmitMessage.Welcome("peer-1", "Bob", "s-1", DidKey.parse(KEY_A), membership = "membership-1", grant = pass)
        val renewal = AdmitMessage.GrantRenewal(pass)
        val rosterCopy = AdmitMessage.encode(welcome.copy(membership = null, grant = null)).decodeToString()
        assertAll(
            { assertEquals(welcome, AdmitMessage.decode(AdmitMessage.encode(welcome))) },
            { assertEquals(welcome, welcome.copy(grant = pass.copyOf())) },
            { assertEquals(welcome.hashCode(), welcome.copy(grant = pass.copyOf()).hashCode()) },
            { assertNotEquals(welcome, welcome.copy(grant = byteArrayOf(9, 8, 7, 5))) },
            { assertNotEquals(welcome, welcome.copy(grant = null)) },
            // Positive control for the absence below: the key IS visible when written.
            { assertTrue("grant" in AdmitMessage.encode(welcome).decodeToString()) },
            { assertFalse("grant" in rosterCopy, "the roster copy wrote a pass") },
            { assertTrue(AdmitMessage.encode(welcome).contains(byteArrayOf(0x44) + pass), "the pass is a CBOR byte string") },
            { assertEquals<AdmitMessage?>(renewal, AdmitMessage.decode(AdmitMessage.encode(renewal))) },
            { assertEquals(renewal.hashCode(), AdmitMessage.GrantRenewal(pass.copyOf()).hashCode()) },
            { assertNotEquals(renewal, AdmitMessage.GrantRenewal(byteArrayOf(1))) },
        )
    }

    /**
     * v9 (#3222): a Welcome marking a device returning on its pass names the id it last held, and
     * round-trips it; a Welcome that marks nothing writes no such field.
     */
    @Test
    fun `a returning Welcome round-trips the id it names`() = runTest {
        val plain = AdmitMessage.Welcome("peer-2", "Bob", "s-2", DidKey.parse(KEY_A))
        val returning = plain.copy(returnKind = ReturnKind.Returning, previousPeerId = "peer-1")
        assertAll(
            { assertEquals(returning, AdmitMessage.decode(AdmitMessage.encode(returning))) },
            { assertNotEquals(returning, returning.copy(previousPeerId = "peer-3")) },
            { assertNotEquals(returning.hashCode(), returning.copy(previousPeerId = null).hashCode()) },
            // Positive control for the absence below: the key IS visible when written.
            { assertTrue("previousPeerId" in AdmitMessage.encode(returning).decodeToString()) },
            { assertFalse("previousPeerId" in AdmitMessage.encode(plain).decodeToString(), "an unmarked Welcome wrote a previous id") },
        )
    }

    /**
     * v8 (Decided 25, #3276): a Reject states whether its code is retryable, so an app's own code,
     * which the joiner does not know, keeps its meaning. A code the joiner knows keeps its own.
     */
    @Test
    fun `a Reject carries its code's retryability to a joiner that does not know the code`() {
        val nonce = nonce(5)
        val never = object : RejectCode {
            override val id = "banned"
            override val retryable = false
        }
        val decoded = AdmitMessage.decode(AdmitMessage.encode(AdmitMessage.Reject("banned", never, nonce))) as AdmitMessage.Reject
        val unstated = AdmitMessage.decode(AdmitMessage.encode(AdmitMessage.Reject("r", "quota", nonce))) as AdmitMessage.Reject
        val known = AdmitMessage.decode(AdmitMessage.encode(AdmitMessage.Reject("r", RejectCode.KeyConflict, nonce))) as AdmitMessage.Reject
        assertAll(
            { assertEquals(false, decoded.retryable, "the wire says the code is terminal") },
            { assertEquals<RejectCode>(RejectCode.Unknown("banned", retryable = false), decoded.code) },
            { assertFalse(decoded.code.retryable, "an app's terminal code stays terminal") },
            { assertEquals<RejectCode>(RejectCode.Unknown("quota"), unstated.code, "a Reject that states nothing leaves its code retryable") },
            { assertTrue(unstated.code.retryable) },
            { assertEquals<RejectCode>(RejectCode.KeyConflict, known.code, "a known code decodes as itself") },
            { assertEquals(false, known.retryable, "and states its own retryability") },
        )
    }

    /**
     * A stated retryability that contradicts a code this build knows is neither refused nor read:
     * the frame is built and decodes, and its code is the one this build knows, with this build's
     * retryability. An unknown code takes the stated value.
     */
    @Test
    fun `a known code keeps its own retryability whatever a Reject states`() {
        val nonce = nonce(5)
        val terminalCalledRetryable = AdmitMessage.Reject("r", RejectCode.BadProof.id, nonce, retryable = true)
        val retryableCalledTerminal = AdmitMessage.Reject("r", RejectCode.HostFailed.id, nonce, retryable = false)
        val decoded = AdmitMessage.decode(AdmitMessage.encode(terminalCalledRetryable)) as? AdmitMessage.Reject
        assertAll(
            { assertEquals(terminalCalledRetryable, decoded, "the frame decodes as it was built") },
            { assertEquals<RejectCode?>(RejectCode.BadProof, decoded?.code) },
            { assertFalse(terminalCalledRetryable.code.retryable, "a terminal code stays terminal") },
            { assertTrue(retryableCalledTerminal.code.retryable, "a retryable code stays retryable") },
            { assertEquals<RejectCode>(RejectCode.Unknown("x", false), AdmitMessage.Reject("r", "x", nonce, retryable = false).code) },
        )
    }

    /** An answer is a joiner nonce, so a wrong width is refused in the constructor, on every path. */
    @Test
    fun `a wrong-width answer is refused`() = runTest {
        val short = ByteArray(AdmitMessage.Hello.JOINER_NONCE_SIZE - 1)
        val key = DidKey.parse(KEY_A)
        assertAll(
            { assertFailsWith<IllegalArgumentException>("Reject") { AdmitMessage.Reject("r", RejectCode.BadProof, short) } },
            { assertFailsWith<IllegalArgumentException>("Welcome") { AdmitMessage.Welcome("p", "n", "s", key, answers = short) } },
        )
    }

    /**
     * #3215 retired the `Resume` and `ResumeAck` frames. A frame still tagged with either decodes to
     * nothing, so an older peer's is dropped like any malformed frame. The frames are built from
     * [LegacyAdmit], a surrogate carrying the retired serial names; its `Goodbye` encodes byte for
     * byte as the real one does, which is what shows the surrogate writes the real frame shape.
     */
    @Test
    fun `a frame tagged as a retired Resume or ResumeAck decodes to nothing`() {
        fun legacy(message: LegacyAdmit): ByteArray = byteArrayOf(AdmitMessage.PREFIX_BYTE) + legacyCbor.encodeToByteArray(LegacyAdmit.serializer(), message)
        assertAll(
            { assertTrue(legacy(LegacyAdmit.Goodbye).contentEquals(AdmitMessage.encode(AdmitMessage.Goodbye)), "the surrogate writes the real shape") },
            { assertEquals(AdmitMessage.Goodbye, AdmitMessage.decode(legacy(LegacyAdmit.Goodbye)), "and it decodes") },
            { assertNull(AdmitMessage.decode(legacy(LegacyAdmit.Resume("peer-1", "room-9", 5L))), "a resume frame") },
            { assertNull(AdmitMessage.decode(legacy(LegacyAdmit.ResumeAck)), "a resume-ack frame") },
        )
    }

    @Test
    fun `Goodbye round-trips`() {
        assertEquals(AdmitMessage.Goodbye, AdmitMessage.decode(AdmitMessage.encode(AdmitMessage.Goodbye)))
    }

    @Test
    fun `Farewell round-trips`() {
        val original = AdmitMessage.Farewell(peerId = "peer-7")
        assertEquals(original, AdmitMessage.decode(AdmitMessage.encode(original)))
    }

    /**
     * #1557 added [AdmitMessage.Farewell.expired] to distinguish a propagated reconnect-window
     * expiry from a clean leave. It must be wire-safe in both directions: a clean-leave Farewell
     * still encodes to the pre-#1557 bytes (defaults are not written), so an older peer decodes
     * it unchanged; and an expiry Farewell round-trips.
     */
    @Test
    fun `Farewell expired flag round-trips and leaves the clean-leave wire unchanged`() {
        val expiry = AdmitMessage.Farewell(peerId = "peer-7", expired = true)
        assertEquals(expiry, AdmitMessage.decode(AdmitMessage.encode(expiry)))

        val cleanLeave = AdmitMessage.encode(AdmitMessage.Farewell(peerId = "peer-7"))
        assertFalse(
            cleanLeave.decodeToString().contains("expired"),
            "a clean-leave Farewell must not write the defaulted field — the wire an older peer " +
                "sees has to stay byte-identical",
        )
        assertEquals(AdmitMessage.Farewell("peer-7", expired = false), AdmitMessage.decode(cleanLeave))
    }

    /**
     * #3213 added [AdmitMessage.Farewell.supersededBy]: it round-trips, and a Farewell without it
     * writes no such field, so every other Farewell keeps its bytes.
     */
    @Test
    fun `Farewell supersededBy round-trips and is absent when unset`() {
        val superseded = AdmitMessage.Farewell(peerId = "bob-1", supersededBy = "bob-2")
        val plain = AdmitMessage.encode(AdmitMessage.Farewell(peerId = "bob-1"))
        assertAll(
            { assertEquals(superseded, AdmitMessage.decode(AdmitMessage.encode(superseded))) },
            { assertFalse(plain.decodeToString().contains("supersededBy"), "an unset successor is not written") },
            { assertEquals(AdmitMessage.Farewell("bob-1", supersededBy = null), AdmitMessage.decode(plain)) },
        )
    }

    @Test
    fun `Paused and Unpaused round-trip`() {
        val paused = AdmitMessage.Paused(peerId = "peer-7", expiresAt = 1_234_567L)
        val unpaused = AdmitMessage.Unpaused(peerId = "peer-7")
        assertEquals(paused, AdmitMessage.decode(AdmitMessage.encode(paused)))
        assertEquals(unpaused, AdmitMessage.decode(AdmitMessage.encode(unpaused)))
    }

    /**
     * Forward compat for whole *variants*, not just fields (the claim #1557's additive
     * [AdmitMessage.Paused] / [AdmitMessage.Unpaused] rely on): a frame naming a message type an
     * older build has never heard of must be tolerated — [AdmitMessage.decode] returns null and
     * the receiver ignores it — never thrown out of the decode path.
     *
     * Crafted from a real Farewell frame by overwriting its `"farewell"` discriminator with an
     * equal-length unknown name, so only the variant tag differs.
     */
    @Test
    fun `an unknown message variant decodes to null instead of throwing`() {
        val frame = AdmitMessage.encode(AdmitMessage.Farewell(peerId = "peer-7"))
        val discriminator = "farewell".encodeToByteArray()
        val at = frame.indices.first { i ->
            i + discriminator.size <= frame.size &&
                discriminator.indices.all { frame[i + it] == discriminator[it] }
        }
        val unknown = frame.copyOf().also { "farewe11".encodeToByteArray().copyInto(it, at) }

        assertNull(
            AdmitMessage.decode(unknown),
            "an unknown variant must decode to null (and be ignored), not propagate an exception",
        )
    }

    /**
     * Golden vector (backward compat): a Hello captured from the current wire format. Decoding it
     * must yield the exact known value — this pins the wire so a future codec tweak (e.g. adding a
     * field) can't silently change the bytes an older peer emits.
     *
     * Structure: `PREFIX_BYTE` then an indefinite CBOR array `[ "hello", { indefinite map } ]`,
     * ending in `0xFF 0xFF` (map break, array break).
     */
    @Test
    fun `golden Hello vector decodes to known value`() {
        val decoded = AdmitMessage.decode(GOLDEN_HELLO)
        assertEquals(
            AdmitMessage.Hello(displayName = "Alice", sessionId = "session-123", deviceId = "device-abc"),
            decoded,
        )
        // #1172 A2 added Hello.targetRoom. The golden bytes predate it and carry no such key,
        // so an old frame must decode with targetRoom = null (the permissive default). This pins
        // that the new field is wire-safe backward-compatible.
        assertNull((decoded as AdmitMessage.Hello).targetRoom)
    }

    /**
     * Forward compat (the point of #1172 A1): a frame minted by a *newer* sender carries an extra
     * unknown field. An old build must still decode it to the fields it knows — before the
     * `ignoreUnknownKeys = true` change this threw, [AdmitMessage.decode]'s guard swallowed it to
     * null, and the joiner hung (no admit timeout).
     *
     * Crafted deterministically from [GOLDEN_HELLO] by splicing an extra `"futureFlag": true` entry
     * in before the map's terminating break (the first of the two trailing `0xFF`s).
     */
    @Test
    fun `Hello with unknown extra field still decodes`() {
        // Extra map entry: key "futureFlag" (text, 10 chars) -> true (0xF5).
        val extraEntry = byteArrayOf(
            0x6A, // CBOR text string, length 10
            'f'.code.toByte(), 'u'.code.toByte(), 't'.code.toByte(), 'u'.code.toByte(), 'r'.code.toByte(),
            'e'.code.toByte(), 'F'.code.toByte(), 'l'.code.toByte(), 'a'.code.toByte(), 'g'.code.toByte(),
            0xF5.toByte(), // CBOR true
        )
        // GOLDEN_HELLO ends in [.., mapBreak(0xFF), arrayBreak(0xFF)]. Insert the entry before the
        // map break so it lands inside the "hello" field map.
        val body = GOLDEN_HELLO.copyOfRange(0, GOLDEN_HELLO.size - 2)
        val crafted = body + extraEntry + byteArrayOf(0xFF.toByte(), 0xFF.toByte())

        val decoded = AdmitMessage.decode(crafted)
        assertEquals(
            AdmitMessage.Hello(displayName = "Alice", sessionId = "session-123", deviceId = "device-abc"),
            decoded,
        )
    }

    // ── v3: the device key and the challenge frames (#3210) ─────────────────────────────────

    /**
     * Every shape v3 adds round-trips, compared by value: a keyed [AdmitMessage.Hello], a keyed
     * [AdmitMessage.Welcome], a [AdmitMessage.Challenge], and a [AdmitMessage.Proof] with and without
     * grants. The byte-array fields compare by content, so each decoded copy, whose arrays are new
     * objects, must still equal the original.
     */
    @Test
    fun `every new shape round-trips`() = runTest {
        val key = DidKey.parse(KEY_A)
        val shapes = listOf(
            AdmitMessage.Hello("Ada", "s-1", deviceId = "d-1", protocolVersion = 3, key = key, joinerNonce = nonce(1)),
            AdmitMessage.Welcome("peer-1", "Ada", "s-1", key = key, deviceId = "d-1", roomId = "room-9"),
            AdmitMessage.Challenge(statement = byteArrayOf(1, 2, 3)),
            AdmitMessage.Proof(statement = byteArrayOf(4, 5)),
            AdmitMessage.Proof(statement = byteArrayOf(4, 5), grants = listOf(byteArrayOf(6), byteArrayOf(7, 8))),
        )
        assertAll(
            *shapes.map { shape -> { assertEquals(shape, AdmitMessage.decode(AdmitMessage.encode(shape)), "$shape") } }
                .toTypedArray(),
        )
    }

    /**
     * The byte-array fields compare by content, not identity: two frames built from equal bytes in
     * distinct arrays are equal and hash alike, and one differing byte makes them unequal. A data
     * class compares a `ByteArray` by reference, and then no decoded frame equals its original.
     */
    @Test
    fun `byte fields compare by content`() = runTest {
        val key = DidKey.parse(KEY_A)
        val hello = AdmitMessage.Hello("Ada", "s-1", protocolVersion = 3, key = key, joinerNonce = nonce(1))
        val challenge = AdmitMessage.Challenge(byteArrayOf(1, 2, 3))
        val proof = AdmitMessage.Proof(byteArrayOf(4, 5), grants = listOf(byteArrayOf(6)))
        assertAll(
            { assertEquals(hello, hello.copy(joinerNonce = nonce(1))) },
            { assertEquals(hello.hashCode(), hello.copy(joinerNonce = nonce(1)).hashCode()) },
            { assertNotEquals(hello, hello.copy(joinerNonce = nonce(2))) },
            { assertNotEquals(hello, hello.copy(joinerNonce = null)) },
            { assertEquals(challenge, AdmitMessage.Challenge(byteArrayOf(1, 2, 3))) },
            { assertEquals(challenge.hashCode(), AdmitMessage.Challenge(byteArrayOf(1, 2, 3)).hashCode()) },
            { assertNotEquals(challenge, AdmitMessage.Challenge(byteArrayOf(1, 2, 4))) },
            { assertEquals(proof, AdmitMessage.Proof(byteArrayOf(4, 5), grants = listOf(byteArrayOf(6)))) },
            {
                assertEquals(
                    proof.hashCode(),
                    AdmitMessage.Proof(byteArrayOf(4, 5), grants = listOf(byteArrayOf(6))).hashCode(),
                )
            },
            { assertNotEquals(proof, AdmitMessage.Proof(byteArrayOf(4, 5), grants = listOf(byteArrayOf(9)))) },
            { assertNotEquals(proof, AdmitMessage.Proof(byteArrayOf(4, 6), grants = listOf(byteArrayOf(6)))) },
        )
    }

    /**
     * Bytes travel as CBOR byte strings (major type 2), not as arrays of integers: the nonce, the
     * statement and each grant appear on the wire as a byte-string header and then the raw bytes. An
     * integer array would round-trip too, so the round trip cannot pin this; these assertions do.
     */
    @Test
    fun `bytes travel as CBOR byte strings`() = runTest {
        val key = DidKey.parse(KEY_A)
        val helloWire = AdmitMessage.encode(
            AdmitMessage.Hello("Ada", "s-1", protocolVersion = 3, key = key, joinerNonce = nonce(1)),
        )
        val proofWire = AdmitMessage.encode(AdmitMessage.Proof(byteArrayOf(4, 5), grants = listOf(byteArrayOf(6, 7, 8))))
        assertAll(
            // 0x50: a byte string of length 16.
            { assertTrue(helloWire.contains(byteArrayOf(0x50) + nonce(1)), "the nonce is a 16-byte CBOR byte string") },
            // 0x42: a byte string of length 2; 0x43: of length 3.
            { assertTrue(proofWire.contains(byteArrayOf(0x42, 4, 5)), "the statement is a CBOR byte string") },
            { assertTrue(proofWire.contains(byteArrayOf(0x43, 6, 7, 8)), "each grant is a CBOR byte string") },
            { assertTrue(helloWire.contains(KEY_A.encodeToByteArray()), "the key travels as its did:key text") },
        )
    }

    /**
     * A Hello naming a key that is not a P-256 `did:key` does not decode: [DidKey]'s serializer
     * checks the syntax, and [AdmitMessage.decode] turns the refusal into `null`, never a throw.
     *
     * Built from a real keyed Hello by overwriting the `D` of the key's `zDn` prefix, so the frame is
     * well-formed CBOR and only the key's syntax is wrong. The control is the unmodified frame.
     */
    @Test
    fun `a hello with a malformed key decodes to null`() = runTest {
        val frame = AdmitMessage.encode(
            AdmitMessage.Hello("Ada", "s-1", protocolVersion = 3, key = DidKey.parse(KEY_A), joinerNonce = nonce(1)),
        )
        val at = frame.indexOf(KEY_A.encodeToByteArray())
        val malformed = frame.copyOf().also { it[at + "did:key:z".length] = 'X'.code.toByte() }
        assertAll(
            { assertTrue(at >= 0, "rig: the key's text is in the frame") },
            { assertNotNull(AdmitMessage.decode(frame), "control: the unmodified frame decodes") },
            { assertNull(AdmitMessage.decode(malformed), "a malformed key must refuse the frame") },
        )
    }

    /**
     * [AdmitMessage.Welcome.key] is required on the wire, unlike [AdmitMessage.Hello.key]: a v2
     * Welcome, which has none, does not decode. No v3 joiner is sent one, because a v2 host refuses
     * the v3 Hello before it would admit anyone.
     */
    @Test
    fun `a welcome without a key does not decode`() {
        assertNull(AdmitMessage.decode(GOLDEN_V2_WELCOME))
    }

    /** A [AdmitMessage.Hello.JOINER_NONCE_SIZE]-byte nonce whose every byte is [fill]. */
    private fun nonce(fill: Int): ByteArray = ByteArray(AdmitMessage.Hello.JOINER_NONCE_SIZE) { fill.toByte() }

    private fun ByteArray.indexOf(needle: ByteArray): Int =
        (0..size - needle.size).firstOrNull { i -> needle.indices.all { this[i + it] == needle[it] } } ?: -1

    private fun ByteArray.contains(needle: ByteArray): Boolean = indexOf(needle) >= 0

    private companion object {
        /** A P-256 `did:key` from the W3C did:key test vectors. */
        const val KEY_A = "did:key:zDnaerx9CtbPJ1q36T5Ln5wYt3MQYeGRG5ehnPAmxcf5mDZpv"

        /** Captured bytes of `Hello("Alice", "session-123", "device-abc")` in the current wire format. */
        val GOLDEN_HELLO = intArrayOf(
            97, 159, 101, 104, 101, 108, 108, 111, 191, 107, 100, 105, 115, 112, 108, 97, 121, 78,
            97, 109, 101, 101, 65, 108, 105, 99, 101, 105, 115, 101, 115, 115, 105, 111, 110, 73,
            100, 107, 115, 101, 115, 115, 105, 111, 110, 45, 49, 50, 51, 104, 100, 101, 118, 105,
            99, 101, 73, 100, 106, 100, 101, 118, 105, 99, 101, 45, 97, 98, 99, 255, 255,
        ).map { it.toByte() }.toByteArray()

        /**
         * `AdmitMessage.encode(Welcome("peer-1", "Ada", "s-1"))`, captured from the v2 encoder in
         * this file's history, before #3210 made the key required.
         */
        val GOLDEN_V2_WELCOME =
            "619f6777656c636f6d65bf6e61737369676e656450656572496466706565722d316b646973706c61794e616d65634164616973657373696f6e496463732d31ffff"
                .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

/** The retired admit frames, by their serial names, for the decode test above. */
@Serializable
private sealed interface LegacyAdmit {
    @Serializable
    @SerialName("resume")
    data class Resume(val tokenPeerId: String, val tokenRoomId: String, val issuedAt: Long) : LegacyAdmit

    @Serializable
    @SerialName("resume-ack")
    data object ResumeAck : LegacyAdmit

    @Serializable
    @SerialName("goodbye")
    data object Goodbye : LegacyAdmit
}

/** The admit codec's settings, so the surrogate writes what the real encoder writes. */
@OptIn(ExperimentalSerializationApi::class)
private val legacyCbor = Cbor {
    ignoreUnknownKeys = true
    alwaysUseByteString = true
}
