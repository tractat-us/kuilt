package us.tractat.kuilt.identity

import platform.posix.ENOENT
import platform.posix.errno
import platform.posix.rename
import platform.posix.symlink
import platform.posix.unlink
import us.tractat.kuilt.conformance.DeviceKeyStoreConformanceSuite
import us.tractat.kuilt.conformance.DeviceKeyStoreFixture
import us.tractat.kuilt.store.NSFileManagerDurableStore

/**
 * The macOS file store against the whole [DeviceKeyStoreConformanceSuite], as a
 * [DeviceKeyStoreFixture.Durable] store over a fresh temporary directory per fixture, on the real file
 * system. Every reopen is a brand-new store over that directory, sharing no memory with the last, so
 * what it answers came off the disk.
 *
 * Each damage hook writes the file the way that failure would leave it:
 *
 * - **corrupt key:** the key file cut to half its length, so it is no longer JSON;
 * - **mismatched key:** a well-formed JWK whose `x` and `y` are one fresh key's and whose `d` is
 *   another's, so it decodes and fails only the self-test;
 * - **cleared key:** the key file deleted;
 * - **interrupted write:** half a JWK left in `device-key.jwk.Ab12Cd.tmp`, a name of the shape the
 *   store's `mkstemps` template `device-key.jwk.XXXXXX.tmp` gives its own temporary files, the one
 *   file a crash mid-write leaves, beside whatever key file is there (`LeftResidue`);
 * - **corrupt grants:** three bytes that are not a grants record, written through the grant store
 *   itself;
 * - **unreadable key:** the key file a symbolic link to itself, so opening it fails with `ELOOP`
 *   while the key itself waits aside, untouched (`Rigged`).
 */
class MacosFileDeviceKeyStoreConformanceTest : DeviceKeyStoreConformanceSuite() {
    override suspend fun newFixture(): DeviceKeyStoreFixture {
        val directory = FileKeyMedium(freshDirectory("kuilt-device-key")).directory
        val keyFile = "$directory/${FileKeyMedium.KEY_FILE}"
        return DeviceKeyStoreFixture.Durable(
            reopen = { beforeKeyIsMade -> fileDeviceKeyStore(directory, beforeKeyIsMade) },
            corruptKey = {
                val held = checkNotNull(readFile(keyFile)) { "precondition: a key file to corrupt" }
                writeFile(keyFile, held.copyOf(held.size / 2))
            },
            plantMismatchedKey = {
                val (jwk, claimed) = mismatchedJwk()
                writeFile(keyFile, jwk)
                claimed
            },
            clearKey = { check(unlink(keyFile) == 0) { "precondition: there was a key file to clear (errno=$errno)" } },
            interruptKeyWrite = {
                // The shape mkstemps gives the store's own temporary files: six random characters.
                val residue = "$keyFile.Ab12Cd.tmp"
                val half = SoftwareDeviceKey.generate().encodeJwk().let { it.copyOf(it.size / 2) }
                writeFile(residue, half)
                DeviceKeyStoreFixture.KeyWriteInterruption.LeftResidue { readFile(residue) }
            },
            corruptGrants = { NSFileManagerDurableStore(directory).write(AppleDeviceKeyStore.GRANTS, byteArrayOf(9, 9, 9)) },
            makeKeyUnreadable = { loopKeyFile(keyFile) },
        )
    }

    /** The key file replaced by a symbolic link to itself, the key kept aside until `restore`. */
    private fun loopKeyFile(keyFile: String): DeviceKeyStoreFixture.UnreadableKey {
        val aside = "$keyFile.held-by-test"
        check(rename(keyFile, aside) == 0) { "precondition: a key file to set aside (errno=$errno)" }
        check(symlink(FileKeyMedium.KEY_FILE, keyFile) == 0) { "precondition: the loop was made (errno=$errno)" }
        return DeviceKeyStoreFixture.UnreadableKey.Rigged {
            check(unlink(keyFile) == 0 || errno == ENOENT)
            check(rename(aside, keyFile) == 0) { "the key was put back (errno=$errno)" }
        }
    }
}
