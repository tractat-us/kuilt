@AGENTS.md

**Do this first, before answering or acting on anything:** if no text from `AGENTS.md` appears above this line, your session started below the repository root and the `@AGENTS.md` import was skipped. Use the Read tool on `AGENTS.md` at the repository root now (and on any `AGENTS.md` in the directory you are working in), then follow it — it holds this repo's rules.

## Claude Code

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Families

kuilt's agent-facing docs are split per family: a path-scoped rule file loads
automatically when an agent reads a file under one of that family's modules, and
the matching cookbook file is where the symptom→primitive snippets live.

| Family | Rule file | Cookbook file |
|---|---|---|
| Fabrics | `.claude/rules/fabrics.md` | `docs/agent-cookbook/fabrics.md` |
| Session | `.claude/rules/session.md` | `docs/agent-cookbook/session.md` |
| Replication | `.claude/rules/replication.md` | `docs/agent-cookbook/replication.md` |
| Consensus | `.claude/rules/consensus.md` | `docs/agent-cookbook/consensus.md` |
| Heddle | `.claude/rules/heddle.md` | `docs/agent-cookbook/heddle.md` |
| Warp | `.claude/rules/warp.md` | `docs/agent-cookbook/warp.md` |
| Otel | `.claude/rules/otel.md` | `docs/agent-cookbook/otel.md` |

## Documentation

### Session scratch is NOT documentation — never commit it

`morning-report.md`, `morning-report-*.md` and `next-plan.md` / `next-plan-*.md` are **hand-off
notes between Claude sessions**, written by `/goodnight`, `/next` and `/donext`. They are gitignored
and must stay that way. Do not `git add -f` one, and do not "preserve" one under a dated filename.

They are the opposite of the two surfaces above on every axis that matters. A report is accurate for
*hours*: it names in-flight PRs, worker ids, branch names and a `main` SHA, and every one of those
claims silently inverts as the work lands — so a reader who finds it in the repo is reading
confident, specific, wrong information with nothing marking it stale. It is addressed to one
session, not to a consumer of the library. And it leaks exactly what the References policy below
keeps out: organisational context, other repos' state, who was dispatched where.

Keeping the *content* is not the reason to commit the file. A report's durable half is small and
belongs where it is already checked: a real finding becomes a **GitHub issue**, a convention learned
becomes a line **in this file**, an architectural decision becomes an **ADR**. What is left after
that extraction is a log, and a log's home is the working tree, not `main`.

Three of these were tracked before #2606 — that PR committed a fourth by following their precedent
rather than questioning it, which is the trap: the presence of a stale scratch file in history reads
as permission to add another. If you find one tracked, untrack it (`git rm --cached`, the file stays
on disk) rather than treating it as an established convention.

### The `kuilt-primitives` skill is part of the public surface

`.claude/skills/kuilt-primitives/SKILL.md` is how a coding agent working in a *consumer* repo
discovers what this library already provides. It is **source of truth**: consumers vendor a
byte-identical copy and refresh it on a daily sync job, so whatever is here propagates outward
unreviewed. The cookbook is its long form — the skill intercepts, the cookbook explains — and the
cookbook is now an index (`docs/agent-cookbook.md`) over seven family files
(`docs/agent-cookbook/<family>.md`), so a route names one family file, not the whole thing.

kuilt is where this pattern started and two sibling libraries (`fgn`, `hanab-kt`) copy it, so treat
the rules below as the reference version rather than a local convention.

- **Adding a public primitive means updating the skill in the SAME PR** — a route in the skill's
  index table, the trigger phrases in `description` that should reach it, and a symptom→primitive
  entry in the family file the route names, `docs/agent-cookbook/<family>.md`, quoting a compiled
  snippet (`<!-- verbatim from … -->`), with its row in the `docs/agent-cookbook.md` index
  alongside. A primitive nobody is routed to gets reinvented downstream. Same obligation for a
  fabric, a `Room`/reconnect entry point, a CRDT, a liveness detector, a consensus/`GameSession`
  entry point, a dealing/gossip primitive — anything a consumer would otherwise hand-roll.
- **But the `description` is a fixed budget, so adding a trigger means removing one.** The index
  table in the body is free of *that* cap — a skill body is lazy. A compact index routes,
  prose does not: the body is capped at **8,192 bytes (8 KiB)** of UTF-8 after the closing
  frontmatter delimiter, enforced by `verifySkillBodyBudget` in `check` and in the
  `doc-citations` CI job. Its measured size is recorded in
  `build/verification/verify-skill-body-budget.ok`. The `description:` is **eager**,
  loaded on every turn of every session and every subagent in every repo that vendors this file,
  and Claude Code truncates it at `skillListingMaxDescChars`, **default 1,536 characters**. Past that a phrase
  is not weak, it is **absent**: it never reaches the deciding model, so it routes nothing, and
  nothing reports it.
  This file's own advice caused the failure — the bullet above says "add the trigger phrases", so a
  skill that wasn't firing kept getting phrases appended, and `kuilt-primitives` reached 8,155
  characters of which **81% had never been seen by any model** (#2662). The receipt is #2572, and it
  is the whole argument in one line: `6e80d89b` fixed a skill that was failing to route by
  **appending** its `pumpIn` trigger — *"OR collecting a flow for the life of a session — a
  background pump, a long-lived collector, `launchIn`, …"* — as a 562-character run landing at
  offset 6,878, with `pump` at 6,942 and `launchIn` at 6,973 against a 1,536 cap. **The routing fix
  for a routing failure was itself unroutable**, and nothing said so. Appending is the natural
  remedy and is precisely the operation that cannot work.

  **And this is not archaeology — it was still happening the night the guard landed.** `2903e32b`
  (#2688) added a discovery-isolation route, correctly, in the same PR as the primitive, exactly as
  the bullet above requires; it appended 252 characters at offset 5,256, putting its `discovery`
  trigger at **5,294** of an 8,155-character description. Written, reviewed and merged by people
  following the documented process, and it would have routed nothing, forever. That is what makes
  this a guard rather than a note: every party was doing as instructed, and the instruction was
  wrong.

  Explanatory prose belongs in the cookbook. The body is outside the eager cap but capped at
  **8,192 bytes (8 KiB)** by `verifySkillBodyBudget`; a trigger only earns its place by
  displacing another. `verifySkillDescriptionBudget` (root build,
  in `check` and in the `doc-citations` CI job, since a SKILL.md edit is docs-only) enforces the cap,
  and also rejects the `: ` and ` #` that silently break the unquoted plain scalar and stop the skill
  loading altogether. **That half has already happened too, and it is not a near-miss:** before
  `e0aa31a4` (#2541) one bare `: ` inside the scalar made the frontmatter **invalid YAML**, so the
  skill was absent from every listing — routing nothing at all — until that commit replaced the
  colon with an em-dash by hand. Note what #2541 is *filed* as: vendored drift, and the fix as
  "repair the canonical skill". It was not drifting, it was **broken** — and the reason nobody could
  tell is the reason this guard exists, since an unparseable frontmatter and a merely-absent skill
  look identical from outside. So **both** things the guard checks have already bitten here, and
  neither announced itself.
- **It must never be more than 7 days behind the library.** `.github/workflows/skill-staleness.yml`
  opens a tracking issue when published `commonMain` moves and the skill doesn't; it deliberately
  does not fail a build, because a stale skill is not a reason to block someone else's merge. The
  same job makes the same comparison **per family** — each family's modules, read out of
  `.claude/rules/<family>.md`'s `paths:`, against the newer of that file and
  `docs/agent-cookbook/<family>.md`, under its own issue title. That arm exists because one touch of
  `SKILL.md` clears the whole-library comparison for all seven families at once, so a cookbook page
  nobody has opened in a year sits behind a green check.
- **Renaming or removing a primitive means fixing the route, not leaving it dangling.** A route
  naming the wrong symbol is worse than no route — it is confident, and the agent trusts it over
  its own reading. Verify against the declaration in source, never against a doc page.
- **Route to a symbol a consumer can actually reference.** `→ use the CRDT module` is prose and
  changes no behaviour; naming `LWWRegister` does. And check the visibility: the rejoin route named
  `SeamRoom` for a year, which is `internal`.
- **The frontmatter is parsed as YAML — keep it parseable.** The `description` is one enormous
  unquoted plain scalar, so a bare `: ` or ` #` inside it breaks the whole block and the skill stops
  loading. Prefer an em-dash. `ruby -ryaml -e 'YAML.safe_load(File.read(ARGV[0]).split(/^---$/)[1])'`
  settles it in a second.
- **Never hand-edit a vendored copy in a consumer repo.** It is a cache; fix it here and let the
  sync carry it down. The corollary is that this copy is the last line of defence — an auto-sync
  propagates corruption just as faithfully as content (#2541).

## CI & merging

**Don't hand-roll a merge poll — run `~/.claude/bin/gh-pr-wait <PR> --arm-auto`.**
It exits on a terminal state (`0` merged, `1` gate failed, `2` conflict, `3`
timeout, `4` closed, `5` blocked) and encodes the traps below. This paragraph
used to describe those traps and expect you to apply them by hand; that failed
twice in one session while landing #1760, which is why the fix is now executable.

**The trap, and why the obvious fix is also wrong.** A drafted-then-readied PR
keeps a STALE `ci-required` FAILURE in `statusCheckRollup`: opening as Draft
skips the `build-jvm` / `build-native` jobs, so the aggregator records a
`FAILURE` for that draft run, and marking the PR ready starts a fresh run while
GitHub leaves **both** entries under the `ci-required` name. Scanning the rollup
for the substring `FAILURE` false-alarms on every iteration. **But keying the
verdict to the newest run id — the fix this file used to recommend — is also
wrong:** for ~30 s after `gh pr ready` the post-ready run does not exist yet, so
the newest row *is* the stale draft row. The sound discriminator is structural:
**a run whose non-aggregator jobs are all `SKIPPED` executed nothing, and is
never a verdict.** Do not avoid it by opening the PR ready: the claim is the
Draft PR (see AGENTS.md), so this stale row is the expected cost of every
draft→ready PR.
