package us.tractat.kuilt.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * A [Seam] that overrides nothing beyond the abstract members inherits the capability floor.
 *
 * The floor must be [FabricAvailability.Unknown], not [FabricAvailability.Available]: a fabric
 * with no live path observer cannot know whether its path is up, and a confident `Available`
 * there is an authoritative false negative once `Room.localFabric` surfaces it (#1712).
 */
class SeamCapabilityFloorTest {
    private class BareSeam : Seam {
        override val selfId: PeerId = PeerId("bare")
        override val peers: StateFlow<Set<PeerId>> = MutableStateFlow(emptySet())
        override val state: StateFlow<SeamState> = MutableStateFlow(SeamState.Woven)
        override val incoming: Flow<Swatch> = emptyFlow()
        override suspend fun broadcast(payload: ByteArray) = Unit
        override suspend fun sendTo(peer: PeerId, payload: ByteArray) = Unit
        override suspend fun close(reason: CloseReason) = Unit
    }

    @Test
    fun floorIsUnknownNotAvailable() {
        assertIs<FabricAvailability.Unknown>(BareSeam().capability.value.availability)
    }
}
