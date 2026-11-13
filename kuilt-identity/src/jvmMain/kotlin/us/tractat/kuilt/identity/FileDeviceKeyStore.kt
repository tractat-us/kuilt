package us.tractat.kuilt.identity

import us.tractat.kuilt.store.StoreKey
import java.nio.file.Path

/** Not yet: the tests come first (#3224). */
public class FileDeviceKeyStore internal constructor(
    @Suppress("unused") private val directory: Path,
    @Suppress("unused") private val beforeKeyIsMade: suspend () -> Unit,
) : DeviceKeyStore {
    public constructor(directory: Path) : this(directory, {})

    override suspend fun deviceKey(): DeviceKey = TODO("#3224")

    override suspend fun grants(): List<Grant> = TODO("#3224")

    override suspend fun keepGrant(grant: Grant): Unit = TODO("#3224")

    internal companion object {
        const val KEY_FILE: String = "device-key.jwk"
        val GRANTS: StoreKey = StoreKey("grants")
    }
}

/** Not yet: the tests come first (#3224). */
public fun DeviceKeyStore.Companion.platform(appId: String, directory: Path): DeviceKeyStore = TODO("#3224 $appId $directory")
