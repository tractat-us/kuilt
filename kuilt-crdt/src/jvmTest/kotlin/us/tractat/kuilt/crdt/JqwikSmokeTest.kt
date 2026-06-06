package us.tractat.kuilt.crdt

import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.LongRange

/**
 * Confirms jqwik is wired into :kuilt-crdt's jvmTest. Every CRDT type will
 * grow proper jqwik property suites in follow-up PRs (Phase B of the test
 * push); this is the wiring smoke test only.
 */
internal class JqwikSmokeTest {

    @Property(tries = 100)
    fun gCounterValueEqualsSumOfPerReplicaCounts(
        @ForAll @LongRange(min = 0L, max = 1000L) seedA: Long,
        @ForAll @LongRange(min = 0L, max = 1000L) seedB: Long,
    ) {
        val a = ReplicaId("A")
        val b = ReplicaId("B")
        val c = GCounter.of(a to seedA, b to seedB)
        check(c.value == seedA + seedB) { "expected ${seedA + seedB}, got ${c.value}" }
    }
}
