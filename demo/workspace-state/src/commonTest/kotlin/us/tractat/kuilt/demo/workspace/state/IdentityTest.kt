package us.tractat.kuilt.demo.workspace.state

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which inputs carry an identity — a [us.tractat.kuilt.crdt.Dot] reachable through
 * `Quilted.causalDots()` — in each model. The #2869 ruling is that identities come from
 * dot-carrying types only, so this is the first thing the probe shows rather than assumes.
 */
class IdentityTest {
    private val a = ReplicaId("a")

    private fun <S> dotsCover(m: DinnerModel<S>) {
        val (s1, handle) = m.addVenue(m.empty(a), a, "Uno", 40)
        val s2 = m.setBudget(s1, a, 30)
        assertAll(
            { assertTrue(handle.dot in m.causalDots(s2), "${m.name}: venue has an identity") },
            { assertTrue(m.causalDots(s2).size >= 2, "${m.name}: budget edit has an identity") },
        )
    }

    @Test fun typedModelGivesEveryInputADot() = dotsCover(TypedModel)

    /** The expected finding: a `JsonNode.Leaf` write mints no dot the document exposes (Review Focus 4). */
    @Test fun jsonModelBudgetLeafHasNoDot() {
        val (s1, _) = JsonModel.addVenue(JsonModel.empty(a), a, "Uno", 40)
        val before = JsonModel.causalDots(s1)
        val after = JsonModel.causalDots(JsonModel.setBudget(s1, a, 30))
        assertEquals(before, after, "Leaf budget contributes no dot (Review Focus 4)")
    }
}
