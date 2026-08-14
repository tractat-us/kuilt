package us.tractat.kuilt.conformance.lattice

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import us.tractat.kuilt.crdt.Quilted
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A test of the **harness**, not of a type — sibling to [VacuityFloorSelfTest], and the standing
 * receipt for [LatticeLawHarness.runCodecLaws].
 *
 * The codec pass makes six assertions: three laws and three rig receipts. A green run over the
 * nineteen live bindings proves none of them fires today, which is what one would expect and says
 * nothing about whether any of them *can*. This file rigs each one from a deliberately broken codec
 * — or a deliberately broken generator — over a single synthetic lattice, and asserts that the arm
 * it targets is the arm that raises, **by the words in the failure**. A rig that reds the pass is
 * half a receipt; a rig that reds the arm it was built for is the whole one.
 *
 * **One type for every arm, so the arms are the only variable.** [TaggedCell] is a last-writer-wins
 * cell with four fields and one deliberate property: its `equals` compares the **value only**. That
 * is not a contrivance to make an arm fire — it is the ordinary shape of a CRDT's equality, which
 * compares what an application observes and ignores the causal bookkeeping underneath, and it is
 * exactly the shape that makes arms 2 and 3 more than restatements of arm 1.
 *
 * **Its join has to *combine*, not merely select, and that is why it has a fourth field.** A join
 * that returns one of its two operands cannot distinguish `a ⊔ b` from `a ⊔ decode(encode(b))` in
 * bytes alone: the result is either `a`, or a state the codec has already been shown (by arm 2) to
 * re-encode identically. So over a purely selecting join the byte half of arm 3 is unreachable, and
 * an earlier draft of this file left it unproven — a mutation deleting it reddened **nothing**.
 * [TaggedCell.derived] accumulates the other operand's [TaggedCell.mark] on every join, which is the
 * cheapest honest way to make the join read something rather than pick something, and
 * [aCodecThatNeverCarriesTheMarkRedsTheJoinCanonicalityArm] is the rig that then reaches it.
 */
internal class CodecLawSelfTest {

    /** The same window [LatticeLawSuite.decodedStateJoinsIdenticallyToTheOriginal] uses. */
    private val seeds = 0L..15L

    /**
     * Control: the faithful codec clears all three laws and all three receipts.
     *
     * Without it the rigs below prove nothing about *discrimination* — an arm that reds on every
     * codec is not a detector, it is an arm that cannot pass.
     */
    @Test
    fun theFaithfulCodecClearsEveryArm() {
        val report = harness(FAITHFUL).runCodecLawsSeeds(seeds)
        println("control — TaggedCell under a faithful codec, seeds $seeds\n$report")
    }

    /**
     * **Arm 1.** A codec that drops the field `equals` reads is the plainest lossy serializer there
     * is, and the round-trip arm is what sees it.
     *
     * This is the arm the issue was filed for, and the one every existing law in the suite is blind
     * to: run the same rig through `associativeJoinLawsHoldOverLowerSeeds` and it stays green, since
     * both sides of every comparison there are encoded by the same lossy path.
     */
    @Test
    fun aCodecThatDropsTheValueRedsTheRoundTripArm() {
        assertRaises(
            codec = dropping { it.copy(v = "") },
            expected = "Codec round-trip failure",
            notAlso = listOf("Codec stability failure", "Codec join failure"),
        )
    }

    /**
     * **Arm 2.** A codec that zeroes a field `equals` ignores round-trips to something that compares
     * equal and does not re-encode to the same bytes.
     *
     * Arm 1 is structurally blind to this, and so is every other byte law in the harness — they all
     * compare two *built* states, never a built state against a parsed one. It is the arm that sees
     * an encoding depending on how the object was constructed, which is what makes a receiver's
     * `stateRoot` differ from the sender's while the two agree on the value.
     */
    @Test
    fun aCodecThatZeroesAFieldEqualityIgnoresRedsTheStabilityArm() {
        assertRaises(
            codec = CellCodec(
                onWrite = { Wire(it.value, it.tag, it.mark, it.derived) },
                onRead = { TaggedCell(it.v, it.t, 0, it.d) },
            ),
            expected = "Codec stability failure",
            notAlso = listOf("Codec round-trip failure", "Codec join failure"),
        )
    }

    /**
     * **Arm 3.** A codec that drops the tag the join reads, and that `equals` and `encode` are both
     * already blind to, passes arms 1 and 2 and lands every later join on the wrong operand.
     *
     * The tag is dropped on the **write** side, so the encoded form carries no trace of it: arm 1 is
     * blind because `equals` compares values, arm 2 is blind because the second encode drops it
     * again. Only joining a decoded operand against a live one exposes it — which is the join every
     * `Quilter` delta performs and the only join this harness did not.
     */
    @Test
    fun aCodecThatDropsTheJoinTagRedsTheJoinArm() {
        assertRaises(
            codec = dropping { it.copy(t = 0L) },
            expected = "Codec join failure",
            notAlso = listOf("Codec round-trip failure", "Codec stability failure"),
        )
    }

    /**
     * **Arm 3, byte half.** A codec that carries the mark in *neither* direction round-trips to an
     * equal state, re-encodes to identical bytes, and still makes every later join encode
     * differently — because the join reads the mark and writes what it read into a field the
     * encoding does carry.
     *
     * This is the one arm a *selecting* join puts out of reach, and the reason [TaggedCell.piece]
     * combines. The failure it produces is the harness's own distinct wording — *EQUAL but encode to
     * DIFFERENT bytes* — because the two states a receiver and a sender hold here agree on
     * everything an application can observe and disagree on their digest, which is precisely what
     * #1955's root-hash gate compares.
     */
    @Test
    fun aCodecThatNeverCarriesTheMarkRedsTheJoinCanonicalityArm() {
        assertRaises(
            codec = CellCodec(
                onWrite = { Wire(it.value, it.tag, 0, it.derived) },
                onRead = { TaggedCell(it.v, it.t, 0, it.d) },
            ),
            expected = "Codec join canonicality failure",
            notAlso = listOf("Codec round-trip failure", "Codec stability failure", "Codec join failure"),
        )
    }

    /**
     * **Receipt 1.** A generator that never changes the state leaves a pool of one distinct value,
     * on which all three arms are green for any codec whatsoever.
     *
     * The guard has to exist because arm 1 cannot notice it: a round-trip of one value against
     * itself passes on a serializer that encodes nothing at all, and the pass would report a
     * confident 228 states searched.
     */
    @Test
    fun aGeneratorThatChangesNothingRedsTheThinnessReceipt() {
        val failure = assertFailsWith<IllegalStateException> {
            harness(FAITHFUL, op = { state, _, _ -> state }).runCodecLawsSeeds(seeds)
        }
        assertNames(failure.message.orEmpty(), "Codec-law pool is vacuous", "distinct value")
    }

    /**
     * **Receipt 2.** A codec that emits one constant encoding over every state has nothing for a
     * decode to get wrong, and reds before any arm runs.
     *
     * It is the lossy-codec defect in its most total form, and the ordering is the point: the reader
     * is told the *pool* proved nothing rather than being handed a round-trip failure on one
     * arbitrary state and left to work out that all 228 were equally broken.
     */
    @Test
    fun aConstantCodecRedsTheEncodingReceipt() {
        val failure = assertFailsWith<IllegalStateException> {
            harness(dropping { Wire("", 0L, 0, 0) }).runCodecLawsSeeds(seeds)
        }
        assertNames(failure.message.orEmpty(), "distinct encoding", "distinct states produced only")
    }

    /**
     * **Receipt 3.** A generator whose tag never advances makes every join select its left operand,
     * so no join ever reads the decoded state and arm 3 holds for any codec.
     *
     * Unreachable through the codec — for a real join, `a ⊔ b == a` over *every* ordered pair forces
     * every state equal, which receipt 1 catches first. It takes a broken **generator** to reach,
     * which is what this rig is: a `set` that mints a constant tag, so [TaggedCell.piece] keeps
     * `this` forever. That is a live hazard rather than an invented one — a generator minting one
     * tag for two writes is exactly the defect
     * [LatticeLawHarness.runOtherJoinLaws] documents in `LWWRegisterConvergenceTest`.
     */
    @Test
    fun aGeneratorWhoseTagNeverAdvancesRedsTheAbsorptionReceipt() {
        val failure = assertFailsWith<IllegalStateException> {
            harness(FAITHFUL, op = { _, _, random -> TaggedCell("v-${random.nextInt(0, 3)}", 0L, 0, 0) })
                .runCodecLawsSeeds(seeds)
        }
        assertNames(failure.message.orEmpty(), "Codec join arm is vacuous", "read anything out of the")
    }

    private fun assertRaises(codec: KSerializer<TaggedCell>, expected: String, notAlso: List<String>) {
        val failure = assertFailsWith<IllegalStateException> { harness(codec).runCodecLawsSeeds(seeds) }
        val message = failure.message.orEmpty()
        assertTrue(expected in message, "the rig must red '$expected', got: $message")
        for (other in notAlso) {
            assertTrue(
                other !in message,
                "'$expected' must be the arm that raised, not '$other' — an arm that reds on every " +
                    "rig is not a detector: $message",
            )
        }
    }

    private fun assertNames(message: String, vararg fragments: String) {
        for (fragment in fragments) {
            assertTrue(fragment in message, "the receipt must say '$fragment', got: $message")
        }
    }

    /**
     * A binding over [TaggedCell] with [codec] as its serializer and [op] as its whole alphabet.
     *
     * The tag advances by one per step and the value roams over three, so the pool is a chain
     * carrying distinct values — the minimum a codec pass needs, and the maximum a self-test should
     * spend to get it.
     */
    private fun harness(
        codec: KSerializer<TaggedCell>,
        op: (TaggedCell, Int, kotlin.random.Random) -> TaggedCell = { state, _, random ->
            val next = state.tag + 1
            TaggedCell("v-${random.nextInt(0, 3)}", next, next.toInt(), state.derived)
        },
    ): LatticeLawHarness<TaggedCell> = LatticeLawHarness(
        initial = TaggedCell("", 0L, 0, 0),
        alphabet = listOf(LatticeOp("set", OpKind.ASSERT, op)),
        serializer = codec,
        floors = VacuityFloors.NOTHING_TO_RETIRE,
        replicaCount = 3,
        opsPerReplica = 8,
    )

    private companion object {
        /** Writes and reads all four fields — the control. */
        val FAITHFUL = CellCodec(
            onWrite = { Wire(it.value, it.tag, it.mark, it.derived) },
            onRead = { TaggedCell(it.v, it.t, it.m, it.d) },
        )

        /** A codec faithful on the read side, whose write side is bent by [bend]. */
        fun dropping(bend: (Wire) -> Wire) = CellCodec(
            onWrite = { bend(Wire(it.value, it.tag, it.mark, it.derived)) },
            onRead = { TaggedCell(it.v, it.t, it.m, it.d) },
        )
    }
}

/**
 * A last-writer-wins cell whose `equals` compares the **value only**.
 *
 * Four fields, each reached by a different arm of the codec pass: [value] is what equality sees,
 * [tag] is what the join *selects* on and equality does not, [mark] is what the join *reads* and
 * neither equality nor a reader ever sees directly, and [derived] is where the join writes what it
 * read, so the encoding carries it. A codec can drop exactly one of them and be caught by exactly
 * one arm — which is what makes this a self-test rather than four restatements of the same rig.
 *
 * The coarse `equals` is the ordinary shape, not the rigged one: a CRDT's equality compares what an
 * application observes and ignores the causal bookkeeping underneath. It is *because* real types are
 * built this way that a lossy codec can pass a round-trip.
 *
 * @property mark the operand contribution [piece] folds into the winner's [derived]. Never consulted
 *   by `equals`, so a codec may drop it and still round-trip.
 * @property derived the running fold of every operand's [mark] this state has absorbed. What makes
 *   the join *combine* rather than *select*, and the only reason the byte half of arm 3 is
 *   reachable at all.
 */
internal class TaggedCell(val value: String, val tag: Long, val mark: Int, val derived: Int) :
    Quilted<TaggedCell> {
    override fun piece(other: TaggedCell): TaggedCell {
        val winner = if (other.tag > tag) other else this
        return TaggedCell(winner.value, winner.tag, winner.mark, derived + other.mark)
    }

    override fun equals(other: Any?): Boolean = other is TaggedCell && other.value == value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "TaggedCell(value=$value, tag=$tag, mark=$mark, derived=$derived)"
}

/** The wire form of a [TaggedCell] — a surrogate, so each rig is one lambda rather than one codec. */
@Serializable
internal class Wire(val v: String, val t: Long, val m: Int, val d: Int) {
    fun copy(v: String = this.v, t: Long = this.t, m: Int = this.m, d: Int = this.d): Wire = Wire(v, t, m, d)
}

/** A [TaggedCell] codec assembled from a write-side and a read-side transform over [Wire]. */
internal class CellCodec(
    private val onWrite: (TaggedCell) -> Wire,
    private val onRead: (Wire) -> TaggedCell,
) : KSerializer<TaggedCell> {
    private val delegate = Wire.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: TaggedCell): Unit = delegate.serialize(encoder, onWrite(value))
    override fun deserialize(decoder: Decoder): TaggedCell = onRead(delegate.deserialize(decoder))
}
