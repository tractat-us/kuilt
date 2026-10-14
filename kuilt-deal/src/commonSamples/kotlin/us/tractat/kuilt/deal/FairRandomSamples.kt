package us.tractat.kuilt.deal

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam

public suspend fun sampleFairRandomReport(seam: Seam, peers: Set<PeerId>): FairRandomReport {
    val round = FairRandom(seam, peers)
    val report = round.rollWithReport()
    check(report.malformedFrameCounts == round.malformedFrameCounts)
    // If the round throws (including cancellation), inspect round.malformedFrameCounts in finally.
    return report
}
