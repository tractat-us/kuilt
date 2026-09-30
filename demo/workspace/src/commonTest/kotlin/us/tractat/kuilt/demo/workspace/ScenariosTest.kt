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
    fun scenariosAreS1ToS7() =
        assertEquals(listOf("S1", "S2", "S3", "S4", "S5", "S6", "S7"), Scenarios.all.map { it.name })

    @Test
    fun actorsMatchThePreRegistration() =
        Scenarios.all.forEach { assertEquals(listOf(alex, sam, remote), it.actors, it.name) }

    /** Ruling Q3: S6 is S2 with no partition, so the only difference between them is the outage. */
    @Test
    fun s6IsS2WithoutThePartition() = assertEquals(
        scenario("S2").steps.filterNot { it is Step.Partition || it is Step.Reconnect },
        scenario("S6").steps,
    )

    /** Ruling Q1: S7 is a harmless note on the recommended venue while r1 is out. */
    @Test
    fun s7IsAHarmlessNoteWhileR1IsOut() = assertEquals(
        listOf(
            Step.StartAgent("r1", host = remote),
            Step.Edit(InputKey("noteV1"), sam, WorkspaceEntry.Note(sam, VenueId("v1"), "Book the window table")),
            Step.ReleaseAgent("r1"),
        ),
        scenario("S7").steps,
    )

    @Test
    fun agentPicksMatchTheStepComments() = assertAll(
        { assertEquals(mapOf("r1" to Recommendation(VenueId("v1"))), picksAtStart(scenario("S2"))) },
        { assertEquals(mapOf("r1" to Recommendation(VenueId("v1"))), picksAtStart(scenario("S3"))) },
        { assertEquals(mapOf("r1" to Recommendation(VenueId("v1")), "r2" to Recommendation(VenueId("v2"))), picksAtStart(scenario("S4"))) },
        { assertEquals(mapOf("r1" to Recommendation(VenueId("v2"))), picksAtStart(scenario("S5"))) },
        { assertEquals(mapOf("r1" to Recommendation(VenueId("v1"))), picksAtStart(scenario("S6"))) },
        { assertEquals(mapOf("r1" to Recommendation(VenueId("v1"))), picksAtStart(scenario("S7"))) },
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
