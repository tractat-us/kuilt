package us.tractat.kuilt.warp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import us.tractat.kuilt.crdt.Quilted

/**
 * A minimal local runtime that ties a [Draft] pipeline to a converging [IncrementalResult].
 *
 * Because every monotone stage in a [Draft] commutes, the query never "finishes" —
 * it **converges**, refining as contributions arrive from distributed peers. This is
 * the execution side of that: callers submit lattice deltas via [submit], which are
 * queued to a channel and processed asynchronously on the provided [scope], then
 * accumulated into [result] via the lattice join.
 *
 * ## Scope discipline
 *
 * The [scope] parameter is **required** with no default. The runtime's lifecycle is
 * explicitly tied to whatever scope the caller provides — a service scope in production,
 * `backgroundScope` in tests (sharing the test scheduler). Defaulting to `GlobalScope`
 * or a real `Dispatchers.*` would silently decouple contributions from the test's
 * virtual clock, breaking determinism.
 *
 * ## Thread safety
 *
 * [submit] is non-blocking: it sends to an `UNLIMITED` channel using `trySend` (which
 * never fails for unlimited channels). A single coroutine launched on [scope] drains
 * the channel, maintaining FIFO ordering for lattice joins. [IncrementalResult.contribute]
 * is CAS-safe, so concurrent submits from multiple threads converge correctly.
 *
 * Under a [kotlinx.coroutines.test.StandardTestDispatcher], call `runCurrent()` after
 * [submit] to drain the channel and observe the updated [result].
 *
 * ## Draft connection
 *
 * The [draft] is stored and exposed for inspection (E-2/E-3 rewrite and cost-model
 * integration). This runtime operates on the **monotone path only** — [DraftStage.Embroider]
 * stages (the coordinated path) are not executed here; [WarpNode] handles them.
 *
 * @param L the lattice type — must be [Quilted].
 * @param draft the dataflow graph whose monotone stages guide convergent execution.
 * @param scope the coroutine scope on which contributions are processed. Required — no default.
 * @param initial the lattice bottom — the result before any contributions arrive.
 * @sample us.tractat.kuilt.warp.sampleConvergentExecution
 */
public class ConvergentExecution<L : Quilted<L>>(
    public val draft: Draft<*>,
    scope: CoroutineScope,
    initial: L,
) {

    /** The converging result — accumulates all submitted contributions. */
    public val result: IncrementalResult<L> = IncrementalResult(initial)

    private val contributions: Channel<L> = Channel(Channel.UNLIMITED)

    init {
        scope.launch {
            for (delta in contributions) {
                result.contribute(delta)
            }
        }
    }

    /**
     * Submit a lattice [delta] for this execution.
     *
     * Non-blocking — the delta is placed on an `UNLIMITED` channel and processed
     * asynchronously on the [scope]'s dispatcher. Under a test dispatcher, call
     * `runCurrent()` after submitting to drain the queue and observe [result].
     */
    public fun submit(delta: L) {
        contributions.trySend(delta)
    }
}
