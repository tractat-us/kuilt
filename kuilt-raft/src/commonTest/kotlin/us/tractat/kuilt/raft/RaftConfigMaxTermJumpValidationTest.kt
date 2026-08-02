package us.tractat.kuilt.raft

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Regression for #1972: [RaftConfig] refuses a [RaftConfig.maxTermJump] outside `1..2^20`.
 *
 * `maxTermJump` is the bound #1897 introduced to stop one hostile frame from wedging a cluster, and
 * before this it was a knob whose safe range lived nowhere executable. Two settings quietly undid the
 * fix the knob belongs to:
 *
 * - **`Long.MAX_VALUE`** (or anything near `2^60`) makes `onMessage`'s
 *   `wireTerm - currentTerm > maxTermJump` vacuous, so a single frame carrying an arbitrary term is
 *   adopted again and #1833's cluster-wide wedge returns in full.
 * - **Negative** makes it true for every frame at or above our own term, so the node drops all of them
 *   and goes silently deaf — nothing logs above `debug`.
 *
 * Neither is exotic misuse; they are the two ends of the same dial on a `public data class` a consumer
 * is expected to construct. The engine's invariant should not be expressible-away from configuration.
 *
 * ### What each edge pins
 *
 * - **Floor at 1, not 0.** At `0` a jump of exactly one is refused (`1 > 0`), so no candidate's
 *   `currentTerm + 1` is ever admitted and the cluster can never elect again — a liveness break, not a
 *   tight bound. Below `0` the node refuses everything at or above its term. `1` is the smallest value
 *   that keeps elections possible, so it must be *admitted*, not merely non-negative.
 * - **Ceiling at 2^20 — a chosen line, not a cliff.** The bound is the attacker's step size, so
 *   climbing to `RaftEngine.MAX_PLAUSIBLE_TERM` (`2^60`) costs `2^60 / maxTermJump` accepted frames:
 *   `2^40` ≈ 1.1×10^12 at this value. That price degrades *continuously* above it, so
 *   [aMaxTermJumpAboveTheCeilingIsRefused] asserting `2^20 + 1` is not a claim that `2^20 + 1` is
 *   dangerous — it is a claim that the line is enforced where it was drawn. A ceiling whose successor
 *   is admitted is decoration.
 *
 * Both edges are asserted from *both* sides — the admitted value and its neighbour — because a bound
 * tested on one side only is satisfied by a bound in the wrong place.
 *
 * Failing at construction is right for this knob and wrong for a frame: this is local, deterministic,
 * consumer-supplied configuration, so the #1818 "never throw on a path a peer controls" rule (which is
 * why `onMessage` drops rather than `require`s) does not reach it.
 */
internal class RaftConfigMaxTermJumpValidationTest {

    /**
     * Mirrors the ceiling in [RaftConfig]'s `init`. Written as a literal rather than read off the
     * config so a silent change to the bound reddens here instead of quietly rewriting what these
     * tests assert — the same reason `TermJumpBoundTest` spells out the default by hand.
     */
    private val ceiling = 1L shl 20

    /** The shipped default, likewise a literal: it must survive its own validation. */
    private val shippedDefault = 10_000L

    @Test
    fun aMaxTermJumpBelowTheFloorIsRefused() = assertAll(
        { assertRefused(0L) },
        { assertRefused(-1L) },
        { assertRefused(Long.MIN_VALUE) },
    )

    @Test
    fun aMaxTermJumpAboveTheCeilingIsRefused() = assertAll(
        { assertRefused(ceiling + 1L) },
        { assertRefused(Long.MAX_VALUE) },
    )

    @Test
    fun bothEdgesAndTheShippedDefaultAreAdmitted() = assertAll(
        { assertEquals(1L, RaftConfig(maxTermJump = 1L).maxTermJump, "the floor itself must be usable") },
        { assertEquals(ceiling, RaftConfig(maxTermJump = ceiling).maxTermJump, "the ceiling is inclusive") },
        { assertEquals(shippedDefault, RaftConfig(maxTermJump = shippedDefault).maxTermJump) },
        { assertEquals(shippedDefault, RaftConfig().maxTermJump, "the no-argument default must be admitted") },
    )

    /**
     * The offending value and the permitted range both appear in the failure. A consumer sees this
     * exception with no other context — a bare "requirement failed" would send them to the source.
     */
    @Test
    fun theRefusalNamesTheOffendingValueAndThePermittedRange() {
        val message = assertFailsWith<IllegalArgumentException> {
            RaftConfig(maxTermJump = Long.MAX_VALUE)
        }.message.orEmpty()

        assertAll(
            { assertContains(message, "${Long.MAX_VALUE}", message = "names the offending value: $message") },
            { assertContains(message, "1..$ceiling", message = "names the permitted range: $message") },
        )
    }

    /**
     * [RaftConfig] is a `data class`, so `copy` is the likeliest route a consumer takes to a bad value —
     * and it runs the same `init`. Pinned so the check can never be relocated to a factory that `copy`
     * bypasses.
     */
    @Test
    fun copyIsValidatedToo() {
        assertFailsWith<IllegalArgumentException> { RaftConfig().copy(maxTermJump = 0L) }
    }

    private fun assertRefused(maxTermJump: Long) {
        assertFailsWith<IllegalArgumentException>(
            "maxTermJump=$maxTermJump is outside 1..$ceiling and must be refused at construction",
        ) {
            RaftConfig(maxTermJump = maxTermJump)
        }
    }
}
