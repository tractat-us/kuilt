# ADR-004 — Split `:kuilt-crdt` (dependency-free) + `:kuilt-quilter`; rename `SeamReplicator` → `Quilter`

**Status:** Accepted
**Date:** 2026-06-17

## Context

`:kuilt-crdt` housed both the delta-state CRDT value types and the live replication
layer (`SeamReplicator` / `ReplicatorMessage`). The replication layer depends on
`:kuilt-core` (`Seam`, `ScopedCloseable`, …), making `:kuilt-crdt` impossible to
use without pulling in the transport layer. This prevents embedding the CRDT zoo in
contexts that have no concept of a `Seam` — pure computation, server-side storage,
test helpers, or future WASM consumers.

Additionally, the class name `SeamReplicator` embedded `Seam` (a transport term)
into what is conceptually a generic CRDT replication component. `Quilter` is
semantically richer (it *quilts* CRDT patches across peers) and consistent with the
project's `Quilted`/`Quilter` terminology.

## Decision

**Split the module:**

| Module | Role | Dependencies |
|--------|------|-------------|
| `:kuilt-crdt` | Delta-state CRDT value types only (`Quilted`, `Patch`, the entire zoo) | **None** — pure Kotlin, no kuilt transitive |
| `:kuilt-quilter` | Live CRDT replication over a `Seam` (`Quilter`, `QuilterConfig`, `QuiltMessage`) | `api(:kuilt-core)` + `api(:kuilt-crdt)` |

**Rename:**

| Before | After |
|--------|-------|
| `SeamReplicator<S>` | `Quilter<S>` |
| `SeamReplicatorConfig` | `QuilterConfig` |
| `ReplicatorMessage<S>` | `QuiltMessage<S>` |

Source files: `SeamReplicator.kt` → `Quilter.kt`, `ReplicatorMessage.kt` → `QuiltMessage.kt`.
Test files: `SeamReplicator*Test.kt` → `Quilter*Test.kt`, `ReplicatorMessageTest.kt` → `QuiltMessageTest.kt`.

All consumer references updated: `:kuilt-session`, `:kuilt-session-test`, `examples/`, KDoc in
`:kuilt-crdt`, `:kuilt-core`, `:kuilt-conformance`.

## Consequences

- `:kuilt-crdt` is dependency-free. Consumers that want only the CRDT zoo pay no
  transport cost; those that want live replication depend on `:kuilt-quilter`.
- The BOM (`kuilt-bom`) and `settings.gradle.kts` include `:kuilt-quilter`.
- `:kuilt-session`'s `RoomReplicator` helper imports from `:kuilt-quilter`.
- **Breaking change** for callers on main who were importing `SeamReplicator` /
  `ReplicatorMessage` / `SeamReplicatorConfig` — the old names do not exist;
  update to `Quilter` / `QuiltMessage` / `QuilterConfig`.
