package us.tractat.kuilt.warp

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * An **opaque fair-share lane tag** carried on a [TaskDescriptor].
 *
 * A lane is just a stable string name — `"acme/interactive"`, `"batch"`, whatever a
 * scheduler layered on top wants it to mean. Warp core assigns it **no** meaning: it
 * never inspects the tag, never routes on it, and never depends on any module that
 * interprets it. The tag rides the envelope so an *enforcement adapter* — the
 * `:kuilt-warp-heddle` satellite — can bind it to a fair-share leaf and gate execution
 * on entitlement. Warp without that satellite behaves exactly as if the field were not
 * there.
 *
 * The default [ROOT] lane means *"no lane"* — the untagged, un-gated path. A descriptor
 * that never touches a lane is byte-for-byte identical on the wire to a pre-lane
 * descriptor (CBOR omits a field left at its default), so adding the tag costs untagged
 * traffic nothing.
 *
 * @property tag the opaque lane name; the empty string is the [ROOT] (no-lane) sentinel.
 */
@Serializable
@JvmInline
public value class Lane(public val tag: String) {
    public companion object {
        /** The default *no-lane* sentinel — untagged, un-gated, today's behaviour. */
        public val ROOT: Lane = Lane("")
    }
}
