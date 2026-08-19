package us.tractat.kuilt.conformance.lattice

import us.tractat.kuilt.crdt.JsonCrdt
import us.tractat.kuilt.crdt.JsonNode
import us.tractat.kuilt.crdt.JsonValue
import us.tractat.kuilt.crdt.MVRegister
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

/**
 * Convergence stress-test for [JsonCrdt].
 *
 * Operations randomly mix:
 * - scalar puts (producing [JsonNode.Leaf])
 * - object puts (producing [JsonNode.Object])
 * - array puts (producing [JsonNode.Array]) — exercises cross-type conflicts
 * - key removals
 *
 * A fixed key pool forces concurrent type-changing and removal collisions across
 * replicas, exercising the full cross-type tiebreak under every delivery permutation.
 */
internal class JsonCrdtConvergenceTest : LatticeLawSuite<JsonCrdt>() {

    private val replicaIds = List(3) { ReplicaId("R$it") }
    private val keys = listOf("k0", "k1", "k2")

    /** The key the critical shape's three steps agree on — see the alphabet's comment. */
    private val focusKey get() = keys[0]

    override fun newHarness(): LatticeLawHarness<JsonCrdt> = LatticeLawHarness(
        initial = JsonCrdt.empty(replicaIds[0]),
        // The derived shape is `set-leaf · remove · set-obj` on the focus key: assert a scalar,
        // retire it, then re-assert a node of a *different* type. Coming back as an object rather
        // than another leaf is what makes the re-assertion visible — a lattice that let the retired
        // leaf survive would have to reconcile it against the object, and the cross-type tiebreak
        // is where that shows.
        alphabet = listOf(
            LatticeOp("set-leaf", OpKind.ASSERT) { state, replicaIndex, random ->
                val r = replicaIds[replicaIndex]
                state.withReplica(r).piece { it.set(focusKey, leafNode(r, "v${random.nextInt(4)}")) }
            },
            LatticeOp("set-obj", OpKind.ASSERT) { state, replicaIndex, _ ->
                val r = replicaIds[replicaIndex]
                state.withReplica(r).piece { it.set(focusKey, objNode(r, "f" to leafNode(r, "obj"))) }
            },
            LatticeOp("set-leaf-roam", OpKind.ASSERT) { state, replicaIndex, random ->
                val r = replicaIds[replicaIndex]
                state.withReplica(r).piece { it.set(keys[random.nextInt(keys.size)], leafNode(r, "v${random.nextInt(4)}")) }
            },
            LatticeOp("set-arr-roam", OpKind.ASSERT) { state, replicaIndex, random ->
                val r = replicaIds[replicaIndex]
                state.withReplica(r).piece { it.set(keys[random.nextInt(keys.size)], arrNode(r, leafNode(r, "item"))) }
            },
            LatticeOp("remove", OpKind.RETIRE) { state, replicaIndex, _ ->
                state.withReplica(replicaIds[replicaIndex]).piece { it.remove(focusKey) }
            },
            LatticeOp("remove-roam", OpKind.RETIRE) { state, replicaIndex, random ->
                state.withReplica(replicaIds[replicaIndex]).piece { it.remove(keys[random.nextInt(keys.size)]) }
            },
        ),
        // No-op ceiling tightened from the shared 25% default. Measured over seeds `0..15` — the
        // window `generatorIsNotVacuous` runs — this binding reads **11.5%**; the ceiling sits at
        // 15%, a 3.5-point margin. See `VacuityFloors.maxNoOpSteps` for the rule and for why the
        // shared default cannot do this job. What the 15% catches that 25% did not:
        //  - the leading assert removed from the pool builder: 18.6%, reds by 3.6 points.
        //  - NOT #2158's shape: retirement dead off replica 0 reads 13.7%, under this ceiling. The
        //    healthy and crippled rates are 2.2 points apart here, too narrow for a ceiling to
        //    separate them without reading as noise on the next generator edit.
        floors = VacuityFloors(maxNoOpSteps = 0.15),
        serializer = JsonCrdt.serializer(),
        replicaCount = 3,
        opsPerReplica = 8,
    )

    private fun leafNode(r: ReplicaId, v: String) =
        JsonNode.Leaf(MVRegister.empty<JsonValue>().set(r, JsonValue.Str(v)))

    private fun objNode(r: ReplicaId, vararg pairs: Pair<String, JsonNode>): JsonNode.Object {
        val map = pairs.fold(ORMap.empty<String, JsonNode>()) { acc, (k, v) -> acc.piece { it.put(r, k, v) } }
        return JsonNode.Object(map)
    }

    private fun arrNode(r: ReplicaId, vararg elements: JsonNode): JsonNode.Array {
        val rga = elements.foldIndexed(Rga.empty<JsonNode>()) { i, acc, elem ->
            val afterId = if (i == 0) RgaId.HEAD else acc.sequence.last()
            acc.insertAfter(r, afterId, elem).first
        }
        return JsonNode.Array(rga)
    }
}
