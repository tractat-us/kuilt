package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.CoroutineScope

/**
 * Run [block] with [attributes] bound to the current scope, so every log line it
 * emits is stamped with them, then restore the previously-bound context.
 *
 * The scope-bound counterpart of [CaptureConfig.attributeMapper], and the answer
 * when a process holds **more than one session at a time**. The mapper is installed
 * once on the whole process's capture edge, so it can only ever fold in whichever
 * session is *currently armed* — a line emitted by a second, concurrent session is
 * then recorded as belonging to the armed one, and no downstream filter can tell the
 * difference (#1659). Binding the attributes to the scope that emits makes the
 * question per-emitter instead of process-wide.
 *
 * ```kotlin
 * withLogContext("session.id" to session.id) {
 *     // every line logged in here — and, on JVM/Android, in any child coroutine —
 *     // carries session.id, whatever else the process is doing concurrently.
 *     runSession()
 * }
 * ```
 *
 * ## Precedence — narrower scope wins
 *
 * One rule at every level: the process-global [CaptureConfig.attributeMapper] is
 * beaten by an enclosing `withLogContext`, which is beaten by a nested one. Nesting
 * **merges** rather than replaces, so a key only an outer scope set is still carried
 * into an inner one, and leaving the inner scope restores the outer binding.
 *
 * That direction is deliberate, and it is the one that fixes the reported defect: a
 * consumer entering the emitting session's scope must *correct* a globally-armed
 * stamp, not be silently overridden by it. Note the consequence — a scope attribute
 * also beats the same key in the mapper's output, including one the mapper derived
 * from the event's own payload. Use a nested `withLogContext` to override a scope
 * attribute for a narrower region.
 *
 * ## When it is resolved
 *
 * At the **synchronous capture edge**, on the caller that logged, in the same step
 * that resolves the mapper and the ambient trace (`LogCapture.resolveAtEdge`) — never
 * on the drain coroutine. So a line emitted inside this block carries this block's
 * attributes however long its record then waits to be written (#1630).
 *
 * ## Platform behaviour
 *
 * The reach of the binding differs by platform, for exactly the reason — and to
 * exactly the extent — that [withActiveTrace]'s does: only the JVM has a coroutine
 * primitive that mirrors a value across thread hops.
 * - **JVM / Android** — propagated across coroutine dispatches and inherited by child
 *   coroutines ([LogContextElement], a `ThreadContextElement`), and the prior context
 *   is restored on exit. Reliable even when [block] suspends and resumes elsewhere,
 *   and — the case this feature exists for — when two scopes are interleaved.
 * - **wasmJs** — single-threaded, so reliable for a line logged synchronously within
 *   [block].
 * - **iOS / macOS** — reliable for a line logged **synchronously within [block]** (the
 *   common case). If [block] suspends and resumes on a *different* worker thread, the
 *   context is **not** carried to that thread — no Kotlin/Native primitive can mirror
 *   a thread-local across coroutine dispatches (`kotlinx.coroutines.ThreadContextElement`
 *   is JVM-only as of coroutines 1.11.0). The restore is identity-guarded, so a hop can
 *   never overwrite an unrelated scope's context; the setting thread's slot is simply
 *   left until the next `withLogContext` on it supersedes it.
 *
 * Where the binding does not reach, a line is stamped with the enclosing scope's
 * attributes or none — it is not stamped with *another* scope's. The one shape that
 * can still mis-attribute on Apple/wasmJs is two scopes interleaving on one thread;
 * prefer keeping a session's logging synchronous within its block there.
 *
 * @param attributes the attributes to bind. Merged over any enclosing binding.
 * @sample us.tractat.kuilt.otel.logging.sampleWithLogContext
 */
public expect suspend fun <T> withLogContext(
    attributes: Map<String, String>,
    block: suspend CoroutineScope.() -> T,
): T

/**
 * [withLogContext] taking the attributes as pairs — `withLogContext("session.id" to id) { … }`.
 *
 * Identical binding, precedence and platform reach; this is only the spelling that
 * avoids a `mapOf` at the call site.
 */
public suspend fun <T> withLogContext(
    vararg attributes: Pair<String, String>,
    block: suspend CoroutineScope.() -> T,
): T = withLogContext(attributes.toMap(), block)
