/**
 * A mux channel view must report the **base seam's** live capability, not the `Unknown` floor (#1546).
 *
 * Uses [UnconfinedTestDispatcher] so the mux's internal launches are eager inside [runTest],
 * matching `MuxSeamTest` / `NamedMuxTest`.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIsNot
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Both public muxers share one `MuxBase.ChannelView`, so both must mirror their base's
 * [Seam.capability]. A view that inherits the interface default reports a roleless
 * [FabricAvailability.Unknown] even when the base has a real OS path observer answering
 * confidently — discarding a verdict already established one layer down.
 *
 * ## Why this is not driven through the conformance harness
 *
 * `MuxServerLoomConformanceTest` muxes over a `RoomHubSeam`, which wires no path observer and so
 * honestly reports the floor. Through that harness "mirrors the base" and "returns the floor"
 * produce the **identical** value, and the property cannot fail. The fixture here therefore starts
 * the base at a capability that is *not* the floor (non-empty [TransportCapability.roles], a
 * definite [FabricAvailability]) — and asserts that of itself, so a future edit that flattens the
 * fixture back onto the floor fails loudly rather than passing vacuously.
 *
 * ## Why the base is mutated after the view exists
 *
 * Reading only the initial value would also pass against an implementation that *snapshotted* the
 * base at construction — a strictly weaker property than the one shipped. Each test moves the base
 * to a different capability **after** taking the view and asserts the view observes the new value.
 */
class MuxCapabilityPassthroughTest {

    @Test
    fun muxSeamChannelViewMirrorsBaseCapability() = runTest(UnconfinedTestDispatcher()) {
        val base = CapabilityReportingSeam(WIFI_LAN_UP)
        val view = MuxSeam(base, backgroundScope).channel(0x07.toByte())

        val beforeChange = view.capability.value
        base.report(RELAY_DOWN)
        val afterChange = view.capability.value

        assertAll(
            { assertMirrorIsFalsifiable() },
            { assertEquals(WIFI_LAN_UP, beforeChange, "MuxSeam channel view must report the base's capability, not the Unknown floor") },
            { assertEquals(RELAY_DOWN, afterChange, "MuxSeam channel view must track the base's capability, not snapshot it at construction") },
        )
    }

    @Test
    fun namedMuxChannelViewMirrorsBaseCapability() = runTest(UnconfinedTestDispatcher()) {
        val base = CapabilityReportingSeam(WIFI_LAN_UP)
        val view = NamedMux(base, backgroundScope).channel("telemetry")

        val beforeChange = view.capability.value
        base.report(RELAY_DOWN)
        val afterChange = view.capability.value

        assertAll(
            { assertMirrorIsFalsifiable() },
            { assertEquals(WIFI_LAN_UP, beforeChange, "NamedMux channel view must report the base's capability, not the Unknown floor") },
            { assertEquals(RELAY_DOWN, afterChange, "NamedMux channel view must track the base's capability, not snapshot it at construction") },
        )
    }

    /**
     * The rig, asserted rather than assumed: unless the two fixture capabilities are both off the
     * floor and differ from each other, neither arm above can distinguish a mirror from the
     * inherited default, nor a live mirror from a construction-time snapshot.
     */
    private fun assertMirrorIsFalsifiable() {
        assertTrue(WIFI_LAN_UP.roles.isNotEmpty(), "rig: the base's roles must be non-empty, or the floor's emptySet() is indistinguishable")
        assertIsNot<FabricAvailability.Unknown>(
            WIFI_LAN_UP.availability,
            "rig: the base must not start at the Unknown floor, or a mirror and the inherited default agree",
        )
        assertNotEquals(WIFI_LAN_UP, RELAY_DOWN, "rig: the fixture must actually change, or the reactivity arm cannot fail")
    }

    private companion object {
        /** A base reporting confidently — the state the floor cannot represent. */
        val WIFI_LAN_UP = TransportCapability(
            roles = setOf(TransportRole.Data, TransportRole.WifiLan),
            availability = FabricAvailability.Available,
        )

        /** A different confident verdict, applied after the view exists. */
        val RELAY_DOWN = TransportCapability(
            roles = setOf(TransportRole.ServerRelay),
            availability = FabricAvailability.Unavailable("radio off"),
        )
    }
}

/**
 * A base [Seam] whose [capability] the test drives. Deliberately local rather than
 * `us.tractat.kuilt.test.FakeSeam`: the point of the fixture is a capability that moves, and the
 * shared fake inherits the static floor.
 */
private class CapabilityReportingSeam(initial: TransportCapability) : Seam {
    private val _capability = MutableStateFlow(initial)

    override val selfId: PeerId = PeerId("mux-capability-base")
    override val peers: StateFlow<Set<PeerId>> = MutableStateFlow(setOf(PeerId("mux-capability-base")))
    override val state: StateFlow<SeamState> = MutableStateFlow(SeamState.Woven)
    override val capability: StateFlow<TransportCapability> = _capability.asStateFlow()
    override val incoming: Flow<Swatch> = emptyFlow()

    /** Moves the base's live verdict, as an OS path observer would. */
    fun report(capability: TransportCapability) {
        _capability.value = capability
    }

    override suspend fun broadcast(payload: ByteArray) = Unit

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) = Unit

    override suspend fun close(reason: CloseReason) = Unit
}
