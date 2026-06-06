@file:OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)

package us.tractat.kuilt.core.internal

import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow

/**
 * A scope-free 1:1 view of [source] through [transform]. Valid as a [StateFlow]
 * only when [transform] is injective on [source]'s distinct values (so conflation
 * and distinct-until-changed are preserved). Used to derive [us.tractat.kuilt.core.Seam.plies]
 * from [us.tractat.kuilt.core.Seam.state] without owning a coroutine scope.
 */
internal class MappedStateFlow<T, R>(
    private val source: StateFlow<T>,
    private val transform: (T) -> R,
) : StateFlow<R> {
    override val value: R get() = transform(source.value)
    override val replayCache: List<R> get() = listOf(value)
    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        source.collect { collector.emit(transform(it)) }
    }
}
