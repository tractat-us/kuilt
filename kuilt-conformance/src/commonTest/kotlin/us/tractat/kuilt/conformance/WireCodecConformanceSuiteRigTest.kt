package us.tractat.kuilt.conformance

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [WireCodecConformanceSuite] driven against deliberately-**lying** harnesses, to establish that
 * each of its properties can fail.
 *
 * A TCK is a claim about codecs it has never seen, so the only evidence that it holds any of them
 * to anything is a codec constructed to break each property and watched going red. Every in-tree
 * subclass is green, which is exactly the state in which a vacuous suite is indistinguishable from
 * a load-bearing one — #1822's own inventory found five sites whose tests were green because the
 * constraint was unenforced, and this file is the same question asked one level up, about the
 * suite itself.
 *
 * Every property below is reached through the suite's own `@Test` method, not a re-implementation
 * of it: a rig that restated the assertion would go on passing after the suite stopped making it.
 */
class WireCodecConformanceSuiteRigTest {

    // ── the exact-width contract has teeth ───────────────────────────────────

    @Test
    fun aCodecThatAcceptsAWrongWidthFailsEveryRejectionProperty() {
        val permissive = harness(decoder = { "accepted whatever arrived" })
        assertAllRed(
            "one byte short" to { permissive.everyExactWidthFieldIsRejectedOneByteShort() },
            "one byte long" to { permissive.everyExactWidthFieldIsRejectedOneByteLong() },
            "zero width" to { permissive.everyExactWidthFieldIsRejectedAtZeroWidth() },
        )
    }

    /**
     * The precondition earns its place: a codec refusing *everything* satisfies all three rejection
     * properties perfectly, and only the acceptance arm separates it from a correct one.
     */
    @Test
    fun aCodecThatRejectsEverythingFailsTheAcceptancePrecondition() {
        val paranoid = harness(decoder = { throw IllegalArgumentException("no frame is ever good enough") })
        assertRed("declared width accepted") { paranoid.everyExactWidthFieldIsAcceptedAtItsDeclaredWidth() }
        // …while passing every rejection property, which is the point.
        paranoid.everyExactWidthFieldIsRejectedOneByteShort()
        paranoid.everyExactWidthFieldIsRejectedOneByteLong()
    }

    /** A rig that ignores the width it is handed makes every rejection a rejection of one frame. */
    @Test
    fun aRigThatIgnoresTheWidthFailsTheRigCheck() {
        val constantRig = harness(
            fields = listOf(ExactWidthField("nonce", FIELD_BYTES) { ByteArray(1 + FIELD_BYTES) }),
        )
        assertRed("rig varies the width") { constantRig.everyExactWidthFieldsRigVariesTheWidthAndNothingElse() }
    }

    // ── the rejection SHAPE is part of the contract (#1819) ──────────────────

    /**
     * The tooth that stops #1819 being reintroduced under a green suite: a codec whose consumer is
     * a receive pump declares [WireRejectionMode.ReturningNull], and a throw is then a failure
     * rather than a refusal — because a throw there ends the pump and leaves the seam deaf.
     */
    @Test
    fun aCodecDeclaringReturningNullThatThrowsInsteadIsNotRejecting() {
        val throwing = harness(
            rejectionMode = WireRejectionMode.ReturningNull,
            decoder = { frame -> if (frame.size == 1 + FIELD_BYTES) "ok" else error("escaped the pump") },
        )
        val failure = assertRed("short width") { throwing.everyExactWidthFieldIsRejectedOneByteShort() }
        assertTrue(
            failure.message.orEmpty().contains("permanently deaf"),
            "the diagnosis must name what an escaping exception costs, not merely that a check " +
                "failed — got: ${failure.message}",
        )
    }

    /** The mirror: a codec declaring `Throwing` does not get to refuse by returning `null`. */
    @Test
    fun aCodecDeclaringThrowingThatReturnsNullInsteadIsNotRejecting() {
        val nulling = harness(
            rejectionMode = WireRejectionMode.Throwing,
            decoder = { frame -> if (frame.size == 1 + FIELD_BYTES) "ok" else null },
        )
        assertRed("short width") { nulling.everyExactWidthFieldIsRejectedOneByteShort() }
    }

    // ── the header contract has teeth, on BOTH sides ─────────────────────────

    @Test
    fun aCodecThatAcceptsAFrameShorterThanItsHeaderFailsTheShortSide() {
        val permissive = headerHarness(decoder = { "accepted whatever arrived" })
        assertRed("shorter than the header") {
            permissive.everyFixedWidthHeaderRejectsAFrameShorterThanTheHeader()
        }
    }

    /**
     * The long side is a real obligation, not a courtesy: a header plus one byte is a frame with a
     * one-byte payload, and a codec refusing it is broken. Asserting that this can fail is what
     * stops a later reader "completing the symmetry" by demanding a rejection there.
     */
    @Test
    fun aCodecThatRefusesAOneBytePayloadFailsTheLongSide() {
        val overStrict = headerHarness(
            decoder = { frame -> if (frame.size == HEADER_BYTES) "ok" else null },
        )
        assertRed("at or one byte over the header") {
            overStrict.everyFixedWidthHeaderAcceptsAFrameAtOrOneByteOverTheHeader()
        }
    }

    @Test
    fun aHeaderRigThatIgnoresTheSizeItWasAskedForFailsTheRigCheck() {
        val constantRig = headerHarness(
            headers = listOf(FixedWidthHeader("header", HEADER_BYTES) { ByteArray(HEADER_BYTES) }),
        )
        assertRed("rig returns the size asked for") {
            constantRig.everyFixedWidthHeadersRigReturnsTheSizeItWasAskedFor()
        }
    }

    // ── the declarations cannot be self-certified ────────────────────────────

    /**
     * The failure mode this suite is most exposed to, because every property iterates a list: a
     * `Proven` over an empty list runs each of them against zero elements and asserts nothing,
     * and the green is byte-identical to a green over three fields.
     */
    @Test
    fun provenOverAnEmptyFieldListIsCaught() {
        val empty = harness(fields = emptyList())
        // Every property it claims to prove passes, vacuously …
        empty.everyExactWidthFieldIsRejectedOneByteShort()
        empty.everyExactWidthFieldIsAcceptedAtItsDeclaredWidth()
        // … and only the declaration check sees it.
        assertRed("Proven over an empty list") { empty.exactWidthDeclarationIsHonest() }
    }

    /** `NotConstructible` over a list that constructs the thing is a self-refuting claim. */
    @Test
    fun notConstructibleOverANonEmptyFieldListIsCaught() {
        val contradictory = harness(
            exactWidth = ObligationDeclaration.NotApplicable.NotConstructible("no field can be built here"),
        )
        assertRed("NotConstructible with fields declared") { contradictory.exactWidthDeclarationIsHonest() }
    }

    /** A `Gap` is a shortfall a reader can go and find, or it is not a gap. */
    @Test
    fun aGapWithoutATrackingUrlIsCaught() {
        val untracked = harness(fields = emptyList(), exactWidth = ObligationDeclaration.Gap("  "))
        assertRed("blank tracking URL") { untracked.exactWidthDeclarationIsHonest() }
    }

    /**
     * `ContractDiffers` is the strong arm everywhere else in this repo, and it stays strong here: a
     * codec cannot claim it answers the width differently on purpose while behaving exactly as the
     * contract requires.
     */
    @Test
    fun contractDiffersOverACodecThatConformsIsCaught() {
        val conforming = harness(
            exactWidth = ObligationDeclaration.NotApplicable.ContractDiffers("we reshape on purpose"),
        )
        assertRed("ContractDiffers without a deviation") { conforming.exactWidthDeclarationIsHonest() }
    }

    /** Subclassing the suite and declaring every obligation away buys a green with nothing behind it. */
    @Test
    fun aHarnessThatDeclaresEveryObligationAwayIsCaught() {
        val hollow = harness(
            fields = emptyList(),
            exactWidth = ObligationDeclaration.Gap("https://github.com/tractat-us/kuilt/issues/1822"),
        )
        assertRed("no obligation Proven") { hollow.theHarnessProvesAtLeastOneObligation() }
    }

    // ── fakes ────────────────────────────────────────────────────────────────

    private fun assertRed(what: String, property: () -> Unit): AssertionError =
        assertFailsWith<AssertionError>("$what: this property cannot fail, so it proves nothing") { property() }

    private fun assertAllRed(vararg properties: Pair<String, () -> Unit>) {
        for ((what, property) in properties) assertRed(what, property)
    }

    /**
     * A harness over a fake codec whose frame is `[tag][field × width]`, correct by default and
     * broken exactly where a caller overrides it.
     */
    private fun harness(
        rejectionMode: WireRejectionMode = WireRejectionMode.Throwing,
        decoder: (ByteArray) -> Any? = { frame ->
            if (frame.size == 1 + FIELD_BYTES) "ok" else throw IllegalArgumentException("wrong width")
        },
        fields: List<ExactWidthField> = listOf(ExactWidthField("nonce", FIELD_BYTES) { ByteArray(1 + it) }),
        exactWidth: ObligationDeclaration = ObligationDeclaration.Proven,
    ): WireCodecConformanceSuite = object : WireCodecConformanceSuite() {
        override fun decode(frame: ByteArray): Any? = decoder(frame)
        override fun rejectionMode(): WireRejectionMode = rejectionMode
        override fun exactWidthFields(): List<ExactWidthField> = fields
        override fun exactWidthDeclaration(): ObligationDeclaration = exactWidth
        override fun fixedWidthHeaders(): List<FixedWidthHeader> = emptyList()
        override fun fixedWidthHeaderDeclaration(): ObligationDeclaration =
            ObligationDeclaration.NotApplicable.NotConstructible("this fake has no header region")
    }

    /** A harness over a fake codec that is a [HEADER_BYTES]-byte header followed by a payload. */
    private fun headerHarness(
        decoder: (ByteArray) -> Any? = { frame -> if (frame.size >= HEADER_BYTES) "ok" else null },
        headers: List<FixedWidthHeader> = listOf(FixedWidthHeader("header", HEADER_BYTES) { ByteArray(it) }),
    ): WireCodecConformanceSuite = object : WireCodecConformanceSuite() {
        override fun decode(frame: ByteArray): Any? = decoder(frame)
        override fun rejectionMode(): WireRejectionMode = WireRejectionMode.ReturningNull
        override fun exactWidthFields(): List<ExactWidthField> = emptyList()
        override fun exactWidthDeclaration(): ObligationDeclaration =
            ObligationDeclaration.NotApplicable.NotConstructible("this fake has no exact-width field")

        override fun fixedWidthHeaders(): List<FixedWidthHeader> = headers
        override fun fixedWidthHeaderDeclaration(): ObligationDeclaration = ObligationDeclaration.Proven
    }

    private companion object {
        const val FIELD_BYTES = 4
        const val HEADER_BYTES = 3
    }
}
