# Module kuilt-crdt

A dependency-free delta-state CRDT zoo. Provides `Quilted<S>` value types only —
no transport dependency. Live replication over a `Seam` lives in `:kuilt-quilter`.

## The zoo

Every type implements `Quilted<S>`, a join-semilattice whose `piece` satisfies the
three lattice laws (idempotent, commutative, associative). Mutations return a
`Patch` — a small state fragment any replica absorbs with `piece`. Replicas that
have absorbed the same set of patches converge to the same value regardless of
delivery order or duplication.

| Type | Convergence rule | Remove? |
|------|-----------------|---------|
| `GCounter` | Sum of per-replica maxima | No |
| `GCounterDouble` | Sum of per-replica maxima (`Double`, canonical-order sum) | No |
| `PNCounter` | Two GCounters: inc − dec | No |
| `GSet` | Set union | No |
| `TwoPhaseSet` | Union; tombstones win permanently | Once |
| `ORSet` | Causal: add-wins over concurrent remove | Yes |
| `LWWRegister` | Largest `(timestamp, replicaId)` tag wins | Via overwrite |
| `MVRegister` | Retain all concurrent writes | Via supersede |
| `LWWMap` | Per-key LWWRegister | Via overwrite |
| `ORMap` | Per-key add-wins; values merge via `piece` | Yes |
| `BoundedCounter` | Escrow-quota counter; spend within quota | Via transfer |
| `ResettableCounter` | Causal: concurrent increment survives reset | Via observed-reset |
| `Rga` | Op-log union; Lamport-ordered insert wins | Via tombstone |
| `Fugue` | Op-log union; tree-based maximal non-interleaving ordering | Via tombstone |
| `JsonCrdt` | Recursive JSON: ORMap objects, Rga arrays, MVRegister leaves | Via key remove |
| `EphemeralMap` | Per-replica slot, higher clock wins; caller-driven TTL eviction | Via graceful leave |
| `MovableTree` | Op-log union; Lamport-ordered replay with cycle prevention | Via reparent |
| `BloomFilter` | Bitwise-OR of bit array; probabilistic membership, bounded FP rate | No (union-only) |
| `HyperLogLog` | Element-wise max of registers; ~0.8% error at p=14 | N/A (sketch, not a set) |
| `CountMinSketch` | Approximate frequency sketch; element-wise max merge (idempotent CMS) | No |

## Replication

Live replication is in `:kuilt-quilter`. `Quilter<S>` streams deltas over a `Seam`
and converges any `Quilted` state across peers automatically.

`MuxSeam` (`:kuilt-core`) multiplexes several CRDTs over one underlying `Seam`,
routing frames by channel tag.

## Minimal sparse fragment idiom

Array-backed CRDTs should ship deltas as the **smallest possible fragment** of
their backing array, not the full array. The mutator (`add`, `increment`, …)
produces a `Patch` whose embedded state is nearly empty — only the cells that
changed are non-zero. The join (`piece`) then applies that fragment via
element-wise max (or OR, or whichever lattice operation applies), so a
re-delivered or duplicate patch is safe and idempotent.

All three probabilistic sketch types in the zoo implement this idiom, each in the
encoding that fits its structure:

| Type | Backing store | Delta encoding | Wire size per add |
|------|--------------|---------------|-------------------|
| `CountMinSketch` | `Long` matrix (`depth × width`) | `List<CellDelta(row, col, value)>` — one triple per hash row | O(depth) cells |
| `HyperLogLog` | 6-bit-packed `ByteArray` | Same-length `ByteArray` with at most one non-zero 6-bit register slot | O(1) registers |
| `BloomFilter` | `LongArray` of bit-words | Same-length `LongArray` with only touched words non-zero; `BloomFilterSerializer` encodes as `(wordIndex, wordValue)` pairs | O(hashCount) words |

The three encodings are intentionally type-specific:
- **CountMinSketch** needs `(row, col)` addressing — a 2D matrix cell requires
  both dimensions.
- **HyperLogLog** packs six bits per register; the "sparse" delta is structurally
  a same-sized `ByteArray` with one slot set — zeros are structurally meaningful
  and the packed accessor is non-trivial.
- **BloomFilter** uses bit-word addressing (`wordIndex`); the sparse encoding lives
  in the serializer layer (`BloomFilterSerializer`), not in the type.

A `SparseArrayDelta<Cell>` shared helper would need to unify three incompatible
carriers (`LongArray`, `ByteArray`, `List<CellDelta>`) or force them all into a
single index+value pair encoding with incompatible value semantics. That helper
would be more complex and less readable than the three clean type-specific forms,
so no shared abstraction is introduced.

**Guidance for future array-backed CRDTs:** produce a minimal-fragment delta by
default; keep the encoding local to the type. Use `piece`'s element-wise operation
to absorb any fragment safely.

## The `Causal` layer

`ORSet`, `MVRegister`, `ORMap`, and `ResettableCounter` are all thin wrappers over `Causal<DotStore>`.
A `DotContext` accumulates witnessed `(ReplicaId, seq)` dots; a remove drops the
dots currently on the element from the store while retaining them in the context,
so a concurrent add on a replica that minted a *different* dot survives the merge.
