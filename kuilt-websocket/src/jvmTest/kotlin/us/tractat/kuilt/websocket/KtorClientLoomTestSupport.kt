// kuilt-websocket/src/jvmTest/kotlin/us/tractat/kuilt/websocket/KtorClientLoomTestSupport.kt
package us.tractat.kuilt.websocket

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.Seam

/**
 * Joins [clientLoom] to [serverLoom] via [advertisement] and returns both sides' [Seam]s once
 * connected. Shared by [KtorClientLoomIdentityTest] and [KtorClientLoomWeftTest].
 */
internal suspend fun connectPair(
    serverLoom: KtorServerLoom,
    advertisement: WebSocketAdvertisement,
    clientLoom: KtorClientLoom,
    timeoutMs: Long = 5_000,
): Pair<Seam, Seam> =
    withTimeout(timeoutMs) {
        coroutineScope {
            val serverLinkDeferred = async { serverLoom.nextLink() }
            val clientLink = clientLoom.join(advertisement)
            serverLinkDeferred.await() to clientLink
        }
    }
