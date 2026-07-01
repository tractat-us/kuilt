package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.CoroutineScope

/**
 * Run [block] with [trace] as the active trace for the log lines it emits, then
 * restore the previously-active trace. The whole point of native trace context:
 * whoever starts a span wraps the work, and kuilt's sampling gate then stamps or
 * drops those lines by that trace — on wasmJs, iOS and macOS, not only the JVM.
 *
 * The trace is read by [CoroutineContextTraceProvider] at the synchronous capture
 * edge, so wire that provider into `installLogCapture` for this to take effect.
 *
 * ## Platform behaviour
 *
 * The reach of the trace differs by platform, because only the JVM has a coroutine
 * primitive that mirrors a value across thread hops:
 * - **JVM / Android** — propagated across coroutine dispatches and inherited by
 *   child coroutines (a thread-context element), and the prior trace is restored on
 *   exit. Reliable even when [block] suspends and resumes on another thread.
 * - **wasmJs** — single-threaded, so fully reliable within [block].
 * - **iOS / macOS** — reliable for a log emitted **synchronously within the span**
 *   (the common case). If [block] suspends and resumes on a *different* worker
 *   thread, the trace is **not** propagated to that thread — no Kotlin/Native
 *   primitive can mirror a thread-local across coroutine dispatches. The restore is
 *   identity-guarded, so a hop can never corrupt an unrelated coroutine's trace; the
 *   setting thread's slot is simply left until the next `withActiveTrace` supersedes
 *   it.
 *
 * @sample us.tractat.kuilt.otel.logging.sampleWithActiveTrace
 */
public expect suspend fun <T> withActiveTrace(trace: ActiveTrace, block: suspend CoroutineScope.() -> T): T
