# LWWRegister

A last-write-wins register: holds a single value; concurrent writes are resolved by timestamp. The write with the higher timestamp wins. Replica id breaks ties lexicographically.

**Converges to:** the value written at the highest `(timestamp, replicaId)` across all replicas.

## Merge rule

Each write records `(timestamp, replicaId, value)`. `piece` takes the write with the greater `(timestamp, replicaId)` tuple. The same timestamp from different replicas is a tie; lexicographically larger `replicaId` wins.

This is deterministic and commutative — two replicas starting from the same state will converge to the same write regardless of merge order.

## Code examples

**Set and read:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/LWWRegisterTest.kt#setThenRead -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/LWWRegisterTest.kt
// Test: setThenRead
assertEquals("x", LWWRegister.empty<String>().set(a, 10L, "x").value)
```

**Later timestamp wins (commutative):**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/LWWRegisterTest.kt#laterTimestampWins -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/LWWRegisterTest.kt
// Test: laterTimestampWins
val r1 = LWWRegister.empty<String>().set(a, 10L, "x")
val r2 = LWWRegister.empty<String>().set(b, 20L, "y")
assertEquals("y", r1.piece(r2).value)
assertEquals("y", r2.piece(r1).value)  // commutative
```

**Tie-break on replica id:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/LWWRegisterTest.kt#tieBreaksOnReplicaIdLexicographically -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/LWWRegisterTest.kt
// Test: tieBreaksOnReplicaIdLexicographically
// Same timestamp; "B" > "A" lexicographically → "y" wins.
val r1 = LWWRegister.empty<String>().set(a, 10L, "x")
val r2 = LWWRegister.empty<String>().set(b, 10L, "y")
assertEquals("y", r1.piece(r2).value)
assertEquals("y", r2.piece(r1).value)
```

## When to use

`LWWRegister` is appropriate when you have a single mutable value and are willing to let wall-clock timestamps resolve conflicts. The convergence guarantee is strong, but the semantic guarantee is not: concurrent writes silently discard the "loser". If you need to observe and explicitly resolve all concurrent writes, use [MVRegister](crdt-mvregister.md).
