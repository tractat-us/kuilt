package us.tractat.kuilt.core.discovery

/**
 * Identifies which transport produced a [us.tractat.kuilt.core.Tag] so the lobby UI can
 * group, badge, or filter by mechanism.
 *
 * Open hierarchy (interface, not enum) so transport modules in other Gradle
 * modules can supply their own kinds without amending `:kuilt-core` —
 * mirroring the policy on [us.tractat.kuilt.core.Tag].
 *
 * The two kinds defined today are [Mdns] (Bonjour over the LAN) and
 * [Multipeer] (Apple's MultipeerConnectivity, used between iOS devices and
 * macOS desktops).
 */
public interface DiscoveryKind {
    public val id: String

    public object Mdns : DiscoveryKind {
        override val id: String = "mdns"
    }

    public object Multipeer : DiscoveryKind {
        override val id: String = "multipeer"
    }
}
