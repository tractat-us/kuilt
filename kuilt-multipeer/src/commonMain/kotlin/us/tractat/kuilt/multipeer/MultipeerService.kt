package us.tractat.kuilt.multipeer

/**
 * Constants defining how this library identifies itself on Apple's
 * MultipeerConnectivity service-type registry.
 *
 * MultipeerConnectivity service-type strings must be 1–15 characters and may
 * contain only ASCII letters, digits, and hyphens — the same rules as
 * Bonjour's `_service._tcp.` form, minus the underscores. The chosen value is
 * stable wire/discovery identifier shared with consumers; do not change.
 */
public object MultipeerService {
    public const val SERVICE_TYPE: String = "fireworks-mc"
}
