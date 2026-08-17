package us.tractat.kuilt.multipeer

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.MultipeerConnectivity.MCEncryptionRequired
import platform.MultipeerConnectivity.MCPeerID
import platform.MultipeerConnectivity.MCSession
import platform.MultipeerConnectivity.MCSessionSendDataMode
import platform.MultipeerConnectivity.MCSessionState

/**
 * A multi-endpoint `MCSession` bus: the Apple analogue of this module's JVM
 * `DeliveringFakeMultipeerNativeLib`, one layer lower down.
 *
 * ## Why a fake `MCSession` and not the real radio
 *
 * `MCSessionLink` is `appleMain` and takes a concrete `MCSession`, which only ever receives peers,
 * data or state callbacks from Apple's framework on live hardware — a real `MCSession` built in a
 * unit test is inert, and a real connection needs a second physical device on the same Wi-Fi. That
 * is why the existing `appleTest`s (`MCSessionLinkTearCollapseTest`,
 * `MultipeerPeerLinkFactoryTerminalDropTest`) drive the *delegate* directly and assert one
 * behaviour apiece, and why `MultipeerConformanceTest` — the module's only `SeamConformanceSuite`
 * subclass until now — is a `jvmTest` that reaches `BridgePeerLink` instead.
 *
 * Kotlin/Native can subclass an Objective-C class, and the override is dispatched through the
 * framework type, so [FakeMCSession] **is** an `MCSession` whose three outbound calls
 * (`connectedPeers`, `sendData:toPeers:withMode:error:`, `disconnect`) are intercepted. That is the
 * whole seam that was needed: everything inbound already arrives through `MCSessionLink.delegate`,
 * which this bus calls exactly as the framework would.
 *
 * ## What the bus models
 *
 * Endpoints keyed by `MCPeerID`. [connect] fires `MCSessionStateConnected` at every delegate for
 * every other endpoint (the completed MC handshake). `sendData` from one endpoint is delivered to
 * each addressee's `didReceiveData`, attributed to the sender's own `MCPeerID`. `disconnect` fires
 * `MCSessionStateNotConnected` at **both** ends of each link — the disconnector sees its peer
 * leave, and the remote sees the disconnector go — which is what a real `MCSession.disconnect()`
 * produces and what drives the remote's last-peer self-drop teardown.
 *
 * ## What it deliberately does NOT model
 *
 * Framework-queue asynchrony. Real MC fires its delegate on a private queue; this bus calls the
 * delegate inline on the caller's coroutine. That is the same simplification the JVM delivering
 * fake makes, and it is what keeps the suite deterministic under virtual time — but it means this
 * harness proves the link's *logic*, never its behaviour under a genuinely concurrent delegate.
 */
@OptIn(ExperimentalForeignApi::class)
internal class FakeMCSessionBus {

    private class Endpoint(val peer: MCPeerID, val session: FakeMCSession)

    private val endpoints = mutableListOf<Endpoint>()

    /** Mint a session for [peer] and register it on the bus. */
    fun session(peer: MCPeerID): FakeMCSession =
        FakeMCSession(peer, this).also { endpoints += Endpoint(peer, it) }

    /**
     * Complete the virtual MC handshake: every registered endpoint sees every *other* endpoint go
     * `MCSessionStateConnected`, so each `MCSessionLink` binds the other in its peer registry and
     * flips [us.tractat.kuilt.core.SeamState.Weaving] → [us.tractat.kuilt.core.SeamState.Woven].
     */
    fun connect() {
        endpoints.forEach { local ->
            local.session.connected = endpoints.filterNot { it === local }.map { it.peer }
        }
        endpoints.forEach { local ->
            local.session.connected.forEach { remote ->
                local.session.delegate?.session(local.session, remote, MCSessionState.MCSessionStateConnected)
            }
        }
    }

    /**
     * Kill the transport under **every** live endpoint with no `close()` anywhere: each end simply
     * observes its remotes go `MCSessionStateNotConnected`, the way a real session dies when the
     * radio drops rather than when the application asks. Returns `false` if there was nothing live
     * to drop, so a caller can report honestly instead of claiming an injection it did not perform.
     */
    fun dropTransport(): Boolean {
        val live = endpoints.filter { it.session.connected.isNotEmpty() }
        if (live.isEmpty()) return false
        val departures = live.map { it to it.session.connected }
        live.forEach { it.session.connected = emptyList() }
        departures.forEach { (local, remotes) ->
            remotes.forEach { remote ->
                local.session.delegate?.session(local.session, remote, MCSessionState.MCSessionStateNotConnected)
            }
        }
        return true
    }

    /** Deliver [data] from [from] to every endpoint named in [toPeers]. */
    fun route(from: MCPeerID, data: NSData, toPeers: List<*>) {
        val targets = toPeers.filterIsInstance<MCPeerID>().map { it.displayName }.toSet()
        endpoints.filter { it.peer.displayName in targets }.forEach { target ->
            target.session.delegate?.session(target.session, data, from)
        }
    }

    /**
     * Model `MCSession.disconnect()` on [from]'s session: both ends of every link it holds observe
     * `MCSessionStateNotConnected`. Idempotent — an endpoint with no connected peers routes
     * nothing, so the suite's close-idempotency obligation cannot re-fire a departure at a peer
     * that already saw one.
     */
    fun disconnect(from: MCPeerID) {
        val local = endpoints.firstOrNull { it.peer.displayName == from.displayName } ?: return
        val remotes = local.session.connected
        local.session.connected = emptyList()
        remotes.forEach { remotePeer ->
            local.session.delegate?.session(local.session, remotePeer, MCSessionState.MCSessionStateNotConnected)
            val remote = endpoints.firstOrNull { it.peer.displayName == remotePeer.displayName } ?: return@forEach
            if (remote === local) return@forEach
            remote.session.connected = remote.session.connected.filterNot { it.displayName == from.displayName }
            remote.session.delegate?.session(remote.session, local.peer, MCSessionState.MCSessionStateNotConnected)
        }
    }
}

/**
 * An `MCSession` whose three outbound framework calls are routed through a [FakeMCSessionBus].
 *
 * It really is an `MCSession` — Kotlin/Native subclasses the Objective-C class, so `MCSessionLink`
 * holds it at the framework type and every call it makes lands here. The delegate the link
 * installs is stored by the superclass and read back through `MCSession.delegate`, so the bus
 * calls exactly the delegate the framework would.
 */
@OptIn(ExperimentalForeignApi::class)
internal class FakeMCSession(
    private val owner: MCPeerID,
    private val bus: FakeMCSessionBus,
) : MCSession(peer = owner, securityIdentity = null, encryptionPreference = MCEncryptionRequired) {

    /** The peers this endpoint currently believes are connected; the bus owns the value. */
    var connected: List<MCPeerID> = emptyList()

    override fun connectedPeers(): List<*> = connected

    override fun sendData(
        data: NSData,
        toPeers: List<*>,
        withMode: MCSessionSendDataMode,
        error: CPointer<ObjCObjectVar<NSError?>>?,
    ): Boolean {
        bus.route(from = owner, data = data, toPeers = toPeers)
        return true
    }

    override fun disconnect() {
        bus.disconnect(owner)
    }
}
