package us.tractat.kuilt.deal.test

import us.tractat.kuilt.deal.CommutativeScheme

/**
 * Verifies the fast [XorKeystreamScheme] test double satisfies the full
 * commutative-encryption contract (round-trip, commutativity, strip-order
 * independence, key distinctness, honest-transition verification) — the same
 * TCK `SraSchemeConformanceTest` runs against the real crypto.
 */
class XorKeystreamSchemeConformanceTest : CommutativeSchemeConformanceSuite() {
    override fun newScheme(): CommutativeScheme = XorKeystreamScheme()
}
