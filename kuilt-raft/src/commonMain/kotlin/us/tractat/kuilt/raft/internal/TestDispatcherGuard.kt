package us.tractat.kuilt.raft.internal

import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.ContinuationInterceptor

/**
 * Emits a warning (or throws, when [strict] is `true`) if [scope] contains a
 * `kotlinx.coroutines.test.TestDispatcher`.
 *
 * Real-clock `delay()` loops never advance automatically under virtual time.
 * Any type in `kuilt-raft` that runs such a loop should call this at
 * construction time so misuse surfaces loudly rather than deadlocking silently.
 *
 * `TestDispatcher` is `internal` in the coroutines library, so the check is
 * class-name-based: "TestDispatcher" in the qualified name, or the package
 * prefix `kotlinx.coroutines.test.`.
 *
 * @param scope The scope to inspect.
 * @param typeName Short name of the calling type, used in the diagnostic message.
 * @param substitute The recommended test substitute (e.g. "FakeRaftNode").
 * @param strict When `true`, throw [IllegalStateException] instead of printing.
 * @param expectVirtualTime When `true`, the caller has explicitly validated that
 *   `UnconfinedTestDispatcher` is appropriate (real-clock `delay()` fires normally
 *   under it). Suppresses both the warning and the [strict] throw. Has no effect
 *   outside a test dispatcher context.
 */
internal fun checkNotUnderTestDispatcher(
    scope: CoroutineScope,
    typeName: String,
    substitute: String,
    strict: Boolean,
    expectVirtualTime: Boolean,
) {
    if (expectVirtualTime) return
    val interceptor = scope.coroutineContext[ContinuationInterceptor]
    val className = interceptor?.let { it::class.qualifiedName ?: it::class.simpleName ?: "" } ?: ""
    if (!isTestDispatcher(className)) return
    val msg = "$typeName constructed under a TestDispatcher ($className). " +
        "Real $typeName uses real-clock delay() — under virtual time, delays never advance " +
        "and your test will deadlock silently. Use $substitute for tests; " +
        "reserve real $typeName for integration tests that drive real time."
    if (strict) error(msg) else println("WARNING: $msg")
}

private fun isTestDispatcher(className: String): Boolean =
    "TestDispatcher" in className || className.startsWith("kotlinx.coroutines.test.")
