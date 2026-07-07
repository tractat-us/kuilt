package us.tractat.kuilt.gossip

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * [GossipSeam] is a [us.tractat.kuilt.core.PrincipalRoster] by delegation: when the base seam has
 * no attestation concept, the roster is a constant empty map (never null, never throwing) — so
 * consumers can read `attestedPrincipals` uniformly across hub and non-hub compositions.
 */
class GossipSeamRosterTest {

    @Test
    fun rosterIsEmptyWhenBaseIsNotAPrincipalRoster() {
        val gossip = GossipSeam(
            base = InertSeam(PeerId("peer-0")),
            random = Random(0L),
            clock = { Instant.fromEpochMilliseconds(0) },
        )
        assertTrue(gossip.attestedPrincipals.value.isEmpty(), "non-roster base must yield a constant empty roster")
    }

    /** A minimal non-[us.tractat.kuilt.core.PrincipalRoster] base seam; never started or driven. */
    private class InertSeam(override val selfId: PeerId) : Seam {
        override val peers: StateFlow<Set<PeerId>> = MutableStateFlow(setOf(selfId))
        override val state: StateFlow<SeamState> = MutableStateFlow<SeamState>(SeamState.Woven)
        override val incoming: Flow<Swatch> = emptyFlow()
        override suspend fun broadcast(payload: ByteArray) = Unit
        override suspend fun sendTo(peer: PeerId, payload: ByteArray) = Unit
        override suspend fun close(reason: CloseReason) = Unit
    }
}
