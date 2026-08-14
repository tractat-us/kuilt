package us.tractat.kuilt.deal.test

import us.tractat.kuilt.deal.SraScheme

class SraSchemeConformanceTest : CommutativeSchemeConformanceSuite() {
    override fun newScheme() = SraScheme()

    // A second SraScheme, constructed exactly the way a remote player's process constructs its own
    // — no shared state is threaded in. SRA's group parameters are a companion constant, so two
    // instances agree on the modulus by construction; the cross-peer properties are what turn that
    // into something the build checks rather than something the reader has to notice.
    override fun newPeerScheme() = SraScheme()

    // SraScheme.verifyEncrypt/verifyStrip are `return true` — the stub CommutativeScheme permits
    // until real proofs land, and which nothing in production consults. Declared rather than
    // asserted away: this line is what the suite holds the scheme to, in both directions, so the
    // day SraScheme grows a real verifier it reds here until someone updates it.
    override fun proofStrength() = ProofStrength.AcceptsEverything
}
