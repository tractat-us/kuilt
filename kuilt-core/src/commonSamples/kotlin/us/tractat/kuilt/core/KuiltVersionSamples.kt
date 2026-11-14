package us.tractat.kuilt.core

/**
 * Samples for [KuiltVersion], cited by this module's generated `KuiltVersion` KDoc and by the agent cookbook.
 *
 * Compiled as part of commonTest, so a rename breaks the build rather than the page.
 */

/**
 * Stamp saved state with the kuilt that wrote it, so a later reader can tell which release made a
 * snapshot without a version string someone copied by hand.
 */
@Suppress("unused")
internal fun sampleKuiltVersion() {
    // Save the version beside the bytes it describes.
    val snapshotHeader = mapOf("writtenBy" to KuiltVersion.VERSION)

    // A later run compares it with the kuilt it is running now.
    val writtenByThisKuilt = snapshotHeader["writtenBy"] == KuiltVersion.VERSION
    check(writtenByThisKuilt)
}
