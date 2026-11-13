package us.tractat.kuilt.identity

/** Not yet written: the failing-tests commit. */
internal object RevocationListLayout {
    fun encode(cutoffs: Map<DidKey, Long>): ByteArray = TODO("the failing-tests commit")

    fun decode(bytes: ByteArray): Map<DidKey, Long>? = TODO("the failing-tests commit")
}
