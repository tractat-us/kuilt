package us.tractat.kuilt.demo

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * One square of the shared quilt, addressed by its grid position.
 */
@Serializable
data class Cell(val x: Int, val y: Int)

/**
 * The colour of a patch, as a CSS-style hex string (e.g. `"#e94f37"`).
 */
@Serializable
@JvmInline
value class Colour(val hex: String)

/**
 * Wall-clock source for last-writer-wins stitch timestamps.
 *
 * Time is a dependency: production callers pass the platform clock, tests pass
 * a controlled counter. [PatchworkSession] additionally bumps each stitch past
 * the previous one, so per-session timestamps are strictly monotonic even if
 * the source stalls — satisfying [us.tractat.kuilt.crdt.LWWMap]'s
 * `(replica, timestamp)` tag-uniqueness precondition.
 */
fun interface StitchClock {
    fun nowMillis(): Long
}
