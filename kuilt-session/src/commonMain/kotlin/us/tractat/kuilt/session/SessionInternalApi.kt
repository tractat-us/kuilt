package us.tractat.kuilt.session

/**
 * Marks what `:kuilt-session` shares with kuilt's own session test support, and nothing else. **It
 * carries no stability promise for consumers.**
 *
 * Some session types are built only by the room in production — an
 * [us.tractat.kuilt.session.admit.AdmissionRequest], for one, exists only after a joiner has proved
 * it holds its key. `:kuilt-session-test` still has to build them so an app can test its own code
 * against them, and Kotlin's `internal` cannot cross a module boundary, so those constructors are
 * `public` in the binary and gated here instead. An app reaches them through `:kuilt-session-test`'s
 * own entry points, not through this marker.
 *
 * An annotated declaration may change shape or disappear in any release, patch releases included.
 * Opting in from outside kuilt's own modules means depending on that.
 *
 * ```kotlin
 * kotlin { compilerOptions { optIn.add("us.tractat.kuilt.session.SessionInternalApi") } }
 * ```
 *
 * The opt-in level is `ERROR`, so using one of these without opting in does not compile. Java
 * callers do not see opt-in requirements at all, so on the JVM this is a convention, not a fence.
 */
@RequiresOptIn(
    message = "Session types only the room builds in production. No stability promise; they may change in any release.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
public annotation class SessionInternalApi
