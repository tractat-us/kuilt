package us.tractat.kuilt.websocket

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingInterval
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlin.time.Duration

private val log = KotlinLogging.logger("us.tractat.kuilt.websocket.WebSocketPingInstall")

/**
 * Install the Ktor server [WebSockets] plugin with [pingPeriod] configured, so a **half-open** link
 * — a silently dead TCP connection with no FIN and no RST (peer crashed, cable pulled, NAT dropped
 * the flow) — is detected within roughly `2 × pingPeriod` instead of the multi-minute TCP-RTO
 * window a read-only side would otherwise wait. A ping that goes unanswered past the pong `timeout`
 * tears the session down; the seam's read loop reaches its `finally` and the dead peer leaves the
 * roster promptly and **symmetrically** (both ends run the same ping). `timeout` tracks
 * [pingPeriod] so a single knob controls the detection bound.
 *
 * **Pre-installed-plugin trap.** The `WebSockets` plugin is application-scoped and Ktor installs it
 * at most once. If the host application already installed it, this function *cannot* reconfigure it
 * — so a pre-installed plugin with no ping period means kuilt's half-open detection **silently never
 * applies**. This is exactly the failure that leaves a dead voter lingering forever, so we do not
 * fail silently: when the plugin is pre-installed without a ping period we log a loud warning. The
 * host must set `pingPeriod` on its own `install(WebSockets)` for half-open detection to work.
 */
internal fun installWebSocketsWithPing(application: Application, pingPeriod: Duration) {
    val existing = application.pluginOrNull(WebSockets)
    if (existing == null) {
        application.install(WebSockets) {
            this.pingPeriod = pingPeriod
            timeout = pingPeriod
        }
    } else if (existing.pingInterval == null) {
        log.warn {
            "WebSockets plugin was pre-installed without a pingPeriod; kuilt's half-open detection " +
                "($pingPeriod) will NOT apply. Install WebSockets { pingPeriod = <duration> } on your " +
                "own Application so a half-open inter-peer link is torn down promptly and symmetrically."
        }
    }
}
