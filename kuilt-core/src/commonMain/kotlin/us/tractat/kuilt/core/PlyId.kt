package us.tractat.kuilt.core

import kotlin.jvm.JvmInline

/** Stable identity of one constituent link ("ply") within a composite fabric. */
@JvmInline
public value class PlyId(public val value: String) {
    public companion object {
        /** The single ply of a non-composite (single-transport) fabric. */
        public val Sole: PlyId = PlyId("sole")
    }
}
