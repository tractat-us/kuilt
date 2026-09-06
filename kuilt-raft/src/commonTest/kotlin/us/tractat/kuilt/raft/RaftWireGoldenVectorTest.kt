@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package us.tractat.kuilt.raft

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ByteArraySerializer
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.raft.internal.raftCbor
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Raft wire format, pinned byte-for-byte (#2160) — following `:kuilt-crdt`'s
 * `CanonicalGoldenVectorTest` and the SRA wire-vector precedent.
 *
 * Two things only a checked-in byte string can hold, and neither is covered by the round-trip tests
 * elsewhere in this module. **Cross-target agreement**: every round-trip in the suite encodes and
 * decodes inside one process on one target, so an encoding that differed between a JVM peer and a
 * Kotlin/Native one would be self-consistent on each side and invisible to all of them — and a Raft
 * cluster is precisely a set of peers on different targets exchanging these bytes. `commonTest`
 * compiles and runs on JVM, Android, iOS, macOS and wasmJs, so **this file *is* that check.**
 * **And deliberateness**: a wire change here breaks every peer on the old build, so it must be a
 * decision rather than a side effect of editing a data class.
 *
 * ## These vectors encode through the engine's own codec
 *
 * [raftCbor] is `internal` rather than mirrored here on purpose. A test that restated the codec
 * config would leave the config itself unpinned — dropping `alwaysUseByteString` from the engine's
 * instance would move every real frame while every vector below stayed green, which is the exact
 * shape of vacuity these files exist to prevent.
 *
 * ## The `PRE_BYTE_STRING_*` vectors are frozen, and they assert a REFUSAL
 *
 * `:kuilt-crdt` keeps `RGA_PRE_FLOOR` as the receipt that a frame written by an older encoder still
 * *decodes*. #2160 is the other case: it is a deliberate, approved break, so the honest receipt is
 * the opposite one — [aPreByteStringFrameIsRefusedRatherThanMisread] holds the bytes an older peer
 * really produced and asserts today's decoder **rejects** them. That is worth more than a note in a
 * changelog, because the alternative failure mode is the dangerous one: a length-prefixed byte
 * string and an indefinite-length array of integers are different CBOR major types, so a mixed-build
 * cluster gets a typed decode failure that `RaftEngine.onMessage` turns into an observable
 * `FrameRefused`, never a silently mis-read command. These bytes stay exactly as they are.
 *
 * **Regenerate the `*_VECTOR` constants only on a deliberate encoding change, and expect them to
 * move together.** One vector changing on one target and not another is the defect this file exists
 * to catch — investigate, do not re-record.
 */
class RaftWireGoldenVectorTest {

    // ── Constructions ─────────────────────────────────────────────────────────
    //
    // Every opaque payload below deliberately straddles CBOR's one-byte range (0..23 / -24..-1),
    // which is what makes the encoding change visible: bytes inside that range cost one byte under
    // *either* framing, so an all-zero construction would pin nothing at all. Guarded by
    // [everyOpaquePayloadStraddlesTheShortRange].

    /** 40 bytes: `0x00..0x13` inside CBOR's short range, then `0x74..0x87` outside it. */
    private val command = ByteArray(40) { if (it < 20) it.toByte() else (0x74 + it - 20).toByte() }

    /** 48 bytes `0x40..0x6F`, every one outside the short range. */
    private val snapshotState = ByteArray(48) { (0x40 + it).toByte() }

    private val clientId = ClientId("auto:dc1-node7-0123456789abcdef")

    private val config = ConfigPayload(
        old = null,
        new = ClusterConfig(
            voters = setOf(NodeId("v1"), NodeId("v2"), NodeId("v3")),
            learners = setOf(NodeId("l1")),
        ),
    )

    private val entry = LogEntry(
        index = 42,
        term = 7,
        command = command,
        dedupKey = DedupKey(clientId, 9_000_000_000L),
    )

    /** An internal config entry — the empty-command shape, which the two framings also disagree on. */
    private val configEntry = LogEntry(index = 43, term = 7, command = ByteArray(0), config = config)

    private val appendEntries: RaftMessage = RaftMessage.AppendEntries(
        term = 7, prevLogIndex = 41, prevLogTerm = 6,
        entries = listOf(entry, configEntry), leaderCommit = 41, round = 3,
    )

    private val installSnapshot: RaftMessage = RaftMessage.InstallSnapshot(
        term = 7, lastIncludedIndex = 40, lastIncludedTerm = 6, offset = 96,
        data = snapshotState, done = true, config = config, round = 3,
    )

    private val forward: RaftMessage = RaftMessage.Forward(
        clientRequestId = 11, command = command, dedupKey = DedupKey(clientId, 9_000_000_000L),
    )

    // ── Properties ────────────────────────────────────────────────────────────

    @Test
    fun everyVectorMatchesOnEveryTarget() {
        assertAll(
            { assertEquals(LOG_ENTRY, hex(LogEntry.serializer(), entry), "LogEntry") },
            { assertEquals(APPEND_ENTRIES, hex(RaftMessage.serializer(), appendEntries), "AppendEntries") },
            { assertEquals(INSTALL_SNAPSHOT, hex(RaftMessage.serializer(), installSnapshot), "InstallSnapshot") },
            { assertEquals(FORWARD, hex(RaftMessage.serializer(), forward), "Forward") },
        )
    }

    /**
     * The break, recorded as a receipt rather than as prose.
     *
     * Each constant is the bytes the same construction produced under the pre-#2160 codec — an
     * indefinite-length CBOR *array of integers* where today's writes a *byte string*. Two things
     * are asserted, and the second is the one that matters to an operator: today's decoder refuses
     * them, and it refuses them by **failing**, not by returning a frame whose command is wrong.
     *
     * The two are different CBOR major types (2 vs 4), so this is structural rather than lucky —
     * but "structural" is exactly the kind of claim that stops being true when a codec option
     * changes, which is why it is a test.
     */
    @Test
    fun aPreByteStringFrameIsRefusedRatherThanMisread() {
        fun refused(name: String, vector: String, serializer: KSerializer<*>): () -> Unit = {
            val outcome = runCatchingCancellable { raftCbor.decodeFromByteArray(serializer, unhex(vector)) }
            assertTrue(
                outcome.isFailure,
                "a pre-#2160 $name frame must be REFUSED by today's decoder, not decoded to " +
                    "something: got ${outcome.getOrNull()}",
            )
        }
        assertAll(
            refused("LogEntry", PRE_BYTE_STRING_LOG_ENTRY, LogEntry.serializer()),
            refused("AppendEntries", PRE_BYTE_STRING_APPEND_ENTRIES, RaftMessage.serializer()),
            refused("InstallSnapshot", PRE_BYTE_STRING_INSTALL_SNAPSHOT, RaftMessage.serializer()),
            refused("Forward", PRE_BYTE_STRING_FORWARD, RaftMessage.serializer()),
        )
    }

    /**
     * What the change actually buys, stated as an invariant rather than as a saving.
     *
     * An opaque payload now costs its own length plus a CBOR byte-string header — 1, 2, 3 or 5
     * bytes, stepping with the length — instead of one *or two* bytes **per byte**. The step table
     * is asserted at each boundary because that header is the only size-dependent quantity left in
     * the frame, and `RaftEngine.chunkBytes` sizes a slice against a budget without measuring it.
     */
    @Test
    fun anOpaquePayloadCostsItsOwnLengthPlusAByteStringHeader() {
        val expected = listOf(
            0 to 1, 23 to 1, 24 to 2, 255 to 2, 256 to 3, 65_535 to 3, 65_536 to 5,
        )
        assertAll(
            *expected.map { (length, header) ->
                {
                    // 0x7F is outside CBOR's one-byte range: under the old framing this was 2*length.
                    val payload = ByteArray(length) { 0x7F }
                    assertEquals(
                        length + header,
                        raftCbor.encodeToByteArray(ByteArraySerializer(), payload).size,
                        "a $length-byte payload costs its length plus a $header-byte header",
                    )
                }
            }.toTypedArray(),
        )
    }

    /**
     * A guard against the whole file going vacuous by **re-record**.
     *
     * Collapsing a construction to bytes inside CBOR's one-byte range would make the two framings
     * agree on that payload, so a regression to array-of-integers framing would move the vector by
     * two bytes rather than doubling it — small enough that a reader re-recording a "moved" constant
     * would not notice what they had lost. Each assertion therefore pins the property the
     * construction exists for, and the last one pins the *magnitude* of the difference the frozen
     * pre-#2160 vector stands for.
     */
    @Test
    fun everyOpaquePayloadStraddlesTheShortRange() {
        fun outsideShortRange(bytes: ByteArray) = bytes.count { it.toInt() !in -24..23 }
        assertAll(
            { assertEquals(20, outsideShortRange(command), "the command must span the boundary, not sit inside it") },
            { assertEquals(48, outsideShortRange(snapshotState), "every snapshot byte is outside the short range") },
            { assertEquals(0, configEntry.command.size, "the config entry pins the empty-payload shape") },
            {
                assertNotEquals(
                    PRE_BYTE_STRING_LOG_ENTRY, LOG_ENTRY,
                    "the frozen pre-#2160 vector must differ from today's, or it records nothing",
                )
            },
            {
                assertEquals(
                    20,
                    (PRE_BYTE_STRING_LOG_ENTRY.length - LOG_ENTRY.length) / 2,
                    "and must be larger by exactly the 20 command bytes the old framing doubled",
                )
            },
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun <S> hex(serializer: KSerializer<S>, value: S): String =
        raftCbor.encodeToByteArray(serializer, value).joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(radix = 16).padStart(2, '0')
        }

    private fun unhex(text: String): ByteArray =
        ByteArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(radix = 16).toByte() }

    private companion object {
        const val LOG_ENTRY =
            "bf65696e646578182a647465726d0767636f6d6d616e645828000102030405060708090a0b0c0d0e0f1011121374" +
                "75767778797a7b7c7d7e7f80818283848586876864656475704b6579bf68636c69656e744964781f6175746f3a64" +
                "63312d6e6f6465372d30313233343536373839616263646566697265717565737449641b0000000218711a00ffff"

        const val APPEND_ENTRIES =
            "9f783875732e747261637461742e6b75696c742e726166742e696e7465726e616c2e526166744d6573736167652e" +
                "417070656e64456e7472696573bf647465726d076c707265764c6f67496e64657818296b707265764c6f67546572" +
                "6d0667656e74726965739fbf65696e646578182a647465726d0767636f6d6d616e64582800010203040506070809" +
                "0a0b0c0d0e0f101112137475767778797a7b7c7d7e7f80818283848586876864656475704b6579bf68636c69656e" +
                "744964781f6175746f3a6463312d6e6f6465372d3031323334353637383961626364656669726571756573744964" +
                "1b0000000218711a00ffffbf65696e646578182b647465726d0767636f6d6d616e644066636f6e666967bf636f6c" +
                "64a0636e6577bf66766f746572739f627631627632627633ff686c6561726e6572739f626c31ffffffffff6c6c65" +
                "61646572436f6d6d6974182965726f756e6403ffff"

        const val INSTALL_SNAPSHOT =
            "9f783a75732e747261637461742e6b75696c742e726166742e696e7465726e616c2e526166744d6573736167652e" +
                "496e7374616c6c536e617073686f74bf647465726d07716c617374496e636c75646564496e6465781828706c6173" +
                "74496e636c756465645465726d06666f6666736574186064646174615830404142434445464748494a4b4c4d4e4f" +
                "505152535455565758595a5b5c5d5e5f606162636465666768696a6b6c6d6e6f64646f6e65f566636f6e666967bf" +
                "636f6c64a0636e6577bf66766f746572739f627631627632627633ff686c6561726e6572739f626c31ffffff6572" +
                "6f756e6403ffff"

        const val FORWARD =
            "9f783275732e747261637461742e6b75696c742e726166742e696e7465726e616c2e526166744d6573736167652e" +
                "466f7277617264bf6f636c69656e745265717565737449640b67636f6d6d616e645828000102030405060708090a" +
                "0b0c0d0e0f101112137475767778797a7b7c7d7e7f80818283848586876864656475704b6579bf68636c69656e74" +
                "4964781f6175746f3a6463312d6e6f6465372d30313233343536373839616263646566697265717565737449641b" +
                "0000000218711a00ffffff"

        /**
         * The four constants below are the bytes the constructions above produced under the
         * **pre-#2160** codec (`Cbor { ignoreUnknownKeys = true }`, no `alwaysUseByteString`).
         *
         * They are the only bytes in this repo an older Raft peer actually put on the wire, and they
         * are what makes [aPreByteStringFrameIsRefusedRatherThanMisread] a receipt rather than a
         * restatement. **Frozen**: they are never regenerated, and are deleted outright only if the
         * decoder deliberately stops refusing them.
         */
        const val PRE_BYTE_STRING_LOG_ENTRY =
            "bf65696e646578182a647465726d0767636f6d6d616e649f000102030405060708090a0b0c0d0e0f101112131874" +
                "18751876187718781879187a187b187c187d187e187f387f387e387d387c387b387a38793878ff6864656475704b" +
                "6579bf68636c69656e744964781f6175746f3a6463312d6e6f6465372d3031323334353637383961626364656669" +
                "7265717565737449641b0000000218711a00ffff"

        const val PRE_BYTE_STRING_APPEND_ENTRIES =
            "9f783875732e747261637461742e6b75696c742e726166742e696e7465726e616c2e526166744d6573736167652e" +
                "417070656e64456e7472696573bf647465726d076c707265764c6f67496e64657818296b707265764c6f67546572" +
                "6d0667656e74726965739fbf65696e646578182a647465726d0767636f6d6d616e649f000102030405060708090a" +
                "0b0c0d0e0f10111213187418751876187718781879187a187b187c187d187e187f387f387e387d387c387b387a38" +
                "793878ff6864656475704b6579bf68636c69656e744964781f6175746f3a6463312d6e6f6465372d303132333435" +
                "36373839616263646566697265717565737449641b0000000218711a00ffffbf65696e646578182b647465726d07" +
                "67636f6d6d616e649fff66636f6e666967bf636f6c64a0636e6577bf66766f746572739f627631627632627633ff" +
                "686c6561726e6572739f626c31ffffffffff6c6c6561646572436f6d6d6974182965726f756e6403ffff"

        const val PRE_BYTE_STRING_INSTALL_SNAPSHOT =
            "9f783a75732e747261637461742e6b75696c742e726166742e696e7465726e616c2e526166744d6573736167652e" +
                "496e7374616c6c536e617073686f74bf647465726d07716c617374496e636c75646564496e6465781828706c6173" +
                "74496e636c756465645465726d06666f6666736574186064646174619f1840184118421843184418451846184718" +
                "481849184a184b184c184d184e184f1850185118521853185418551856185718581859185a185b185c185d185e18" +
                "5f1860186118621863186418651866186718681869186a186b186c186d186e186fff64646f6e65f566636f6e6669" +
                "67bf636f6c64a0636e6577bf66766f746572739f627631627632627633ff686c6561726e6572739f626c31ffffff" +
                "65726f756e6403ffff"

        const val PRE_BYTE_STRING_FORWARD =
            "9f783275732e747261637461742e6b75696c742e726166742e696e7465726e616c2e526166744d6573736167652e" +
                "466f7277617264bf6f636c69656e745265717565737449640b67636f6d6d616e649f000102030405060708090a0b" +
                "0c0d0e0f10111213187418751876187718781879187a187b187c187d187e187f387f387e387d387c387b387a3879" +
                "3878ff6864656475704b6579bf68636c69656e744964781f6175746f3a6463312d6e6f6465372d30313233343536" +
                "373839616263646566697265717565737449641b0000000218711a00ffffff"
    }
}
