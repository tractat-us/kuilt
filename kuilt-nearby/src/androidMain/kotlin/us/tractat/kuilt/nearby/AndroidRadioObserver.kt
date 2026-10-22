package us.tractat.kuilt.nearby

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.google.android.gms.common.ConnectionResult as GmsConnectionResult

/**
 * The live Android observer behind [NearbyApi.radioState] (#1543) — the thing that takes a
 * [NearbySeam]'s capability off the [us.tractat.kuilt.core.FabricAvailability.Unknown] floor.
 *
 * ## Why the radio-state broadcasts, and not `ConnectivityManager.NetworkCallback`
 * A `NetworkCallback` observes **networks** — an associated link that carries traffic, normally
 * filtered on `NET_CAPABILITY_INTERNET`. Nearby Connections needs no such thing: `P2P_STAR`
 * bootstraps over BLE and Bluetooth Classic and only later upgrades onto Wi-Fi Direct or a Wi-Fi
 * hotspot, none of which produce a `TRANSPORT_WIFI` network with internet capability. A device with
 * Wi-Fi powered but joined to no access point is a perfectly good Nearby peer, and a NetworkCallback
 * would report it unusable — a fabricated verdict, which is exactly what #1712 forbids. The radio
 * **power** broadcasts observe the thing the fabric actually depends on, so those are what we watch:
 *  - [BluetoothAdapter.ACTION_STATE_CHANGED] — the Bluetooth adapter powering on/off.
 *  - [WifiManager.WIFI_STATE_CHANGED_ACTION] — the Wi-Fi radio powering on/off.
 *
 * ## Why Play services is polled rather than observed
 * The issue asked for a "Play-services Nearby listener"; there is no such surface.
 * `ConnectionsClient` exposes only per-operation callbacks (connection lifecycle, payload,
 * discovery) and `GoogleApiAvailability.isGooglePlayServicesAvailable` is a one-shot poll with no
 * observer. So the GMS half of the verdict is **re-read on every radio transition** instead: a
 * runtime that has gone away is picked up at the next transition, and a missing runtime makes both
 * radios [NearbyRadioStatus.Unsupported] — accurate, because [NearbyRadioStatus] describes what is
 * usable *by this fabric*, not what hardware exists.
 *
 * ## Permissions
 * Reading the Bluetooth adapter's state needs `BLUETOOTH_CONNECT` on API 31+, and runtime
 * permissions are the consuming app's responsibility (see [GmsNearbyApi]). An ungranted permission
 * therefore yields [NearbyRadioStatus.Unknown] rather than a crash or a confident "off" — and
 * [NearbyRadioState.toAvailability] carries that through as
 * [us.tractat.kuilt.core.FabricAvailability.Unknown], never a definite `Unavailable`. A radio we
 * were not permitted to read may well be on.
 *
 * ## Lifecycle
 * [GmsNearbyApi] starts observation eagerly and binds [close] to its owner's scope. Closing is
 * permanent: a later [start] cannot reopen the observer. If registration is in flight, the
 * registering thread releases the receiver as soon as the platform returns. Both explicit close
 * and scope cancellation use this same path.
 */
internal class AndroidRadioObserver(private val appContext: Context) {

    private val _radioState = MutableStateFlow<NearbyRadioState?>(null)

    /** Latest-value radio state; `null` until [start] takes the first reading. */
    val radioState: StateFlow<NearbyRadioState?> = _radioState.asStateFlow()

    private enum class State { NEW, REGISTERING, REGISTERED, CLOSED }

    private val lock = reentrantLock()
    private var state = State.NEW

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = publish()
    }

    /**
     * Subscribe to the radio-state broadcasts and publish an immediate first reading, so a seam
     * woven before any transition still gets a verdict rather than sitting on the floor.
     */
    fun start() {
        lock.withLock {
            if (state != State.NEW) return
            state = State.REGISTERING
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        // NO export flag, deliberately — and specifically NOT `RECEIVER_NOT_EXPORTED`.
        //
        // Android 14's flag requirement applies only when a receiver is NOT registered exclusively
        // for system broadcasts; both actions here are `<protected-broadcast>` entries, so the
        // exemption genuinely holds and there is nothing a flag could protect (a protected broadcast
        // cannot be sent by another app).
        //
        // `RECEIVER_NOT_EXPORTED` would be actively WRONG: it is a delivery filter, not
        // belt-and-braces. Some system broadcasts come from highly privileged apps that are part of
        // the framework but do NOT run under the system UID — Bluetooth is the documented example
        // (`com.android.bluetooth`, UID 1002, not system_server). AMS drops those for a
        // NOT_EXPORTED receiver, so on API 33+ we would silently stop seeing
        // ACTION_STATE_CHANGED while WIFI_STATE_CHANGED_ACTION (from system_server) kept arriving.
        // That asymmetry is invisible to casual testing and would freeze `Seam.capability` at a
        // stale `Available` after the user switches Bluetooth off — precisely the fabricated verdict
        // #1712 exists to prevent, and one this fabric's `reportsLiveCapability = true` now vouches
        // for. Receiving all system broadcasts requires the flag to be absent or EXPORTED.
        try {
            appContext.registerReceiver(receiver, filter)
        } catch (failure: Throwable) {
            lock.withLock { state = State.CLOSED }
            throw failure
        }
        // Never unregister before the platform has finished registering. close() marks an
        // in-flight registration CLOSED; this thread then owns its sole unregister call.
        val release = lock.withLock {
            if (state == State.CLOSED) true else {
                state = State.REGISTERED
                false
            }
        }
        if (release) appContext.unregisterReceiver(receiver) else publish()
    }

    /** Permanently close observation. Safe before, during, or after [start], and idempotent. */
    fun close() {
        val release = lock.withLock {
            val wasRegistered = state == State.REGISTERED
            state = State.CLOSED
            wasRegistered
        }
        if (release) appContext.unregisterReceiver(receiver)
    }

    private fun publish() {
        val gmsReady = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(appContext) == GmsConnectionResult.SUCCESS
        val reading =
            if (gmsReady) {
                NearbyRadioState(bluetooth = bluetoothStatus(), wifi = wifiStatus())
            } else {
                // No Nearby runtime ⇒ neither radio is usable BY THIS FABRIC, whatever the hardware.
                NearbyRadioState(NearbyRadioStatus.Unsupported, NearbyRadioStatus.Unsupported)
            }
        lock.withLock {
            // A queued broadcast or an in-flight read must not revive a closed observer.
            if (state == State.REGISTERED) _radioState.value = reading
        }
    }

    private fun bluetoothStatus(): NearbyRadioStatus {
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: return NearbyRadioStatus.Unsupported
        return try {
            when (adapter.state) {
                BluetoothAdapter.STATE_ON -> NearbyRadioStatus.On
                BluetoothAdapter.STATE_OFF -> NearbyRadioStatus.Off
                // TURNING_ON / TURNING_OFF: mid-transition, so claim neither. The broadcast that
                // carries the settled state is already on its way.
                else -> NearbyRadioStatus.Unknown
            }
        } catch (_: SecurityException) {
            // BLUETOOTH_CONNECT not granted (API 31+). Unreadable is NOT off.
            NearbyRadioStatus.Unknown
        }
    }

    private fun wifiStatus(): NearbyRadioStatus {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return NearbyRadioStatus.Unsupported
        return try {
            when (wifi.wifiState) {
                WifiManager.WIFI_STATE_ENABLED -> NearbyRadioStatus.On
                WifiManager.WIFI_STATE_DISABLED -> NearbyRadioStatus.Off
                // ENABLING / DISABLING / UNKNOWN: mid-transition or unreadable — claim neither.
                else -> NearbyRadioStatus.Unknown
            }
        } catch (_: SecurityException) {
            NearbyRadioStatus.Unknown
        }
    }
}
