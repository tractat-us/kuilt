package us.tractat.kuilt.multipeer

/**
 * Constants defining how Fireworks identifies itself on Apple's
 * MultipeerConnectivity service-type registry.
 *
 * MultipeerConnectivity service-type strings must be 1–15 characters and may
 * contain only ASCII letters, digits, and hyphens — the same rules as
 * Bonjour's `_service._tcp.` form, minus the underscores. We pick
 * `fireworks-mc` so it's instantly recognisable in `tcpdump`/Bonjour Browser
 * yet does not collide with our mDNS service type (`_fireworks._tcp.`).
 */
public object MultipeerService {
    public const val SERVICE_TYPE: String = "fireworks-mc"
}
