package us.tractat.kuilt.conformance

import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One documented **fixed-width field** on a wire type, plus the rig that builds a frame carrying
 * that field at an arbitrary width.
 *
 * The field is identified by its rig, not by an offset: a codec's fields sit at positions only the
 * codec knows, and several of the in-tree ones (a CBOR byte string, a trailing nonce behind a
 * variable-length id) have no fixed offset at all.
 *
 * ## What [encodeAtWidth] owes, and the one way it goes vacuous
 *
 * `encodeAtWidth(w)` must return a frame that is well-formed **in every respect except that this
 * field is `w` bytes wide**. For a raw, self-describing layout that is byte surgery on the field's
 * range. For a **length-delimited** encoding (CBOR, protobuf, anything with a length header in
 * front of the field) it is **not**: truncating the field's bytes without moving its length header
 * leaves the *parser* with a short read, and the parser rejects it. The property then passes with
 * the width check deleted — which is the whole failure this suite exists to catch, reappearing one
 * level up inside the rig.
 *
 * So for a length-delimited encoding, **re-encode**: run the codec's own serializer over a
 * surrogate that is not width-constrained, and let it emit a correct length header for `w`. See
 * `TapAdmitChallengeWireCodecTest` for the worked example.
 *
 * [WireCodecConformanceSuite.everyExactWidthFieldsRigVariesTheWidthAndNothingElse] checks what it
 * can of this — that the rig's frames grow strictly with `w`, and that the codec accepts the frame
 * at the declared width — but it cannot see *why* a wrong-width frame was refused. Only reverting
 * the codec's width check and watching this suite go red establishes that, which is the evidence a
 * new harness owes alongside a new declaration (#1822).
 *
 * @property name the field, as a reader of the wire format would name it (`nonce`, `secret`).
 * @property declaredWidth the width the codec's own documentation fixes, in bytes.
 * @property encodeAtWidth builds a frame whose [name] field is the given number of bytes wide.
 */
public class ExactWidthField(
    public val name: String,
    public val declaredWidth: Int,
    public val encodeAtWidth: (width: Int) -> ByteArray,
) {
    init {
        require(name.isNotBlank()) { "an ExactWidthField needs a name a diagnostic can print" }
        require(declaredWidth > 0) {
            "declaredWidth must be positive, was $declaredWidth — a zero-width field has no short side"
        }
    }

    override fun toString(): String = "$name (declared $declaredWidth bytes)"
}

/**
 * A **fixed-width header** followed by an arbitrary payload — the *other* shape a documented width
 * takes on a wire, and deliberately not an [ExactWidthField].
 *
 * The distinction is the whole reason this is a separate declaration rather than a third arm of
 * the exact-width one. For a header, a frame one byte **longer** than the header is a frame with a
 * one-byte payload: it must be **accepted**, and a codec that refused it would be broken. Only the
 * short side is a width violation. Folding the two shapes together would either impose a false
 * obligation on every header or quietly relax the exact-width contract to "short is enough" — and
 * that relaxation is exactly how a fixed-width field stops being checked on its long side.
 *
 * @property name the header, as the codec names it (`ChunkCodec.HEADER_SIZE`).
 * @property headerBytes the header's documented width, in bytes.
 * @property encodeFrameOfSize builds a frame of exactly the given total size whose header region is
 *   well-formed as far as it goes. Sizes below [headerBytes] are truncations of a valid header;
 *   sizes at or above it are a valid header plus that many payload bytes.
 */
public class FixedWidthHeader(
    public val name: String,
    public val headerBytes: Int,
    public val encodeFrameOfSize: (totalBytes: Int) -> ByteArray,
) {
    init {
        require(name.isNotBlank()) { "a FixedWidthHeader needs a name a diagnostic can print" }
        require(headerBytes > 0) { "headerBytes must be positive, was $headerBytes" }
    }

    override fun toString(): String = "$name (header $headerBytes bytes)"
}

/**
 * How a codec says *no* — part of its contract, not an implementation detail.
 *
 * The two are **not** interchangeable, and folding them together is how a suite loses its teeth.
 * On a fabric receive loop an escaping exception is not a rejection at all: it is #1819, where 16
 * bytes from any peer killed the pump and left a `NearbySeam` permanently deaf with no `Torn` to
 * observe. A suite that scored "it threw" as "it refused the frame" would have watched that defect
 * be introduced and stayed green — so each harness declares which shape its codec uses, and the
 * other shape is a failure.
 */
public enum class WireRejectionMode {
    /**
     * Refusal is a `null` return. An escaping exception is a **defect**, not a rejection: this is
     * the mode for a decoder called from a long-lived pump, where a throw ends the pump.
     */
    ReturningNull,

    /**
     * Refusal is a throw. The decoder never returns `null`, and its caller is expected to catch —
     * typically a handshake path that tears the one connection the bad frame arrived on.
     */
    Throwing,
}

/**
 * The contract every wire codec fed by **peer-controlled bytes** must satisfy about the widths its
 * own documentation fixes: *a frame whose fixed-width field is short by one, or long by one, is
 * rejected.*
 *
 * Subclass once per wire type, declare its fields, and point [decode] at the codec's entry point:
 *
 * ```kotlin
 * class NwHelloWireCodecTest : WireCodecConformanceSuite() {
 *     override fun decode(frame: ByteArray): Any? = NwHello.decode(frame)
 *     override fun rejectionMode(): WireRejectionMode = WireRejectionMode.Throwing
 *     override fun exactWidthDeclaration(): ObligationDeclaration = ObligationDeclaration.Proven
 *     override fun exactWidthFields(): List<ExactWidthField> =
 *         listOf(ExactWidthField("nonce", NONCE_BYTES) { w -> helloBodyWithNonceWidth(w) })
 *     // …
 * }
 * ```
 *
 * ## Why a suite and not five `require` lines
 *
 * #1822 found the same defect at three sites — *a nonce whose width is documented by a
 * `NONCE_BYTES` constant and unenforced on decode* — in three modules, with three different
 * serializers. Two were copy-propagation; the **third was an independent re-derivation** by a
 * different hand, which is what separates a recurring class from a duplicated mistake. Per-site
 * `require`s fix the three instances and leave the class generative: the fourth fabric's author
 * has nothing to inherit and re-derives the omission along with the format.
 *
 * A lint rule cannot close it. "A documented width that isn't checked" relates *prose* to a
 * comparison, and the syntactic-shape guards this repo does use (`forbidBareSeamStateFlow`,
 * `forbidBareLaunchIn`) work precisely because their targets are call shapes. A TCK can, and it
 * rides a habit contributors already have here — a new fabric subclasses a conformance suite.
 *
 * ## Why rejection, never reshaping
 *
 * Every field this suite covers is an **identity or a MAC input**, not a quantity. A quantity can
 * be clamped into range; an identity cannot. Truncating or padding a wrong-width nonce to its
 * declared width launders the proof of a malformed or forged frame into a valid-looking value —
 * the forger simply receives whichever in-range value the reshaping picks. So the property is
 * *rejected*, and a codec that silently reshapes fails it. (`ObligationDeclaration.NotApplicable`
 * has no arm for "we reshape on purpose" because reshaping an identity is not a design choice this
 * contract recognises; a codec that does it declares `ContractDiffers` and has to demonstrate it.)
 *
 * ## The knobs, and what each of them switches off
 *
 * A fixture's configuration is a prescription too, and it drifts toward the setting where the
 * property cannot fail. The three this suite has:
 *
 * - **The declared width comes from the codec's own constant, never a literal.** A harness that
 *   writes `16` rather than `NONCE_BYTES` keeps passing after the constant moves, testing a width
 *   the codec no longer has.
 * - **`0` is tested alongside `w ± 1`.** The boundary is the strongest single case, but zero is
 *   the width that actually bit at two of the three sites — an empty nonce hex-encodes to the
 *   empty string, so two distinct peers derive one link identity, and it collapses a MAC input to
 *   `HMAC(code, "")`. It is only the same case as `w - 1` when `w` is 1, and the suite dedupes.
 * - **[everyExactWidthFieldIsAcceptedAtItsDeclaredWidth] is a precondition, not a bonus.** Without
 *   it, "rejects short and long" is satisfied by a codec that rejects *everything* — including one
 *   whose rig builds garbage at every width. It is the assertion that proves the rig fired.
 *
 * ## What this suite cannot detect
 *
 * - **Why a frame was refused.** It sees the *shape* of a refusal ([WireRejectionMode]) but not its
 *   reason, so a rig whose mutation also breaks the parse is indistinguishable from one that
 *   isolates the width. [ExactWidthField] says how to avoid writing that rig; the per-site revert
 *   evidence is what confirms nobody did.
 * - **A field it was never told about.** The declaration hooks are the reason an empty list cannot
 *   pass silently, but nothing forces a harness to declare its *second* field. Adding a fixed-width
 *   field to a wire type means adding it here, in the same PR, exactly as adding a module means
 *   adding its row to `CLAUDE.md`.
 * - **Value ranges.** A `chunkCount` bound per-message and checked per-chunk (#1819) is a
 *   constraint on a field's *value*, not its width, and no arm of this suite reaches it. That is
 *   why `ChunkCodecWireCodecTest` declares `NotConstructible` on the exact-width obligation rather
 *   than dressing a value constraint up as a width one.
 */
public abstract class WireCodecConformanceSuite {

    /**
     * Decode one frame the way the codec's own consumer does, returning the decoded value.
     *
     * Returning a non-`null` value is **acceptance**. Refusal must take the shape [rejectionMode]
     * declares, and the other shape is a failure rather than a pass — see [WireRejectionMode].
     *
     * Point this at the codec entry point a *remote's* bytes actually reach — the decoder, or the
     * serializer that invokes the type's constructor — never at a convenience wrapper that
     * pre-validates. The invariant under test is the one a hostile frame meets.
     */
    protected abstract fun decode(frame: ByteArray): Any?

    /** How [decode] signals refusal. The other shape fails; see [WireRejectionMode]. */
    protected abstract fun rejectionMode(): WireRejectionMode

    /** The fixed-width fields this codec documents. Empty only under a non-[ObligationDeclaration.Proven] declaration. */
    protected abstract fun exactWidthFields(): List<ExactWidthField>

    /**
     * What this harness claims about the exact-width obligation.
     *
     * [ObligationDeclaration.Proven] requires [exactWidthFields] to be non-empty — a `Proven` over
     * an empty list is the silent skip this vocabulary exists to prevent, and it is the one this
     * suite is most exposed to, since every property below iterates a list and a green over zero
     * elements looks identical to a green over three.
     *
     * The two [ObligationDeclaration.NotApplicable] arms are cross-checked the same way
     * `SeamConformanceSuite` cross-checks its own: `NotConstructible` must hand back an empty list
     * (a harness that *can* declare a field has just shown the field is constructible), and
     * `ContractDiffers` must hand back a non-empty one **and be watched deviating** — it is the
     * strong arm precisely because a codec cannot claim it reshapes on purpose without
     * demonstrating that it does.
     */
    protected abstract fun exactWidthDeclaration(): ObligationDeclaration

    /** The fixed-width headers this codec documents. Empty only under a non-`Proven` declaration. */
    protected abstract fun fixedWidthHeaders(): List<FixedWidthHeader>

    /** What this harness claims about the header obligation. Cross-checked like [exactWidthDeclaration]. */
    protected abstract fun fixedWidthHeaderDeclaration(): ObligationDeclaration

    // ── (1) the exact-width contract ─────────────────────────────────────────

    /**
     * The precondition every other exact-width property rests on: the rig can build a frame this
     * codec **accepts**.
     *
     * Without it a codec that refused every frame — or a rig that emitted garbage at every width —
     * would satisfy "rejects short" and "rejects long" perfectly.
     */
    @Test
    public fun everyExactWidthFieldIsAcceptedAtItsDeclaredWidth() {
        if (exactWidthDeclaration() !is ObligationDeclaration.Proven) return
        assertAll(
            *exactWidthFields().map { field ->
                {
                    val outcome = decodeOutcome(field.encodeAtWidth(field.declaredWidth))
                    assertTrue(
                        outcome.wasAccepted(),
                        "$field: the rig's frame at the DECLARED width was ${outcome.describe()}. Every " +
                            "rejection below is vacuous until this passes — fix the rig, not the codec.",
                    )
                }
            }.toTypedArray(),
        )
    }

    /** A frame whose fixed-width field is one byte short is rejected. */
    @Test
    public fun everyExactWidthFieldIsRejectedOneByteShort(): Unit =
        assertRejectedAtEachWrongWidth { listOf(it.declaredWidth - 1) }

    /** A frame whose fixed-width field is one byte long is rejected. */
    @Test
    public fun everyExactWidthFieldIsRejectedOneByteLong(): Unit =
        assertRejectedAtEachWrongWidth { listOf(it.declaredWidth + 1) }

    /**
     * A frame whose fixed-width field is **empty** is rejected.
     *
     * Not a duplicate of one-byte-short except at `declaredWidth == 1` (where the suite dedupes):
     * zero is the width that actually bit. An empty nonce hex-encodes to the empty string, so two
     * distinct misbehaving peers derive the *same* canonical link identity; and it collapses a MAC
     * input to `HMAC(code, "")`, carrying no per-attempt freshness at all.
     */
    @Test
    public fun everyExactWidthFieldIsRejectedAtZeroWidth(): Unit =
        assertRejectedAtEachWrongWidth { if (it.declaredWidth == 1) emptyList() else listOf(0) }

    /**
     * The rig varies the width and nothing else: its frames grow **strictly** with the declared
     * width, so a rig returning one constant frame, or unrelated frames, is caught.
     *
     * Deliberately *not* the stronger linear form (`size(w) == base + w`), true though it is for
     * every in-tree field today. A length-delimited encoding widens its own length header as the
     * field crosses a size boundary — CBOR's byte-string header grows from one byte to two at 24 —
     * so the linear check would false-red a perfectly correct rig for a 24-byte field. Strict
     * monotonicity holds for every encoding. Do not "strengthen" this.
     */
    @Test
    public fun everyExactWidthFieldsRigVariesTheWidthAndNothingElse() {
        if (exactWidthDeclaration() !is ObligationDeclaration.Proven) return
        assertAll(
            *exactWidthFields().map { field ->
                {
                    val widths = (0..field.declaredWidth + 1).toList()
                    val sizes = widths.map { field.encodeAtWidth(it).size }
                    assertTrue(
                        sizes.zipWithNext().all { (smaller, larger) -> smaller < larger },
                        "$field: the rig's frame size must grow strictly with the field width, but " +
                            "widths $widths produced sizes $sizes. A rig that ignores the width it is " +
                            "handed makes every rejection below a rejection of the same frame.",
                    )
                }
            }.toTypedArray(),
        )
    }

    // ── (2) the fixed-width-header contract ──────────────────────────────────

    /** A frame too short to hold the whole header is rejected, at every length below it. */
    @Test
    public fun everyFixedWidthHeaderRejectsAFrameShorterThanTheHeader() {
        if (fixedWidthHeaderDeclaration() !is ObligationDeclaration.Proven) return
        assertAll(
            *fixedWidthHeaders().flatMap { header ->
                (0 until header.headerBytes).map { size ->
                    {
                        val outcome = decodeOutcome(header.encodeFrameOfSize(size))
                        assertTrue(
                            outcome.wasRejectedAsDeclared(),
                            "${header.name}: a $size-byte frame cannot hold the ${header.headerBytes}-byte " +
                                "header, but decoding it produced ${outcome.describe()}. " + wrongShapeHint(),
                        )
                    }
                }
            }.toTypedArray(),
        )
    }

    /**
     * A frame of exactly the header width is accepted, and so is one a byte longer.
     *
     * The long arm is the property that makes a header **not** an [ExactWidthField]: the extra byte
     * is payload, and refusing it would be the bug. Asserting it here is what stops a later reader
     * "completing" the symmetry by demanding a rejection on the long side.
     */
    @Test
    public fun everyFixedWidthHeaderAcceptsAFrameAtOrOneByteOverTheHeader() {
        if (fixedWidthHeaderDeclaration() !is ObligationDeclaration.Proven) return
        assertAll(
            *fixedWidthHeaders().flatMap { header ->
                listOf(header.headerBytes, header.headerBytes + 1).map { size ->
                    {
                        val outcome = decodeOutcome(header.encodeFrameOfSize(size))
                        assertTrue(
                            outcome.wasAccepted(),
                            "$header: a $size-byte frame is a whole header plus " +
                                "${size - header.headerBytes} payload byte(s) and must be accepted, " +
                                "but it was ${outcome.describe()}",
                        )
                    }
                }
            }.toTypedArray(),
        )
    }

    /** The header rig returns frames of exactly the size it was asked for. */
    @Test
    public fun everyFixedWidthHeadersRigReturnsTheSizeItWasAskedFor() {
        if (fixedWidthHeaderDeclaration() !is ObligationDeclaration.Proven) return
        assertAll(
            *fixedWidthHeaders().flatMap { header ->
                (0..header.headerBytes + 1).map { size ->
                    {
                        assertEquals(
                            size,
                            header.encodeFrameOfSize(size).size,
                            "$header: the rig was asked for a $size-byte frame and returned a " +
                                "different size, so the property above is testing a length it did not choose",
                        )
                    }
                }
            }.toTypedArray(),
        )
    }

    // ── (3) the declarations are honest ──────────────────────────────────────

    /**
     * Whichever arm the harness declares for the exact-width obligation, the suite checks it.
     *
     * The cross-check the harness cannot fake is the **list**: a `Proven` over an empty list has
     * run every property above against zero elements and asserted nothing, and a `NotConstructible`
     * over a non-empty one has just demonstrated that the field *is* constructible.
     */
    @Test
    public fun exactWidthDeclarationIsHonest(): Unit =
        assertDeclarationIsHonest(
            declared = exactWidthDeclaration(),
            obligation = "exact-width field",
            hook = "exactWidthFields()",
            declaredCount = exactWidthFields().size,
            deviates = { exactWidthFields().any { field -> wrongWidthsOf(field).any { deviatesAt(field, it) } } },
        )

    /** Whichever arm the harness declares for the header obligation, the suite checks it. */
    @Test
    public fun fixedWidthHeaderDeclarationIsHonest(): Unit =
        assertDeclarationIsHonest(
            declared = fixedWidthHeaderDeclaration(),
            obligation = "fixed-width header",
            hook = "fixedWidthHeaders()",
            declaredCount = fixedWidthHeaders().size,
            deviates = {
                fixedWidthHeaders().any { header ->
                    (0 until header.headerBytes).any {
                        !decodeOutcome(header.encodeFrameOfSize(it)).wasRejectedAsDeclared()
                    }
                }
            },
        )

    /**
     * A harness that declares nothing on either obligation is not a conformance test.
     *
     * `Proven` on both with two empty lists is caught by the declaration checks above; this catches
     * the subtler shape where every obligation is honestly declared away and subclassing the suite
     * has bought a green with no property behind it at all.
     */
    @Test
    public fun theHarnessProvesAtLeastOneObligation(): Unit =
        assertTrue(
            exactWidthDeclaration() is ObligationDeclaration.Proven ||
                fixedWidthHeaderDeclaration() is ObligationDeclaration.Proven,
            "neither obligation is Proven, so this harness subclasses WireCodecConformanceSuite and " +
                "asserts nothing about its codec. Declare the fields this codec does have, or do not " +
                "subclass — a green with no property behind it is worse than an absent test.",
        )

    // ── shared machinery ─────────────────────────────────────────────────────

    private fun assertRejectedAtEachWrongWidth(widths: (ExactWidthField) -> List<Int>) {
        if (exactWidthDeclaration() !is ObligationDeclaration.Proven) return
        assertAll(
            *exactWidthFields().flatMap { field ->
                widths(field).map { width ->
                    {
                        val outcome = decodeOutcome(field.encodeAtWidth(width))
                        assertTrue(
                            outcome.wasRejectedAsDeclared(),
                            "${field.name}: a frame carrying a $width-byte ${field.name} produced " +
                                "${outcome.describe()}. A wrong width is proof of a malformed or forged " +
                                "frame and must be refused, never truncated or padded into range. " +
                                wrongShapeHint(),
                        )
                    }
                }
            }.toTypedArray(),
        )
    }

    private fun assertDeclarationIsHonest(
        declared: ObligationDeclaration,
        obligation: String,
        hook: String,
        declaredCount: Int,
        deviates: () -> Boolean,
    ) {
        when (declared) {
            is ObligationDeclaration.Proven ->
                assertTrue(
                    declaredCount > 0,
                    "Proven claims the $obligation properties ran — but $hook is empty, so every one of " +
                        "them iterated nothing and asserted nothing. Declare the fields, or declare " +
                        "Gap/NotApplicable.",
                )

            is ObligationDeclaration.Gap -> assertAll(
                { assertTrue(declared.trackingUrl.isNotBlank(), GAP_NEEDS_A_URL) },
                {
                    assertEquals(
                        0,
                        declaredCount,
                        "this harness declares $declaredCount $obligation(s), so it has no gap: declare " +
                            "Proven, or ContractDiffers if the codec deviates on purpose",
                    )
                },
            )

            is ObligationDeclaration.NotApplicable.NotConstructible -> assertAll(
                { assertTrue(declared.reason.isNotBlank(), NOT_APPLICABLE_NEEDS_A_REASON) },
                {
                    assertEquals(
                        0,
                        declaredCount,
                        "NotConstructible says no $obligation can be built here, but $hook declares " +
                            "$declaredCount of them — which is a demonstration that it can",
                    )
                },
            )

            is ObligationDeclaration.NotApplicable.ContractDiffers -> assertAll(
                { assertTrue(declared.reason.isNotBlank(), NOT_APPLICABLE_NEEDS_A_REASON) },
                {
                    assertTrue(
                        declaredCount > 0,
                        "ContractDiffers must DEMONSTRATE the deviation on a declared $obligation, and " +
                            "$hook is empty — there is nothing to watch deviate",
                    )
                },
                {
                    assertTrue(
                        deviates(),
                        "ContractDiffers says this codec answers the $obligation differently on purpose, " +
                            "but every declared one behaves exactly as the contract requires. Declare Proven.",
                    )
                },
            )
        }
    }

    private fun wrongWidthsOf(field: ExactWidthField): List<Int> =
        listOfNotNull(0.takeIf { field.declaredWidth > 1 }, field.declaredWidth - 1, field.declaredWidth + 1)

    private fun deviatesAt(field: ExactWidthField, width: Int): Boolean =
        !decodeOutcome(field.encodeAtWidth(width)).wasRejectedAsDeclared()

    private fun decodeOutcome(frame: ByteArray): Result<Any?> = runCatchingCancellable { decode(frame) }

    private fun Result<Any?>.wasAccepted(): Boolean = getOrNull() != null

    /**
     * Refusal in the shape the harness declared — never merely "did not return a value".
     *
     * A codec declared [WireRejectionMode.ReturningNull] that throws has not rejected the frame: on
     * a long-lived pump the throw ends the pump, which is a strictly worse outcome than accepting
     * the frame would have been. Scoring it as a rejection is what would let #1819 be reintroduced
     * under a green suite.
     */
    private fun Result<Any?>.wasRejectedAsDeclared(): Boolean = when (rejectionMode()) {
        WireRejectionMode.ReturningNull -> isSuccess && getOrNull() == null
        WireRejectionMode.Throwing -> isFailure
    }

    /** Why a refusal in the wrong shape is a failure, in the words of the mode that was declared. */
    private fun wrongShapeHint(): String = when (rejectionMode()) {
        WireRejectionMode.ReturningNull ->
            "This harness declares ReturningNull, so an escaping exception is not a rejection: it is " +
                "the shape that kills a receive pump and leaves a seam permanently deaf (#1819)."

        WireRejectionMode.Throwing ->
            "This harness declares Throwing, so a null return is not a rejection this codec's callers " +
                "are written to see."
    }

    private fun Result<Any?>.describe(): String = fold(
        onSuccess = { if (it == null) "a null return" else "ACCEPTED, decoding to $it" },
        onFailure = { "a thrown ${it::class.simpleName}: ${it.message}" },
    )

    private companion object {
        const val GAP_NEEDS_A_URL: String =
            "Gap must name the issue or doc anchor that will close it — a blank tracking URL is a " +
                "shortfall nobody can find"
        const val NOT_APPLICABLE_NEEDS_A_REASON: String =
            "NotApplicable must say why, in prose a reviewer can check against the codec"
    }
}
