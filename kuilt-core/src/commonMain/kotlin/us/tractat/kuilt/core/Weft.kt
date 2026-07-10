package us.tractat.kuilt.core

/**
 * A **weft** is the thread woven in fresh on every pass of the shuttle across a [Loom] — here,
 * a per-dial value recomputed on every [Loom.weave] attempt, including every reconnect. Never
 * cached by kuilt: a fabric [Loom] implementation that needs fresh per-dial data invokes this
 * itself, inside its own `weave()`, so the caller's [C] is recomputed on the first dial and on
 * every subsequent redial.
 *
 * Deliberately generic, not shaped around any one use case: a credential that must be refreshed
 * on reconnect (the motivating case — see
 * [#1330](https://github.com/tractat-us/kuilt/issues/1330)) is the first consumer, but any
 * per-dial value a fabric implementation needs recomputed rather than fixed at construction
 * fits the same shape.
 *
 * @sample us.tractat.kuilt.core.sampleWeft
 */
public typealias Weft<C> = suspend () -> C
