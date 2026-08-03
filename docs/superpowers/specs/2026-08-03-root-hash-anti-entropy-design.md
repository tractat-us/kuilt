# Root-hash-gated anti-entropy for `Quilter`

**Issue:** [#1955](https://github.com/tractat-us/kuilt/issues/1955) · **Date:** 2026-08-03 ·
**Status:** design approved, ready to plan

## What this changes, in one sentence

`Quilter`'s anti-entropy tick stops pushing the entire CRDT state to a random peer every 60 s and
pushes a 31-byte hash of it instead, shipping the state only when the hashes disagree.

## Why — the measured case

Phase 0 ([#1985](https://github.com/tractat-us/kuilt/pull/1985),
[analysis](https://github.com/tractat-us/kuilt/issues/1955#issuecomment-5167125682)) priced today's
behaviour through the real codec and grounded the model against bytes actually counted on a
`MeteredSeam` — framing overhead measured at exactly **0**, so the numbers are wire cost, not a proxy.

| `GSet` entries | full state | steady-state egress/node @60 s | with a digest |
|---|---|---|---|
| 1,000 | 32.9 KB | 549 B/s | 0.52 B/s |
| 10,000 | 339 KB | 5.6 KB/s | 0.52 B/s |
| 100,000 | 3.49 MB | **58.1 KB/s** | 0.52 B/s |

That egress is what a **fully converged** node pays, forever, to tell a peer something it already
knows. At 100k entries it is ≈5 GB/day/node. Below ~1k entries it is noise.

**The win is quiescence, not diffing.** Phase 0 also measured the sharded-diff alternative and found
its advantage collapses as divergence grows — at n=100k with S=256 shards, 1 differing key is 245×
cheaper than full state but 1,000 differing keys is **1.0×**. Since anti-entropy is a *backstop*
(deltas do the real work, so rounds are overwhelmingly quiescent), a bare root hash captures nearly
all of the benefit for a fraction of the design surface. A tree or shard vector is explicitly **out
of scope** here and remains a separate, independently-measured decision.

**This saves bytes, not CPU.** `reconcileWithRandomPeer` already encodes the whole state under the
lock on every tick, so hashing is encode *plus* hash: net CPU is *up* by the hash cost (1.59 ms per
100k-entry round, 0.0027% of one core against a 60 s interval). Nobody should expect a CPU win.

Phase 0 also settled #1955's last open design question — **rehash per round, no incremental digest
maintenance.** At that duty cycle there is no case for the machinery. Memoising the root against the
immutable state reference is a further easy win and is deliberately **not** in scope (YAGNI; the
measured cost does not justify the field).

## Wire protocol

Two new `QuiltMessage` variants. `FullState` is untouched and remains the always-correct fallback.

```
A (anti-entropy tick)                 B
  RootDigest ──────────────────────►  resync cursor from upThrough
  (sender, root, upThrough)           compare root
   ~31 b                              │
                                      ├─ equal ──►  done.  ~31 b for the whole round
                                      │
                                      └─ differ ─►  FullStateRequest ─────────►  A
                                                    ◄───────── FullState (exactly as today)
```

- **`RootDigest(sender: ReplicaId, root: Long, upThrough: Long)`** — the tick frame. Flat in state
  size; that constancy *is* the optimization.
- **`FullStateRequest(requester: ReplicaId, sender: ReplicaId)`** — mismatch only.

### Why the receiver requests rather than pushing

On a mismatch the receiver cannot tell from a hash *which* side is behind. Two options:

1. **Receiver requests, sender ships** (chosen). Costs one extra tiny frame; ships exactly the same
   number of full states as today.
2. Receiver pushes its own `FullState` and lets the existing bidirectional heal sort it out. One
   fewer message type, but anti-entropy exists to heal a peer that *missed a delta* — so the
   receiver is usually the one **behind**, and that is precisely the case where pushing costs a
   wasted full-state shipment before the real one. It would double the cost of the common mismatch.

### Mixed-version rooms — an accepted limitation

An older peer receives an unknown `SerialName` and the frame is silently dropped at
`Quilter.kt:612`, so anti-entropy between a new and an old peer stops working with no signal. This
is **accepted**: `QuiltMessage` has no version handshake today, so uniform peer versions are already
an implicit precondition, and consumers pin via `kuilt-bom`. Documented as a precondition rather
than defended.

The upgrade path, if mixed-version support is ever needed, is a reply-always protocol: have a root
*match* send back a tiny confirmation, making "no reply at all" a reliable signal that the peer does
not speak digests, after which the sender reverts to `FullState` for that peer permanently. Tracked
separately; not built here.

## The trap this design exists to avoid

**An anti-entropy `FullState` does two jobs, not one.** It ships the state, *and* it carries
`upThrough` — the sender's own-delta high-water — which the receiver feeds to `resyncReceiveCursor`
(`Quilter.kt:816`). That function's KDoc is explicit about what happens without it (#1266):

> a receiver whose gap range outlives the sender's GC — the late-joiner case — livelocks: every
> subsequent delta is buffered against a cursor that can never advance … the receiver never acks via
> the delta path, and the sender's `pendingDeltas` (plus this side's `pendingInbound`) grow without
> bound.

A naive gate that sends *nothing* when roots match would silently reintroduce that livelock for any
peer whose state matches while its delta cursor is behind — reachable, because state can arrive via
another peer or a gossip flood while this sender's cursor stays stale.

**Therefore `RootDigest` carries `upThrough`.** This is the load-bearing requirement of the whole
design, not an optimization.

### …but resync only on the *match* branch

The tempting implementation — resync first, then compare roots — is wrong, and in a way today's
protocol never exhibits. `resyncReceiveCursor` **acks** `upThrough` (`Quilter.kt:829`), and today
that ack is always issued *after* the state has been merged: `onFullState` merges at `:791-794` and
only then resyncs at `:801`. The ack is therefore honest.

Resyncing on a *mismatched* digest would ack history the receiver has not received. It would also
drop buffered inbound deltas at or below `upThrough` (`:823`). If the `FullStateRequest` or the
`FullState` reply is then lost, the receiver is stale *and* its delta path to that history is severed
— the sender GC'd on the ack, and the receiver's cursor has moved past it. Convergence still recovers
on a later round's mismatch, so this is a window rather than a permanent hole, but it is a new
failure mode bought for nothing.

So:

- **Match** → roots equal ⇒ states equal ⇒ resync and ack are honest. This is exactly the #1266 case
  the design exists for, and exactly where today's `FullState` would have been a no-op merge followed
  by a resync.
- **Mismatch** → do *not* resync. Send `FullStateRequest`; the `FullState` reply carries its own
  `upThrough` and `onFullState` resyncs precisely as it does today. If a frame drops, the cursor was
  never falsely advanced — matching today's drop semantics.

### What else the unconditional push was doing — checked, and preserved

The `upThrough` carry is the non-obvious dependency, but it is not the only consequence of a frame
arriving every 60 s. Each of these was traced and survives:

- **`lastSeenAt` / eviction.** `evictStalePeers` keys on `peer !in seam.peers && isStale`
  (`Quilter.kt:524-550`) — frame-kind- and size-agnostic. A digest refreshes liveness exactly as a
  full state did.
- **`cancelFullStateRetry(sender)`** fires on every inbound frame in `dispatch` (`Quilter.kt:611`),
  digests included.
- **The push-back heal** (`onFullState`, `:795-800`) is *preserved through the chain*, and is worth
  naming because a future refactor could break it invisibly: on a mismatch the receiver requests, the
  sender ships, and the receiver then sees `merged == current && msg.state != current` and pushes its
  own state back. Both heal directions still work.
- **Sender-side GC.** The match branch's ack drives `onAck` → `ackedThrough` →
  `recomputeUniversalAck` → `gcPendingDeltas` (`:734-764`) identically.
- **`recomputeDeliveredLocal`, the causal matrix, `cutFrontier`, RGA/Fugue GC, BoundedCounter
  transfer.** None were driven by a *converged* round: `recomputeDeliveredLocal` fires only when
  `merged != current` (`:792-794`), and the rest ride the `Delivered` broadcast or their own message
  types, which `runAntiEntropy` still calls independently (`:491-495`).

## Components

All changes in `:kuilt-quilter`.

| Unit | Change |
|---|---|
| `Fnv1a64.kt` (new) | `internal fun fnv1a64(bytes: ByteArray): Long`. ~8 lines, its own file so it is independently testable and cannot quietly accrete callers. |
| `QuiltMessage.kt` | Add `RootDigest` and `FullStateRequest`. `FullState` unchanged. |
| `Quilter.reconcileWithRandomPeer` | Ship `RootDigest` instead of `FullState`. Fire-and-forget, otherwise unchanged. |
| `Quilter.onRootDigest` (new) | Resync cursor from `upThrough`, compare root, send `FullStateRequest` on mismatch. |
| `Quilter.onFullStateRequest` (new) | Ship `FullState` directly, **without** arming `scheduleFullStateRetry`. |
| `Quilter.resyncReceiveCursor` | Narrow the signature (below). |

### Computing the root without a new constructor parameter

`Quilter`'s primary constructor holds only `messageSerializer: KSerializer<QuiltMessage<S>>`
(`Quilter.kt:157`) — there is **no** `KSerializer<S>` on the class. `valueSerializer` exists only as
a parameter of the top-level convenience factory (`Quilter.kt:929`) and is not retained, so the class
cannot encode a bare `S`.

Rather than widen the primary constructor — a public API change churning every direct call site,
including every test — hash a **fixed synthetic frame** through the serializer it already has:

```kotlin
private fun stateRoot(): Long = fnv1a64(
    binaryFormat.encodeToByteArray(
        messageSerializer,
        QuiltMessage.FullState(sender = ReplicaId.Bottom, state = _state.value, upThrough = 0L),
    ),
)
```

`ReplicaId.Bottom` is the existing named empty sentinel (`ReplicaId.kt:31`), and both fixed fields
contribute a constant to every peer's encoding, so equal states still give equal roots. This also
means the root covers *the state as it appears on the wire*, which is the quantity actually being
compared. Computed under the existing lock, where the full encode already happens.

### Where the digest lives, and which hash

**`:kuilt-quilter`, hashing the `binaryFormat` bytes.** `:kuilt-crdt` has no production CBOR — only
`api(kotlinx-serialization-core)`, with cbor confined to `commonTest` — so putting it there means a
new production dependency on the module whose whole point is having almost none.

The tempting alternative was to put it in `:kuilt-crdt` over pinned CBOR and have
`:kuilt-conformance`'s existing `canonicalDigest` delegate to it, giving one implementation and one
golden-vector set. That argument dissolves on inspection: the two digests **never interoperate**
(`canonicalDigest` is a test/harness divergence alarm; this one is peer-to-peer), so they are not
required to agree and sharing buys nothing. `:kuilt-quilter` then wins on three counts — no new
production dependency, no second encoding path, and the digest covers *exactly* the bytes the wire
carries, so a canonicality bug cannot hide in one and not the other.

**64-bit FNV-1a**, matching `canonicalDigest`'s in-tree precedent, rather than the already-vendored
32-bit `Murmur3`. Same constants, so the two agree by construction even though nothing requires them
to: offset basis `-3750763034362895579L` (`0xcbf29ce484222325`), prime `1099511628211L`, folding
each byte as `hash = (hash xor (byte.toLong() and 0xFF)) * PRIME`. Not merely a birthday-bound argument: a root collision between two *stable*
divergent states means that pair never heals via anti-entropy, and the standing rule is that the
optimization must never be load-bearing for correctness. 64 bits is the same ~8 lines. (The mesh
would still heal such a pair through any third peer, which is why this is a robustness argument
rather than a correctness one — but it is free.)

### Narrowing `resyncReceiveCursor`

It currently takes a `QuiltMessage.FullState<S>` and uses only `msg.sender` and `msg.upThrough`.
Changing the signature to `(senderReplica: ReplicaId, upThrough: Long)` lets `onRootDigest` and
`onFullState` share one implementation, so the #1266 path cannot be correct in one handler and wrong
in the other. This is the seam the new handler has to enter through — a targeted improvement to the
code being changed, not unrelated refactoring.

### Why `onFullStateRequest` must not call `sendFullStateTo`

`sendFullStateTo` arms `scheduleFullStateRetry` (`Quilter.kt:583`), which exists for the
*first-contact* path where nothing else retries. Anti-entropy already retries every interval by
construction, so reusing that helper would layer two independent retry machines over the same peer.
The handler sends directly, matching `reconcileWithRandomPeer`'s fire-and-forget shape.

### Amplification guard

An unsolicited `FullStateRequest` would otherwise let any peer pull a full state on demand,
repeatedly. Guard, costing one flag per peer: **honor a request only from a peer we have sent a
`RootDigest` to since the last request we honored**, clearing the flag on honor. An unsolicited
request is then a silent no-op.

Stated precisely, because the obvious phrasing overclaims: this is one full state *per digest sent*,
not one per interval. A matched round sends nothing back, so the flag stays armed and the grant is
redeemable later — a long-lived converged peer accumulates a standing one-shot grant. The bound that
matters is unaffected: redemption *rate* is still capped by the digest rate, one per peer per
interval, which is exactly the pre-#1955 ceiling. The handler should also mirror `onResend`'s
`if (msg.sender != replica) return` check (`Quilter.kt:853`), or the frame's `ReplicaId` fields are
decoration.

### Deliberately untouched

First-contact `FullState` (`Quilter.kt:577`) and its retry (`598`) keep shipping state
unconditionally: a brand-new peer needs it, and a digest round there would only add a round trip.
**Only the anti-entropy tick at `Quilter.kt:517` changes.**

## Scope: all `Quilted` types, and why that is safe

The gate applies uniformly. It is mechanically type-agnostic (hash the encoded state), and `Quilter`
is generic over `S : Quilted` so a per-type opt-in has nowhere clean to live.

**The gate degrades gracefully on non-canonical types.** If `Rga`/`Fugue` encode non-canonically
([#1978](https://github.com/tractat-us/kuilt/issues/1978)), roots simply always mismatch →
`FullStateRequest` → `FullState` → converges exactly as today, with no saving and one extra tiny
round trip. So **#1978 gates the *benefit*, not the *safety***, and this work does not wait on it.

Bounded types (`GCounter`, `HyperLogLog`, …) sit in #1955's "skip" bucket; their states are small
enough that gating is roughly neutral rather than a win. Measured crossover is 1 entry, so uniform
application is not a pessimization.

## Error handling

| Case | Behaviour |
|---|---|
| Undecodable frame | Dropped at `Quilter.kt:612`, as today. Covers the mixed-version case above. |
| Send failure | Fire-and-forget; the next tick is the retry (unchanged). |
| Root collision | 2⁻⁶⁴, and it clears on the next state mutation on either side (a new state gives a new root) or via a third peer where one exists. Only a permanently quiescent **2-peer** session stays divergent — there is no third peer to route around it. |
| State mutates between digest and request | Harmless — `FullState` is a join, always correct. |
| Unsolicited `FullStateRequest` | Silent no-op via the amplification guard. |
| `FullStateRequest` or its reply lost | The behind peer heals on a later round instead. Note the heal now needs three frames to survive rather than one, so under independent per-frame loss `p` the per-round success rate falls from `p` to `p³`. Correct either way — anti-entropy is a backstop with an unbounded number of rounds — but it is a real change in healing *latency* under lossy fabrics. |

## Testing

### (a) The #1266 regression test — first commit on the branch

A peer whose state **matches** but whose delta cursor is **behind** must still ack and unpin the
sender's `pendingDeltas`. Per TDD-for-bug-fixes: write it first, then implement, then revert the
`upThrough` carry and confirm it goes red, then restore.

**This needs two tests, not one, and the reason is the whole point of mutation-checking.** A
receive-side test that *fabricates* an inbound `RootDigest(upThrough = 5)` and injects it never
executes `sendRootDigestTo` — so hardcoding `upThrough = 0L` on the **send** side leaves it green,
and the mutation the design nominates as its proof does nothing. Required:

1. **Receive side** — inject a matched digest carrying `upThrough`, assert exactly one `Ack` at that
   seq. Pins the handler.
2. **Send side** — short `antiEntropyInterval`, `apply` three patches so `nextSeq == 3`, advance one
   interval, decode the emitted frame, assert `RootDigest.upThrough == 3L`. **This** is the test that
   goes red under the named mutation.

And the structural fix, which is better than either test: **`RootDigest.upThrough` takes no default
value.** Omitting it then fails to compile rather than silently sending `0L`. `FullState.upThrough`
has a default only because it predates callers; a brand-new frame has no such constraint.

### (b) Cross-target golden vectors — non-negotiable

Absolute pinned `Long`s in `commonTest`, so JVM/Android/iOS/macOS/wasmJs are held to the same
constants: for `fnv1a64` over fixed byte inputs, and for the root of specific `GSet`/`LWWMap` states.
`CanonicalGoldenVectorTest` is the template.

Two facts from the #1957 run that shape this:

- **`jvmTest` is zero signal** for cross-target hash-order canonicality (0/28 vs 14/28 on
  macOS/wasm).
- **`ci-required` runs on Linux**, and `apple-nightly.yml` is not required, so **wasmJs is the only
  non-JVM target in the per-PR gate**; Apple agreement is nightly. Golden vectors are the stronger
  net precisely because they pin absolute values, which *is* JVM-visible.
- `:kuilt-crdt:wasmJsTest` is a lifecycle task that rejects `--tests`; use `wasmJsBrowserTest`.

### (c) Vacuity audit — treat as blocking

This inserts a new gate **ahead of** an existing one: `reconcileWithRandomPeer` no longer sends
`FullState`, so every existing test that asserted anti-entropy ships full state now silently
exercises the digest path instead. That is the failure mode where the suite stays green while an
older guard's coverage drops to zero (#1872).

Enumerate the current anti-entropy tests and, for each, mutation-test the *old* assertion to confirm
it still fails when broken. **Per-field, never batched**, and a mutation that fails to compile is
never a red test — abort on a non-zero build exit before reading any results XML.

### (d) Close the loop on the Phase-0 measurement

`MerkleDigestCostModelTest` predicted 58.1 KB/s → 0.52 B/s. Add a `:kuilt-scale` test that measures
**actual** converged-round bytes over a `MeteredSeam` and asserts the drop, reusing part (B)'s
grounding approach. This makes the Phase-0 model the acceptance criterion rather than a standing
claim — and if the real saving misses the prediction, that is a finding, not a surprise.

### (e) Remaining behavioural tests

- Diverged pair still converges (digest → request → full state).
- Unsolicited `FullStateRequest` is a no-op.
- A deliberately non-canonical serializer still converges — the graceful-degradation property above.
- Existing `Quilter` convergence suites stay green.

Multi-node tests go through the canonical harness; bounded `await*`/`settle()` only, never
`advanceUntilIdle()`; per-node seeded RNG; a generous 30 s `runTest` backstop that is **not** a
load-bearing assertion, with the tight fence on the *shell* command instead.

## Success criteria

1. A converged 100k-entry pair's anti-entropy round drops from ~3.5 MB to a small constant frame,
   **measured** on a `MeteredSeam`, matching Phase 0's prediction.
2. The #1266 ack/GC path survives a matched round — proven by a test that fails without the
   `upThrough` carry.
3. Golden vectors agree across JVM, wasmJs (per-PR) and Apple targets (nightly).
4. No existing anti-entropy assertion has gone vacuous.
5. No correctness property rests on the digest: the mismatch path ships `FullState` and heals, and
   every convergence guarantee still traces to that shipment rather than to a hash comparison.

## Out of scope

- Merkle tree / shard vector — measured, narrow payoff; separate decision.
- The dot-based family — [#1986](https://github.com/tractat-us/kuilt/issues/1986); `DotContext`
  already answers "what are you missing" exactly, so those types want a version-vector diff, not a
  hash.
- `Rga`/`Fugue` canonical encoding — #1978. Gates benefit, not safety.
- Root memoisation against the state reference.
- Reply-always / mixed-version fallback.
