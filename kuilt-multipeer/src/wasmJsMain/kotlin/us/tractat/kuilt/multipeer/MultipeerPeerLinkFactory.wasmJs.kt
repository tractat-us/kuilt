package us.tractat.kuilt.multipeer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam

/**
 * wasmJs unavailability stub. MultipeerConnectivity is an Apple-platform API
 * and is not available on wasmJs.
 */
public actual class MultipeerPeerLinkFactory actual constructor(
    displayName: String,
    serviceType: String,
) : Loom {
    public actual override suspend fun weave(rendezvous: Rendezvous): Seam =
        throw UnsupportedOperationException("MultipeerConnectivity is unavailable on wasmJs")

    public actual val visiblePeers: StateFlow<Set<MultipeerAdvertisement>> =
        MutableStateFlow(emptySet())

    public actual fun close(): Unit = Unit
}
