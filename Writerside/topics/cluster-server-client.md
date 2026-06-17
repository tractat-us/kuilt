# Server-Cluster Topology

Use `kuilt-cluster` when you need a **server-cluster**: a small set of servers
running consensus with many learner-clients that submit actions and observe the
committed log. Fault tolerance (number of voters) and client count are decoupled.

## The two-tier overlay

The cluster is a two-tier overlay network:

- **Voter core** — a complete graph `K_m` of `m` servers (m = 1/3/5). Every
  voter pair is directly linked; leader election, quorum, and commitment happen
  entirely here.
- **Learner periphery** — many clients, each attached to the core through one or
  more server links. Clients never vote and never count toward quorum.

Each client's **attachment degree** `d` is the number of distinct servers it
holds a live link to. The current facade uses `d = 1` (star leaf): one live
link plus a static endpoint list to round-robin to on tear.

**Safety is topology-independent.** Raft consistency depends only on the voter
quorum; a client's `d` — even momentarily `0` during failover — can never
threaten it. Client connectivity is an availability dial, not a correctness one.

## Add the dependency

```kotlin
implementation(platform("us.tractat.kuilt:kuilt-bom:VERSION"))
implementation("us.tractat.kuilt:kuilt-cluster")
```

`kuilt-cluster` is published for JVM and Android. `ServerCluster` (the server
facade) is JVM/Android-only; `ClusterClient` and `VoterMesh` are multiplatform.

## Stand up a server

`serverCluster()` wires `m` voter nodes in-process (complete-graph channel
transport) and mounts a `KtorRoomHost` relay accept loop. Voter nodes start
immediately; call `start()` in a `launch` to run the accept loop:

<!-- verbatim from examples/src/test/kotlin/us/tractat/kuilt/examples/ServerClusterE2ETest.kt#`ClusterClient propose commits end-to-end through ServerCluster facade` -->
```kotlin
val serverScope = CoroutineScope(coroutineContext + Job())
val cluster = serverScope.serverCluster(
    host = host,
    voterIds = listOf(voterId),
    raftConfig = raftCfg,
)

// Collect committed entries from the voter mesh before start() runs.
serverScope.launch {
    cluster.committed
        .first { it is Committed.Entry }
        .let { committed ->
            serverCommittedPayload.complete(
                (committed as Committed.Entry).entry.command,
            )
        }
}

// Launch the relay accept loop. ServerCluster.start() holds each admitted
// room open via awaitCancellation() — the WebSocket stays alive until
// serverScope is cancelled below.
serverScope.launch { cluster.start() }
```

> **NodeId ↔ PeerId alignment.** Each voter's `NodeId` must equal
> `NodeId(serverPeerId.value)` — the `KtorRoomHost.serverPeerId` cast to a
> `NodeId`. The relay stamps the server's `PeerId` as the Raft-message sender;
> mismatched IDs cause silently dropped AppendEntries.

## Connect a client

Join the server relay room, derive the client's `NodeId` from the Seam `selfId`
assigned at join time, wait for the admit handshake, then start the learner
`RaftNode` and wrap it in a `ClusterClient`:

<!-- verbatim from examples/src/test/kotlin/us/tractat/kuilt/examples/ServerClusterE2ETest.kt#`ClusterClient propose commits end-to-end through ServerCluster facade` -->
```kotlin
val clientScope = CoroutineScope(coroutineContext + Job())
val clientRoom = SeamRoomFactory.systemClock(loom = clientLoom, scope = clientScope)
    .join(
        WebSocketAdvertisement(
            url = "ws://localhost$serverPath",
            serverPeerId = serverPeerId,
            displayName = "cluster-client",
        ),
    )
val clientSeam = clientRoom.channel("raft")

// Derive the client's NodeId from the Seam selfId (UUID assigned at join time).
// The ServerCluster admission loop derives the same NodeId from the room roster.
val clientNodeId = NodeId(clientSeam.selfId.value)
val learnerConfig = ClusterConfig(
    voters = setOf(voterId),
    learners = setOf(clientNodeId),
)

// Ensure the admit handshake is complete before starting the RaftNode.
withTimeout(5.seconds) { clientRoom.roster.first { it.isNotEmpty() } }

val clientNode = clientScope.raftNode(
    clusterConfig = learnerConfig,
    transport = SeamRaftTransport(clientSeam),
    storage = InMemoryRaftStorage(),
    raftConfig = raftCfg,
)

// Wrap in ClusterClient facade — this is what S3b-3 proves.
val client: ClusterClient = clusterClientWithNode(clientNode)
```

## Propose and observe

<!-- verbatim from examples/src/test/kotlin/us/tractat/kuilt/examples/ServerClusterE2ETest.kt#`ClusterClient propose commits end-to-end through ServerCluster facade` -->
```kotlin
// Observe committed entries via the ClusterClient.committed surface.
clientScope.launch {
    client.committed
        .first { it is Committed.Entry }
        .let { committed ->
            clientCommittedPayload.complete(
                (committed as Committed.Entry).entry.command,
            )
        }
}

val command = "action:move=1".encodeToByteArray()
withTimeout(15.seconds) { client.propose(command) }
```

`propose()` forwards the command to the leader (via Raft propose-forwarding)
and suspends until a quorum commits it. The client never needs to know who the
leader is.

For cross-crash exactly-once semantics, persist the `requestId` before
calling and replay it on retry:

```kotlin
val entry = client.propose("action:move=2".encodeToByteArray(), requestId = 42L)
```

The server's `ClientSessionTable` deduplicates retries so the command is
applied at most once.

## Failover (round-robin)

When the client's link to its entry server tears, it round-robins to the next
endpoint from the `ClusterEndpoints` list. Cross-server resume always degrades
to fresh-join: each server's reconnect-window registry is in-memory and
per-room-instance, so a `ResumeToken` from server-A is unknown to server-B.
`ClusterClient` treats this as a fall-back-to-fresh-join signal — reconnect is
correct and costs a re-snapshot on the learner's log, never wrong state.

## Current limitations

| Item | Status |
|------|--------|
| M=1 voter, real sockets | Proven (`ServerClusterE2ETest`) |
| M=3 voter mesh | Proven under simulation (#541); real-socket M>1 E2E is #545 |
| Failover / entry-server change | Unit-tested and sim-proven; production `clusterClient(loom,…)` path pending #544 |
| Cross-server resume | Always degrades to fresh-join (see #532) |

Use `clusterClientWithNode()` with a caller-managed `RaftNode` +
`SeamRaftTransport` for the relay-room production path until #544 lands.

## Key types

| Type | Module scope | Role |
|------|-------------|------|
| `ServerCluster` | jvmAndAndroidMain | Server facade: voter mesh + relay accept loop |
| `ClusterClient` | commonMain | Client facade: propose + observe committed |
| `VoterMesh` | commonMain | K_m complete-graph voter set |
| `ManagedRaftTransport` | commonMain | `RaftTransport` with hot-swappable backing `Seam` |
| `ClusterEndpoints` | commonMain | Endpoint list + rotation policy |

See `docs/architecture.md` for the topology design and safety rationale, and
`docs/usage.md` for a full end-to-end code walkthrough.
