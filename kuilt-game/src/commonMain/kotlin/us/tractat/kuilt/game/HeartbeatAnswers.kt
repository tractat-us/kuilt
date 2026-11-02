package us.tractat.kuilt.game

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PumpVerdict
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.pumpIn
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector

private val logger = KotlinLogging.logger("us.tractat.kuilt.game.HeartbeatAnswers")

private val PONG = HeartbeatPartitionDetector.PONG_PREFIX.encodeToByteArray()

/**
 * Tells a host watching this player that the player is still here.
 *
 * A host given a `livenessConfig` pings each voter and evicts one it stops hearing from. Those pings
 * land on [heartbeatSeam], and something on this side has to answer them. A host answers nobody, because
 * nobody watches it, so this is only for a peer that [gameJoin] seats. Without it every healthy player
 * is evicted when its window runs out (#3078).
 *
 * The channel is opened before the player is admitted, because a [us.tractat.kuilt.core.MuxSeam] drops
 * a frame for a channel nobody has opened. It ends with the seam, so a closed session goes quiet and its
 * seat comes free.
 *
 * **A player who has left stops answering.** Once this player has called [GamePresence.declareVacate] (a local latch, not the replicated board, which any peer can write), a ping goes
 * unanswered, so a [GameSession.leave] with no [GameSession.close] after it still frees the seat by the
 * end of the window, even if the host never acts on the vacate itself.
 *
 * **An answer proves the session's seam is open, not that its node is taking part.** A caller that
 * stops [GameSession.node] and keeps the seam open will go on answering. [GameSession.close] does both.
 *
 * The send runs off the collector's path because [Seam.sendTo] can stall on a wedged link, and a view
 * nobody is reading fills its spool and then stalls every other channel on the mux, Raft included. One
 * send per peer at a time: a pong is only evidence of life, so one already on its way is as good as a
 * second, and a wedged peer cannot grow a queue of them.
 *
 * @return the pump's job. Each pong runs as a child of the receiver, not of this job.
 */
internal fun CoroutineScope.answerHeartbeatPings(heartbeatSeam: Seam, presence: GamePresence): Job {
    val pongsInFlight = MutableStateFlow<Set<PeerId>>(emptySet())
    return heartbeatSeam.incoming.pumpIn(
        scope = this,
        onFailure = { failure, cause ->
            logger.warn(cause) { "heartbeat.answers.failed self=${heartbeatSeam.selfId.value} half=$failure" }
            PumpVerdict.CONTINUE
        },
        name = "game-heartbeat-answers",
    ) { frame ->
        val pinger = frame.sender ?: return@pumpIn
        if (!frame.decodeToString().startsWith(HeartbeatPartitionDetector.PING_PREFIX)) return@pumpIn
        if (presence.hasLeft()) return@pumpIn
        if (pinger in pongsInFlight.getAndUpdate { it + pinger }) return@pumpIn
        launch {
            try {
                runCatchingCancellable { heartbeatSeam.sendTo(pinger, PONG) }
                    .onFailure { logger.debug { "heartbeat.pong.failed to=${pinger.value}: $it" } }
            } finally {
                pongsInFlight.update { it - pinger }
            }
        }
    }
}
