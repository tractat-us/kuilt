# Implementing your own CRDT

If none of the built-in types fit your use case, you can implement a custom
CRDT that works with `SeamReplicator` and the rest of the zoo.

## The `Quilted` interface

Every CRDT implements `Quilted<S>`:

```kotlin
interface Quilted<S : Quilted<S>> {
    fun piece(other: S): S  // merge — idempotent, commutative, associative
}
```

`piece` is the join in the join-semilattice. Three laws make it correct:

- **Idempotent** — calling it with the same argument twice gives the same result as once.
- **Commutative** — order of the two arguments doesn't matter.
- **Associative** — multiple merges can be grouped in any order.

These three laws guarantee that any two replicas which have seen the same set
of updates will converge to the same value, regardless of network order.

## Delta state and `Patch`

Instead of shipping the entire state on every update, implementations emit a
*delta* — a minimal value that represents only what changed. Merging a delta
advances the state the same way merging the full state would; `SeamReplicator`
exploits this to send small messages over the wire and ship the full state only
to late joiners.

Mutations produce a `Patch<S>` wrapping the delta:

```kotlin
val counter = GCounter.ZERO
val delta: GCounter = counter.inc(replica, 3L)  // only the increment, not the full counter
val next: GCounter = counter.piece(delta)        // apply it

// Pass to SeamReplicator:
replicator.apply(Patch(delta))
```

## Minimal example

```kotlin
@Serializable
class MaxInt(val value: Int) : Quilted<MaxInt> {
    override fun piece(other: MaxInt) = MaxInt(maxOf(value, other.value))
}

// Use standalone
val a = MaxInt(3)
val b = MaxInt(7)
val merged = a.piece(b)  // MaxInt(7) — always the higher value, on every replica

// Or live-replicate it:
val replicator = SeamReplicator(
    replica = ReplicaId("node-1"),
    seam = seam,
    initial = MaxInt(0),
    messageSerializer = ReplicatorMessage.serializer(MaxInt.serializer()),
    scope = scope,
)
replicator.apply(Patch(MaxInt(42)))
```

## Conformance laws

Test your implementation against the three laws before wiring it into a
`SeamReplicator`. A type that violates any law will silently diverge across
replicas:

```kotlin
fun <S : Quilted<S>> checkLaws(a: S, b: S, c: S) {
    // Idempotent
    check(a.piece(a) == a)
    // Commutative
    check(a.piece(b) == b.piece(a))
    // Associative
    check(a.piece(b).piece(c) == a.piece(b.piece(c)))
}
```
