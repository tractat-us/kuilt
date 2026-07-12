package us.tractat.kuilt.nw

import us.tractat.kuilt.core.Tag

/**
 * A joiner-side [Tag] for the kuilt-nw (Apple Network.framework) fabric that carries
 * the room's bearer secret.
 *
 * **`roomKey` is a bearer secret on this fabric — this upgrades the base [Tag.roomKey]
 * contract.** On most fabrics [Tag.roomKey] is a permissive room *label* (nullable,
 * used only for host-side room matching on a flat mesh). On kuilt-nw it is instead a
 * **required, non-null shared secret**: it is the input from which the TLS-PSK is
 * derived (see [NwPsk]), so anyone holding it can both join the session and decrypt
 * its traffic. Treat it like a password, not a name — never log or advertise it.
 *
 * [sessionName] and [peerKey] retain their base meaning: the human-readable session
 * name and this peer's stable identity within the transport.
 */
public data class NwTag(
    override val sessionName: String,
    override val peerKey: String,
    override val roomKey: String,
) : Tag {
    /**
     * Redacts [roomKey] — the auto-generated `data class` `toString()` would otherwise print the
     * bearer secret into any log line, exception message, or debugger frame that stringifies a tag
     * (tags are naturally logged during discovery/admission), contradicting the "never log it"
     * contract above. `equals`/`hashCode`/`copy` keep the full secret.
     */
    override fun toString(): String =
        "NwTag(sessionName=$sessionName, peerKey=$peerKey, roomKey=<redacted>)"
}
