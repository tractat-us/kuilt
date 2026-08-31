package us.tractat.kuilt.deal.test

import us.tractat.kuilt.deal.CommutativeScheme
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Proves that **no** property of [CommutativeSchemeConformanceSuite] can be reached with an empty
 * plaintext domain.
 *
 * [CommutativeSchemeConformanceSuite.validPlaintexts] is a `public open fun` with a non-empty
 * default — a free knob whose empty setting is where the suite stops asserting anything. Two
 * failure shapes, and only one of them was ever a red:
 *
 *  - a property that **loops** over the domain passes green over an empty list, asserting nothing.
 *    That is the hazard: `encryptionIsCommutative` was the last one in this shape (#2347);
 *  - a property that takes `.first()` throws `NoSuchElementException` from inside the standard
 *    library. That is a red, but an illegible one — it names the collection, not the fixture
 *    setting that emptied it, and it aborts before the property's own diagnostics run.
 *
 * Both now assert the domain is non-empty first, so both name their own cause. This file is what
 * holds that: a green reference subclass shows a property *can* pass, never that it would notice
 * an empty domain, and every property here is a quantifier that an empty list satisfies for free.
 *
 * **The harness is an anonymous object built by a factory, not a named subclass.** A concrete
 * subclass of the suite inherits its `@Test` methods, so the test runner would collect it as a
 * test class of its own and every property would red for real. Same reason
 * `SeamConformanceUngatedCoreTest` and `DiscoverySourceConformanceSuiteRigTest` are written this
 * way; a fresh harness per property, because the suite's rigs count encryptions per instance.
 *
 * Every property here returns `Unit` rather than a `TestResult`, so unlike the discovery-source rig
 * this one runs on every target rather than JVM only.
 */
class CommutativeSchemeConformanceSuiteEmptyDomainRigTest {

    /**
     * The one fixture edit under test: a domain that computes empty. Everything else is a healthy
     * [XorKeystreamScheme] binding, so a red here is the empty domain and nothing else — and the
     * scheme is the cheap test double rather than SRA, since no property gets far enough to
     * encrypt anything.
     */
    private fun emptyDomainSuite(): CommutativeSchemeConformanceSuite =
        object : CommutativeSchemeConformanceSuite() {
            private val seeder = Random(seed = 0xE377)

            override fun newScheme(): CommutativeScheme = XorKeystreamScheme(Random(seeder.nextInt()))

            override fun newPeerScheme(): CommutativeScheme = XorKeystreamScheme(Random(seeder.nextInt()))

            override fun proofStrength() = ProofStrength.AcceptsEverything

            override fun validPlaintexts(): List<ByteArray> = emptyList()
        }

    @Test
    fun everyPropertyRedsOnItsOwnCauseWhenTheDomainIsEmpty() {
        assertAll(
            *propertiesUnderTest.map { (name, property) -> { assertNamesTheEmptyDomain(name, property) } }
                .toTypedArray(),
        )
    }

    /**
     * The suite's full `@Test` surface, listed by hand.
     *
     * Hand-listed is the cost of the anonymous harness — there is no reflective enumeration on
     * Kotlin/Native or wasmJs — so [everyPropertyIsListed] pins the count, and a property added
     * without a row here reds there rather than going quietly unchecked.
     */
    private val propertiesUnderTest: List<Pair<String, (CommutativeSchemeConformanceSuite) -> Unit>> = listOf(
        "encryptHidesThePlaintextAndStripRecoversIt" to { it.encryptHidesThePlaintextAndStripRecoversIt() },
        "encryptionIsCommutative" to { it.encryptionIsCommutative() },
        "encryptionIsCommutativeAcrossPeerInstances" to { it.encryptionIsCommutativeAcrossPeerInstances() },
        "multiLayerDealRecoversPlaintextRegardlessOfStripOrder" to {
            it.multiLayerDealRecoversPlaintextRegardlessOfStripOrder()
        },
        "multiLayerDealAcrossPeerInstancesRecoversPlaintextRegardlessOfStripOrder" to {
            it.multiLayerDealAcrossPeerInstancesRecoversPlaintextRegardlessOfStripOrder()
        },
        "distinctKeysProduceDistinctCiphertexts" to { it.distinctKeysProduceDistinctCiphertexts() },
        "generatedKeyPairsAreUsable" to { it.generatedKeyPairsAreUsable() },
        "verifyAcceptsHonestTransitions" to { it.verifyAcceptsHonestTransitions() },
        "verifyAnswersForgedTransitionsAsDeclared" to { it.verifyAnswersForgedTransitionsAsDeclared() },
    )

    @Test
    fun everyPropertyIsListed() {
        // A bare count, because that is all this can be without reflection — but it is enough to
        // stop a tenth property from being added to the suite and silently skipping this rig.
        assertTrue(
            propertiesUnderTest.size == EXPECTED_PROPERTY_COUNT,
            "CommutativeSchemeConformanceSuite's @Test surface changed: ${propertiesUnderTest.size} " +
                "properties listed here, $EXPECTED_PROPERTY_COUNT expected. Add the new property to " +
                "propertiesUnderTest (and give it a non-empty check) rather than bumping this number.",
        )
    }

    private fun assertNamesTheEmptyDomain(name: String, property: (CommutativeSchemeConformanceSuite) -> Unit) {
        // assertFailsWith re-throws a wrong exception type wrapped in an AssertionError, so a
        // property that still dies on `.first()` reaches the reader as NoSuchElementException with
        // this property's name attached, rather than as a bare "List is empty."
        val failure = assertFailsWith<AssertionError>(
            "$name accepted an empty validPlaintexts() — it asserted nothing and passed",
        ) { property(emptyDomainSuite()) }
        assertTrue(
            failure.message.orEmpty().contains(EMPTY_DOMAIN_MESSAGE),
            "$name red for a reason other than the empty domain, so it does not name its own cause. " +
                "Expected a message containing \"$EMPTY_DOMAIN_MESSAGE\", got: ${failure.message}",
        )
    }

    private companion object {
        const val EMPTY_DOMAIN_MESSAGE = "validPlaintexts() is empty"
        const val EXPECTED_PROPERTY_COUNT = 9
    }
}
