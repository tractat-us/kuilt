@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package us.tractat.kuilt.websocket

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.JsFun
import kotlin.js.JsAny

@JsFun("() => navigator.onLine")
private external fun navigatorOnLine(): Boolean

/**
 * Register one handler for both transitions and **return the JS function that was registered**.
 *
 * Returning it is the whole point. Kotlin/Wasm hands JS a fresh wrapper per call, so a symmetric
 * `removeEventListener` given a re-wrapped Kotlin lambda would be handed a *different* function
 * object, remove nothing, and leak a listener per observer — silently, since removal reports no
 * failure. Holding the returned handle is the only way the pair can match.
 */
@JsFun(
    "(handler) => { " +
        "const f = () => handler(); " +
        "window.addEventListener('online', f); " +
        "window.addEventListener('offline', f); " +
        "return f; }",
)
private external fun addConnectivityListeners(handler: () -> Unit): JsAny

@JsFun(
    "(f) => { " +
        "window.removeEventListener('online', f); " +
        "window.removeEventListener('offline', f); }",
)
private external fun removeConnectivityListeners(registered: JsAny)

/**
 * The live browser binding behind [ConnectivityObserver] (#1725) — `navigator.onLine` plus the
 * `online` and `offline` window events.
 *
 * ```kotlin
 * val connectivity = browserConnectivityObserver()
 * val loom = KtorClientLoom(httpClient, connectivity = connectivity)
 * // …
 * connectivity.close()
 * ```
 *
 * ## Why "online" is reported as [NetworkReachability.Indeterminate], not [NetworkReachability.Reachable]
 * The two directions of this signal are not equally trustworthy, so this observer does not treat
 * them as if they were.
 *
 * `navigator.onLine === false` is worth acting on: every engine sets it when the machine has no
 * usable interface at all, and no browser reports a *false* offline. That becomes
 * [NetworkReachability.Unreachable] — a definite verdict.
 *
 * `navigator.onLine === true` means only that the user agent believes it has *an* interface. It is
 * famously `true` behind a captive portal, on a LAN with no uplink, on a machine whose VPN just
 * dropped, and on a laptop plugged into a switch attached to nothing. Mapping it to `Reachable`
 * would hand `Room.localFabric` a confident "your connection is fine" in exactly the situations a
 * user most needs the opposite — the #1712 fabricated verdict, from the one platform whose signal
 * is least able to support it. So it becomes [NetworkReachability.Indeterminate], which folds to
 * [us.tractat.kuilt.core.FabricAvailability.Unknown] with a reason saying why.
 *
 * The consequence is worth stating plainly: on wasmJs this fabric never reports `Available`. It
 * moves between "cannot tell" and a definite "offline", which is strictly more than the roleless
 * floor it replaces and is the most the platform can honestly support. A browser fabric that wants
 * a real positive verdict needs an application-level reachability probe, which is a different
 * mechanism and not a capability one — see [ConnectivityObserver].
 *
 * ## Lifecycle
 * [close] removes both listeners and is idempotent. It removes the exact JS function
 * [addConnectivityListeners] registered — see that declaration for why re-wrapping would silently
 * remove nothing.
 *
 * **Unguarded by any test in this repo**, like its Android sibling: the repo has no linter to read
 * this file statically (#2540), and CI has no browser harness for this module.
 * `WebSocketSeamCapabilityTest` proves the
 * seam's reaction to a reading; that the browser *emits* one is not provable from here.
 */
public class BrowserConnectivityObserver internal constructor() : ConnectivityObserver {

    private val _reachability = MutableStateFlow<NetworkReachability?>(null)

    override val reachability: StateFlow<NetworkReachability?> = _reachability.asStateFlow()

    /**
     * The registered JS listener, or `null` when not listening — the handle and the flag in one
     * field, so the two cannot disagree about whether a removal is owed.
     *
     * A plain `var` needs no atomic here: a wasm browser target has a single JS event loop, so
     * [start] and [close] cannot interleave. That is a property of the platform rather than a
     * convenience, and it is why this file may do what `AndroidConnectivityObserver` may not.
     */
    private var registered: JsAny? = null

    /** Read `navigator.onLine` once and subscribe to its transitions. Idempotent. */
    internal fun start() {
        if (registered != null) return
        registered = addConnectivityListeners { publish() }
        publish()
    }

    /** Remove both listeners. Idempotent, and safe without a preceding [start]. */
    public fun close() {
        val handle = registered ?: return
        registered = null
        removeConnectivityListeners(handle)
    }

    private fun publish() {
        _reachability.value =
            if (navigatorOnLine()) NetworkReachability.Indeterminate else NetworkReachability.Unreachable
    }
}

/**
 * A started [BrowserConnectivityObserver] for [KtorClientLoom]'s `connectivity` parameter (#1725).
 *
 * **The caller owns it**: keep the reference and call [BrowserConnectivityObserver.close] when the
 * looms built from it are done, or every construction adds another pair of window listeners.
 *
 * Read [BrowserConnectivityObserver] before wiring this — a browser can report a definite *offline*
 * but never a trustworthy *online*, so this lane reports `Unavailable` or `Unknown` and never
 * `Available`.
 */
public fun browserConnectivityObserver(): BrowserConnectivityObserver =
    BrowserConnectivityObserver().apply { start() }
