package us.tractat.kuilt.core

import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.ContinuationInterceptor

/**
 * Emits a warning (or throws, when [strict] is `true`) if [scope] contains a
 * `kotlinx.coroutines.test.TestDispatcher`.
 *
 * Types that own a real-clock `delay()` loop (e.g. an election timer, an anti-entropy
 * ticker) should call this at construction time. Under virtual time those delays never
 * advance automatically, causing tests to deadlock silently rather than failing with a
 * clear message. Calling this guard surfaces the misuse immediately, at the moment the
 * type is constructed.
 *
 * `TestDispatcher` is `internal` in the coroutines library, so detection is class-name-based:
 * "TestDispatcher" in the qualified name, or the package prefix `kotlinx.coroutines.test.`.
 *
 * @param scope The scope to inspect.
 * @param typeName Short name of the calling type, used in the diagnostic message.
 * @param substitute The recommended test substitute (e.g. "FakeRaftNode").
 * @param strict When `true`, throw [IllegalStateException] instead of printing.
 * @param expectVirtualTime When `true`, the caller has explicitly validated that a
 *   `TestDispatcher` is appropriate (e.g. an `UnconfinedTestDispatcher` where real-clock
 *   `delay()` fires normally, or a test that advances virtual time manually). Suppresses
 *   both the warning and the [strict] throw. Has no effect outside a test dispatcher context.
 */
public fun checkNotUnderTestDispatcher(
    scope: CoroutineScope,
    typeName: String,
    substitute: String,
    strict: Boolean,
    expectVirtualTime: Boolean,
) {
    if (expectVirtualTime) return
    val interceptor = scope.coroutineContext[ContinuationInterceptor]
    val className = interceptor?.let { it::class.qualifiedName ?: it::class.simpleName ?: "" } ?: ""
    if (!isTestDispatcherClass(className)) return
    val msg = "$typeName constructed under a TestDispatcher ($className). " +
        "Real $typeName uses real-clock delay() — under virtual time, delays never advance " +
        "and your test will deadlock silently. Use $substitute for tests; " +
        "reserve real $typeName for integration tests that drive real time."
    if (strict) error(msg) else println("WARNING: $msg")
}

private fun isTestDispatcherClass(className: String): Boolean =
    "TestDispatcher" in className || className.startsWith("kotlinx.coroutines.test.")
