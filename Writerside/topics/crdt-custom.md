# Implementing your own CRDT

If your app data model does not fit the built-in types, you can still get the
same replication behavior by implementing your own type and wiring it into
`SeamReplicator`.

## The `Quilted` interface

Every CRDT implements `Quilted<S>`:

```kotlin
interface Quilted<S : Quilted<S>> {
    fun piece(other: S): S  // merge — idempotent, commutative, associative
}
```

`piece` is the merge operation that keeps replicas aligned. Formally, it is the
join in a join-semilattice. Three laws make it correct:

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

## Your custom CRDT

Start with a tiny type whose merge rule is obvious. `MaxInt` keeps the highest
value seen so far, so every replica converges to the same answer.

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

## Tiny tutorial: using dots

When your type needs add/remove behavior with causality (for example, “remove
this item unless another peer added it concurrently”), build on **dots**.

A dot is a unique event id: `(replicaId, counter)`. You assign fresh dots when
you add data, track seen dots in `DotContext`, and let merge rules decide what
survives.

```kotlin
// Conceptual sketch: one value tracked by dots
@Serializable
data class PresenceByDot(
    val state: Causal<DotSet>
) : Quilted<PresenceByDot> {
    override fun piece(other: PresenceByDot): PresenceByDot =
        PresenceByDot(state.piece(other.state))
}

// add: mint a fresh dot and put it in the store/context
// remove: move seen dots into context so future merges can drop them
```

You usually do not need to start this low. Prefer higher-level types first
(`ORSet`, `MVRegister`, `ORMap`), then reach for dots when you are designing a
new structure with custom causal semantics.

For the causal model and working tests, see [Causal primitives](crdt-causal.md).

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

## External CRDT references

If you want deeper background while designing custom types, these are solid
starting points:

- [A comprehensive study of Convergent and Commutative Replicated Data Types (Shapiro et al., 2011)](https://hal.inria.fr/inria-00555588/document)
- [CRDTs website and bibliography](https://crdt.tech/)
- [Riak Data Types: practical CRDT design notes](https://docs.riak.com/riak/kv/latest/developing/data-types/index.html)
