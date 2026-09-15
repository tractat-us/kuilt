package us.tractat.kuilt.session

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import us.tractat.kuilt.core.PeerId

private val logger = KotlinLogging.logger("us.tractat.kuilt.session.MemberInbox")

/**
 * One member admission's frames, held for [Room.incomingFrom] (#2802).
 *
 * **Invariant: every frame [offer]ed is either delivered to the claimant, in order, or the claimant's
 * flow ends saying why** — completion when the admission ends ([close]), a [MemberInboxException] when
 * frames were lost. Nothing here loses a frame without the reader being told.
 *
 * The owning room creates an inbox in the critical section that admits the member and [close]s it in
 * the one that evicts it; that pairing — not anything in this class — is what makes a frame's inbox
 * exist before the frame can be routed. Everything else is here, under this inbox's own [lock], which
 * is taken either on its own or inside the room's lock, never the other way round.
 */
internal class MemberInbox(
    private val member: PeerId,
    private val capacity: Int,
) {
    private enum class State { Holding, Released, Claimed, Closed }

    private val lock = reentrantLock()
    private val frames = Channel<RoomFrame>(capacity)

    // All guarded by [lock].
    private var state = State.Holding
    private var dropped = 0L
    private var collecting = false
    private var releasedFailure: MemberInboxException.ReleasedBeforeClaim? = null

    /**
     * Hold [frame] for the claimant. Never suspends — the room routes from its main loop — so a full
     * inbox is resolved here rather than waited out: an unclaimed one releases, a claimed one fails.
     */
    fun offer(frame: RoomFrame) {
        val released = lock.withLock {
            if (state == State.Released || state == State.Closed) {
                dropped++
                return
            }
            if (frames.trySend(frame).isSuccess) return
            dropped++
            if (state == State.Claimed) {
                // A cause, not a cancel: the collector drains what is held, then sees why it stopped.
                frames.close(MemberInboxException.CollectorFellBehind(member, capacity))
                state = State.Closed
                return
            }
            // Nobody claimed this admission within a full inbox, so its consumer reads `incoming`
            // only. Release the memory; a late claim is told exactly how much it missed.
            state = State.Released
            dropped += capacity
            frames.cancel()
            true
        }
        if (released) {
            logger.debug { "room.inbox.released member=${member.value} — not claimed within $capacity frames" }
        }
    }

    /**
     * The claimant's flow. Claiming a released inbox yields a flow that fails with
     * [MemberInboxException.ReleasedBeforeClaim]; every claim of one admission reads the same frames.
     */
    fun claim(): Flow<RoomFrame> {
        val failure = lock.withLock {
            when (state) {
                State.Holding -> state = State.Claimed
                State.Released -> releasedFailure = releasedFailure ?: releasedBeforeClaim()
                State.Claimed, State.Closed -> Unit
            }
            releasedFailure
        }
        return if (failure != null) failingInboxFlow(failure) else collectOnce()
    }

    /** End this admission: a collector drains what is held and completes. Idempotent. */
    fun close() {
        lock.withLock {
            // A released inbox's channel is cancelled, not closed; keep its failure for any claim so a
            // reader is told what it missed rather than handed a cancellation.
            if (state == State.Released) releasedFailure = releasedFailure ?: releasedBeforeClaim()
            state = State.Closed
            // A no-op on a channel already closed with a CollectorFellBehind cause, which is kept.
            frames.close()
        }
    }

    private fun releasedBeforeClaim() = MemberInboxException.ReleasedBeforeClaim(member, dropped)

    private fun collectOnce(): Flow<RoomFrame> =
        flow {
            lock.withLock {
                check(!collecting) {
                    "incomingFrom(${member.value}) is already being collected — it is single-collection; " +
                        "collect it from one coroutine at a time"
                }
                collecting = true
            }
            try {
                emitAll(frames.receiveAsFlow())
            } finally {
                lock.withLock { collecting = false }
            }
        }
}

/** A flow that fails with [failure] when collected — for a claim that has nothing to deliver. */
internal fun failingInboxFlow(failure: MemberInboxException): Flow<RoomFrame> = flow { throw failure }
