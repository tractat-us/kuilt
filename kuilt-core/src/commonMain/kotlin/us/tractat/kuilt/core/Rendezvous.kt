package us.tractat.kuilt.core

/**
 * Describes the role a [Loom] peer takes when weaving a session.
 *
 * Pass to [Loom.weave] instead of calling the individual [Loom.host] /
 * [Loom.join] helpers. ADR-002.
 */
public sealed interface Rendezvous {
    /** Host / start a new session. */
    public data class New(public val pattern: Pattern) : Rendezvous

    /** Join an existing session by its advertisement. */
    public data class Existing(public val tag: Tag) : Rendezvous
}
