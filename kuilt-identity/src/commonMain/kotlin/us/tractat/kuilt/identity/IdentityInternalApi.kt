package us.tractat.kuilt.identity

/**
 * Marks the statements only kuilt's own session protocol needs, and nothing else. **It carries no
 * stability promise for consumers.**
 *
 * The admission exchange signs and checks statements — a host's challenge, a joiner's proof — whose
 * layouts are defined here so that the bytes signed and the bytes verified come from one place. The
 * room code in `:kuilt-session` has to build and read them, and Kotlin's `internal` cannot cross a
 * module boundary, so those declarations are `public` in the binary and gated here instead.
 *
 * An annotated declaration may change shape or disappear in any release, patch releases included.
 * Opting in from outside kuilt's own modules means depending on that.
 *
 * ```kotlin
 * kotlin { compilerOptions { optIn.add("us.tractat.kuilt.identity.IdentityInternalApi") } }
 * ```
 *
 * The opt-in level is `ERROR`, so using one of these without opting in does not compile. Java
 * callers do not see opt-in requirements at all, so on the JVM this is a convention, not a fence.
 */
@RequiresOptIn(
    message = "kuilt's admission-protocol statements. No stability promise; they may change in any release.",
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
public annotation class IdentityInternalApi
