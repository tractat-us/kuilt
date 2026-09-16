package us.tractat.kuilt.session

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import us.tractat.kuilt.core.PeerId

private val logger = KotlinLogging.logger("us.tractat.kuilt.session.MemberInbox")

/**
 * One member admission's frames, held for [Room.incomingFrom] (#2802).
 *
 * **Invariant: every frame [offer]ed is handed to a collection of this inbox exactly once, in arrival
 * order, or the stream ends saying why** — completion when the admission ends ([close]), [FramesLost]
 * when frames were lost. A frame leaves this inbox only at the instant it is handed to a collector.
 *
 * ## Why a deque and a wakeup, not a Channel
 *
 * A `Channel` receive can take a frame and then be cancelled before anyone sees it, and a `flow {}`
 * builder checks for cancellation after the receive and before the downstream sees the value — two windows
 * in which a frame silently left the inbox, so a re-collection skipped it. Here a frame stays in [held]
 * until a collection removes it under [lock] and hands it to the collector with no suspension point in
 * between, and the flow implements [Flow] directly, so no check of this class's own sits between the two.
 * Cancellation is observed before a frame is taken, never after.
 *
 * The flow is a direct [Flow] implementation, which kotlinx.coroutines documents as not stable for
 * inheritance. It is required anyway: the `flow {}` builder's `SafeCollector.emit` calls `ensureActive()`
 * *before* delivering, so a frame taken and then emitted through it can be lost to a cancellation that lands
 * in between.
 *
 * What the consumer's own pipeline does with a frame once it has it is the consumer's: at-most-once, as
 * for any Flow. That includes every operator that takes frames ahead of the consumer — `buffer`, `flowOn`,
 * `conflate`, `produceIn`, `shareIn` / `stateIn` — each of which loses what it holds when cancelled, so the
 * "nothing is lost across re-collection" guarantee reaches only as far as the first such operator. That
 * boundary is also why a frame is not put back when `emit` throws — `take(n)` aborts by throwing from `emit`
 * *after* the frame was delivered, so a put-back would deliver it twice.
 *
 * A new collection straight after cancelling the previous one throws [IllegalStateException] until the
 * cancelled one has finished — its `finally` clears the single-collection flag — so `cancelAndJoin` it first.
 *
 * ## Locking
 *
 * Every decision is made under [lock]; every wakeup is sent, and every frame handed over, after releasing
 * it. A collector resumed in place — on `Dispatchers.Unconfined`, say, going straight on to call back into
 * the room — therefore never runs while the waking thread holds this lock. The owning room keeps the same
 * rule for its own lock: it removes an inbox under that lock and [close]s it after releasing it.
 */
internal class MemberInbox(
    private val member: PeerId,
    private val capacity: Int,
) {
    private enum class State { Holding, Released, Claimed, Ended }

    private enum class Offered { Held, Discarded, Released }

    private sealed interface Step {
        class Deliver(val frame: RoomFrame) : Step

        data object Wait : Step

        class End(val failure: FramesLost?) : Step
    }

    private val lock = reentrantLock()

    // All guarded by [lock].
    private val held = ArrayDeque<RoomFrame>()
    private var state = State.Holding
    private var dropped = 0L
    private var collecting = false

    /**
     * Whether the stream ends in loss rather than completion, and on which side of the claim — the two
     * fields [FramesLost] carries besides [dropped], which is read when the reader reaches the end so it
     * counts every frame lost by then, not only those lost when the loss began.
     */
    private var endInLoss = false
    private var lostWhileClaimed = false

    /**
     * Wakes a parked collection. Conflated, so a wakeup sent while nobody waits is kept for the next wait
     * — none is lost — and many collapse into one. Only ever touched outside [lock].
     */
    private val wakeup = Channel<Unit>(Channel.CONFLATED)

    /**
     * Hold [frame] for the claimant. Never suspends — the room routes from its main loop — so a full inbox
     * is resolved here rather than waited out: an unclaimed one releases, a claimed one ends in failure.
     */
    fun offer(frame: RoomFrame) {
        val offered = lock.withLock {
            when {
                state == State.Released || state == State.Ended -> {
                    dropped++
                    Offered.Discarded
                }
                held.size < capacity -> {
                    held.addLast(frame)
                    Offered.Held
                }
                state == State.Claimed -> {
                    // A full inbox behind: end the stream after what is held rather than make routing wait.
                    dropped++
                    state = State.Ended
                    endInLoss = true
                    lostWhileClaimed = true
                    Offered.Held
                }
                else -> {
                    // Nobody claimed this admission within a full inbox, so its consumer reads `incoming`
                    // only. Release the memory; a late claim is told exactly how much it missed.
                    dropped += held.size + 1L
                    held.clear()
                    state = State.Released
                    endInLoss = true
                    lostWhileClaimed = false
                    Offered.Released
                }
            }
        }
        when (offered) {
            Offered.Held -> wakeup.trySend(Unit)
            Offered.Released -> logger.debug { "room.inbox.released member=${member.value} — not claimed within $capacity frames" }
            Offered.Discarded -> Unit
        }
    }

    /**
     * The claimant's flow. Every claim of one admission reads the same stream: a released inbox is
     * already ended, so its claimant reads the [FramesLost] [nextStep] builds rather than any frames.
     */
    fun claim(): Flow<RoomFrame> {
        lock.withLock {
            when (state) {
                State.Holding -> state = State.Claimed
                // Released: nothing is held and nothing will be. The claimant reads the same ended
                // stream every other claim of this admission reads, and [nextStep] fails it with the
                // count as it stands when the reader arrives.
                State.Released -> state = State.Ended
                State.Claimed, State.Ended -> Unit
            }
        }
        return frames
    }

    /** End this admission: a collection delivers what is held and then completes. Idempotent. */
    fun close() {
        lock.withLock {
            // An admission that already lost frames keeps that verdict: ending it here does not turn a
            // loss into a clean completion, and [endInLoss] is what [nextStep] reads.
            state = State.Ended
        }
        wakeup.trySend(Unit)
    }

    private fun nextStep(): Step =
        lock.withLock {
            val frame = held.removeFirstOrNull()
            when {
                frame != null -> Step.Deliver(frame)
                // Built here rather than when the loss began, so `dropped` counts every frame lost by
                // the time the reader arrives — including those routed while it was draining what was
                // held, or after a released inbox stopped holding.
                state == State.Ended -> Step.End(if (endInLoss) FramesLost(member, dropped, lostWhileClaimed) else null)
                else -> Step.Wait
            }
        }

    private val frames: Flow<RoomFrame> =
        object : Flow<RoomFrame> {
            override suspend fun collect(collector: FlowCollector<RoomFrame>) {
                lock.withLock {
                    check(!collecting) {
                        "incomingFrom(${member.value}) is already being collected — it is single-collection; " +
                            "collect it from one coroutine at a time"
                    }
                    collecting = true
                }
                try {
                    while (true) {
                        // Before taking a frame, never between taking and handing it over: a cancelled
                        // collection stops here with the frame still held for the next one.
                        currentCoroutineContext().ensureActive()
                        when (val step = nextStep()) {
                            is Step.Deliver -> collector.emit(step.frame)
                            Step.Wait -> wakeup.receive()
                            is Step.End -> {
                                step.failure?.let { throw it }
                                return
                            }
                        }
                    }
                } finally {
                    lock.withLock { collecting = false }
                }
            }
        }
}
