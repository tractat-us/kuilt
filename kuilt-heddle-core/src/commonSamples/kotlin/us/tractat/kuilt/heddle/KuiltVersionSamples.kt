package us.tractat.kuilt.heddle

/**
 * Samples for [KuiltVersion], cited by its generated KDoc and by the agent cookbook.
 *
 * Compiled as part of commonTest, so a rename breaks the build rather than the page.
 */

/**
 * Stamp a saved ledger with the kuilt that wrote it, without depending on `:kuilt-core`, so a later
 * reader can tell which release made it.
 */
@Suppress("unused")
internal fun sampleKuiltVersion() {
    // Save the version beside the ledger bytes it describes.
    val ledgerHeader = mapOf("writtenBy" to KuiltVersion.VERSION)

    // A later run compares it with the kuilt it is running now.
    val writtenByThisKuilt = ledgerHeader["writtenBy"] == KuiltVersion.VERSION
    check(writtenByThisKuilt)
}
