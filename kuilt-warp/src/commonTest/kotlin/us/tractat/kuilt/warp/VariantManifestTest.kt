@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.drainAntiEntropy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val VM_QUILTER_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

private fun TestScope.settle() =
    drainAntiEntropy(VM_QUILTER_CONFIG.antiEntropyInterval, rounds = 8)

class VariantManifestTest {

    /**
     * A compiler node publishes a fake-compiled variant of a raw kernel; the variant's
     * BobbinMeta (with provenance) gossips to a second peer, and the variant bytes are
     * fetchable on demand and re-hash to the advertised hash.
     */
    @Test
    fun compiledVariantGossipsWithProvenanceAndIsFetchable() =
        runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamA = loom.host(Pattern("variant-gossip"))
            val seamB = loom.join(InMemoryTag("b"))
            val exchangeA = BobbinExchange(seamA, Creel(), backgroundScope, VM_QUILTER_CONFIG)
            val exchangeB = BobbinExchange(seamB, Creel(), backgroundScope, VM_QUILTER_CONFIG)

            val source = exchangeA.put(MINIMAL_WASM)
            val compiled = fakeCompile(MINIMAL_WASM, Target.Jvm)
            val variantHash = exchangeA.putVariant(compiled, VariantKey(source, Target.Jvm, OptLevel.O2))
            settle()

            val fetched = exchangeB.fetch(variantHash)

            assertAll(
                {
                    assertTrue(
                        exchangeB.manifest.value.any { it.hash == variantHash && it.variantOf == VariantKey(source, Target.Jvm, OptLevel.O2) },
                        "B learns the variant's provenance via gossip",
                    )
                },
                { assertEquals(compiled.toList(), fetched.toList(), "fetched variant bytes match the compiled bytes") },
                { assertTrue(variantHash != source, "the variant is a distinct bobbin from its source") },
            )
        }
}
