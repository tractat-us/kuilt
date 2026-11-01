package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Byte-level pins for the control log's framing (#1738). They run in `commonTest` because every target
 * must read the same bytes the same way.
 *
 * A fail-closed reader turns any accidental wire change into a halted node, so these are checked-in
 * literals rather than round trips: renaming a [ControlCommand] subclass, or reordering a field, reds
 * here instead of stranding a durable log. Regenerate one only for a deliberate format change, and
 * bump [ControlLogCodec.CURRENT_VERSION] when you do.
 */
class HeddleControlLogCodecTest {

    @Test
    fun everyCommandEncodesToItsGoldenTaggedBytes() {
        val wrong = ControlLogVectors.commands.filter { (name, command) ->
            ControlLogCodec.encode(ControlLogVectors.envelope(command)).toHexString() !=
                ControlLogVectors.TAGGED_PREFIX + ControlLogVectors.bodies.getValue(name)
        }
        assertEquals(10, ControlLogVectors.commands.size, "rig: every ControlCommand subclass has a vector")
        assertEquals(emptyList(), wrong.map { it.first }, "commands whose bytes moved")
    }

    /** Fixed bytes in, so a broken encoder cannot agree with a broken decoder. */
    @Test
    fun goldenTaggedBytesDecodeBackToEveryCommand() {
        for ((name, command) in ControlLogVectors.commands) {
            val bytes = (ControlLogVectors.TAGGED_PREFIX + ControlLogVectors.bodies.getValue(name)).hexToByteArray()
            assertEquals(ControlEntry.Act(ControlLogVectors.envelope(command)), ControlLogCodec.decode(bytes), name)
        }
    }

    /** An act written before the header existed stays readable while this build can decode its shape. */
    @Test
    fun goldenUntaggedBytesDecodeBackToEveryCommand() {
        for ((name, command) in ControlLogVectors.commands) {
            val bytes = ControlLogVectors.bodies.getValue(name).hexToByteArray()
            assertEquals(ControlEntry.Act(ControlLogVectors.envelope(command)), ControlLogCodec.decode(bytes), name)
        }
    }

    /**
     * `v0.7.3`'s `Mint` has no `root`. The fixture's bytes come from that release's shape run through
     * the serializer, and the failure has to name `root`, so this cannot pass on a fixture that fails
     * for some other reason.
     */
    @Test
    fun aV073MintIsUnreadableBecauseItHasNoRoot() {
        val decoded = assertIs<ControlEntry.Unreadable>(
            ControlLogCodec.decode(ControlLogVectors.V0_7_3_MINT.hexToByteArray()),
        )
        assertAll(
            { assertEquals(ControlLogCodec.UNTAGGED_VERSION, decoded.version) },
            { assertTrue("root" in decoded.failure, "failed for the wrong reason: ${decoded.failure}") },
        )
    }

    /**
     * `v0.7.3`'s `AttachmentRecord` carries an `initialVirtualTime` that #2379 deleted. So a decoding
     * default for `root` would not have saved a `v0.7.3` log: its `Prepare`s are unreadable too.
     */
    @Test
    fun aV073PrepareIsUnreadableBecauseOfADeletedField() {
        val decoded = assertIs<ControlEntry.Unreadable>(
            ControlLogCodec.decode(ControlLogVectors.V0_7_3_PREPARE.hexToByteArray()),
        )
        assertAll(
            { assertEquals(ControlLogCodec.UNTAGGED_VERSION, decoded.version) },
            { assertTrue("initialVirtualTime" in decoded.failure, "failed for the wrong reason: ${decoded.failure}") },
        )
    }

    @Test
    fun aTaggedEntryOfAnUnknownVersionIsUnreadableAndNamesTheVersion() {
        val decoded = ControlLogCodec.decode(ControlLogVectors.taggedAtVersion(99))
        assertEquals(99, assertIs<ControlEntry.Unreadable>(decoded).version)
    }

    @Test
    fun aTaggedEntryWithACorruptBodyIsUnreadableAtItsVersion() {
        val decoded = ControlLogCodec.decode(ControlLogVectors.CORRUPT_TAGGED.hexToByteArray())
        assertEquals(ControlLogCodec.CURRENT_VERSION, assertIs<ControlEntry.Unreadable>(decoded).version)
    }

    @Test
    fun aTaggedEntryCutOffBeforeItsVersionByteHasNoVersion() {
        val decoded = ControlLogCodec.decode(ControlLogVectors.MAGIC_ONLY.hexToByteArray())
        assertEquals(null, assertIs<ControlEntry.Unreadable>(decoded).version)
    }

    /** An untagged act that no longer decodes is never mistaken for a foreign entry. */
    @Test
    fun anUntaggedActThatNoLongerDecodesIsUnreadableNotForeign() {
        val truncated = ControlLogVectors.bodies.getValue("Enroll").dropLast(4).hexToByteArray()
        assertIs<ControlEntry.Unreadable>(ControlLogCodec.decode(truncated))
    }

    @Test
    fun bytesNoVersionOfHeddleWroteAreNotHeddle() {
        val foreign = listOf(
            "" to byteArrayOf(),
            "CBOR break byte" to "ff0042".hexToByteArray(),
            "a CBOR map, but not an envelope" to "a1616101".hexToByteArray(),
            "the indefinite map opening alone" to "bf".hexToByteArray(),
            "an indefinite map with another first key" to "bf6170ff".hexToByteArray(),
            "plain text" to "set x=1".encodeToByteArray(),
        )
        assertEquals(
            emptyList(),
            foreign.filter { (_, bytes) -> ControlLogCodec.decode(bytes) != ControlEntry.NotHeddle }.map { it.first },
            "entries wrongly claimed as heddle's",
        )
    }
}

/** The checked-in bytes behind [HeddleControlLogCodecTest] and the fail-closed tests. */
internal object ControlLogVectors {
    private const val KEY = "p#b#0"
    private val P = ReplicaId("p")
    private val EDGE = AttachmentId("e")

    /** `KHCL` and version 1. */
    const val TAGGED_PREFIX: String = "4b48434c01"

    const val MAGIC_ONLY: String = "4b48434c"

    /** A tagged Enroll cut off partway through its last field. */
    const val CORRUPT_TAGGED: String =
        "4b48434c01bf6a726571756573744b657965702362233067636f6d6d616e649f782d75732e747261637461742e6b75696c742e68" +
            "6564646c652e436f6e74726f6c436f6d6d616e642e456e726f6c6cbf677265706c6963616170ffff6870726f706f73"

    private const val OPENING: String = "bf6a726571756573744b657965702362233067636f6d6d616e649f"
    private const val CLOSING: String = "ffff6870726f706f7365726170ff"
    private const val NS: String = "75732e747261637461742e6b75696c742e686564646c652e436f6e74726f6c436f6d6d616e642e"

    /** `v0.7.3`'s `Mint(holder, amount)`, with no `root`. */
    const val V0_7_3_MINT: String = OPENING + "782b" + NS + "4d696e74" + "bf66686f6c646572617066616d6f756e7407" + CLOSING

    /** `v0.7.3`'s `Prepare`, whose record still carries `initialVirtualTime`. */
    const val V0_7_3_PREPARE: String = OPENING + "782e" + NS + "50726570617265" +
        "bf667265636f7264bf626964616566706172656e7464726f6f74656368696c64616366776569676874" +
        "bf696e756d657261746f72036b64656e6f6d696e61746f7201ff72696e697469616c5669727475616c54696d6500ff" + CLOSING

    /** The untagged CBOR of each command under [KEY] and proposer `p`; a tagged act is the same bytes after [TAGGED_PREFIX]. */
    val bodies: Map<String, String> = mapOf(
        "Mint" to OPENING + "782b" + NS + "4d696e74" + "bf64726f6f7464726f6f7466686f6c646572617066616d6f756e7407" + CLOSING,
        "Prepare" to OPENING + "782e" + NS + "50726570617265" +
            "bf667265636f7264bf626964616566706172656e7464726f6f74656368696c64616366776569676874" +
            "bf696e756d657261746f72036b64656e6f6d696e61746f7201ffff" + CLOSING,
        "Activate" to OPENING + "782f" + NS + "4163746976617465" + "bf64656467656165" + CLOSING,
        "Close" to OPENING + "782c" + NS + "436c6f7365" + "bf64656467656165" + CLOSING,
        "Retire" to OPENING + "782d" + NS + "526574697265" + "bf64656467656165" + CLOSING,
        "Reconcile" to OPENING + "7830" + NS + "5265636f6e63696c65" + "bf656368696c646163" + CLOSING,
        "Quiesce" to OPENING + "782e" + NS + "51756965736365" + "bf64656467656165" + CLOSING,
        "QuiesceAck" to OPENING + "7831" + NS + "5175696573636541636b" +
            "bf64656467656165677265706c69636161706666696e616c73" +
            "bf66697373756564016872657475726e656402696c6561665370656e74036b726f6c6c75705370656e7404" +
            "697472616e7366657273bf617105ffff" + CLOSING,
        "Enroll" to OPENING + "782d" + NS + "456e726f6c6c" + "bf677265706c6963616170" + CLOSING,
        "Depart" to OPENING + "782d" + NS + "446570617274" + "bf677265706c6963616170" + CLOSING,
    )

    val commands: List<Pair<String, ControlCommand>> = listOf(
        "Mint" to ControlCommand.Mint(GroupId("root"), P, 7L),
        "Prepare" to ControlCommand.Prepare(AttachmentRecord(EDGE, GroupId("root"), GroupId("c"), Weight.of(3))),
        "Activate" to ControlCommand.Activate(EDGE),
        "Close" to ControlCommand.Close(EDGE),
        "Retire" to ControlCommand.Retire(EDGE),
        "Reconcile" to ControlCommand.Reconcile(GroupId("c")),
        "Quiesce" to ControlCommand.Quiesce(EDGE),
        "QuiesceAck" to ControlCommand.QuiesceAck(EDGE, P, SlotFinals(1L, 2L, 3L, 4L, mapOf(ReplicaId("q") to 5L))),
        "Enroll" to ControlCommand.Enroll(P),
        "Depart" to ControlCommand.Depart(P),
    )

    fun envelope(command: ControlCommand): ControlEnvelope = ControlEnvelope(KEY, command, P)

    /** A well-formed tagged Enroll, under a version this build does not read. */
    fun taggedAtVersion(version: Int): ByteArray =
        (MAGIC_ONLY + version.toString(radix = 16).padStart(2, '0') + bodies.getValue("Enroll")).hexToByteArray()
}
