package us.tractat.kuilt.tcp

import us.tractat.kuilt.core.Tag

/**
 * Discovery handle for a TCP fabric: the host and port a joiner dials.
 *
 * Pure data, so it lives in `commonMain` even though the TCP socket code that
 * consumes it is JVM/Android-only.
 */
public data class TcpAddress(
    val host: String,
    val port: Int,
    override val displayName: String = "$host:$port",
) : Tag {
    override val peerKey: String get() = "$host:$port"
}
