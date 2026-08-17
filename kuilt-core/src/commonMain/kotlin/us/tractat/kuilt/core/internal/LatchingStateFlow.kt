@file:OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)

package us.tractat.kuilt.core.internal

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
 * ## Why this is not [MappedStateFlow]
 * The [MappedStateFlow] shape — a state-dependent `transform` over [source] — cannot express this
 * safely. Its own precondition is that `transform` is **injective** on [source]'s distinct values,
 * and a transform collapsing every input onto one constant is the opposite of injective. The
 * consequence is concrete rather than notational: [source] may keep changing after the latch (a
 * shared registry other parties still mutate), and a mapped view would push a **duplicate**
 * `terminal` to every surviving collector on each such change, breaking distinct-until-changed for
 * exactly the consumers watching for the latch.
 *
 * Latching avoids that by construction: after the latch, [source] changes are not forwarded **at
 * all**. Before the latch, what is forwarded is [source] itself, which is already
 * distinct-until-changed because it is a [StateFlow]. So no de-duplication operator is needed in
 * either phase, and there is no window in which one would be.
 *
 * ## Cancellation
 * [collect] on a [StateFlow] returns `Nothing` — it never completes normally — so after emitting
 * [terminal] this suspends in [awaitCancellation] rather than returning. That leaves the collector
 * cancellable exactly as any other [StateFlow] collection is.
 *
 * Owns no [kotlinx.coroutines.CoroutineScope]: everything happens inside the *collector's* own
 * coroutine, which is what lets a scope-free type (`InMemorySeam`) expose it as its `peers`.
 */
internal class LatchingStateFlow<T : Any>(
    private val source: StateFlow<T>,
    private val latched: StateFlow<Boolean>,
    private val terminal: T,
) : StateFlow<T> {

    private data class Reading<T>(val value: T, val latched: Boolean)

    override val value: T get() = if (latched.value) terminal else source.value

    override val replayCache: List<T> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        // `takeWhile` stops BEFORE the first latched reading, so the terminal emission below is the
        // only one that can carry `terminal` — reached whether the latch flipped mid-collection or
        // had already flipped when this collector subscribed.
        combine(source, latched) { current, isLatched -> Reading(current, isLatched) }
            .takeWhile { !it.latched }
            .collect { collector.emit(it.value) }
        collector.emit(terminal)
        awaitCancellation()
    }
}
