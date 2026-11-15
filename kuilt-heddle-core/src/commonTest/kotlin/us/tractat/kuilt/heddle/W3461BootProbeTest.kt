package us.tractat.kuilt.heddle

import kotlin.test.Test

/** Probe for #3461: what a boot over a weight set the budget refuses leaves, per written order. Not a test. */
class W3461BootProbeTest {

    private val policy = LocalPolicy(defaultReservation = 1L)
    private val heavy = Weight.of(185_000_000_000_000_000L)
    private val fine = Weight.of(1, 30_720)

    private val cases: List<Pair<String, Topology>> = listOf(
        "1 a,fine" to draft("p") { lane("a"); lane("fine", fine) },
        "2 fine,a" to draft("p") { lane("fine", fine); lane("a") },
        "3 z=heavy,a=1/3" to draft("p") { lane("z", heavy); lane("a", Weight.of(1, 3)) },
        "4 a=1/3,z=heavy" to draft("p") { lane("a", Weight.of(1, 3)); lane("z", heavy) },
        "5 a=heavy,z=1/3" to draft("p") { lane("a", heavy); lane("z", Weight.of(1, 3)) },
        "6 z=1/3,a=heavy" to draft("p") { lane("z", Weight.of(1, 3)); lane("a", heavy) },
        "7 group g=fine{x,y},a" to draft("p") { group("g", fine) { lane("x"); lane("y") }; lane("a") },
        "8 group a=1/3{x},z=heavy" to draft("p") { group("a", Weight.of(1, 3)) { lane("x") }; lane("z", heavy) },
        "9 nested q{a,fine},b" to draft("p") { group("q") { lane("a"); lane("fine", fine) }; lane("b") },
    )

    private fun groups(t: Topology): List<GroupId> {
        val out = ArrayList<GroupId>()
        fun walk(prefix: List<String>, nodes: List<Topology.Node>) {
            for (n in nodes) {
                val path = prefix + n.name
                out += t.groupId(*path.toTypedArray())
                if (n is Topology.Group) walk(path, n.children)
            }
        }
        walk(emptyList(), t.children)
        return out
    }

    @Test
    fun probe() {
        for ((name, t) in cases) {
            val threw = try {
                LocalHeddle.fromTopology(t, policy, epoch = 0L); "no"
            } catch (e: IllegalStateException) {
                "ISE"
            }
            val engine = LocalHeddle.over(EntitlementLedger.ZERO, t.groupId(), policy, 0L, Leasable.ByTopology(t))
            val result = engine.apply(t, generation = 0L)
            val ledger = engine.snapshot().ledger
            val states = groups(t).joinToString(" ") { g ->
                val edges = ledger.inboundEdges(g).map { ledger.lifecycle(it) }
                val leased = engine.admit(g, startedAt = 0L) is AcquireOutcome.Acquired
                "${g.value}=$edges${if (leased) "+lease" else ""}"
            }
            val refused = result.refusals.joinToString { "${it.child.value}:${(it as? StepRefusal.Refused)?.step?.let { s -> s::class.simpleName }}" }
            println("W3461 [$name] fromTopology=$threw refused=[$refused] groups: $states")
        }
    }
}
