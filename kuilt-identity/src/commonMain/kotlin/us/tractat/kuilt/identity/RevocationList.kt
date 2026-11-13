package us.tractat.kuilt.identity

/** Skeleton. */
public class RevocationList private constructor() {
    public fun notBefore(subject: DidKey): Long? = TODO()

    public suspend fun revoke(subject: DidKey, notBefore: Long): Unit = TODO()

    public companion object {
        public fun inMemory(): RevocationList = RevocationList()
    }
}
