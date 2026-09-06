package us.tractat.kuilt.websocket

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

// Explicitly named, per `InternalLoggerNameGuardTest`: an empty-lambda logger takes its name from
// the enclosing class, which the Native self-capture exclusion (#1003) cannot key on.
private val logger = KotlinLogging.logger("us.tractat.kuilt.websocket.AndroidConnectivityObserver")

/**
 * The live Android binding behind [ConnectivityObserver] (#1725) — a
 * [ConnectivityManager.NetworkCallback] watching whether this device currently has a network that
 * can carry a WebSocket to a relay.
 *
 * Build it with [androidConnectivityObserver], hand it to a loom, and [close] it when done:
 *
 * ```kotlin
 * val connectivity = androidConnectivityObserver(context)
 * val loom = KtorClientLoom(httpClient, connectivity = connectivity)
 * // …
 * connectivity.close()
 * ```
 *
 * ## Why `NetworkCallback` here, when `:kuilt-nearby` deliberately refused it
 * `:kuilt-nearby` watches Bluetooth and Wi-Fi *radio power* instead, because Nearby bootstraps over
 * BLE and upgrades onto Wi-Fi Direct — neither of which yields a network, so a `NetworkCallback`
 * would call a perfectly good Nearby peer unusable. The WebSocket fabric is the exact opposite
 * case: it needs a routable network to reach a relay and nothing else, which is precisely what a
 * `NetworkCallback` observes. Same rule, opposite answer — watch what the fabric depends on.
 *
 * ## What is required of a network, and what is not
 * A network counts only with [NetworkCapabilities.NET_CAPABILITY_VALIDATED] as well as
 * [NetworkCapabilities.NET_CAPABILITY_INTERNET]. `INTERNET` alone is not enough: it means the
 * network *claims* to route, and is `true` on a captive portal that will swallow the WebSocket
 * upgrade. `VALIDATED` is Android's own end-to-end probe — the closest the platform comes to the
 * question actually being asked.
 *
 * No transport is required, deliberately: Wi-Fi, cellular, Ethernet and a VPN are all fine ways to
 * reach a relay, and naming one would report a device on the "wrong" transport as unreachable.
 *
 * ## The states, and the one that cannot be reached from here
 * [NetworkReachability.Reachable] and [NetworkReachability.Unreachable] both occur.
 * [NetworkReachability.Indeterminate] does **not** — this platform gives a definite answer, and
 * inventing a shrug it never expressed would be its own fabrication. The pre-[start] value is
 * `null` ("nothing observed yet"), a distinct fact again, folding to the same honest `Unknown`
 * floor an unwired binding reports.
 *
 * Note the asymmetry in what a verdict means. `Unreachable` says *this device* has no network. It
 * says nothing about whether the relay at the far end is alive, which is a peer-liveness question
 * for `:kuilt-liveness`; conflating them would tell a user with five bars that their network is
 * down (see [NetworkReachability]).
 *
 * ## Permission
 * `android.permission.ACCESS_NETWORK_STATE` is declared by this module's own manifest and merges
 * into the consuming app, so nothing is asked of a caller. It is a *normal*, install-time
 * permission: granted automatically, no runtime prompt, and it reveals only whether a network
 * exists — not what travels over it. This differs from `:kuilt-nearby`, which leaves its Bluetooth
 * permissions to the app because those are **runtime** permissions needing a user prompt, and a
 * library cannot decide when to interrupt a user.
 *
 * A consumer may still remove it (`tools:node="remove"`), so [start] degrades to `null` on a
 * [SecurityException] rather than throwing out of loom construction — the fabric keeps working and
 * only the live capability is lost.
 *
 * ## Thread safety
 * The platform delivers callbacks on its own thread while [start] seeds from the constructing one
 * and [close] may arrive from a third, so [usable] is guarded by an explicit lock rather than left
 * to whichever thread happens to touch it. Each mutation and the publish it implies are one
 * critical section: a set read outside the lock could observe a half-applied transition and freeze
 * `capability` on a value no reading ever produced.
 *
 * **Unguarded by any test in this repo.** There is no device or emulator in CI, no unit test
 * reaches `registerNetworkCallback`, and the repo has no linter to read this file statically
 * (#2540). A fake-injected signal proves the seam's *reaction* — that is
 * `WebSocketSeamCapabilityTest`'s job — never this file's *emission*. This rests on the platform
 * contract above and on review.
 */
public class AndroidConnectivityObserver internal constructor(
    appContext: Context,
) : ConnectivityObserver {

    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _reachability = MutableStateFlow<NetworkReachability?>(null)

    override val reachability: StateFlow<NetworkReachability?> = _reachability.asStateFlow()

    // CAS, not a plain flag: `close` is public and callable from any thread while `start` runs on
    // the constructing one, so check-then-set would race — and this also makes a double
    // unregister (which the platform throws on) unreachable.
    private val registered = AtomicBoolean(false)

    private val lock = Any()

    /**
     * The networks currently satisfying the request, guarded by [lock].
     *
     * A set rather than a boolean because a device holds several at once — Wi-Fi plus cellular plus
     * a VPN — which come and go independently. With a boolean, the `onLost` for one would clear a
     * flag another network is still holding up, reporting a dual-homed phone as offline the moment
     * it left Wi-Fi.
     */
    private val usable = mutableSetOf<Network>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = update { usable += network }

        override fun onLost(network: Network) = update { usable -= network }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) = update {
            // A network already matching the request can lose VALIDATED — walking out of range, or
            // onto a captive portal — without ever being `onLost`. Re-reading here is what stops
            // `capability` freezing at a stale `Available`.
            if (networkCapabilities.isUsable()) usable += network else usable -= network
        }
    }

    /**
     * Subscribe, and publish an immediate first reading so a seam woven before any transition gets
     * a verdict instead of sitting on the floor. Idempotent.
     *
     * Reports `null` forever — never a fabricated `Unavailable` — on the two ways this can fail to
     * observe anything: a device exposing no [ConnectivityManager], and a host app that has not
     * declared `ACCESS_NETWORK_STATE`. Unreadable is not unreachable, and a definite verdict we
     * cannot substantiate is the #1712 defect (see the permission note on the class).
     */
    internal fun start() {
        val manager = connectivityManager ?: return
        if (!registered.compareAndSet(false, true)) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            manager.registerNetworkCallback(request, callback)
            // Seed from the active network. `registerNetworkCallback` does report already-connected
            // networks, but asynchronously; seeding closes the window in which a seam woven in the
            // same tick reads the floor on a device that is plainly online.
            update {
                val active = manager.activeNetwork
                if (active != null && manager.getNetworkCapabilities(active).isUsable()) {
                    usable += active
                }
            }
        } catch (denied: SecurityException) {
            // ACCESS_NETWORK_STATE not declared by the host app. Undo the CAS so `close` does not
            // try to unregister a callback that was never accepted — which would throw in turn —
            // and leave `reachability` at `null`, the honest "cannot tell".
            registered.set(false)
            logger.warn(denied) {
                "kuilt-websocket connectivity observer disabled: the host app must declare " +
                    "android.permission.ACCESS_NETWORK_STATE. Seam.capability stays Unknown."
            }
        }
    }

    /** Unregister. Idempotent, and safe without a preceding [start]. */
    public fun close() {
        if (!registered.compareAndSet(true, false)) return
        // No try/catch: `unregisterNetworkCallback` throws only for a callback that was never
        // registered, which the CAS makes unreachable.
        connectivityManager?.unregisterNetworkCallback(callback)
    }

    private inline fun update(mutate: () -> Unit) {
        synchronized(lock) {
            mutate()
            _reachability.value =
                if (usable.isEmpty()) NetworkReachability.Unreachable else NetworkReachability.Reachable
        }
    }

    private fun NetworkCapabilities?.isUsable(): Boolean =
        this != null &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

}

/**
 * A started [AndroidConnectivityObserver] for [KtorClientLoom]/[KtorServerLoom]'s `connectivity`
 * parameter (#1725).
 *
 * The observer holds a process-lifetime network callback registered against the application
 * context. **The caller owns it**: keep the reference and call [AndroidConnectivityObserver.close]
 * when the looms built from it are done, or every construction adds another subscription.
 *
 * @param context any [Context]; only its application context is retained, so this captures no
 *   Activity and outlives none.
 */
public fun androidConnectivityObserver(context: Context): AndroidConnectivityObserver =
    AndroidConnectivityObserver(context.applicationContext).apply { start() }
