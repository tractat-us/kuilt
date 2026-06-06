package us.tractat.kuilt.raft.pbt

import net.jqwik.api.ForAll
import net.jqwik.api.Property
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Phase 1 spike — proves jqwik runs in kuilt-raft's jvmTest alongside the
 * existing kotlin-test (JUnit4) suite. This file is replaced in Phase 2.
 */
internal class SpikePropertyTest {
    @Property
    fun additionCommutes(@ForAll a: Int, @ForAll b: Int) {
        assertTrue(a + b == b + a)
    }
}
