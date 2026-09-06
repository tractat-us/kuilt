package us.tractat.kuilt.multipeer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.TransportCapability

/**
 * wasmJs unavailability stub. MultipeerConnectivity is an Apple-platform API
 * and is not available on wasmJs.
 *
 * Every constructor argument is ignored, including `selfId` and `policy`. That is
 * not the silently-ignored-parameter defect #1430 exists to prevent: [weave] throws
 * unconditionally, so this class never mints a seam for an identity to name or a
 * delivery policy to govern. The parameters exist only because an `actual` must
 * match its `expect`.
 */
public actual class MultipeerPeerLinkFactory actual constructor(
    @Suppress("UNUSED_PARAMETER") displayName: String,
    @Suppress("UNUSED_PARAMETER") serviceType: String,
    @Suppress("UNUSED_PARAMETER") selfId: PeerId,
    @Suppress("UNUSED_PARAMETER") policy: DeliveryPolicy,
) : Loom {
    /**
     * [FabricAvailability.Unavailable] — the same statement the androidMain stub makes, for the same
     * reason (#1746). [weave] below throws unconditionally, so the roleless
     * [FabricAvailability.Available] this used to inherit was a claim it can never honour. Known
     * exactly, so not [FabricAvailability.Unknown]; constructible here via `expect`/`actual`, so not
     * the "simply absent" case either.
     */
    override fun capability(): TransportCapability =
        TransportCapability(
            roles = emptySet(),
            availability = FabricAvailability.Unavailable(
                "MultipeerConnectivity is an Apple-platform API; this wasmJsMain stub cannot weave",
            ),
        )

    public actual override suspend fun weave(rendezvous: Rendezvous): Seam =
        throw UnsupportedOperationException("MultipeerConnectivity is unavailable on wasmJs")

    public actual val visiblePeers: StateFlow<Set<MultipeerAdvertisement>> =
        MutableStateFlow(emptySet())

    public actual fun close(): Unit = Unit
}
