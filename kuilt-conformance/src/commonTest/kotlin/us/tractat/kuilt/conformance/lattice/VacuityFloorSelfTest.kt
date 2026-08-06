package us.tractat.kuilt.conformance.lattice

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.test.assertAll
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
