# LWWRegister

Holds one value. When two devices write at the same time, the one with the later timestamp wins. Ties are broken by device id. The result is always a single agreed value — conflicts are silently resolved.

**Converges to:** the value written at the highest `(timestamp, replicaId)` across all replicas.

## Merge rule

Each write records `(timestamp, replicaId, value)`. `piece` takes the write with the greater `(timestamp, replicaId)` tuple. The same timestamp from different replicas is a tie; lexicographically larger `replicaId` wins.

This is deterministic and commutative — two replicas starting from the same state will converge to the same write regardless of merge order.

## Code example

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleLWWRegister -->
```kotlin
val a = ReplicaId("A")
val b = ReplicaId("B")

val left = LWWRegister.empty<String>().set(a, timestamp = 1L, value = "v1")
val right = LWWRegister.empty<String>().set(b, timestamp = 2L, value = "v2")

check(left.piece(right).value == "v2")  // ts=2 wins
check(right.piece(left).value == "v2")  // commutative
```

## When to use

`LWWRegister` is appropriate when you have a single mutable value and are willing to let wall-clock timestamps resolve conflicts. The convergence guarantee is strong, but the semantic guarantee is not: concurrent writes silently discard the "loser". If you need to observe and explicitly resolve all concurrent writes, use [MVRegister](crdt-mvregister.md).
