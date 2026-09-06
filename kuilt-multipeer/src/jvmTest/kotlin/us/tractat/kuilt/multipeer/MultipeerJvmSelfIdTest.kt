package us.tractat.kuilt.multipeer

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.freshPeerId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The JVM half of #1430. The JVM factory does not own an identity — the seam lives
 * inside the macOS dylib — so "the caller's `selfId` is the wire identity" is only
 * true here if two separate things hold: the id **crosses the cdecl ABI** into
 * `mc_runtime_create`, and this side **derives its own id from the wire name** the
 * native runtime reports, exactly as a remote peer would.
 *
 * Both are asserted, and the second is asserted with a wire name that deliberately
 * disagrees with the supplied `selfId`. Without that arm the whole test would pass
 * against a `resolveSelfId` that simply returned its input, which is the version of
 * this code that would silently disagree with every remote if the dylib ever went
 * stale.
 */
class MultipeerJvmSelfIdTest {
    @Test
    fun `the caller's selfId crosses the ABI into mc_runtime_create`() {
        val lib = FakeMultipeerNativeLib()
        val selfId = freshPeerId()
        val factory =
            MultipeerPeerLinkFactory(
                displayName = "Mac Mini",
                serviceType = "kuilt-t1430",
                injectedLib = lib,
                injectedRuntimeHandle = null,
                selfId = selfId,
            )
        factory.requireRuntimeHandle()
        assertEquals(selfId.value, lib.createdSelfId, "the runtime must be created with the caller's identity")
    }

    @Test
    fun `a woven seam derives its selfId from the wire name, not from the whole string`() =
        runTest {
            val lib = FakeMultipeerNativeLib()
            val selfId = freshPeerId()
            lib.wireDisplayName = "Mac Mini#${selfId.value}"
            val factory =
                MultipeerPeerLinkFactory(
                    displayName = "Mac Mini",
                    serviceType = "kuilt-t1430",
                    injectedLib = lib,
                    injectedRuntimeHandle = null,
                    selfId = selfId,
                )
            val seam = factory.weave(Rendezvous.New(Pattern("room")))
            assertEquals(selfId, seam.selfId)
            factory.close()
        }

    @Test
    fun `the wire name wins over the supplied selfId when they disagree`() =
        runTest {
            // What a stale dylib looks like: it advertised an identity this side did not
            // choose. Remotes see the wire name, so this side must too — anything else is
            // a seam whose `selfId` nobody else agrees with. This arm is also the positive
            // control for the test above: `resolveSelfId` returning its input passes there
            // and fails here.
            val lib = FakeMultipeerNativeLib()
            lib.wireDisplayName = "Mac Mini#legacy-identity"
            val factory =
                MultipeerPeerLinkFactory(
                    displayName = "Mac Mini",
                    serviceType = "kuilt-t1430",
                    injectedLib = lib,
                    injectedRuntimeHandle = null,
                    selfId = freshPeerId(),
                )
            val seam = factory.weave(Rendezvous.New(Pattern("room")))
            assertEquals(PeerId("legacy-identity"), seam.selfId)
            factory.close()
        }

    @Test
    fun `a native lib that reports no wire name falls back to the supplied selfId`() =
        runTest {
            val lib = FakeMultipeerNativeLib() // wireDisplayName stays null → writes nothing
            val selfId = freshPeerId()
            val factory =
                MultipeerPeerLinkFactory(
                    displayName = "Mac Mini",
                    serviceType = "kuilt-t1430",
                    injectedLib = lib,
                    injectedRuntimeHandle = null,
                    selfId = selfId,
                )
            val seam = factory.weave(Rendezvous.New(Pattern("room")))
            assertEquals(selfId, seam.selfId, "the fallback must be the caller's id, not the display name")
            factory.close()
        }
}
