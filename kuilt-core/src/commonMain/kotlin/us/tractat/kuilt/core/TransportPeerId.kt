package us.tractat.kuilt.core

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Stable, comparable identifier for one peer within one session. */
@Serializable
@JvmInline
public value class TransportPeerId(
    public val value: String,
)
