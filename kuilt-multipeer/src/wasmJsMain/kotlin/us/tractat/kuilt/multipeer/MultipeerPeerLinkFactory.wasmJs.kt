package us.tractat.kuilt.multipeer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Tag

/**
 * wasmJs unavailability stub. MultipeerConnectivity is an Apple-platform API
 * and is not available on wasmJs.
 */
public actual class MultipeerPeerLinkFactory actual constructor(
    displayName: String,
    serviceType: String,
) : Loom {
    public actual override suspend fun open(config: Pattern): Seam =
        throw UnsupportedOperationException("MultipeerConnectivity is unavailable on wasmJs")

    public actual override suspend fun join(advertisement: Tag): Seam =
        throw UnsupportedOperationException("MultipeerConnectivity is unavailable on wasmJs")

    public actual val visiblePeers: StateFlow<Set<MultipeerAdvertisement>> =
        MutableStateFlow(emptySet())

    public actual fun close(): Unit = Unit
}
