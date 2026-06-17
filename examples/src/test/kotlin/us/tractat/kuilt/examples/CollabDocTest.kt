@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.examples

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.JsonCrdt
import us.tractat.kuilt.crdt.JsonNode
import us.tractat.kuilt.crdt.JsonValue
import us.tractat.kuilt.crdt.MVRegister
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Example: collaborative JSON document sync using [JsonCrdt] + [Quilter].
 *
 * Two peers edit different fields of a shared document concurrently. Each peer
 * mutates its own replica locally; [Quilter] broadcasts the deltas and
 * both documents converge to a merged result that includes all edits.
 *
 * ## Why JsonCrdt fits
 *
 * - Concurrent edits to different keys always merge cleanly — no field can silently
 *   overwrite another.
 * - Nested objects merge recursively: Alice can add a "bio" key inside "profile"
 *   while Bob adds "location" inside the same object, and both keys survive.
 * - Concurrent scalar writes surface as multi-value registers so the application
 *   can detect and resolve the conflict explicitly rather than having one write win
 *   silently.
 *
 * ## API surface exercised
 *
 * - [InMemoryLoom] + `host`/`join` for in-process transport
 * - [Quilter] convenience factory with [JsonCrdt.serializer]
 * - [Quilter.mutate] with [JsonCrdt.set] for field edits
 * - [JsonCrdt.withReplica] to re-bind the local [ReplicaId] after the replicator
 *   provides the replica via [Quilter.replica]
 * - Nested [JsonNode.Object] merge showing add-wins at the object level
 */
class CollabDocTest {

    private val replicatorCfg = QuilterConfig(expectVirtualTime = true)

    @Test
    fun `two peers editing different top-level fields converge`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamAlice = loom.host(Pattern("collab-doc"))
            val seamBob = loom.join(InMemoryTag("bob"))

            val aliceDoc = Quilter(
                seamAlice,
                JsonCrdt.empty(ReplicaId(seamAlice.selfId.value)),
                JsonCrdt.serializer(),
                backgroundScope,
                config = replicatorCfg,
            )
            val bobDoc = Quilter(
                seamBob,
                JsonCrdt.empty(ReplicaId(seamBob.selfId.value)),
                JsonCrdt.serializer(),
                backgroundScope,
                config = replicatorCfg,
            )

            delay(1) // let collectors subscribe under StandardTestDispatcher

            // Alice sets the document title.
            aliceDoc.mutate { doc ->
                Patch(doc.withReplica(aliceDoc.replica).set("title", strLeaf(aliceDoc.replica, "Quarterly Report")))
            }

            // Bob concurrently sets the author field.
            bobDoc.mutate { doc ->
                Patch(doc.withReplica(bobDoc.replica).set("author", strLeaf(bobDoc.replica, "Bob")))
            }

            delay(10) // advance virtual time so all delta broadcasts deliver

            // Both peers must see both fields.
            assertEquals(setOf("title", "author"), aliceDoc.state.value.keys)
            assertEquals(aliceDoc.state.value.keys, bobDoc.state.value.keys)
        }

    @Test
    fun `concurrent edits to different nested fields both survive`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamAlice = loom.host(Pattern("nested-doc"))
            val seamBob = loom.join(InMemoryTag("bob"))

            val aliceDoc = Quilter(
                seamAlice,
                JsonCrdt.empty(ReplicaId(seamAlice.selfId.value)),
                JsonCrdt.serializer(),
                backgroundScope,
                config = replicatorCfg,
            )
            val bobDoc = Quilter(
                seamBob,
                JsonCrdt.empty(ReplicaId(seamBob.selfId.value)),
                JsonCrdt.serializer(),
                backgroundScope,
                config = replicatorCfg,
            )

            delay(1) // let collectors subscribe under StandardTestDispatcher

            // Alice and Bob each populate a different nested key in "profile".
            aliceDoc.mutate { doc ->
                val profile = JsonNode.Object(
                    ORMap.empty<String, JsonNode>().put(
                        aliceDoc.replica,
                        "name",
                        strLeaf(aliceDoc.replica, "Alice"),
                    ),
                )
                Patch(doc.withReplica(aliceDoc.replica).set("profile", profile))
            }
            bobDoc.mutate { doc ->
                val profile = JsonNode.Object(
                    ORMap.empty<String, JsonNode>().put(
                        bobDoc.replica,
                        "location",
                        strLeaf(bobDoc.replica, "London"),
                    ),
                )
                Patch(doc.withReplica(bobDoc.replica).set("profile", profile))
            }

            delay(10) // advance virtual time so all delta broadcasts deliver

            // Both "name" and "location" must survive inside the merged "profile" object.
            val aliceProfile = assertIs<JsonNode.Object>(aliceDoc.state.value["profile"])
            val bobProfile = assertIs<JsonNode.Object>(bobDoc.state.value["profile"])
            assertEquals(setOf("name", "location"), aliceProfile.map.keys)
            assertEquals(aliceProfile.map.keys, bobProfile.map.keys)
        }

    // ---- helpers ----

    private fun strLeaf(replica: ReplicaId, value: String): JsonNode.Leaf =
        JsonNode.Leaf(MVRegister.empty<JsonValue>().set(replica, JsonValue.Str(value)))
}
