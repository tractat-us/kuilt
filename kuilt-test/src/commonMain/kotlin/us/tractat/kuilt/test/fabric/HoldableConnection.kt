package us.tractat.kuilt.test.fabric

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import us.tractat.kuilt.core.fabric.Connection

/**
 * A test's grip on the point where a transport's send would block: hold it, count what is stuck
 * there, and let it go one frame at a time.
 *
 * This exists for what an in-memory fabric cannot show. `InMemoryLoom` delivers before `sendTo`
 * returns, so "the frame was accepted but has not left yet" never happens on it, and a test of `flush`
 * over it passes whether or not anything waited.
 */
public interface SendHold {
    /** From now on, every send stops at the hold until it is released. Sends already past it finish. */
    public fun hold()

    /** Let the oldest held send through, or the next one to arrive if none is held yet. */
    public fun releaseOne()

    /** Stop holding: everything held goes through, and so does everything after it. */
    public fun releaseAll()

    /**
     * How many sends are stuck at the hold right now. A test asserts this before anything else: a
     * hold nothing reached makes "the flush waited" pass by absence.
     */
    public val parkedSends: Int
}

/**
 * A [Connection] whose sends can be held open, over any [delegate].
 *
 * A held send stops before it reaches [delegate], so the frame has not been handed over: it is the
 * state a real transport is in while a peer has stopped reading. [close] unsticks it the way a real
 * transport does: the send throws, and the frame is gone.
 *
 * Everything else, [flush] included, is [delegate]'s. A flush answered there says nothing about a
 * send held here, which is correct: [Connection.flush] covers sends that have returned.
 */
public class HoldableConnection(private val delegate: Connection) : Connection by delegate, SendHold {
    private data class Gate(val allowance: Long, val closed: Boolean)

    /** Sends numbered below [Gate.allowance] pass; the rest wait. One flow, so a close wakes them too. */
    private val gate = MutableStateFlow(Gate(allowance = Long.MAX_VALUE, closed = false))
    private val issued = atomic(0L)
    private val parked = atomic(0)

    override val parkedSends: Int get() = parked.value

    override fun hold() {
        gate.update { it.copy(allowance = issued.value) }
    }

    override fun releaseOne() {
        gate.update { current ->
            if (current.allowance == Long.MAX_VALUE) current else current.copy(allowance = current.allowance + 1)
        }
    }

    override fun releaseAll() {
        gate.update { it.copy(allowance = Long.MAX_VALUE) }
    }

    override suspend fun send(frame: ByteArray) {
        val ticket = issued.getAndIncrement()
        if (ticket >= gate.value.allowance) {
            parked.incrementAndGet()
            try {
                gate.first { ticket < it.allowance || it.closed }
            } finally {
                parked.decrementAndGet()
            }
        }
        check(!gate.value.closed) { "the link closed with this frame still held" }
        delegate.send(frame)
    }

    override suspend fun close() {
        gate.update { it.copy(closed = true) }
        delegate.close()
    }
}
