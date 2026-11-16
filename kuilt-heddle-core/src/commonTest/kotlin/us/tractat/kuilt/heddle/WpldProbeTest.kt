package us.tractat.kuilt.heddle

import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.timesWide
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** TEMPORARY measurement probe (wpld): the old and the corrected load over the driver's run. Deleted before ready. */
class WpldProbeTest {

    private val weights: List<Weight> = listOf(
        Weight.of(1L, 1L shl 14),
        Weight.of(1L, 30_000L),
        Weight.ONE,
        Weight.of(4L),
        Weight.of(1L shl 40),
        Weight.of(1L shl 57),
        Weight.of(1L shl 58),
    )
    private val names: List<String> = listOf("a", "b", "c", "d")

    private fun drive(check: (LocalHeddleState, Int, Int) -> Unit): Int {
        var refusals = 0
        for (seed in 0 until 6) {
            val rng = Random(seed)
            val pick = { list: List<Weight> -> list[rng.nextInt(list.size)] }
            var lanes: List<Pair<String, Weight>> = listOf("a" to weights[0])
            var generation = 0L
            fun shape() = draft("p") { for ((name, w) in lanes.sortedBy { it.first }) lane(name, w) }
            val h = LocalHeddle.bootWhole(shape(), LocalPolicy(defaultReservation = 1L), epoch = 1L)
            h.mintSupply(1L shl 58)
            repeat(400) { step ->
                when (rng.nextInt(10)) {
                    0 -> {
                        val name = names[rng.nextInt(names.size)]
                        lanes = if (lanes.any { it.first == name } && lanes.size > 1 && rng.nextBoolean()) {
                            lanes.filterNot { it.first == name }
                        } else {
                            lanes.filterNot { it.first == name } + (name to pick(weights))
                        }
                        val result = h.apply(shape(), generation = ++generation)
                        refusals += result.refusals.count { "position limit" in (it as? StepRefusal.Refused)?.reason.orEmpty() }
                    }
                    else -> {
                        val leaves = lanes.map { shape().groupId(it.first) }.sortedBy { it.value }.toSet()
                        when (val out = h.acquire(leaves, leaves, startedAt = step.toLong(), reservation = LocalHeddle.MAX_RESERVATION)) {
                            is AcquireOutcome.Acquired -> when (rng.nextInt(4)) {
                                0 -> h.cancel(out.lease.id)
                                1 -> h.complete(out.lease.id, LocalHeddle.MAX_RESERVATION + rng.nextLong(1L, 1L shl 20), waiting = emptySet())
                                else -> h.complete(out.lease.id, LocalHeddle.MAX_RESERVATION, waiting = emptySet())
                            }
                            is AcquireOutcome.LifetimeExhausted -> refusals++
                            else -> Unit
                        }
                    }
                }
                check(h.snapshot(), seed, step)
            }
        }
        return refusals
    }

    private fun storedSum(ledger: EntitlementLedger, parent: GroupId): Int128? = try {
        ledger.activeChildren(parent).map { it.attachment }.sortedBy { it.value }
            .mapNotNull { ledger.gauge(it)?.let { g -> Int128.fromTwosComplement(g.ticksHigh, g.ticksLow) } }
            .fold(Int128.ZERO) { sum, p -> sum + p }
    } catch (_: ArithmeticException) {
        null
    }

    private fun load(state: LocalHeddleState, parent: GroupId, offsets: Boolean = true): Int128? = try {
        val ledger = state.ledger
        ledger.activeChildren(parent).sortedBy { it.attachment.value }.fold(Int128.ZERO) { load, summary ->
            val id = summary.attachment
            val base = ledger.baseIssuance(id)
            val g = ledger.gauge(id)
            val gross = if (g == null) base.timesWide(16L) else Int128.fromTwosComplement(g.ticksHigh, g.ticksLow) + (base - g.folded).timesWide(16L)
            val p = gross - summary.returned.timesWide(16L) + (if (offsets) state.allocatorMemory.wakeOffsets[id] ?: Int128.ZERO else Int128.ZERO)
            load + (if (p < Int128.ZERO) Int128.ZERO - p else p)
        }
    } catch (_: ArithmeticException) {
        null
    }

    private fun bits(v: Int128?): String = if (v == null) "OVF" else v.toString().length.toString() + "d"

    @Test
    fun stats() {
        var differ = 0
        var offsetDiffer = 0
        var steps = 0
        var oldFail = 0
        var newFail = 0
        var maxNew = Int128.ZERO
        var maxOld = Int128.ZERO
        val refusals = drive { s, _, _ ->
            steps++
            val o = storedSum(s.ledger, GroupId("p"))
            val n = load(s, GroupId("p"))
            val nNoOff = load(s, GroupId("p"), offsets = false)
            if (o == null) oldFail++
            if (n == null) newFail++
            if (o != n) differ++
            if (n != nNoOff) offsetDiffer++
            if (n != null && n > maxNew) maxNew = n
            if (o != null && o > maxOld) maxOld = o
        }
        println("WPLD steps=$steps differ=$differ offsetDiffer=$offsetDiffer oldFail=$oldFail newFail=$newFail maxOld=${bits(maxOld)} maxNew=${bits(maxNew)} refusals=$refusals")
    }

    @Test
    fun clampProbe() {
        for (heavyExp in listOf(56, 57, 58)) {
            for (n in listOf(10, 20, 40)) {
                val heavy = Weight.of(1L shl heavyExp)
                val shape = try {
                    draft("p") { lane("a", Weight.of(1L, 1L shl 14)); lane("b", heavy); lane("c", heavy) }
                } catch (e: Throwable) { println("WPLD-CLAMP 2^$heavyExp draft threw $e"); continue }
                val h = try {
                    LocalHeddle.bootWhole(shape, LocalPolicy(defaultReservation = 1L), epoch = 1L)
                } catch (e: Throwable) { println("WPLD-CLAMP 2^$heavyExp boot threw $e"); continue }
                h.mintSupply(1L shl 58)
                val a = setOf(shape.groupId("a"))
                val all = listOf("a", "b", "c").map { shape.groupId(it) }.sortedBy { it.value }.toSet()
                var last: AcquireOutcome? = null
                repeat(n) { i ->
                    val out = h.acquire(a, a, startedAt = i.toLong(), reservation = LocalHeddle.MAX_RESERVATION)
                    if (out is AcquireOutcome.Acquired) h.complete(out.lease.id, LocalHeddle.MAX_RESERVATION, waiting = emptySet()) else last = out
                }
                val before = load(h.snapshot(), GroupId("p"))
                val out = h.acquire(all, all, startedAt = n.toLong(), reservation = LocalHeddle.MAX_RESERVATION)
                val s = h.snapshot()
                println(
                    "WPLD-CLAMP 2^$heavyExp n=$n warm=${last?.let { it::class.simpleName }} before=$before out=${out::class.simpleName} " +
                        "load=${load(s, GroupId("p"))} noOff=${load(s, GroupId("p"), offsets = false)} stored=${storedSum(s.ledger, GroupId("p"))} " +
                        "offsets=${s.allocatorMemory.wakeOffsets.size} limit=$POSITION_LIMIT",
                )
            }
        }
    }

    @Test
    fun limitStats() {
        val slack = (LocalHeddle.MAX_RESERVATION + (1L shl 20)).timesWide(16L)
        val bound = POSITION_LIMIT + slack
        var over = 0
        var overNoOff = 0
        var maxNew = Int128.ZERO
        var first = ""
        drive { s, seed, step ->
            val n = load(s, GroupId("p"))
            val nNoOff = load(s, GroupId("p"), offsets = false)
            if (n == null || n > bound) { over++; if (first.isEmpty()) first = "$seed/$step" }
            if (nNoOff == null || nNoOff > bound) overNoOff++
            if (n != null && n > maxNew) maxNew = n
        }
        println("WPLD-LIMIT limit=$POSITION_LIMIT maxNew=$maxNew over=$over overNoOff=$overNoOff first=$first")
    }

    @Test
    fun limitAssertion() {
        val bound = POSITION_LIMIT + (LocalHeddle.MAX_RESERVATION + (1L shl 20)).timesWide(16L)
        val failures = ArrayList<String>()
        drive { s, seed, step -> val n = load(s, GroupId("p")); if (n == null || n > bound) failures += "$seed/$step" }
        assertTrue(failures.isEmpty(), "WPLD limit: ${failures.size} failing steps, first ${failures.take(3)}")
    }

    @Test
    fun storedLimitAssertion() {
        val bound = POSITION_LIMIT + (LocalHeddle.MAX_RESERVATION + (1L shl 20)).timesWide(16L)
        val failures = ArrayList<String>()
        drive { s, seed, step -> val n = storedSum(s.ledger, GroupId("p")); if (n == null || n > bound) failures += "$seed/$step" }
        assertTrue(failures.isEmpty(), "WPLD storedLimit: ${failures.size} failing steps, first ${failures.take(3)}")
    }

    @Test
    fun oldAssertion() {
        val failures = ArrayList<String>()
        drive { s, seed, step -> if (storedSum(s.ledger, GroupId("p")) == null) failures += "$seed/$step" }
        assertTrue(failures.isEmpty(), "WPLD old: ${failures.size} failing steps, first ${failures.take(3)}")
    }

    @Test
    fun newAssertion() {
        val failures = ArrayList<String>()
        drive { s, seed, step -> if (load(s, GroupId("p")) == null) failures += "$seed/$step" }
        assertTrue(failures.isEmpty(), "WPLD new: ${failures.size} failing steps, first ${failures.take(3)}")
    }
}
