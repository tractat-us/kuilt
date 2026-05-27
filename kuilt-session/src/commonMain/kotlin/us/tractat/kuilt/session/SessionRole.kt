package us.tractat.kuilt.session

/** The role this peer plays in the room. */
public enum class SessionRole {
    /** This peer hosted the room (called [RoomFactory.host]). */
    Host,

    /** This peer joined an existing room (called [RoomFactory.join]). */
    Joiner,
}
