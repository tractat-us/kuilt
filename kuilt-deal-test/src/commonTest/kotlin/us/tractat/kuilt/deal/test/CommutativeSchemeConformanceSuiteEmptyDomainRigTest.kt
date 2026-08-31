package us.tractat.kuilt.deal.test

import us.tractat.kuilt.deal.CommutativeScheme
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Every property of [CommutativeSchemeConformanceSuite], paired with the name it is asserted under.
 *
 * Hand-listed, because neither Kotlin/Native nor wasmJs can enumerate a class's `@Test` methods.
 * `CommutativeSchemeConformanceSuitePropertyCoverageTest` in `jvmTest` is what keeps the list
 * honest — it reflects over the suite on the one target that can and fails if this table has
 * drifted from the real `@Test` surface, in either direction.
 */
internal val COMMUTATIVE_SCHEME_SUITE_PROPERTIES:
    List<Pair<String, (CommutativeSchemeConformanceSuite) -> Unit>> = listOf(
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

/** The message every property's non-empty check carries, and the thing this rig matches on. */
internal const val EMPTY_DOMAIN_MESSAGE: String = "validPlaintexts() is empty"

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
 * holds that: a green reference subclass shows a property *can* pass, never that it would notice an
 * empty domain, and every property here is a quantifier that an empty list satisfies for free.
 *
 * **The harness is an anonymous object built by a factory, not a named subclass.** A concrete
 * subclass of the suite inherits its `@Test` methods, so the test runner would collect it as a test
 * class of its own and every property would red for real. Same reason `SeamConformanceUngatedCoreTest`
 * and `DiscoverySourceConformanceSuiteRigTest` are written this way; a fresh harness per property,
 * because the suite's rigs count encryptions per instance.
 *
 * Every property here returns `Unit` rather than a `TestResult`, so unlike the discovery-source rig
 * this one runs on every target rather than JVM only.
 */
class CommutativeSchemeConformanceSuiteEmptyDomainRigTest {

    /**
     * The one fixture edit under test: a domain that computes empty. Everything else is a healthy
     * [XorKeystreamScheme] binding, so a red here is the empty domain and nothing else — and the
     * scheme is the cheap test double rather than SRA, since no property gets far enough to encrypt
     * anything.
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
            *COMMUTATIVE_SCHEME_SUITE_PROPERTIES
                .map { (name, property) -> { assertNamesTheEmptyDomain(name, property) } }
                .toTypedArray(),
        )
    }

    /**
     * The rig's own positive control: the *same* harness with the default domain restored must run
     * every property green.
     *
     * Without it, a suite that had grown some unrelated defect would red above for a reason that
     * has nothing to do with the domain — and the message check would be the only thing separating
     * the two. This makes the separation an assertion rather than a hope.
     */
    @Test
    fun everyPropertyPassesOnceTheDomainIsRestored() {
        val healthy = object : CommutativeSchemeConformanceSuite() {
            private val seeder = Random(seed = 0x600D)
            override fun newScheme(): CommutativeScheme = XorKeystreamScheme(Random(seeder.nextInt()))
            override fun newPeerScheme(): CommutativeScheme = XorKeystreamScheme(Random(seeder.nextInt()))
            override fun proofStrength() = ProofStrength.AcceptsEverything
        }
        assertAll(*COMMUTATIVE_SCHEME_SUITE_PROPERTIES.map { (_, property) -> { property(healthy) } }.toTypedArray())
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
}
