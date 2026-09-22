@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package us.tractat.kuilt.raft

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.raft.internal.ForwardOutcome
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.raft.internal.messageType
import us.tractat.kuilt.raft.internal.raftCbor
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
 * ## One wire epoch, two changes, and the break is total
 *
 * #2160 changed the Raft wire in two ways at once. Opaque payloads became CBOR byte strings rather
 * than arrays of integers, which alone moved only the frames that carry bytes — a vote, a heartbeat,
 * a response or a `TimeoutNow` stayed byte-identical, so a group of mixed builds would elect a leader
 * and then fail on every entry, forever. So every frame type's CBOR **tag** changed with it, from the
 * fully-qualified class name to a short code (see [RaftMessage]), and now no frame a build on one side
 * sends decodes on the other. [aPre2160FrameIsRefusedInBothDirections] holds that for the payload-free
 * frames as well as the payload-carrying ones, and [everyFrameTypeCarriesItsDocumentedTag] holds it
 * for every type.
 *
 * ## The `PRE_2160_*` vectors are frozen, and they assert a REFUSAL
 *
 * `:kuilt-crdt` keeps `RGA_PRE_FLOOR` as the receipt that a frame written by an older encoder still
 * *decodes*. #2160 is the other case: a deliberate, approved break, so the honest receipt is the
 * opposite one. The `PRE_2160_*` constants are the bytes a build from before #2160 puts on the wire,
 * and today's decoder must **reject** them — by failing, not by returning a frame whose fields are
 * wrong.
 *
 * **What an operator actually sees is [RaftTraceEvent.FrameUndecodable], not `FrameRefused`.** The
 * failure is raised in `RaftEngine.decodeInbound`, carried to the actor loop as
 * `EngineCommand.UndecodableMessage`, and emitted by `onUndecodableMessage`. It is deliberately
 * *not* a `FrameRefused`: that event names a `RaftMessageType` and a `RefusalGate`, neither of which
 * a frame that failed to decode can supply — see the "Why this is not a [FrameRefused]" section on
 * [RaftTraceEvent.FrameUndecodable] itself. So the observable carries the peer and a byte count and
 * nothing more; grep for `FrameUndecodable`.
 *
 * **Regenerate the non-frozen constants only on a deliberate encoding change, and expect them to
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

    /**
     * The codec a build from before #2160 used — `raftCbor` minus `alwaysUseByteString`, and nothing
     * else. Paired with [Pre2160Wire] it is that build's whole decoder.
     */
    private val pre2160Cbor = Cbor { ignoreUnknownKeys = true }

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

    /** A vote request: no opaque payload anywhere, so only the tag can tell the two builds apart. */
    private val requestVote: RaftMessage = RaftMessage.RequestVote(term = 7, lastLogIndex = 41, lastLogTerm = 6)

    /** An entry-less `AppendEntries` — a heartbeat, the frame a mixed group exchanges most. */
    private val heartbeat: RaftMessage = RaftMessage.AppendEntries(
        term = 7, prevLogIndex = 41, prevLogTerm = 6, entries = emptyList(), leaderCommit = 41, round = 3,
    )

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
            { assertEquals(REQUEST_VOTE, hex(RaftMessage.serializer(), requestVote), "RequestVote") },
            { assertEquals(HEARTBEAT, hex(RaftMessage.serializer(), heartbeat), "AppendEntries, no entries") },
            { assertEquals(APPEND_ENTRIES, hex(RaftMessage.serializer(), appendEntries), "AppendEntries") },
            { assertEquals(INSTALL_SNAPSHOT, hex(RaftMessage.serializer(), installSnapshot), "InstallSnapshot") },
            { assertEquals(FORWARD, hex(RaftMessage.serializer(), forward), "Forward") },
        )
    }

    /**
     * The break, recorded as a receipt rather than as prose, in **both** directions.
     *
     * Old bytes into today's decoder is what an upgraded node experiences; today's bytes into the old
     * decoder is what the node left behind experiences. `raftCbor`'s KDoc and `kuilt-raft/module.md`
     * both claim a peer on *either* build refuses the other's frames, so both halves are asserted.
     *
     * Each is refused by **failing** with a [SerializationException] — not by returning a frame whose
     * fields are wrong, and not by some incidental throw such as a bounds error. The payload-free
     * frames are the arms that matter most: before the tags changed they were byte-identical across
     * builds, so they are what let a mixed group elect a leader and then wedge instead of refusing.
     *
     * ### What proves the rig fired
     *
     * The old decoder is a reconstruction ([Pre2160Wire] over [pre2160Cbor]), so its refusals are only
     * evidence if it would *accept* an old frame. [theFrozenVectorsAreWhatTheOldCodecReallyProduced]
     * shows it encodes every construction to the frozen bytes and decodes them back; its refusal here
     * is therefore about the new bytes, not about a decoder that refuses everything.
     */
    @Test
    fun aPre2160FrameIsRefusedInBothDirections() {
        fun refused(name: String, codec: Cbor, vector: String, serializer: KSerializer<*>, direction: String): () -> Unit = {
            val outcome = runCatchingCancellable { codec.decodeFromByteArray(serializer, unhex(vector)) }
            assertTrue(
                outcome.isFailure,
                "a $direction $name frame must be REFUSED, not decoded to something: got ${outcome.getOrNull()}",
            )
            // Narrow deliberately: `isFailure` alone would be satisfied by an OOM or a bounds error,
            // which are not "the decoder rejected a foreign framing".
            assertIs<SerializationException>(
                outcome.exceptionOrNull(),
                "$direction $name must be refused as a decode failure, not by some incidental throw",
            )
        }
        val today = RaftMessage.serializer()
        val old = Pre2160Wire.serializer()
        assertAll(
            // Old bytes into today's decoder.
            refused("LogEntry", raftCbor, PRE_2160_LOG_ENTRY, LogEntry.serializer(), "pre-#2160"),
            refused("RequestVote", raftCbor, PRE_2160_REQUEST_VOTE, today, "pre-#2160"),
            refused("heartbeat", raftCbor, PRE_2160_HEARTBEAT, today, "pre-#2160"),
            refused("AppendEntries", raftCbor, PRE_2160_APPEND_ENTRIES, today, "pre-#2160"),
            refused("InstallSnapshot", raftCbor, PRE_2160_INSTALL_SNAPSHOT, today, "pre-#2160"),
            refused("Forward", raftCbor, PRE_2160_FORWARD, today, "pre-#2160"),
            // Today's bytes into the old decoder.
            refused("LogEntry", pre2160Cbor, LOG_ENTRY, LogEntry.serializer(), "post-#2160"),
            refused("RequestVote", pre2160Cbor, REQUEST_VOTE, old, "post-#2160"),
            refused("heartbeat", pre2160Cbor, HEARTBEAT, old, "post-#2160"),
            refused("AppendEntries", pre2160Cbor, APPEND_ENTRIES, old, "post-#2160"),
            refused("InstallSnapshot", pre2160Cbor, INSTALL_SNAPSHOT, old, "post-#2160"),
            refused("Forward", pre2160Cbor, FORWARD, old, "post-#2160"),
        )
    }

    /**
     * The receipt that makes the frozen vectors evidence rather than assertion, and the old decoder a
     * decoder rather than a stub.
     *
     * `:kuilt-crdt`'s precedent and CLAUDE.md both say the same thing: a surrogate used to stand in
     * for bytes you cannot otherwise produce must itself be proven, or the vacuity simply moves one
     * level up. There are two surrogates here and each proves the other against bytes neither wrote.
     * The `PRE_2160_*` constants were recorded from the real pre-#2160 serializer — the four
     * payload-carrying ones before byte-string framing, the two payload-free ones before the tag
     * change, which is the same thing for a frame with no bytes in it. [Pre2160Wire] is a hand-written
     * mirror of that serializer's shape. The mirror encoding every construction to exactly those
     * bytes, and decoding them back, is what shows both are faithful; a typo in a constant or a field
     * dropped from the mirror reds here even though every refusal arm would still pass.
     *
     * It also pins the premise the file rests on: that framing and tags are the **only** differences
     * between the two builds. If some other codec option had changed with them, these equalities would
     * fail.
     */
    @Test
    fun theFrozenVectorsAreWhatTheOldCodecReallyProduced() {
        val old = Pre2160Wire.serializer()
        fun witnessed(name: String, vector: String, value: Pre2160Wire): () -> Unit = {
            assertEquals(vector, hexWith(pre2160Cbor, old, value), "$name: the mirror must reproduce the frozen bytes")
            assertEquals(
                vector, hexWith(pre2160Cbor, old, pre2160Cbor.decodeFromByteArray(old, unhex(vector))),
                "$name: and the old decoder must accept them, or its refusals prove nothing",
            )
        }
        assertAll(
            {
                assertEquals(
                    PRE_2160_LOG_ENTRY, hexWith(pre2160Cbor, LogEntry.serializer(), entry),
                    "LogEntry carries no tag, so the real serializer under the old codec is the witness",
                )
            },
            witnessed("RequestVote", PRE_2160_REQUEST_VOTE, Pre2160Wire.RequestVote(term = 7, lastLogIndex = 41, lastLogTerm = 6)),
            witnessed(
                "heartbeat", PRE_2160_HEARTBEAT,
                Pre2160Wire.AppendEntries(term = 7, prevLogIndex = 41, prevLogTerm = 6, entries = emptyList(), leaderCommit = 41, round = 3),
            ),
            witnessed(
                "AppendEntries", PRE_2160_APPEND_ENTRIES,
                Pre2160Wire.AppendEntries(
                    term = 7, prevLogIndex = 41, prevLogTerm = 6, entries = listOf(entry, configEntry), leaderCommit = 41, round = 3,
                ),
            ),
            witnessed(
                "InstallSnapshot", PRE_2160_INSTALL_SNAPSHOT,
                Pre2160Wire.InstallSnapshot(
                    term = 7, lastIncludedIndex = 40, lastIncludedTerm = 6, offset = 96,
                    data = snapshotState, done = true, config = config, round = 3,
                ),
            ),
            witnessed(
                "Forward", PRE_2160_FORWARD,
                Pre2160Wire.Forward(clientRequestId = 11, command = command, dedupKey = DedupKey(clientId, 9_000_000_000L)),
            ),
        )
    }

    /**
     * Every frame type's tag, pinned — the table in [RaftMessage]'s KDoc, as a test.
     *
     * The golden vectors above cover four of the eleven frame types. A tag is a wire identifier: an
     * edit to any one of the other seven would split a group along that one frame type, which is the
     * partial break this epoch exists to avoid. So each type is encoded and its tag read off the front
     * of the frame, and each is checked against the fully-qualified class name a pre-#2160 build writes
     * — the tag the old decoder looks for, and so the one tag a new frame must never carry.
     *
     * `ForwardOutcome` rides inside `ForwardResponse` and is polymorphic too, so its three tags are
     * pinned the same way.
     */
    @Test
    fun everyFrameTypeCarriesItsDocumentedTag() {
        val frames: List<Pair<String, RaftMessage>> = listOf(
            "rv" to RaftMessage.RequestVote(1, 0, 0),
            "rvr" to RaftMessage.RequestVoteResponse(1, true),
            "ae" to RaftMessage.AppendEntries(1, 0, 0, emptyList(), 0),
            "aer" to RaftMessage.AppendEntriesResponse(1, true),
            "is" to RaftMessage.InstallSnapshot(1, 1, 1, 0, ByteArray(0), true),
            "isr" to RaftMessage.InstallSnapshotResponse(1, 0),
            "pv" to RaftMessage.PreVote(1, 0, 0, 1),
            "pvr" to RaftMessage.PreVoteResponse(1, true, 1, 1),
            "tn" to RaftMessage.TimeoutNow(1),
            "fw" to RaftMessage.Forward(1, ByteArray(0)),
            "fwr" to RaftMessage.ForwardResponse(1, ForwardOutcome.NotLeader),
        )
        val outcomes: List<Triple<String, String, ForwardOutcome>> = listOf(
            Triple("cm", "Committed", ForwardOutcome.Committed(1, 1)),
            Triple("nl", "NotLeader", ForwardOutcome.NotLeader),
            Triple("fl", "Failed", ForwardOutcome.Failed),
        )
        val fqFrame = "us.tractat.kuilt.raft.internal.RaftMessage."
        val fqOutcome = "us.tractat.kuilt.raft.internal.ForwardOutcome."
        assertAll(
            {
                assertEquals(
                    RaftMessageType.entries.toSet(),
                    frames.map { (_, frame) -> frame.messageType }.toSet(),
                    "rig: one construction per frame type — a new RaftMessage subtype needs a row here",
                )
            },
            {
                assertEquals(
                    frames.size, frames.map { (tag, _) -> tag }.toSet().size,
                    "no two frame types may share a tag, or one decodes as the other",
                )
            },
            *frames.map { (tag, frame) ->
                {
                    val type = frame.messageType.name
                    val tagged = leadingTag(raftCbor.encodeToByteArray(RaftMessage.serializer(), frame))
                    assertEquals(tag, tagged, "$type must carry the tag \"$tag\"")
                    assertNotEquals(fqFrame + type, tagged, "$type must not carry the tag a pre-#2160 build decodes")
                }
            }.toTypedArray(),
            *outcomes.map { (tag, type, outcome) ->
                {
                    val tagged = leadingTag(raftCbor.encodeToByteArray(ForwardOutcome.serializer(), outcome))
                    assertEquals(tag, tagged, "ForwardOutcome.$type must carry the tag \"$tag\"")
                    assertNotEquals(fqOutcome + type, tagged, "ForwardOutcome.$type must not carry its pre-#2160 tag")
                }
            }.toTypedArray(),
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
     * construction exists for; the frozen `LogEntry` pins the *magnitude* of the framing difference,
     * and the frozen vote pins that the **tag** is the whole of the payload-free difference.
     */
    @Test
    fun everyOpaquePayloadStraddlesTheShortRange() {
        fun outsideShortRange(bytes: ByteArray) = bytes.count { it.toInt() !in -24..23 }
        fun textHeader(text: String) = hexOf(byteArrayOf((0x60 + text.length).toByte())).takeIf { text.length < 24 }
            ?: (hexOf(byteArrayOf(0x78, text.length.toByte())))
        fun tag(text: String) = textHeader(text) + hexOf(text.encodeToByteArray())
        assertAll(
            { assertEquals(20, outsideShortRange(command), "the command must span the boundary, not sit inside it") },
            { assertEquals(48, outsideShortRange(snapshotState), "every snapshot byte is outside the short range") },
            { assertEquals(0, configEntry.command.size, "the config entry pins the empty-payload shape") },
            {
                assertNotEquals(
                    PRE_2160_LOG_ENTRY, LOG_ENTRY,
                    "the frozen pre-#2160 vector must differ from today's, or it records nothing",
                )
            },
            {
                assertEquals(
                    20,
                    (PRE_2160_LOG_ENTRY.length - LOG_ENTRY.length) / 2,
                    "and must be larger by exactly the 20 command bytes the old framing doubled",
                )
            },
            {
                assertEquals(
                    REQUEST_VOTE,
                    PRE_2160_REQUEST_VOTE.replace(tag("us.tractat.kuilt.raft.internal.RaftMessage.RequestVote"), tag("rv")),
                    "a payload-free frame must differ across the break in its tag and nowhere else",
                )
            },
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun <S> hex(serializer: KSerializer<S>, value: S): String =
        hexWith(raftCbor, serializer, value)

    private fun <S> hexWith(codec: Cbor, serializer: KSerializer<S>, value: S): String =
        hexOf(codec.encodeToByteArray(serializer, value))

    private fun hexOf(bytes: ByteArray): String =
        bytes.joinToString("") { byte -> (byte.toInt() and 0xFF).toString(radix = 16).padStart(2, '0') }

    private fun unhex(text: String): ByteArray =
        ByteArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(radix = 16).toByte() }

    /**
     * The tag at the front of a polymorphic frame: kotlinx CBOR writes one as a two-element array,
     * `[tag, value]`, so the tag is the text string straight after the array's opening byte. Read
     * here rather than through a descriptor so it is what the wire carries, not what the class says.
     */
    private fun leadingTag(frame: ByteArray): String {
        check(frame[0] == 0x9F.toByte()) { "a polymorphic frame opens an indefinite array, got ${hexOf(frame.copyOf(1))}" }
        val head = frame[1].toInt() and 0xFF
        val (length, start) = when (head) {
            in 0x60..0x77 -> (head - 0x60) to 2
            0x78 -> (frame[2].toInt() and 0xFF) to 3
            else -> error("a tag is a short text string, got head ${hexOf(frame.copyOfRange(1, 2))}")
        }
        return frame.copyOfRange(start, start + length).decodeToString()
    }

    private companion object {
        const val LOG_ENTRY =
            "bf65696e646578182a647465726d0767636f6d6d616e645828000102030405060708090a0b0c0d0e0f1011121374" +
                "75767778797a7b7c7d7e7f80818283848586876864656475704b6579bf68636c69656e744964781f6175746f3a64" +
                "63312d6e6f6465372d30313233343536373839616263646566697265717565737449641b0000000218711a00ffff"

        const val REQUEST_VOTE =
            "9f627276bf647465726d076c6c6173744c6f67496e64657818296b6c6173744c6f675465726d06ffff"

        const val HEARTBEAT =
            "9f626165bf647465726d076c707265764c6f67496e64657818296b707265764c6f675465726d0667656e74726965" +
                "739fff6c6c6561646572436f6d6d6974182965726f756e6403ffff"

        const val APPEND_ENTRIES =
            "9f626165bf647465726d076c707265764c6f67496e64657818296b707265764c6f675465726d0667656e74726965" +
                "739fbf65696e646578182a647465726d0767636f6d6d616e645828000102030405060708090a0b0c0d0e0f101112" +
                "137475767778797a7b7c7d7e7f80818283848586876864656475704b6579bf68636c69656e744964781f6175746f" +
                "3a6463312d6e6f6465372d30313233343536373839616263646566697265717565737449641b0000000218711a00" +
                "ffffbf65696e646578182b647465726d0767636f6d6d616e644066636f6e666967bf636f6c64a0636e6577bf6676" +
                "6f746572739f627631627632627633ff686c6561726e6572739f626c31ffffffffff6c6c6561646572436f6d6d69" +
                "74182965726f756e6403ffff"

        const val INSTALL_SNAPSHOT =
            "9f626973bf647465726d07716c617374496e636c75646564496e6465781828706c617374496e636c756465645465" +
                "726d06666f6666736574186064646174615830404142434445464748494a4b4c4d4e4f505152535455565758595a" +
                "5b5c5d5e5f606162636465666768696a6b6c6d6e6f64646f6e65f566636f6e666967bf636f6c64a0636e6577bf66" +
                "766f746572739f627631627632627633ff686c6561726e6572739f626c31ffffff65726f756e6403ffff"

        const val FORWARD =
            "9f626677bf6f636c69656e745265717565737449640b67636f6d6d616e645828000102030405060708090a0b0c0d" +
                "0e0f101112137475767778797a7b7c7d7e7f80818283848586876864656475704b6579bf68636c69656e74496478" +
                "1f6175746f3a6463312d6e6f6465372d30313233343536373839616263646566697265717565737449641b000000" +
                "0218711a00ffffff"

        /**
         * The six constants below are the bytes the constructions above produce on a build from
         * **before #2160** — fully-qualified tags, and opaque payloads as arrays of integers. The
         * two payload-free ones were recorded from the real serializer just before the tags changed,
         * which for a frame with no bytes in it is the same thing.
         *
         * They are what make [aPre2160FrameIsRefusedInBothDirections] a receipt rather than a
         * restatement. **Frozen**: they are never regenerated, and are deleted outright only if the
         * decoder deliberately stops refusing them.
         */
        const val PRE_2160_LOG_ENTRY =
            "bf65696e646578182a647465726d0767636f6d6d616e649f000102030405060708090a0b0c0d0e0f101112131874" +
                "18751876187718781879187a187b187c187d187e187f387f387e387d387c387b387a38793878ff6864656475704b" +
                "6579bf68636c69656e744964781f6175746f3a6463312d6e6f6465372d3031323334353637383961626364656669" +
                "7265717565737449641b0000000218711a00ffff"

        const val PRE_2160_REQUEST_VOTE =
            "9f783675732e747261637461742e6b75696c742e726166742e696e7465726e616c2e526166744d6573736167652e" +
                "52657175657374566f7465bf647465726d076c6c6173744c6f67496e64657818296b6c6173744c6f675465726d06" +
                "ffff"

        const val PRE_2160_HEARTBEAT =
            "9f783875732e747261637461742e6b75696c742e726166742e696e7465726e616c2e526166744d6573736167652e" +
                "417070656e64456e7472696573bf647465726d076c707265764c6f67496e64657818296b707265764c6f67546572" +
                "6d0667656e74726965739fff6c6c6561646572436f6d6d6974182965726f756e6403ffff"

        const val PRE_2160_APPEND_ENTRIES =
            "9f783875732e747261637461742e6b75696c742e726166742e696e7465726e616c2e526166744d6573736167652e" +
                "417070656e64456e7472696573bf647465726d076c707265764c6f67496e64657818296b707265764c6f67546572" +
                "6d0667656e74726965739fbf65696e646578182a647465726d0767636f6d6d616e649f000102030405060708090a" +
                "0b0c0d0e0f10111213187418751876187718781879187a187b187c187d187e187f387f387e387d387c387b387a38" +
                "793878ff6864656475704b6579bf68636c69656e744964781f6175746f3a6463312d6e6f6465372d303132333435" +
                "36373839616263646566697265717565737449641b0000000218711a00ffffbf65696e646578182b647465726d07" +
                "67636f6d6d616e649fff66636f6e666967bf636f6c64a0636e6577bf66766f746572739f627631627632627633ff" +
                "686c6561726e6572739f626c31ffffffffff6c6c6561646572436f6d6d6974182965726f756e6403ffff"

        const val PRE_2160_INSTALL_SNAPSHOT =
            "9f783a75732e747261637461742e6b75696c742e726166742e696e7465726e616c2e526166744d6573736167652e" +
                "496e7374616c6c536e617073686f74bf647465726d07716c617374496e636c75646564496e6465781828706c6173" +
                "74496e636c756465645465726d06666f6666736574186064646174619f1840184118421843184418451846184718" +
                "481849184a184b184c184d184e184f1850185118521853185418551856185718581859185a185b185c185d185e18" +
                "5f1860186118621863186418651866186718681869186a186b186c186d186e186fff64646f6e65f566636f6e6669" +
                "67bf636f6c64a0636e6577bf66766f746572739f627631627632627633ff686c6561726e6572739f626c31ffffff" +
                "65726f756e6403ffff"

        const val PRE_2160_FORWARD =
            "9f783275732e747261637461742e6b75696c742e726166742e696e7465726e616c2e526166744d6573736167652e" +
                "466f7277617264bf6f636c69656e745265717565737449640b67636f6d6d616e649f000102030405060708090a0b" +
                "0c0d0e0f10111213187418751876187718781879187a187b187c187d187e187f387f387e387d387c387b387a3879" +
                "3878ff6864656475704b6579bf68636c69656e744964781f6175746f3a6463312d6e6f6465372d30313233343536" +
                "373839616263646566697265717565737449641b0000000218711a00ffffff"
    }
}

/**
 * A pre-#2160 build's `RaftMessage`, as far as the wire can see it — the fully-qualified tags and the
 * same fields, for the four frame types the vectors cover.
 *
 * Paired with `pre2160Cbor` this is that build's decoder, so today's bytes can be put through it.
 * It is a hand-written mirror, and so a surrogate: its fidelity is proven by
 * [RaftWireGoldenVectorTest.theFrozenVectorsAreWhatTheOldCodecReallyProduced], which requires it to
 * reproduce, byte for byte, frames the real pre-#2160 serializer wrote.
 */
@Serializable
private sealed interface Pre2160Wire {
    @Serializable
    @SerialName("us.tractat.kuilt.raft.internal.RaftMessage.RequestVote")
    data class RequestVote(
        val term: Long,
        val lastLogIndex: Long,
        val lastLogTerm: Long,
        val leadershipTransfer: Boolean = false,
    ) : Pre2160Wire

    @Serializable
    @SerialName("us.tractat.kuilt.raft.internal.RaftMessage.AppendEntries")
    data class AppendEntries(
        val term: Long,
        val prevLogIndex: Long,
        val prevLogTerm: Long,
        val entries: List<LogEntry>,
        val leaderCommit: Long,
        val round: Long = 0L,
    ) : Pre2160Wire

    @Serializable
    @SerialName("us.tractat.kuilt.raft.internal.RaftMessage.InstallSnapshot")
    class InstallSnapshot(
        val term: Long,
        val lastIncludedIndex: Long,
        val lastIncludedTerm: Long,
        val offset: Long,
        val data: ByteArray,
        val done: Boolean,
        val config: ConfigPayload? = null,
        val round: Long = 0L,
    ) : Pre2160Wire

    @Serializable
    @SerialName("us.tractat.kuilt.raft.internal.RaftMessage.Forward")
    class Forward(
        val clientRequestId: Long,
        val command: ByteArray,
        val dedupKey: DedupKey? = null,
    ) : Pre2160Wire
}
