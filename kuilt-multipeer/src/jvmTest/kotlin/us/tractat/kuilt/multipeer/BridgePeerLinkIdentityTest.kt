package us.tractat.kuilt.multipeer

import com.sun.jna.Pointer
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.multipeer.internal.BridgePeerLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The JVM bridge's half of #1821 — `BridgePeerLink` used to keep membership in a bare
 * `Set<PeerId>` mutated straight from the native peer-state callback's string, with the terminal
 * teardown decided by the set-equality test `remaining == setOf(selfId)`.
 *
 * The rig is [CapturingFakeMultipeerNativeLib.firePeerState], which hands the link **any** string
 * the native side could hand it — including the ones a conforming session never produces. That is
 * what stops these from being vacuous: the fake can emit a blank id, a duplicate id and the link's
 * own `selfId`, so "is it refused?" has two possible answers. [aDistinctRemoteIsAdmittedEvictedAndTears]
 * is the positive arm — without it a guard that refused *every* peer would pass every other test here.
 *
 * `BridgePeerLink` starts no timers and its state transitions are driven synchronously from the
 * callback, so these run without a coroutine harness; the only suspend surface (`close`) is unused.
 */
class BridgePeerLinkIdentityTest {

    private companion object {
        val SELF = PeerId("self")
        val REMOTE = PeerId("remote-peer")
        val SESSION_HANDLE: Pointer = Pointer(0xDEADBEEFL)
    }

    private fun newLink(lib: CapturingFakeMultipeerNativeLib): BridgePeerLink =
        BridgePeerLink(nativeLib = lib, sessionHandle = SESSION_HANDLE, selfId = SELF)

    /**
     * The positive control. A legitimate, distinct remote is admitted, flips the link Woven, and —
     * as the last remote — tears the seam when it drops. Every refusal below is only meaningful
     * against this.
     */
    @Test
    fun aDistinctRemoteIsAdmittedEvictedAndTears() {
        val lib = CapturingFakeMultipeerNativeLib()
        val link = newLink(lib)

        lib.firePeerState(REMOTE.value, isConnected = 1)
        assertEquals(setOf(SELF, REMOTE), link.peers.value, "a distinct remote joins the roster")
        assertIs<SeamState.Woven>(link.state.value, "the first remote flips Weaving → Woven")

        lib.firePeerState(REMOTE.value, isConnected = 0)
        assertEquals(setOf(SELF), link.peers.value, "the departed remote leaves the roster")
        assertIs<SeamState.Torn>(link.state.value, "losing the last remote tears the seam")
    }

    /**
     * A blank id is not a peer. Admitting one puts an unaddressable entry in `peers` — and, worse,
     * wedges the terminal teardown: the old `remaining == setOf(selfId)` test can never hold again
     * while a blank entry sits beside `selfId`, so the seam stays Woven after its last real peer is
     * gone, `incoming` never completes, and the owning factory's `ActiveSeamSlot` never frees.
     */
    @Test
    fun aBlankPeerIdIsNeverAdmittedAndNeverWedgesTheTeardown() {
        val lib = CapturingFakeMultipeerNativeLib()
        val link = newLink(lib)

        lib.firePeerState(REMOTE.value, isConnected = 1)
        lib.firePeerState("", isConnected = 1)

        assertEquals(
            setOf(SELF, REMOTE),
            link.peers.value,
            "a blank peer id must never reach the roster — it is unaddressable, and two blank " +
                "remotes would collapse onto one entry",
        )

        lib.firePeerState(REMOTE.value, isConnected = 0)
        assertIs<SeamState.Torn>(
            link.state.value,
            "losing the last REAL remote must tear the seam; a blank entry must not be able to " +
                "keep it Woven forever",
        )
    }

    /**
     * The link's own id, arriving as a remote — the #1466 self-dial. It must never be bound, and it
     * must not stop the teardown either: a session holding only `selfId` as a "remote" has no peer.
     */
    @Test
    fun theLinksOwnIdIsNeverAdmittedAsARemote() {
        val lib = CapturingFakeMultipeerNativeLib()
        val link = newLink(lib)

        lib.firePeerState(REMOTE.value, isConnected = 1)
        lib.firePeerState(SELF.value, isConnected = 1)

        assertEquals(setOf(SELF, REMOTE), link.peers.value, "a self-dial never registers as a remote")

        lib.firePeerState(REMOTE.value, isConnected = 0)
        assertIs<SeamState.Torn>(link.state.value, "a refused self-dial does not keep the seam alive")
    }

    /**
     * A self-dial that forms and drops **before any real peer has arrived** must leave the link
     * Weaving, not tear it down.
     *
     * The end-of-session test — "are there no bound remotes?" — cannot by itself tell *before the
     * first peer* from *after the last one*, and a self `.notConnected` reaching it unbinds nothing
     * and finds an empty registry either way. So the refusal has to run ahead of BOTH branches of
     * the callback, not just the `connected` one. `MCSessionLink`'s delegate says exactly this about
     * its own guard; the JVM half needs it for the same reason.
     */
    @Test
    fun aSelfDialOnANeverConnectedLinkDoesNotTearItDown() {
        val lib = CapturingFakeMultipeerNativeLib()
        val link = newLink(lib)

        // The self-link's form-then-drop lifecycle, on a link that has met nobody.
        lib.firePeerState(SELF.value, isConnected = 1)
        lib.firePeerState(SELF.value, isConnected = 0)

        assertIs<SeamState.Weaving>(
            link.state.value,
            "a self-dial is not a peer arriving and its drop is not the last peer leaving — the " +
                "link is still waiting for its first real remote",
        )
        assertEquals(setOf(SELF), link.peers.value, "and the roster is untouched")

        // And the link still works afterwards: a real peer can still arrive and be admitted.
        lib.firePeerState(REMOTE.value, isConnected = 1)
        assertIs<SeamState.Woven>(link.state.value, "a real peer still weaves the link")
    }

    /**
     * A duplicate `connected` for a peer already bound is the framework re-announcing, not a second
     * device: idempotent, and a single `disconnect` still ends the session. The complement of the
     * refusals — the registry must not turn a repeated callback into a second entry that then needs
     * two drops to clear.
     */
    @Test
    fun aRepeatedConnectedCallbackIsIdempotent() {
        val lib = CapturingFakeMultipeerNativeLib()
        val link = newLink(lib)

        lib.firePeerState(REMOTE.value, isConnected = 1)
        lib.firePeerState(REMOTE.value, isConnected = 1)
        assertEquals(setOf(SELF, REMOTE), link.peers.value, "a repeated connect adds no second entry")

        lib.firePeerState(REMOTE.value, isConnected = 0)
        assertIs<SeamState.Torn>(link.state.value, "one drop clears one bind — the seam tears")
    }
}
