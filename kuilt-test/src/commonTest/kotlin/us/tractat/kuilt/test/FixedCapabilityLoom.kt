package us.tractat.kuilt.test

import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.TransportCapability

/**
 * A [Loom] that reports a fixed [declared] capability and refuses to weave.
 *
 * Stands in for a fabric that has *established* its verdict — the JVM `MultipeerPeerLinkFactory`
 * reporting [us.tractat.kuilt.core.FabricAvailability.Unavailable] because its dylib will not load
 * — so a decorator wrapping it can be pinned to forwarding that verdict rather than substituting
 * [Loom]'s confident roleless default (#1936).
 *
 * [weaveCount] pins the other half: [Loom.capability] is a **pre-connect** surface, read by
 * `CompositeLoom.weave` and `CompositeSeam.attachDesiredPly` *before* the delegate is woven, so a
 * forwarding decorator must not weave to answer it.
 */
internal class FixedCapabilityLoom(private val declared: TransportCapability) : Loom {
    var weaveCount: Int = 0
        private set

    override suspend fun weave(rendezvous: Rendezvous): Seam {
        weaveCount++
        error("FixedCapabilityLoom is capability-only and must not be woven")
    }

    override fun capability(): TransportCapability = declared
}
