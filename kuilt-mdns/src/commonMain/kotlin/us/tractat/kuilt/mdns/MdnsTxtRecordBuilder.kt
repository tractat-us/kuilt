package us.tractat.kuilt.mdns

import us.tractat.kuilt.core.PeerId

/**
 * Builds the TXT record map shared by all platform advertisers (JVM JmDNS and
 * Android NsdManager).
 *
 * All v1 required fields are always present. Optional v2 fields are only
 * included when non-null, preserving backward-compatibility with v1 peers.
 * [txtExtensions] are merged last — caller-supplied keys are written verbatim
 * and must not collide with the kuilt-reserved constants in
 * [MDNSAdvertisement.Companion].
 */
internal fun buildTxtMap(
    selfId: PeerId,
    wsPath: String,
    hostOs: MDNSAdvertisement.HostOs?,
    fabrics: String?,
    txtExtensions: Map<String, String>,
): Map<String, String> =
    buildMap {
        put(MDNSAdvertisement.TXT_KEY_PEER_ID, selfId.value)
        put(MDNSAdvertisement.TXT_KEY_WS_PATH, wsPath)
        put(MDNSAdvertisement.TXT_KEY_PROTOCOL_VERSION, MDNSAdvertisement.PROTOCOL_VERSION)
        hostOs?.let { put(MDNSAdvertisement.TXT_KEY_HOST_OS, it.txtValue) }
        fabrics?.let { put(MDNSAdvertisement.TXT_KEY_FABRICS, it) }
        putAll(txtExtensions)
    }
