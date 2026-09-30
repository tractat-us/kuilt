package us.tractat.kuilt.demo.workspace

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins Scenarios.all to the step lists in HYPOTHESES.md, including the picks their comments predict. */
class ScenariosTest {
    private val alex = ActorId("alex")
    private val sam = ActorId("sam")
    private val remote = ActorId("remote")

    private fun scenario(name: String) = Scenarios.all.first { it.name == name }

    /** What the scripted agent sees at each StartAgent: every input created before it, in order. */
    private fun picksAtStart(s: Scenario): Map<String, Recommendation> {
        val seen = mutableListOf<WorkspaceEntry>()
        val picks = mutableMapOf<String, Recommendation>()
        for (step in s.steps) {
            when (step) {
                is Step.Edit -> seen += step.entry
                is Step.ToolResult -> seen += step.entry
                is Step.StartAgent -> picks[step.request] = ScriptedAgent.recommend(seen.toList())
                else -> Unit
            }
        }
        return picks
    }

    @Test
    fun scenariosAreS1ToS6() =
        assertEquals(listOf("S1", "S2", "S3", "S4", "S5", "S6"), Scenarios.all.map { it.name })

    @Test
    fun actorsMatchThePreRegistration() = assertAll(
        { assertEquals(listOf(alex, remote), scenario("S6").actors) },
        { listOf("S1", "S2", "S3", "S4", "S5").forEach { assertEquals(listOf(alex, sam, remote), scenario(it).actors) } },
    )

    @Test
    fun agentPicksMatchTheStepComments() = assertAll(
        { assertEquals(mapOf("r1" to Recommendation(VenueId("v1"))), picksAtStart(scenario("S2"))) },
        { assertEquals(mapOf("r1" to Recommendation(VenueId("v1"))), picksAtStart(scenario("S3"))) },
        { assertEquals(mapOf("r1" to Recommendation(VenueId("v1")), "r2" to Recommendation(VenueId("v2"))), picksAtStart(scenario("S4"))) },
        { assertEquals(mapOf("r1" to Recommendation(VenueId("v2"))), picksAtStart(scenario("S5"))) },
        { assertEquals(mapOf("r1" to Recommendation(VenueId("v1"))), picksAtStart(scenario("S6"))) },
    )

    @Test
    fun s4HasTheTwoOfflineAccepts() = assertEquals(
        listOf(Step.Accept(alex, "r2"), Step.Accept(sam, "r1")),
        scenario("S4").steps.filterIsInstance<Step.Accept>(),
    )

    @Test
    fun remoteHostsEveryAgent() = assertEquals(
        setOf(remote),
        Scenarios.all.flatMap { it.steps }.filterIsInstance<Step.StartAgent>().map { it.host }.toSet(),
    )
}
