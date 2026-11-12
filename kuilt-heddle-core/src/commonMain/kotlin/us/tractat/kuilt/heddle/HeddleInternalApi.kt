package us.tractat.kuilt.heddle

/**
 * Marks the protocol the entitlement ledger shares with heddle's own control plane, and nothing
 * else. **It carries no stability promise for consumers.**
 *
 * The ledger and the policy ship in `:kuilt-heddle-core` so they can be used without a network.
 * The control plane that drives the relocation fence lives in `:kuilt-heddle`, and it has to read
 * ledger state no consumer should: the slots a replica declares final on a fenced edge
 * ([SlotFinals]), the move derived from them ([Relocation]), and the edge projections that feed
 * both. Kotlin's `internal` cannot cross a module boundary, so those declarations are `public` in
 * the binary and gated here instead.
 *
 * An annotated declaration may change shape or disappear in any release, patch releases included.
 * Opting in from outside kuilt's own heddle modules means depending on that.
 *
 * ```kotlin
 * kotlin { compilerOptions { optIn.add("us.tractat.kuilt.heddle.HeddleInternalApi") } }
 * ```
 *
 * The opt-in level is `ERROR`, so using one of these without opting in does not compile. Java
 * callers do not see opt-in requirements at all, so on the JVM this is a convention, not a fence.
 */
@RequiresOptIn(
    message = "Heddle's ledger/control-plane protocol. No stability promise; it may change in any release.",
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
public annotation class HeddleInternalApi
