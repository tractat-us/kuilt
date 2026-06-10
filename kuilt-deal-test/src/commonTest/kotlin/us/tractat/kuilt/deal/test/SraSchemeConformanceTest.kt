package us.tractat.kuilt.deal.test

import us.tractat.kuilt.deal.SraScheme

class SraSchemeConformanceTest : CommutativeSchemeConformanceSuite() {
    override fun newScheme() = SraScheme()
}
