package us.tractat.kuilt.multipeer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.TransportCapability

/**
 * wasmJs unavailability stub. MultipeerConnectivity is an Apple-platform API
 * and is not available on wasmJs.
 */
public actual class MultipeerPeerLinkFactory actual constructor(
    displayName: String,
    serviceType: String,
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
