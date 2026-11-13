package us.tractat.kuilt.otel.bench

import java.lang.management.ManagementFactory

/**
 * One measured arm: the median of [rounds] per-round averages, plus how many operations the timed
 * rounds actually executed.
 *
 * [executed] is the harness's own count. Every arm ALSO checks a side effect the operation leaves —
 * a durable-write count, a counter total — because a loop counter only proves the loop turned, not
 * that the code under measurement did anything.
 */
data class Sample(
    val opsPerRound: Int,
    val rounds: Int,
    val executed: Long,
    val nanosPerOp: Double,
    val allocatedBytesPerOp: Double,
)

/**
 * Bytes allocated by the calling thread, from HotSpot's per-thread counter. Every arm runs inside a
 * `runBlocking` that never changes dispatcher, and the code under measurement neither dispatches nor
 * blocks off-thread, so the calling thread's count is the whole operation's allocation.
 */
private val threadBean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

private fun allocatedBytes(): Long = threadBean.currentThreadAllocatedBytes

/**
 * Runs [op] `opsPerRound` times per round for [rounds] rounds and times each round as a whole. For
 * operations in the microsecond range, where a clock read per operation would be visible in the
 * number. Warm up separately with [warmUp] before calling it.
 */
suspend fun measureRounds(opsPerRound: Int, rounds: Int, op: suspend (index: Int) -> Unit): Sample {
    require(opsPerRound > 0 && rounds > 0) { "opsPerRound and rounds must be positive" }
    var executed = 0L
    val nanos = DoubleArray(rounds)
    val alloc = DoubleArray(rounds)
    for (round in 0 until rounds) {
        val a0 = allocatedBytes()
        val t0 = System.nanoTime()
        for (i in 0 until opsPerRound) {
            op(round * opsPerRound + i)
            executed++
        }
        val elapsed = System.nanoTime() - t0
        val allocated = allocatedBytes() - a0
        nanos[round] = elapsed.toDouble() / opsPerRound
        alloc[round] = allocated.toDouble() / opsPerRound
    }
    check(executed == opsPerRound.toLong() * rounds) { "ran $executed ops, expected ${opsPerRound * rounds}" }
    return Sample(opsPerRound, rounds, executed, nanos.median(), alloc.median())
}

/**
 * Runs an untimed [setup] then a timed [op], [rounds] times, and reports the median [op]. For
 * millisecond-scale operations that need state changed between them — an edge drain needs fresh
 * series to send — where the setup must stay out of the number.
 */
suspend fun measureEach(rounds: Int, setup: suspend (round: Int) -> Unit, op: suspend (round: Int) -> Unit): Sample {
    require(rounds > 0) { "rounds must be positive" }
    var executed = 0L
    val nanos = DoubleArray(rounds)
    val alloc = DoubleArray(rounds)
    for (round in 0 until rounds) {
        setup(round)
        val a0 = allocatedBytes()
        val t0 = System.nanoTime()
        op(round)
        val elapsed = System.nanoTime() - t0
        val allocated = allocatedBytes() - a0
        executed++
        nanos[round] = elapsed.toDouble()
        alloc[round] = allocated.toDouble()
    }
    check(executed == rounds.toLong()) { "ran $executed ops, expected $rounds" }
    return Sample(1, rounds, executed, nanos.median(), alloc.median())
}

/** Runs [op] [ops] times, untimed, so the JIT has compiled the path before a timed round starts. */
suspend fun warmUp(ops: Int, op: suspend (index: Int) -> Unit) {
    for (i in 0 until ops) op(i)
}

private fun DoubleArray.median(): Double {
    val sorted = sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}
