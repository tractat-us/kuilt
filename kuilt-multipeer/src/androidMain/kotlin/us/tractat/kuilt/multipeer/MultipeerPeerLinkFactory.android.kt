package us.tractat.kuilt.multipeer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.TransportCapability

/**
 * Android unavailability stub. MultipeerConnectivity is an Apple-platform API
 * and is not available on Android.
 */
public actual class MultipeerPeerLinkFactory actual constructor(
    displayName: String,
    serviceType: String,
) : Loom {
    /**
     * [FabricAvailability.Unavailable], not the roleless [FabricAvailability.Available] this used to
     * inherit from `Loom.capability()` (#1746): [weave] below throws unconditionally, so a consuming
     * app reading this pre-connect surface was being told a fabric was ready that can never carry a
     * frame. Not [FabricAvailability.Unknown] either — nothing here is unprobed, the answer is known
     * exactly. Roleless because this stub offers no transport role at all.
     *
     * `FabricAvailability`'s KDoc reserves "simply absent" for a fabric scoped out by target; this
     * class is not that. `expect`/`actual` makes it constructible on Android, so a consumer really
     * does hold a `Loom` here, and it has to answer honestly.
     */
    override fun capability(): TransportCapability =
        TransportCapability(
            roles = emptySet(),
            availability = FabricAvailability.Unavailable(
                "MultipeerConnectivity is an Apple-platform API; this androidMain stub cannot weave",
            ),
        )

    public actual override suspend fun weave(rendezvous: Rendezvous): Seam =
        throw UnsupportedOperationException("MultipeerConnectivity is unavailable on android")

    public actual val visiblePeers: StateFlow<Set<MultipeerAdvertisement>> =
        MutableStateFlow(emptySet())

    public actual fun close(): Unit = Unit
}
