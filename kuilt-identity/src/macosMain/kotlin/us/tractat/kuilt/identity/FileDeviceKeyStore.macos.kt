package us.tractat.kuilt.identity

import us.tractat.kuilt.store.NSFileManagerDurableStore

/**
 * This device's key store on macOS: the key in a file only this user can read, and the grants beside
 * it, in `~/Library/Application Support/<appId>/kuilt-identity` (inside the app's container when it is
 * sandboxed). Opening it again with the same [appId] after a restart is the same device; calling it
 * twice, or from two processes, gives stores that are one device.
 *
 * Not the Keychain (Decided 28). On macOS a Keychain item can be kept to this device only in the
 * data-protection keychain, which needs an app signed by a team with a provisioning profile; the login
 * keychain an unsigned binary reaches ignores that setting and travels with a backup anyway.
 *
 * ## The trade: a backup carries the key to the next Mac
 *
 * Application Support never roams between machines while both are in use, but **Time Machine backs it
 * up, and Migration Assistant copies it to a new Mac** — from that backup or straight from the old
 * Mac. After a migration the old Mac and the new one hold one key and are one device, until one of
 * them deletes its key file (`device-key.jwk`) and starts again as a new device. kuilt does not
 * exclude the directory from Time Machine: that would also lose the key when someone restores this
 * same Mac from its backup. An app that would rather a restore make a new device can exclude the
 * directory itself (`NSURLIsExcludedFromBackupKey`).
 *
 * ## The key file
 *
 * `device-key.jwk` is a JWK carrying `x`, `y` and `d`, created with mode `0600`, written to a temporary
 * file, flushed and renamed into place so it always holds a whole key. It is read back with a
 * sign-then-verify self-test, named from the file's own `x` and `y`. A key file that was read and is not
 * a P-256 JWK, or fails the self-test, is moved aside to `device-key.jwk.refused-<n>` and replaced with
 * a new key, with one warning in the log and no throw. A key file that is there but cannot be read — no
 * permission, a symbolic-link loop, an I/O error — throws [IllegalStateException] and nothing is
 * written: replacing it would turn a passing fault into a new identity. Deleting the key file is the
 * deliberate way to start again.
 *
 * Opening a store creates its directory. Key and grant calls do file I/O, and wait for another
 * process's lock, on the caller's dispatcher.
 *
 * @param appId one path segment naming the app: letters, digits, `.`, `_` and `-`, and neither `.` nor
 *   `..`. On a case-insensitive volume, the default, two ids differing only in case name one device.
 * @throws IllegalArgumentException when [appId] is not one such segment.
 */
public fun DeviceKeyStore.Companion.platform(appId: String): DeviceKeyStore =
    macosDeviceKeyStore(appId, applicationSupportDirectory()) {}

/** [platform] under [applicationSupport]: the tests' way in, away from the real home directory. */
internal fun macosDeviceKeyStore(
    appId: String,
    applicationSupport: String,
    beforeKeyIsMade: suspend () -> Unit,
): DeviceKeyStore {
    requireAppId(appId)
    return fileDeviceKeyStore(identityDirectory(applicationSupport, appId), beforeKeyIsMade)
}

/** A store keeping its key file and grants in [directory]. [beforeKeyIsMade] is the conformance suite's gate. */
internal fun fileDeviceKeyStore(directory: String, beforeKeyIsMade: suspend () -> Unit): DeviceKeyStore {
    val medium = FileKeyMedium(directory)
    return AppleDeviceKeyStore(
        medium = medium,
        grantStore = NSFileManagerDurableStore(medium.directory),
        mediumId = "file:${medium.directory}",
        beforeKeyIsMade = beforeKeyIsMade,
    )
}
