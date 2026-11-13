package us.tractat.kuilt.session.admit

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.session.admit.AdmitMessage.Hello
import kotlin.time.Instant

internal class PendingChallenges(private val clock: () -> Instant) {

    internal class Pending(
        val generation: Long,
        val hello: Hello,
        val hostNonce: ByteArray,
        val deadline: Instant,
    )

    val retained: Int get() = TODO()

    fun open(sender: PeerId, hello: Hello, hostNonce: ByteArray, deadline: Instant): Unit = TODO()

    fun take(sender: PeerId): Pending? = TODO()

    fun isCurrent(sender: PeerId, generation: Long): Boolean = TODO()

    fun drop(sender: PeerId): Unit = TODO()
}
