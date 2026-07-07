package us.tractat.kuilt.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.QuilterConfig

/**
 * One peer's view of a shared Patchwork quilt — the collaborative canvas at the
 * heart of the kuilt demo app. (TDD stub — implementation lands next commit.)
 */
class PatchworkSession(
    private val loom: Loom,
    val stitcher: ReplicaId,
    private val scope: CoroutineScope,
    private val clock: StitchClock,
    private val quilterConfig: QuilterConfig = QuilterConfig(),
) {
    private val _quilt = MutableStateFlow<Map<Cell, Colour>>(emptyMap())

    /** The merged canvas as everyone should render it: cell → colour, live. */
    val quilt: StateFlow<Map<Cell, Colour>> = _quilt.asStateFlow()

    private val _connected = MutableStateFlow(false)

    /** Whether this peer is currently online (woven into a session). */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** Hosts a new quilt session. */
    suspend fun host(pattern: Pattern): Unit = connect(Rendezvous.New(pattern))

    /** Joins an existing quilt session. */
    suspend fun join(tag: Tag): Unit = connect(Rendezvous.Existing(tag))

    /** Goes online: weaves a seam from [loom] and replicates the board over it. */
    suspend fun connect(rendezvous: Rendezvous) {
        TODO("slice 2: not yet implemented")
    }

    /** Stitches a patch: sets [cell] to [colour] on the shared quilt. */
    fun stitch(cell: Cell, colour: Colour) {
        TODO("slice 2: not yet implemented")
    }

    /** Goes offline (tunnel mode): tears the seam but keeps the local board. */
    suspend fun disconnect() {
        TODO("slice 2: not yet implemented")
    }
}
