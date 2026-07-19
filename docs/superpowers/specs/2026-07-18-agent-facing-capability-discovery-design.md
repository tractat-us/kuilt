# Agent-facing capability discovery — design

**Date:** 2026-07-18
**Status:** approved (brainstorm), pending implementation-plan

## Problem

Downstream AI coding agents — working in a *different* repo (e.g. the game app)
that depends on kuilt via Maven — keep **hand-rolling primitives kuilt already
provides**: reconnect/resume tokens, CRDTs, roster/`Room`, dedup sets,
heartbeat/partition detection, consensus, fair dealing. They reinvent because at
the moment of need the existing kuilt primitive is not in the agent's context.

This is **not a docs-quality problem — it is a delivery problem.** The best
possible doc *in kuilt's repo* is worthless to an agent working in another repo,
because nothing in that agent's context ever points at it. Human-oriented
"Internals" prose fails twice over: humans skim it, and agents never load it.

**Explicit non-goal:** the accessible/simple published surfaces (the Writerside
guide landing, the README opening, the "accessible → technical" descent) are
**not touched**. This adds a *new, third surface* aimed at a different reader —
the downstream agent.

## The hard constraint (shapes everything below)

There is **no automatic cross-repo bridge for JVM/Gradle.** The npm trick —
shipping `AGENTS.md` inside a package so it lands in `node_modules/<pkg>/` where
the consumer's agent walks it by default — does not transfer. kuilt resolves to
an opaque JAR in `~/.gradle/caches/`, not a browsable source tree.

Therefore "any consumer inherits" **cannot mean zero-wiring inheritance.** It
means: **source-of-truth artifacts live in kuilt, versioned with the library,
and each consumer opts in with one cheap, standing step.** The one leak-through
we exploit: kuilt's presence-gated `includeBuild("../kuilt")` means that when a
consumer is developed side-by-side, kuilt source *is* on disk at `../kuilt`, so a
consumer-side pointer can reference `../kuilt/docs/...` directly.

Push beats pull. Coding agents reliably skip anything requiring a discretionary
fetch (evidence: llms.txt / Context7 have no demonstrated consumption for this
use case). The mechanisms that work put the pointer where the agent *already
looks by default*.

## Scope of this first pass

**In:** Layer 2 (intent-indexed cookbook) + Layer 3 (shippable skill). These are
the mechanisms that actually work: the skill is *push* (fires only when a
matching task is described, costs nothing otherwise, scolds no one), and the
cookbook is what it routes to.

**Layer 1 (a detekt reinvention ruleset) is dropped — see the "Rejected" section
below.** Static analysis detects *banned symbols* well and *the shape of
reinvention* terribly, and a consumer-installed ruleset that scolds toward
another library is an intrusive ask most consumers correctly refuse. It is not
built and is not in the implementation plan.

**Explicitly skipped:** llms.txt and Context7 submission. Pull mechanisms with
no demonstrated consumption; only relevant for external consumers we don't
control. Noted as a cheap future hedge, not part of this.

## Design

### Layer 2 — the intent-indexed cookbook (source of truth)

A new markdown surface in kuilt: `docs/agent-cookbook.md` (working title). **Not**
an "Internals walkthrough" — a **symptom → primitive reverse index**, keyed by
the words a *developer describes their problem with*, not by module or
architecture.

Each entry is terse and self-contained:

- **Intent** — phrased as the problem/mistake ("resume a session after a dropped
  connection", "a counter that converges across peers with no server", "drop
  duplicate frames", "detect a peer that went silent").
- **Primitive** — the exact kuilt type/entry point (`ResumeToken` +
  `SeamRoom` reconnect flow; `PNCounter`; `HeartbeatPartitionDetector`; …).
- **One-line call** — the minimal usage, copy-pasteable.
- **`@sample` link** — pointing at a compiled sample function (see "Anti-rot").

Requirements:

1. **Code is mandatory, not decorative.** Removing runnable code from
   agent-facing docs collapses retrieval pass rates (cited: 66–82% → 22–39%).
   Every entry carries a runnable snippet.
2. **Self-contained, jargon-consistent.** No "as described above" cross-refs;
   each entry retrieves standalone. Consistent symbol naming so a grep for the
   problem phrase lands.
3. **Opens with a "don't build this yourself" tripwire list** — the anti-pattern
   framing, phrased as the *mistake* ("if you're writing a heartbeat, a dedup
   set, or a reconnect token, you're reinventing kuilt — see below"), because
   that is what an agent mid-reinvention recognizes.
4. **Grouped by capability area**, mirroring the module map (fabric / room &
   reconnect / replicated data / consensus / discovery / dealing), but each row
   is intent-first.

This artifact doubles as the human "Internals" doc Iain wanted — just structured
for lookup instead of narrative.

### Layer 3 — the shippable skill (the bridge into consumer context)

A Claude Code skill authored **in kuilt's repo** at a canonical, discoverable
path (working location: `.claude/skills/kuilt-primitives/SKILL.md`). It also
helps agents working *inside* kuilt, and is the copy-source for consumers.

Mechanics:

- **Trigger by the *consumer's* problem phrasing, not kuilt's.** A skill only
  loads if its `description` matches the prompt, and the survey (below) proves
  the consumer describes these tasks in *different words* than kuilt names them:
  fireworks says **rejoin / table / seat / host / idle reaper / holding the
  slot**; kuilt says **resume / Room / Member / leader / liveness**. A
  description written in kuilt's vocabulary silently fails to fire on the
  consumer's vocabulary — the exact silent-miss failure mode. So the description
  MUST enumerate the downstream synonyms from the nomenclature map (below), and
  **the phrasing is tested against real prompts, not written once** (see
  Validation).
- **Body = pointer, not payload.** It routes to the Layer-2 cookbook rather than
  duplicating it. Resolution order: the local cookbook if the agent is in kuilt;
  `../kuilt/docs/agent-cookbook.md` when developed side-by-side via
  `includeBuild`; otherwise the published docs URL.
- **Consumer opt-in is one step:** copy/symlink the skill into the consumer's
  `.claude/skills/`, documented in `docs/usage.md` (the consumer-integration
  doc), not in the accessible landing surfaces.

### Rejected: a detekt "reinvention" ruleset

Recorded so the idea isn't re-proposed. Static analysis detects a **banned
symbol** precisely (one import to match, near-zero false positives) but detects
**the shape of reinvention** terribly — "you hand-rolled a heartbeat" /
"you reinvented a reconnect token" is semantic-intent detection. A rule matching
every `while(true){ delay(); send(...) }` or every `*Token` class fires on
legitimate code, gets suppressed repo-wide on day one, and is net-negative. This
is exactly the complaint kuilt is trying to solve (agents inventing
reconnect/dedup/CRDT *shapes*), and lint cannot see it. Adoption is the harder
wall: a consumer installing a third-party ruleset that *scolds them toward
another library* is an intrusive, unusual ask most consumers correctly refuse.
**Dropped — not built, not planned.** (If a genuine 1:1 banned-import ever
appears — kuilt ships `X`, reinvention is literally a specific competing import
like `java.util.Timer` — that is a thin opt-in rule and a separate future
issue, not part of this work.)

## Seed data — from the fireworks-compose survey (2026-07-18)

A read-only survey of the one real consumer (`fireworks-compose`, `main`, 244
`.kt` files importing `us.tractat.kuilt`, pinned `kuilt = 0.7.0-dev.788`) grounds
the cookbook entries and the skill's trigger vocabulary in *real* usage and
*real* reinvention. It also shows the "discovered ceiling": live-runtime's
lobby/presence/annotation/chat stack is already a textbook consumer
(`Quilter<LWWMap<…>>` throughout) — so the reinvention is what sits *outside*
the already-adopted core.

### Cookbook seed entries (reinvention → the primitive it should use)

Each becomes a symptom→primitive cookbook row. Ranked by the survey's confidence.

| Consumer reinvention (file) | kuilt primitive the cookbook routes to |
|---|---|
| Homegrown reconnect state machine + fixed-list backoff; `ResumeToken` imported but effectively unused in the live reconnect path — the resume/grace window is re-tracked app-side (`live-runtime/.../session/ReconnectPolicy.kt`, `ReconnectLoop.kt`, `PeerPresence.Paused`) | `session.partition.ResumeToken`/`ResumeResult` + `SeamRoom` resume flow; `core.util.ExponentialBackoff` for the backoff |
| Homegrown turn-based session facade: `propose(action) → Proposed/Authoritative/Rejected`, `HostElected(peer, term)`, `Snapshot`/`requestResync()` (`session-protocol/.../PeerSession.kt`, `PeerEvent.kt`) — a partial migration frozen mid-way (app already imports `gameHost` in 8 files) | `:kuilt-game` `GameSession` + `TurnSequencer` (propose/commit `IndexedAction`) over `:kuilt-raft` |
| Hand-rolled idle/liveness reapers: `IdleReaper` `while(true){ delay() }` (`server/.../SignalingRoutes.kt`), `SessionJanitor` idle-evict (`mcp-server/.../SessionJanitor.kt`), 1 Hz `hb.<seq>` Multipeer heartbeat (`composeApp/.../MultipeerDiagnosticsScreen.ios.kt`) | `:kuilt-liveness` `HeartbeatPartitionDetector` / `HeartbeatConfig` (with the gap caveat below — connection-idle reaping is a slightly different shape) |
| Ad-hoc dedup seen-set: `mutableSetOf<Long>()` filtering already-pushed ids (`server/.../LiveRoutes.kt`) | `:kuilt-crdt` `GSet` / kuilt dedup (low stakes) |

The `session-protocol` and `ReconnectPolicy` rows are the highest-value cookbook
entries: they are exactly "kuilt already provides the facade, the consumer built
a parallel one." The cookbook's opening tripwire list names these by their
consumer symptom ("if you're writing a rejoin loop / a propose-commit session / a
heartbeat reaper — kuilt has it").

### Skill trigger vocabulary (the nomenclature map)

The skill `description` and the cookbook entry headings must carry **both**
vocabularies so a grep/description match lands on the consumer's word. Load-bearing
mismatches:

| Capability | kuilt term | consumer term(s) the skill MUST also match |
|---|---|---|
| Rejoin after drop | `ResumeToken`, resume, "reconnect window" | **rejoin**, reconnecting, "grace window", "holding the slot", "server holds the seat open" |
| Room / session container | `Room`, `SeamRoom`, `Rendezvous` | **table**, **lobby**, **hub**, seat roster |
| Peer | `PeerId`, `Member` | **peer**, **seat**, spoke/host |
| Presence / liveness | `Liveness`, `HeartbeatPartitionDetector` | **presence**, **heartbeat**, **idle reaper**, "evict stale session", lastSeen, isPaired |
| Leader / consensus | `RaftRole`, leader election, term, `TurnSequencer` | **host**, "who hosts / tiebreak / lowest peer wins", propose/Authoritative/Rejected |
| Retry/backoff | `ExponentialBackoff` | back-off, `ReconnectPolicy`, `delayBefore(retryNumber)` |
| Dedup | kuilt dedup / `DedupKey` | **seenIds**, dedupe, "skip-if-exists" |
| Replicated state | `Quilter`, `LWWMap`, CRDT | already adopted verbatim — presence patch, directory CRDT |

### Genuine gaps → follow-up kuilt issues (NOT cookbook rows)

The survey found four things the consumer needs that kuilt does **not** provide —
real missing primitives, not reinvention. These are **not** cookbook entries
(there's nothing to route to); each is filed as its own kuilt issue so the
decision to build-or-decline is tracked. Per the proactive-follow-up convention,
file them in the same pass:

1. **Pre-Seam bootstrap leader election** — deterministically pick a host from a
   set of *discovery advertisements* before any `Seam` exists (QuickPlay's
   `globalMin(peers)` over an mDNS/Multipeer snapshot). kuilt election is
   intra-`Seam`; this is repeatedly hit.
2. **A reconnect *state/policy* taxonomy for UI** — the transient / auth-expired /
   protocol-mismatch / unrecoverable *classification* (`ConnectionState` /
   `ReconnectReason` / `FailureReason`) is a reusable networking taxonomy kuilt
   doesn't expose, even though it owns the mechanics.
3. **Server-side seat-hold across a client drop** — kuilt has client-side
   `ResumeToken`; the *server holding a seat open for a grace window and
   broadcasting a Paused presence* is built app-side. If kuilt intends to own
   reconnect, this is the missing server half.
4. **Idle-reaping with a "not-idle-while-solo" gate** — close a half-formed room
   that never filled (`IdleReaper.isPaired`); kuilt-liveness detects peer
   partition, not "never-paired room."

Filing these is also the honest answer to "make discoverability better": some
reinvention exists because the primitive genuinely isn't there yet.

## Anti-rot: keeping the cookbook honest

The cookbook's snippets cite **compiled `@sample` functions** in
`src/commonSamples/kotlin/` (the existing infra — samples are compiled as part of
`commonTest`, so an API change that breaks a sample breaks the build). New
cookbook entries either reuse existing samples or add new ones. The cookbook
prose block is the human-readable copy; the `@sample` is the compile-checked
source of truth. This is the same discipline the Writerside `<!-- verbatim from
… -->` convention uses, reused here.

`@sample` compile-checks that a snippet still *compiles* — it does **not** catch
a *new* primitive that ships with no cookbook entry, or a skill `description`
that no longer matches how the primitive is described. Those are prose-drift
failures with no compile-time guard, so they need a **written maintenance
convention** (below).

### Maintenance convention (CLAUDE.md change — REQUIRED)

Add a rule to `CLAUDE.md` (the repo instructions) so the cookbook and skill are
kept in sync as the API moves. Wording to land, in the docs-sync area of
CLAUDE.md:

> **Agent cookbook + skill stay in sync with the primitives.** When you add,
> rename, or remove a public primitive that a downstream consumer would reach for
> (a fabric, a `Room`/reconnect entry point, a CRDT, a liveness detector, a
> consensus/`GameSession` entry point, a dealing/gossip primitive): (1) add or
> update its **symptom→primitive** entry in `docs/agent-cookbook.md` with a
> compiled `@sample`; and (2) check that `.claude/skills/kuilt-primitives/`
> still routes to it and that the skill `description` still matches how a
> developer would phrase the need. A new primitive with no cookbook entry is the
> failure mode this whole surface exists to prevent — treat a missing entry as a
> broken build even though nothing enforces it automatically.

Rationale for putting it in CLAUDE.md rather than trusting memory: this surface's
entire value is *completeness* — one undiscovered primitive is one reinvention.
The existing CLAUDE.md "Keeping docs in sync with code" section is the precedent;
this is a sibling bullet.

## Verified anchors (real symbols, not invented)

- `ResumeToken` — `kuilt-session/src/commonMain/.../partition/ResumeToken.kt`
- `SeamRoom` reconnect flow — `kuilt-session/.../SeamRoom.kt`,
  `JoinerReconnectController`, `JoinerResumeMachine`
- `HeartbeatPartitionDetector` — `:kuilt-liveness`
- CRDT zoo (`PNCounter`, `ORSet`, `LWWRegister`, …) — `:kuilt-crdt`
- `@sample` infra present in 11 modules' `src/commonSamples/`

## Non-goals / out of scope

- No change to the accessible-first published surfaces (Writerside landing,
  README opening, the descent narrative).
- No llms.txt, no Context7 submission (pull mechanisms, external-consumer hedge
  only).
- No detekt ruleset at all (Layer 1 dropped — see "Rejected").
- No attempt to make the artifact auto-visible across the Maven boundary — the
  constraint above rules that out; consumer opt-in is accepted and made cheap.

## Validation

The skill's leverage is entirely hostage to its `description` firing on the
consumer's phrasing, and that failure is *silent* (the skill just doesn't load).
So validation is not optional and not "read it once":

- **Prompt-match test.** Take the phrase-level tells from the survey — *"rejoin
  the table"*, *"reconnect banner / grace window"*, *"is this peer still alive"*,
  *"idle reaper / evict stale session"*, *"dedupe by game id"*, *"who hosts /
  tiebreak / lowest peer wins"* — and confirm each causes the skill to load and
  route to the right cookbook entry. Any miss is a `description` fix, not a
  known-limitation.
- **Compile check.** Every cookbook snippet is a compiled `@sample`; `./gradlew
  build` breaking on a bad snippet is the regression guard.
- **Completeness check.** Cross-check the cookbook's capability rows against the
  module map in `CLAUDE.md` — a public primitive a consumer would reach for with
  no row is the failure this surface exists to prevent.

## Success criteria

- An agent working in a consumer repo, briefed with the skill, describes a
  reconnect/sync/dedup task *in the consumer's own words* and is routed to the
  correct kuilt primitive *instead of* writing a new one.
- Every cookbook snippet compiles (via `@sample`), so the doc cannot silently
  drift from the API.
- The four genuine-gap issues are filed, so reinvention-because-it-doesn't-exist
  is tracked separately from reinvention-because-it-wasn't-discovered.
