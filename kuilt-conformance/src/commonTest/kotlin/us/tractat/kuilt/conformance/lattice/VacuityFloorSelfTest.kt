package us.tractat.kuilt.conformance.lattice

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A test of the **harness**, not of a type — the only file in this package that is not a binding.
 *
 * [VacuityFloors] asserts four rates, and three of them are ones a reviewer would reach for
 * unprompted: ancestry, concurrency, and join-non-triviality are all computable from a generic
 * [us.tractat.kuilt.crdt.Quilted] with no per-binding declaration. The fourth — the effective
 * retirement rate — costs every binding an [OpKind] annotation on every op, and is the only one that
 * cannot be computed at all (see [OpKind]). The entire case for paying that price is a single claim:
 * **a generator that has stopped searching still clears the other three.**
 *
 * That claim was a number in [OpKind]'s KDoc, measured once by a throwaway probe and deleted. This
 * file makes it a standing assertion. Two arms over the same type, the same harness, and the same
 * seeds, differing only in whether the generator can retire:
 *
 * - **control** — [ORMapConvergenceTest]'s harness, taken live rather than copied, so the arm cannot
 *   drift away from the binding it is supposed to represent. Clears all four floors.
 * - **vacuous** — that harness with every [OpKind.RETIRE] op deleted from the alphabet. Breaches the
 *   retirement floor and **clears the other three**.
 *
 * The asymmetry is the whole point. If the vacuous arm ever starts breaching one of the other three
 * as well, the argument for the retirement floor existing weakens — the cheap computable floors
 * would be catching the vacuity on their own — and this test says so directly rather than leaving a
 * reader to re-derive it. Do not "fix" such a failure by relaxing the assertion.
 *
 * **What this does not do is reproduce the numbers in [OpKind]'s table.** That arm was described as
 * "same generator, removes deleted", which leaves the branch structure open — and the branch
 * structure decides where the pool cap truncates, so it decides the triple and step counts. Four
 * spellings were measured while closing this and none reproduces both the published triple count and
 * the published percentages, though every one of them reaches the same conclusion. The conclusion is
 * what is pinned here; the figures are not recoverable from the description and are not chased.
 *
 * @see OpKind for why retirement has to be declared rather than computed.
 * @see VacuityFloors for the exact pair and step definitions the rates are measured over.
 */
internal class VacuityFloorSelfTest {

    /** The same window [LatticeLawSuite.generatorIsNotVacuous] uses, so both arms describe that pool. */
    private val seeds = 0L..15L

    /**
     * The real binding, unmodified — the control arm.
     *
     * Taken from [ORMapConvergenceTest] rather than restated, because a copied alphabet is a copy
     * that silently stops being the binding the moment someone edits one of them. `ORMap` is the
     * type the design's controlled experiment ran on, and it is the one whose broken lattice (#2086)
     * the retirement floor was measured against.
     */
    private fun controlArm(): LatticeLawHarness<ORMap<String, GCounter>> = ORMapConvergenceTest().newHarness()

    /**
     * [controlArm] with every retiring op deleted — the arm that has stopped searching.
     *
     * The critical shape goes with them, and that is not a shortcut: [defaultCriticalShapes] returns
     * nothing for an alphabet with no [OpKind.RETIRE] op, so `assert · retire · re-assert` is
     * unspellable here. Deleting the op really does delete the shape, which is exactly what happens
     * to a binding whose retiring op is dropped in a refactor.
     */
    private fun vacuousArm(): LatticeLawHarness<ORMap<String, GCounter>> {
        val real = controlArm()
        val asserting = real.alphabet.filter { it.kind == OpKind.ASSERT }
        check(asserting.size < real.alphabet.size) {
            "The control binding declares no RETIRE op, so there is nothing to delete and this " +
                "test compares an arm against itself: ${real.alphabet}"
        }
        return LatticeLawHarness(
            initial = real.initial,
            alphabet = asserting,
            serializer = real.serializer,
            floors = real.floors,
            replicaCount = real.replicaCount,
            opsPerReplica = real.opsPerReplica,
        )
    }

    /**
     * Control: the unmodified binding clears every floor.
     *
     * Without this the vacuous arm proves nothing about *discrimination* — a floor that fails on
     * both arms is not a detector, it is a floor set too high.
     */
    @Test
    fun controlArmClearsEveryFloor() {
        val report = controlArm().checkVacuityFloors(seeds)
        println("control — ORMapConvergenceTest unmodified, seeds $seeds\n$report")
    }

    /**
     * The claim: deleting the retiring ops breaches the retirement floor **and nothing else**.
     *
     * Measured directly off [LatticeLawHarness.measureVacuity] rather than through
     * [LatticeLawHarness.checkVacuityFloors], because that one raises on the first floor it finds
     * breached and so could never report that a *later* floor is still healthy. The no-op ceiling is
     * checked last there, so a `checkVacuityFloors` failure naming retirement says nothing at all
     * about no-ops.
     */
    @Test
    fun vacuousArmBreachesTheRetirementFloorAndNoOther() {
        val harness = vacuousArm()
        val floors = harness.floors
        val report = harness.measureVacuity(seeds)
        println("vacuous — ORMapConvergenceTest minus its RETIRE ops, seeds $seeds\n$report")
        assertAll(
            {
                assertEquals(
                    0,
                    report.effectiveRetireSteps,
                    "an alphabet with no RETIRE op cannot take a retiring step",
                )
            },
            {
                assertTrue(
                    report.effectiveRetireRate < floors.effectiveRetireSteps,
                    "the vacuous arm must BREACH the retirement floor: measured " +
                        "${report.effectiveRetireRate}, floor ${floors.effectiveRetireSteps}",
                )
            },
            {
                assertTrue(
                    report.strictAncestorRate >= floors.strictAncestorPairs,
                    "the vacuous arm must CLEAR the ancestry floor — if it no longer does, ancestry " +
                        "is catching this vacuity on its own and the retirement floor's case is weaker: " +
                        "measured ${report.strictAncestorRate}, floor ${floors.strictAncestorPairs}",
                )
            },
            {
                assertTrue(
                    report.concurrentRate >= floors.concurrentPairs,
                    "the vacuous arm must CLEAR the concurrency floor — see the ancestry message: " +
                        "measured ${report.concurrentRate}, floor ${floors.concurrentPairs}",
                )
            },
            {
                assertTrue(
                    report.noOpRate <= floors.maxNoOpSteps,
                    "the vacuous arm must CLEAR the no-op ceiling — see the ancestry message: " +
                        "measured ${report.noOpRate}, ceiling ${floors.maxNoOpSteps}",
                )
            },
        )
    }

    /**
     * **A retire at the lattice bottom is not always inert**, and nothing the harness does may
     * assume it is.
     *
     * #2145's leading asserts exist because a retiring draw on a replica still holding `initial`
     * usually *cannot* be effective — there is nothing yet to retire — and on eight of the twelve
     * retiring bindings every single bottom-state no-op was exactly that. The tempting shortcut is
     * to read that as a law and act on it directly: skip a retiring draw at the bottom, or re-draw
     * until an asserting op comes up. It is not a law. `TwoPhaseSet.remove` and `LWWRegister.unset`
     * both write a **tombstone**, so on an empty state they change it — and both bindings measured
     * **0** bottom-state no-ops for that reason. A harness that suppressed the draw would delete
     * real coverage from precisely these two, and would do it silently, because the rates it
     * reports would only improve.
     *
     * So the fix leads with an assert rather than filtering the draw, and this test pins the premise
     * that makes the distinction matter. The `ORSet` arm is the control: without it the test passes
     * on a world where every retire everywhere is effective, which is the world the shortcut would
     * be safe in.
     *
     * **What the leading assert does cost those two, stated exactly.** The states `⊥ · remove` and
     * `⊥ · unset-high` leave the randomised pool, and three passes build from that pool, so be
     * precise about which still see them:
     *
     * - `runExhaustiveSmall` **does** — it walks every word of length `1..L` from `initial` on
     *   replica 0, so `[remove]`, `[unset-high]` and every continuation within the bound are still
     *   searched exhaustively on every run. That is the bracketing pair (associativity and
     *   canonicality), which is the pass the whole suite is built around.
     * - `runOtherJoinLaws` and `runCodecLaws` **do not** — both build from `causalPool`. The codec
     *   pass calls itself "the one seam every test above skips", so this is a real narrowing and not
     *   a technicality. What still covers the shape there is `:kuilt-crdt`'s cross-target golden
     *   vectors: `CanonicalGoldenVectorTest`'s `TwoPhaseSet` fixture joins in
     *   `TwoPhaseSet.empty().remove("delta")` — a tombstone for an element never added — so a
     *   codec that dropped it fails there. That is byte-level pinning of one constructed state, not
     *   the three codec arms over a pool, and it is what the tree has.
     *
     * Restoring it here would mean naming those words in a critical shape, which on `TwoPhaseSet`
     * is not spellable: its elements are single-shot, so `remove · add · remove` no-ops on its third
     * step and the harness rejects the shape as decoration.
     */
    @Test
    fun aBottomStateRetireIsEffectiveOnTheBindingsThatWriteATombstone() {
        assertAll(
            { assertBottomRetires("TwoPhaseSet", TwoPhaseSetConvergenceTest().newHarness(), effective = true) },
            { assertBottomRetires("LWWRegister", LWWRegisterConvergenceTest().newHarness(), effective = true) },
            { assertBottomRetires("ORSet", ORSetConvergenceTest().newHarness(), effective = false) },
        )
    }

    /**
     * Every [OpKind.RETIRE] op of [harness]'s alphabet, applied to `initial`, either changes the
     * state ([effective] true) or is absorbed by it ([effective] false).
     *
     * A fixed [Random] because a roaming op draws its target from the stream; the claim is about the
     * op's behaviour at the bottom, and one draw is enough to make it.
     */
    private fun <S : us.tractat.kuilt.crdt.Quilted<S>> assertBottomRetires(
        name: String,
        harness: LatticeLawHarness<S>,
        effective: Boolean,
    ) {
        val retires = harness.alphabet.filter { it.kind == OpKind.RETIRE }
        assertTrue(retires.isNotEmpty(), "$name declares no RETIRE op, so this arm asserts nothing")
        for (op in retires) {
            val after = op.apply(harness.initial, 0, Random(0))
            assertEquals(
                effective,
                after != harness.initial,
                "$name's '${op.name}' at the lattice bottom: expected it to " +
                    (if (effective) "CHANGE" else "leave unchanged") +
                    " `initial`. Read the KDoc on this test before touching the number — the two " +
                    "answers are what stops the leading-assert fix from being written as a filter " +
                    "on the draw. initial=${harness.initial}, after=$after",
            )
        }
    }

    /**
     * The floor is not merely breached in the numbers — the harness **enforces** it, and names it.
     *
     * [vacuousArmBreachesTheRetirementFloorAndNoOther] reads the rates and compares them to
     * [VacuityFloors] itself, so it stays green if the check in
     * [LatticeLawHarness.checkVacuityFloors] is deleted outright and the floor becomes decorative.
     * This one fails in that case. The pair is deliberate: one covers a floor lowered to nothing, the
     * other a floor no longer consulted.
     */
    @Test
    fun vacuousArmFailsTheHarnessOnTheRetirementFloorByName() {
        val failure = assertFailsWith<IllegalStateException> { vacuousArm().checkVacuityFloors(seeds) }
        val message = failure.message.orEmpty()
        assertAll(
            {
                assertTrue(
                    "effective RETIRE steps" in message,
                    "the failure must name the retirement floor, so a reader learns *how* the " +
                        "generator stopped searching rather than merely that it did: $message",
                )
            },
            {
                assertTrue(
                    "strict-ancestor pairs" !in message.substringBefore('\n'),
                    "retirement must be the floor that raised, not ancestry: $message",
                )
            },
        )
    }
}
