package us.tractat.kuilt.mdns

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CompletableDeferred
import us.tractat.kuilt.core.PeerId

/**
 * Registers a peer as a DNS-SD service so that other peers on the
 * same LAN can discover it via Android's [NsdManager].
 *
 * Mirrors the JVM JmDNS-backed [MDNSServiceAdvertiser] interface:
 * - [register] suspends until `NsdManager` confirms registration via
 *   [NsdManager.RegistrationListener.onServiceRegistered], then returns.
 * - [unregister] removes the registration. Safe to call if [register] was
 *   never called or has already failed.
 *
 * **Service type:** [SERVICE_TYPE] (`_fireworks._tcp.` — NsdManager format,
 * no trailing `.local.`). This matches the browse type used by
 * [MDNSServiceDiscoverer].
 *
 * **TXT records:** all v1 + v2 keys from [MDNSAdvertisement] are written so
 * that both v1 and v2 peers can parse the advertisement.
 *
 * @param nsdManager The system NSD manager — obtain via
 *   `context.getSystemService(NsdManager::class.java)` and inject via Koin.
 * @param displayName Human-readable service name (shown in NSD browsers).
 * @param port TCP port the local WebSocket server listens on.
 * @param selfId This peer's [PeerId] — embedded in TXT records for joiners.
 * @param wsPath WebSocket path to advertise (default: [MDNSAdvertisement.DEFAULT_WS_PATH]).
 * @param hostOs OS family of this host — written as [MDNSAdvertisement.TXT_KEY_HOST_OS].
 *   Defaults to [MDNSAdvertisement.HostOs.Android].
 * @param fabrics Comma-separated transport labels this host accepts (e.g. `"ws"`).
 * @param gameMinVersion Minimum game-protocol version this host accepts.
 * @param gameMaxVersion Maximum game-protocol version this host accepts.
 */
public class MDNSServiceAdvertiser(
    private val nsdManager: NsdManager,
    private val displayName: String,
    private val port: Int,
    private val selfId: PeerId,
    private val wsPath: String = MDNSAdvertisement.DEFAULT_WS_PATH,
    private val hostOs: MDNSAdvertisement.HostOs? = MDNSAdvertisement.HostOs.Android,
    private val fabrics: String? = null,
    private val gameMinVersion: Int? = null,
    private val gameMaxVersion: Int? = null,
) {
    private var registrationListener: NsdManager.RegistrationListener? = null

    /**
     * Registers the service and suspends until [NsdManager] confirms.
     *
     * Throws [NsdRegistrationException] if [NsdManager] reports a failure via
     * [NsdManager.RegistrationListener.onRegistrationFailed].
     *
     * Idempotent — calling while already registered unregisters first, then
     * re-registers.
     */
    public suspend fun register() {
        if (registrationListener != null) unregister()
        val serviceInfo = buildServiceInfo()
        val result = CompletableDeferred<Unit>()
        val listener =
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    result.complete(Unit)
                }

                override fun onRegistrationFailed(
                    info: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    result.completeExceptionally(NsdRegistrationException(errorCode))
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) {}

                override fun onUnregistrationFailed(
                    info: NsdServiceInfo,
                    errorCode: Int,
                ) {}
            }
        registrationListener = listener
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        result.await()
    }

    /**
     * Unregisters the service. Safe to call if [register] was never called or
     * if registration failed. Idempotent.
     */
    public fun unregister() {
        registrationListener?.let {
            runCatching { nsdManager.unregisterService(it) }
        }
        registrationListener = null
    }

    private fun buildServiceInfo(): NsdServiceInfo =
        NsdServiceInfo().apply {
            serviceName = displayName
            serviceType = SERVICE_TYPE
            port = this@MDNSServiceAdvertiser.port
            buildTxtMap(selfId, wsPath, hostOs, fabrics, gameMinVersion, gameMaxVersion)
                .forEach { (key, value) -> setAttribute(key, value) }
        }

    public companion object {
        // NsdManager expects the type without a trailing .local. suffix.
        // This matches the browse type used by MDNSServiceDiscoverer on Android.
        public const val SERVICE_TYPE: String = "_fireworks._tcp."
    }
}

/** Thrown when [NsdManager] reports registration failure. */
public class NsdRegistrationException(
    public val errorCode: Int,
) : Exception("NsdManager registration failed with error code $errorCode")
