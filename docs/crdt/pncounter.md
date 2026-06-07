# PNCounter

A positive/negative counter: the simplest extension of `GCounter` that allows
both increments and decrements.

## Intuition

A `PNCounter` is two independent `GCounter`s in a product lattice — one for
increments (`inc`), one for decrements (`dec`). Each half converges exactly as a
`GCounter` would. The observable value is `inc.value - dec.value`.

Because both halves grow monotonically (each slot only ever increases), the
product lattice converges: joining two `PNCounter`s is just joining each half
separately. Idempotent, commutative, associative by the same argument that holds
for `GCounter`.

## The lattice

- **State:** `(inc: GCounter, dec: GCounter)` — a pair.
- **`piece(a, b)`:** `(a.inc.piece(b.inc), a.dec.piece(b.dec))` — elementwise join on each half.
- **`value`:** `inc.value - dec.value` — a subtraction of two non-negative sums.
- **Delta for `increment(r, n)`:** `(GCounter{r→n}, GCounter.ZERO)`.
- **Delta for `decrement(r, n)`:** `(GCounter.ZERO, GCounter{r→n})`.

## Replica ownership

Each replica owns its own slot in each half. Replica `A` only ever writes to
the `A` slot of `inc` and `dec`. Two replicas writing to different slots compose
without conflict — that is the whole point of `GCounter`.

## Gotcha: value can go negative

There is no floor at zero. A replica can decrement without having incremented.
`value = inc - dec` and `dec` is bounded only by `Long.MAX_VALUE` per slot.
This is correct PNCounter semantics. If you need a guarantee that value stays
non-negative (a bounded shared budget), use `BoundedCounter` instead.

## When to use PNCounter vs BoundedCounter

| Need | Use |
|------|-----|
| Concurrent add/remove of an integer quantity, any sign | `PNCounter` |
| Shared budget that must never go negative | `BoundedCounter` |
| Only ever counts up | `GCounter` |
