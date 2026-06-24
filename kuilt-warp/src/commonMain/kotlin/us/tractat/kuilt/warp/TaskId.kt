package us.tractat.kuilt.warp

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * An opaque, stable identifier for a single unit of work in the warp task scheduler.
 *
 * Task IDs are the keys into the work queue (`ORSet<TaskId>`) and the results board
 * (`ORMap<TaskId, LWWRegister<Result>>`). They travel with the work and must be
 * stable across nodes — the same physical task must present the same [value] on
 * every peer that ever sees it.
 *
 * A string that names the task's content (a hash of the input, a UUID, a filename)
 * is a natural choice.
 */
@Serializable
@JvmInline
public value class TaskId(public val value: String)
