package us.tractat.kuilt.demo.workspace

import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.test.FaultProfile
import us.tractat.kuilt.test.FaultyLoom
import us.tractat.kuilt.test.FaultySeam

/**
 * The test-side [WorkspaceNetwork]: every actor on one `FaultyLoom(InMemoryLoom())`. Each actor's link
 * runs under its own [faults] profile. [cut] drops everything in and out of that link, and [restore]
 * puts the actor's own profile back rather than forcing `Healthy`, so a lossy link stays lossy after
 * an outage.
 */
class FaultyNetwork(
    scope: CoroutineScope,
    private val faults: (ActorId) -> FaultProfile = { FaultProfile.Healthy },
) : WorkspaceNetwork {
    private val loom = FaultyLoom(InMemoryLoom(), scope)
    private val seams = linkedMapOf<ActorId, FaultySeam>()

    override suspend fun weave(actor: ActorId): Seam {
        val seam = if (seams.isEmpty()) loom.host(Pattern("workspace")) else loom.join(InMemoryTag(actor.value))
        seam.setFaultProfile(faults(actor))
        seams[actor] = seam
        return seam
    }

    override fun cut(actor: ActorId): Unit = seams.getValue(actor).partition()

    override fun restore(actor: ActorId): Unit = seams.getValue(actor).setFaultProfile(faults(actor))
}
