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
 * ## Platform behaviour — the guarantee is NOT the same everywhere
 *
 * The capture edge is a non-`suspend` callback, so what it reads is an
 * execution-local slot; the whole question on any platform is who last wrote that
 * slot. Only the JVM has a coroutine primitive that re-establishes it per dispatch,
 * so only there is the binding airtight — the same split, for the same reason, as
 * [withActiveTrace]'s.
 *
 * - **JVM / Android — the full guarantee.** [LogContextElement] (a
 *   `ThreadContextElement`) re-establishes the slot on *every* dispatch, so the
 *   binding follows [block] across thread hops, is inherited by child coroutines, and
 *   is restored on exit. Two scopes may interleave freely: whichever is running is the
 *   one the edge reads. "Enclosing" here is the true structural parent — it is read
 *   from the coroutine context, not from the thread.
 *
 * - **iOS / macOS / wasmJs — reliable for a line logged *synchronously within*
 *   [block], and no further.** `kotlinx.coroutines.ThreadContextElement` does not
 *   exist on Kotlin/Native or wasmJs (checked against coroutines 1.11.0), so the slot
 *   is set once on entry and nothing re-establishes it per dispatch. Two consequences,
 *   both ordinary rather than exotic for an app running concurrent sessions on one
 *   thread — an iOS app on `Dispatchers.Main` is exactly that:
 *     - **A scope that suspends and resumes while a sibling scope is mid-block on the
 *       same thread reads the *sibling's* attributes** (#2569). Its line is stamped
 *       with the sibling's session, not its own: a genuine mis-attribution, not a
 *       dropped stamp.
 *     - **"Enclosing" degrades to "whatever the thread last set"**, which may be a
 *       concurrent *sibling* rather than a lexical parent — so keys a sibling bound,
 *       and this scope never did, can be inherited into this scope's records. Keys
 *       this scope does set still win the collision, so its own are correct.
 *
 *   The identity-guarded restore bounds the damage but does not remove it: leaving a
 *   block can never *overwrite* a different scope's binding, and the slot is simply
 *   left until the next `withLogContext` on that thread supersedes it. Keep a
 *   session's logging synchronous within its block on these platforms. Closing the gap
 *   needs a per-dispatch hook Kotlin/Native does not currently offer; tracked in #2569.
 *
 * This remains a strict improvement everywhere over the process-global
 * [CaptureConfig.attributeMapper] — which is wrong for *every* line of *every*
 * non-armed session — but on Apple and wasmJs it is an improvement, not a guarantee.
 *
 * @param attributes the attributes to bind. Merged over the enclosing binding, this
 *   scope's own keys winning. On Apple/wasmJs read "enclosing" as *whatever the thread
 *   last set*, which may be a concurrent sibling — see the platform note above.
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
