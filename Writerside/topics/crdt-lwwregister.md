# LWWRegister

Holds one value. When two devices write at the same time, the one with the later timestamp wins. Ties are broken by device id. The result is always a single agreed value — conflicts are silently resolved.

**Converges to:** the value written at the highest `(timestamp, replicaId)` across all replicas.

## Merge rule

Each write records `(timestamp, replicaId, value)`. `piece` takes the write with the greater `(timestamp, replicaId)` tuple. The same timestamp from different replicas is a tie; lexicographically larger `replicaId` wins.

This is deterministic and commutative — two replicas starting from the same state will converge to the same write regardless of merge order.

## Code examples

**Set and read:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/LWWRegisterTest.kt" include-symbol="setThenRead" }

**Later timestamp wins (commutative):**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/LWWRegisterTest.kt" include-symbol="laterTimestampWins" }

**Tie-break on replica id:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/LWWRegisterTest.kt" include-symbol="tieBreaksOnReplicaIdLexicographically" }

## When to use

`LWWRegister` is appropriate when you have a single mutable value and are willing to let wall-clock timestamps resolve conflicts. The convergence guarantee is strong, but the semantic guarantee is not: concurrent writes silently discard the "loser". If you need to observe and explicitly resolve all concurrent writes, use [MVRegister](crdt-mvregister.md).
