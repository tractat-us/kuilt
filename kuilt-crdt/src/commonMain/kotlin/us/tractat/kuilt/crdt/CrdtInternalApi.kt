package us.tractat.kuilt.crdt

/**
 * Marks a CRDT entry point that kuilt's own modules need and a consumer should not reach for.
 * **It carries no stability promise for consumers.**
 *
 * Such an entry point steps outside a guarantee the type otherwise keeps. [Rga.advanceClock] is
 * the case it was made for: it moves a replica's seq past ids no op carries, which is what a log
 * kept across several keys needs after one of them comes back unreadable, and it breaks
 * [RgaId.seq]'s density for everyone else. Kotlin's `internal` cannot cross a module boundary, so
 * the declaration is `public` in the binary and gated here instead.
 *
 * An annotated declaration may change shape or disappear in any release, patch releases included.
 * Opting in from outside kuilt's own modules means depending on that.
 *
 * ```kotlin
 * kotlin { compilerOptions { optIn.add("us.tractat.kuilt.crdt.CrdtInternalApi") } }
 * ```
 *
 * The opt-in level is `ERROR`, so using one of these without opting in does not compile. Java
 * callers do not see opt-in requirements at all, so on the JVM this is a convention, not a fence.
 */
@RequiresOptIn(
    message = "A CRDT entry point for kuilt's own modules. No stability promise; it may change in any release.",
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
public annotation class CrdtInternalApi
