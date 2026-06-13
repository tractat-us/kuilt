package us.tractat.kuilt.deal.test

import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.deal.CommutativeScheme
import us.tractat.kuilt.deal.DealSession
import us.tractat.kuilt.deal.SraScheme
import us.tractat.kuilt.test.fakeSeamPair

public fun fakeDealSessionPair(
    aliceId: PeerId,
    bobId: PeerId,
    scheme: CommutativeScheme = SraScheme(),
    scope: CoroutineScope,
): Pair<DealSession, DealSession> {
    val allPlayers = setOf(aliceId, bobId)
    val (aliceSeam, bobSeam) = fakeSeamPair(aliceId, bobId)
    val aliceKey = scheme.generateKey()
    val bobKey = scheme.generateKey()
    val alice = DealSession(
        seam = aliceSeam,
        scheme = scheme,
        myKey = aliceKey,
        allPlayers = allPlayers,
        myId = aliceId,
        scope = scope,
    )
    val bob = DealSession(
        seam = bobSeam,
        scheme = scheme,
        myKey = bobKey,
        allPlayers = allPlayers,
        myId = bobId,
        scope = scope,
    )
    return alice to bob
}
