package us.tractat.kuilt.identity

import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * This process's Application Support directory: `~/Library/Application Support` on a Mac (inside the
 * container for a sandboxed app), and the app container's `Library/Application Support` on iOS. Neither
 * roams to another machine; both are copied by a backup, which each store's KDoc weighs.
 */
internal fun applicationSupportDirectory(): String {
    val paths = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
    return checkNotNull(paths.firstOrNull() as? String) { "kuilt-identity: this process has no Application Support directory" }
}

/** The directory a store for [appId] keeps its files in, under [applicationSupport]. */
internal fun identityDirectory(applicationSupport: String, appId: String): String =
    "${applicationSupport.trimEnd('/')}/$appId/$IDENTITY_DIRECTORY"

/** The folder kuilt-identity keeps to itself inside the app's own Application Support folder. */
internal const val IDENTITY_DIRECTORY: String = "kuilt-identity"
