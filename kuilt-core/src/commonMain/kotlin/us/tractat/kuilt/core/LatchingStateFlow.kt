@file:OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.takeWhile

/**
 * A scope-free [StateFlow] view that **follows** [source] until [latched] flips to `true`, then
 * reports [terminal] and never changes again.
 *
 * ## Contract
 *  - [latched] must be **monotonic**: once `true` it never returns to `false`. That is the whole
 *    premise — it is what makes [terminal] *terminal* rather than merely a value the view happens
 *    to be showing, and it is what lets this preserve [StateFlow]'s distinct-until-changed
 *    guarantee (below).
 *  - [terminal] is a constant, and is the only value observable once [latched] is `true`.
 *
 * ## Why this is not a mapped view
 * The `MappedStateFlow` shape — a state-dependent `transform` over [source] — cannot express this
 * safely. Its own precondition is that `transform` is **injective** on [source]'s distinct values,
 * and a transform collapsing every input onto one constant is the opposite of injective. The
 * consequence is concrete rather than notational: [source] may keep changing after the latch (a
 * shared registry other parties still mutate), and a mapped view would push a **duplicate**
 * `terminal` to every surviving collector on each such change, breaking distinct-until-changed for
 * exactly the consumers watching for the latch.
 *
 * Latching avoids that by construction: after the latch, [source] changes are not forwarded **at
 * all**. Before the latch, what is forwarded is [source] itself, which is already
 * distinct-until-changed because it is a [StateFlow]. So no de-duplication operator is needed
 * *within* either phase.
 *
 * ## The boundary between the phases is the one place that needs a check
 * Neither phase can produce a duplicate on its own, but the **transition** can: if the last value
 * published while following already equals [terminal], the latch changes nothing, and publishing
 * [terminal] anyway would republish a value the collector is already holding. [collect] therefore
 * emits [terminal] only when it differs from the last value that collector was actually handed —
 * which is a strictly narrower check than a blanket `distinctUntilChanged()`, because it is the
 * only comparison the two phases cannot already make for themselves.
 *
 * Concretely, on `InMemorySeam`: a seam that is the sole member of its mesh already publishes
 * `{ selfId }`, so tearing it must publish nothing at all. Same for a seam whose only remote left
 * before it closed.
 *
 * ## Why this is public API
 * The shape it solves is not one implementation's: it is *any* seam whose `peers` reads a registry
 * **shared** with the peers it is leaving. Those cannot collapse by writing their own `MutableStateFlow`
 * the way `LinkSeam` and `MeshSeam` do, and they cannot spin up a mirroring coroutine either, because a
 * `Seam` that owns no [kotlinx.coroutines.CoroutineScope] has nowhere to run one. `InMemorySeam` was the
 * first (#1849) and `ControllableSeam` in `:kuilt-test` the second (#2443) — at which point keeping this
 * `internal` to `:kuilt-core` would have meant copying a subtle distinct-until-changed argument into a
 * second module, which is the drift this repo generally refuses. A third-party fabric layered on a shared
 * registry needs it for the same reason and has no way to re-derive it correctly by accident.
 *
 * ## Cancellation
 * [collect] on a [StateFlow] returns `Nothing` — it never completes normally — so after emitting
 * [terminal] this suspends in [awaitCancellation] rather than returning. That leaves the collector
 * cancellable exactly as any other [StateFlow] collection is.
 *
 * Owns no [kotlinx.coroutines.CoroutineScope]: everything happens inside the *collector's* own
 * coroutine, which is what lets a scope-free type (`InMemorySeam`) expose it as its `peers`.
 */
public class LatchingStateFlow<T : Any>(
    private val source: StateFlow<T>,
    private val latched: StateFlow<Boolean>,
    private val terminal: T,
) : StateFlow<T> {

    private data class Reading<T>(val value: T, val latched: Boolean)

    override val value: T get() = if (latched.value) terminal else source.value

    override val replayCache: List<T> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        // `takeWhile` stops BEFORE the first latched reading, so the emission below is the only one
        // that can carry `terminal` — reached whether the latch flipped mid-collection or had
        // already flipped when this collector subscribed.
        //
        // `lastEmitted` is what closes the boundary case. `null` means "this collector has been
        // handed nothing yet", which is exactly the already-latched subscriber (`takeWhile` stops
        // before its first reading) — and it must still receive `terminal`, since that is its
        // initial value. The [T] : Any bound is what lets `null` carry that meaning unambiguously.
        var lastEmitted: T? = null
        combine(source, latched) { current, isLatched -> Reading(current, isLatched) }
            .takeWhile { !it.latched }
            .collect {
                collector.emit(it.value)
                lastEmitted = it.value
            }
        if (lastEmitted != terminal) collector.emit(terminal)
        awaitCancellation()
    }
}
