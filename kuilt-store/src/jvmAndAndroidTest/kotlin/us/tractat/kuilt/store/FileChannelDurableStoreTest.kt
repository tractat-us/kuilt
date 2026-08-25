package us.tractat.kuilt.store

import us.tractat.kuilt.conformance.DurableStoreConformanceSuite
import us.tractat.kuilt.conformance.DurableStoreFilenameConformanceSuite
import us.tractat.kuilt.conformance.RestartFixture
import java.io.File

/**
 * Verifies [FileChannelDurableStore] satisfies the whole [DurableStoreConformanceSuite], **on both
 * the JVM and Android**.
 *
 * In `jvmAndAndroidTest`, not `jvmTest`, because `FileChannelDurableStore` lives in
 * `jvmAndAndroidMain` and Android is the target an app actually depends on for durable storage.
 * A subclass in `jvmTest` would leave the Android variant compiled and never run — the suite would
 * be green on a target it had never executed against.
 *
 * Every test this class used to hold by hand — absent key, round trip, overwrite, delete, the
 * defensive copy, the large and empty payloads, independent keys, and the "crash" simulated by a
 * second instance over the same directory — is now a property of the shared contract, checked here
 * and on the other three backends alike. Nothing was dropped; the suite states each of them at least
 * as strongly, and several considerably more so (an overwrite that *shrinks*, a key set built to
 * collide, a restart that also has to remember a delete).
 *
 * The six **filename** properties (#2506) that used to sit below — the legacy fold, the orphaned
 * legacy files the fix deliberately does not migrate, the `.tmp` sidecar namespace, and the
 * case-insensitive filesystem — went the same way one release later, into
 * [DurableStoreFilenameConformanceSuite], which [FileChannelDurableStoreFilenameTest] binds. They
 * were duplicated verbatim in the Apple backend's test file, which is the shape that produced
 * #2506 in the first place (#2515).
 */
class FileChannelDurableStoreTest : DurableStoreConformanceSuite() {

    /**
     * The directory each store was given, so [restart] can reopen it.
     *
     * Keyed on the store instance rather than tracked in a single field because the suite's
     * [twoFreshStoresDoNotShareState] holds two live stores at once, and a single field would hand
     * the second store's directory back for a restart of the first.
     */
    private val directories = mutableMapOf<DurableStore, File>()

    override suspend fun newStore(): DurableStore {
        val dir = freshTempDir("kuilt-store-conformance")
        return FileChannelDurableStore(dir).also { directories[it] = dir }
    }

    /**
     * A restart, modelled the way this store's own contract defines one: a **brand-new instance over
     * the same directory**. It shares no in-memory state with the original, so everything it answers
     * came off the disk the original wrote to.
     */
    override suspend fun restart(store: DurableStore): RestartFixture =
        RestartFixture.Durable(
            FileChannelDurableStore(
                requireNotNull(directories[store]) { "restart() was handed a store this fixture did not create" },
            ),
        )
}
