# Design: disentangle session-name from member-name (#1177)

**Status:** approved 2026-07-03 · **Issue:** #1177 · **Stacked on:** #1190 (nullable `Pattern.roomKey`)

## Problem

`Tag.displayName` is documented as "human-readable **service name** as broadcast by
the advertising peer" — the name of the *session you discovered* ("Alice's Game"). But
`RoomFactory.join(tag)` has no parameter for the joiner's own name, so `SeamRoom` reuses
`tag.displayName` as the *joiner's own* `MemberIdentity.displayName` in the roster.
Symmetrically, `RoomFactory.host(pattern)` reuses `pattern.displayName` (the advertised
session name) as the *host's own* member name.

This "works" only because the test/example convention hand-builds tags with the joiner's
own name — `join(InMemoryTag("bob"))` makes session-name "bob" double as member-name
"bob". On a **real discovered fabric** (mDNS / WebSocket / Multipeer), `tag.displayName`
is whatever the host advertised, so **the joiner mis-announces itself in the roster as the
host's session name** — Bob shows up as "Alice's Game". A latent behavioral bug, masked by
convention. #1172 already split *room identity* into a separate nullable `roomKey` rather
than overload `displayName` further; this issue finishes the job for *member identity*.

## Three concepts, cleanly separated

| Concept | Authored by | Lives on | Example |
|---|---|---|---|
| **Session name** | host | `Pattern.sessionName` / `Tag.sessionName` | "Alice's Game" |
| **Member name** | each peer, itself | supplied at `host()` / `join()` | "Alice", "Bob" |
| Room identity | host | `Pattern.roomKey` / `Tag.roomKey` | *(already split — #1172)* |
| Machine identity | transport | `PeerId` / `Tag.peerKey` | *(unchanged)* |

## The changes

### 1. Rename `displayName` → `sessionName` on `Pattern` and `Tag`

Full symmetry: the host has the identical latent conflation (it lists *itself* as
"Alice's Game"), so both sides are renamed. Chosen name is **`sessionName`**, not
`serviceName`: the codebase's own contract vocabulary is already *session*
(`Pattern` = "configuration for opening a peer **session**"; `Seam` = "one peer's
symmetric view of a **session**"). "Service" is mDNS dialect and leans one transport;
`sessionName` reads correctly on every fabric. The rename is also the fix's **forcing
function** — the compiler re-audits all ~49 `.displayName` reads, so no misuse survives
silently.

Affected `Tag` implementations (all carry `displayName` today):
`InMemoryTag` (`:kuilt-core`), `WebSocketAdvertisement` (`:kuilt-websocket`),
`MDNSAdvertisement` (`:kuilt-mdns`), `MultipeerAdvertisement` (`:kuilt-multipeer`),
`NearbyTag` (`:kuilt-nearby`), plus the conformance-test tags in each fabric module.

`Pattern.sessionName` is the value the transport advertises (e.g. the mDNS TXT service
name via the #1189 advertiser path); that role is unchanged, only the field name.

### 2. Member name supplied per-call at the membership layer

```kotlin
// us.tractat.kuilt.session.RoomFactory
suspend fun host(pattern: Pattern, memberName: String? = null): Room
suspend fun join(tag: Tag, memberName: String? = null): Room
```

The `RoomFactory` interface, its `SeamRoomFactory` impl, and the `FakeRoomFactory` in
`:kuilt-session-test` all take the new signature.

**Per-call, not factory-level.** A `SeamRoomFactory` is *not* structurally one peer — over
a shared `InMemoryLoom` mesh one factory routinely `host()`s and `join()`s distinct peers
(existing tests do this). A factory-level `localMemberName` would be a fiction there and
would hide the name away from the site where the roster entry is minted.

**Optional, defaulting from `PeerId`.** `MemberIdentity.displayName` is pure *presentation*;
the stable machine identity is already `PeerId` / `peerKey`. When `memberName` is null,
`SeamRoom` derives `MemberIdentity.displayName` from `seam.selfId` (its `PeerId` string
value), keeping the field a non-null `String`. This does **not** violate the "optional ≠ tuning" rule: the default
gates nothing functional and, unlike today's "borrow the counterpart's session name"
default, can never *mis-attribute*. Headless / CRDT-replication peers (the bulk of the
~484 `InMemoryTag("…")` sites) are not forced to invent a human name.

### 3. `SeamRoom` stops deriving member identity from the session name

`SeamRoom` takes an explicit `memberName: String?` (already has a private `displayName`
field — repurpose it). `host()` / `join()` pass the supplied `memberName` through instead
of `pattern.sessionName` / `tag.sessionName`. Null → derive from `seam.selfId`.

### 4. TCK pin in `RoomConformanceSuite`

Add a conformance test — runs against **every** fabric — that a joiner joining with
`memberName = "Bob"` into a session advertised as `sessionName = "Alice's Game"` appears
as **"Bob"** in the host's roster (and the host with `memberName = "Alice"` appears as
"Alice", not the session name). This is the test that would have caught #1177 on every
fabric, and it prevents regression.

## Migration & blast radius

- **~484 `InMemoryTag("bob")` sites do not need to change.** They are *positional* ctor
  calls; a property rename (`displayName` → `sessionName`) does not touch the call site.
  After the change, `"bob"` is read as the *session* name and member name defaults from
  `PeerId` — harmless wherever nothing asserts on the roster label (nearly all of them).
- **Roster-asserting tests break loudly** at their assertions and are the ones that get
  `memberName = "…"` added. This is the intended, self-locating migration.
- **Accepted semantic drift:** those positional sites silently re-mean `"bob"` as the
  session name. Named explicitly here so it is a decision, not an accident.
- **Do NOT special-case `InMemoryTag`'s single-arg ctor to mean "member name."** The
  reference fabric would then re-encode the very conflation the real fabrics just escaped,
  and the conformance suite would bless it. Member name lives *only* at the `host()`/`join()`
  membership boundary.

## Non-goals

- No change to `PeerId` / `peerKey` / `roomKey` semantics.
- No change to the transport layer's use of the advertised name (only the field rename).
- Not making `MemberIdentity.displayName` nullable — it stays non-null, derived when unset.

## Test strategy

TDD throughout. The load-bearing new test is the `RoomConformanceSuite` pin (§4);
per-module, the rename is compiler-driven and existing conformance/round-trip suites
(re-green after mechanical updates) cover the field. A focused `SeamRoom` unit test asserts
the null-`memberName` → `PeerId`-derived path.
