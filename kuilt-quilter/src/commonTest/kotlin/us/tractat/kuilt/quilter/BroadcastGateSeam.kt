package us.tractat.kuilt.quilter

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam

/**
 * Test-only [Seam] wrapper that discards [broadcast] frames while [dropBroadcasts] returns
 * true, and passes everything else — [sendTo], [incoming], [peers] — through untouched.
 *
 * This is the scalpel that [ChaosSeam] is not. `ChaosSeam(dropProbability = 1.0)` also
 * silences the delta path, but its `partitioned` gate black-holes *both* directions
 * including `sendTo`, which is the very channel anti-entropy runs on (`RootDigest` →
 * `FullStateRequest` → `FullState`). A test that wants "the delta path is dead, the
 * anti-entropy path is alive" — and wants to toggle that mid-run — needs exactly this.
 *
 * Why a toggle rather than a constructor flag: `deltaTargets` selects whom a [Quilter]
 * *garbage-collects against*, **not** whom it *sends to* ([Quilter.apply] broadcasts to
 * every peer regardless). So a test cannot make a peer delta-starved by dropping it from
 * `deltaTargets`; it has to silence the fabric. Closing this gate at the moment the target
 * set changes is what turns "and then it converges via anti-entropy" from narration into an
 * assertion (#2002).
 */
internal class BroadcastGateSeam(
    private val delegate: Seam,
    private val dropBroadcasts: () -> Boolean,
) : Seam by delegate {

    override suspend fun broadcast(payload: ByteArray) {
        if (dropBroadcasts()) return
        delegate.broadcast(payload)
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) = delegate.sendTo(peer, payload)
}
