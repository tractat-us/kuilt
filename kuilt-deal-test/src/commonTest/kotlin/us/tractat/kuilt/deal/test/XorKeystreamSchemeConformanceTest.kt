package us.tractat.kuilt.deal.test

import us.tractat.kuilt.deal.CommutativeScheme
import kotlin.random.Random

/**
 * Verifies the fast [XorKeystreamScheme] test double satisfies the full
 * commutative-encryption contract (round-trip, secrecy of every covered intermediate,
 * commutativity, strip-order independence, key distinctness, cross-peer-instance
 * commutativity) — the same TCK `SraSchemeConformanceTest` runs against the real crypto.
 *
 * It does **not** verify anything about cheat detection, and the KDoc here used to imply it did by
 * listing "honest-transition verification" alongside the laws. This scheme's `verify*` return
 * `true` for everything; [ProofStrength.AcceptsEverything] below is that fact, stated where the
 * build can hold it. `RecomputingXorSchemeConformanceTest` is the binding that exercises the other
 * arm.
 */
class XorKeystreamSchemeConformanceTest : CommutativeSchemeConformanceSuite() {

    // One seeded source of sub-seeds, so every instance the suite asks for is deterministic AND
    // distinct. Seeding the instances identically would be the vacuous configuration this scheme
    // makes easiest to reach: XOR is self-inverse, so two peers holding the same key have layers
    // that cancel, and the cross-peer properties would pass without crossing anything. The suite's
    // "peers encrypted the same message to the same ciphertext" precondition reds on that, but the
    // fixture should not be relying on the suite to catch its own configuration.
    private val seeder = Random(seed = 0x5EED)

    override fun newScheme(): CommutativeScheme = XorKeystreamScheme(Random(seeder.nextInt()))

    override fun newPeerScheme(): CommutativeScheme = XorKeystreamScheme(Random(seeder.nextInt()))

    override fun proofStrength() = ProofStrength.AcceptsEverything
}
