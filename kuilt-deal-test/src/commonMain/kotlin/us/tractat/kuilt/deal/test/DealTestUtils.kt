package us.tractat.kuilt.deal.test

import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.deal.CommutativeScheme
import us.tractat.kuilt.deal.DealSession
import us.tractat.kuilt.deal.SraScheme
import us.tractat.kuilt.test.fakeSeamGroup
import us.tractat.kuilt.test.fakeSeamPair

/**
 * Two wired [DealSession]s over a [fakeSeamPair], each running its **own** scheme instance.
 *
 * [newScheme] is a factory, not an instance, and that is the whole of #2311 at the integration
 * layer: in production every player constructs their own [CommutativeScheme] in their own process,
 * so a harness that hands one object to both sessions is testing the single arrangement that
 * cannot expose a cross-instance disagreement. Each session also draws its key from the instance
 * that will apply it, as a real player does.
 */
public fun fakeDealSessionPair(
    aliceId: PeerId,
    bobId: PeerId,
    newScheme: () -> CommutativeScheme = { SraScheme() },
    scope: CoroutineScope,
): Pair<DealSession, DealSession> {
    val allPlayers = setOf(aliceId, bobId)
    val (aliceSeam, bobSeam) = fakeSeamPair(aliceId, bobId)
    val aliceScheme = newScheme()
    val bobScheme = newScheme()
    val alice = DealSession(
        seam = aliceSeam,
        scheme = aliceScheme,
        myKey = aliceScheme.generateKey(),
        allPlayers = allPlayers,
        myId = aliceId,
        scope = scope,
    )
    val bob = DealSession(
        seam = bobSeam,
        scheme = bobScheme,
        myKey = bobScheme.generateKey(),
        allPlayers = allPlayers,
        myId = bobId,
        scope = scope,
    )
    return alice to bob
}

/**
 * Build a wired group of [DealSession]s (one per id in [playerIds], in order)
 * over a [fakeSeamGroup], each running its **own** instance from [newScheme] and holding a key
 * that instance minted — the N-player generalisation of [fakeDealSessionPair], and per-player
 * separate for the same reason.
 */
public fun fakeDealSessionGroup(
    playerIds: List<PeerId>,
    newScheme: () -> CommutativeScheme = { SraScheme() },
    scope: CoroutineScope,
): List<DealSession> {
    val allPlayers = playerIds.toSet()
    val seams = fakeSeamGroup(playerIds)
    return playerIds.zip(seams).map { (id, seam) ->
        val scheme = newScheme()
        DealSession(
            seam = seam,
            scheme = scheme,
            myKey = scheme.generateKey(),
            allPlayers = allPlayers,
            myId = id,
            scope = scope,
        )
    }
}
