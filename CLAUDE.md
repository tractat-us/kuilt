# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**kuilt** is a peer-symmetric, multiplatform networking library — the transport
layer extracted from `tractat-us/fireworks-compose` (epic
[#1515](https://github.com/tractat-us/fireworks-compose/issues/1515)). It moves
opaque byte frames between peers over interchangeable *fabrics* (WebSocket,
mDNS-discovered LAN, Multipeer, WebRTC) behind one contract. It knows nothing
about Hanabi, games, or session semantics — that lives in the consumer.

Published to GitHub Packages under `us.tractat.kuilt:*`. Kotlin Multiplatform
(JVM, Android, iOS, macOS, wasmJs). See `docs/architecture.md` for the design
and `docs/usage.md` for how to consume it.

> **Status: in active development (pre-1.0).** The API and module layout are
> still moving as the extraction lands. Bias toward **aggressive, low-ceremony
> merging** — small PRs, auto-merge once green, fix-forward over long review
> cycles. The only hard gate is the `ci-required` check (below); everything else
> (up-to-date branches, reviews, signed-off discussions) is intentionally relaxed
> while the foundation settles.

## Module structure & dependency direction

| Module | Targets | Role |
|--------|---------|------|
| `:kuilt-core` | all | The contract (`Loom`/`Seam`/`Swatch`/…), the `InMemoryLoom` reference impl, and `SeamConformanceSuite`. Depends on nothing but coroutines + serialization. |
| `:kuilt-websocket` | all | Ktor WebSocket fabric — the "Far"/relay topology. `KtorClientLoom` everywhere; `KtorServerLoom` on JVM/Android only. |
| `:kuilt-mdns` | JVM, Android, iOS | Bonjour/mDNS discovery. On JVM it depends on `:kuilt-websocket` (discovery feeds a WebSocket connection — discovery is orthogonal to topology). |

Every fabric module depends only on `:kuilt-core`. The dependency arrow never
points back up — `:kuilt-core` must stay free of fabric-specific imports.

## The contract (one-paragraph orientation)

`Loom` is a factory: `weave(Rendezvous): Seam` is the single abstract method —
pass `Rendezvous.New(pattern)` to host a session or `Rendezvous.Existing(tag)` to
join one. Convenience wrappers `host(Pattern)` and `join(Tag)` delegate to `weave`.
`availability(): FabricAvailability` reports whether the fabric is usable on this
runtime. `Seam` is one peer's *symmetric* view of a multi-peer session — there is
no client/server split at this layer; a 2-peer WebSocket connection is just the
degenerate `peers.size == 2` case. `Swatch` is the opaque, binary-only frame.
`incoming: Flow<Swatch>` is **single-collection** — collect it once per `Seam`;
fan out with `shareIn`, never collect twice. This is the cohered contract of
ADR-034 / ADR-002; the full rationale is in `docs/architecture.md`.

## Build & test commands

Non-interactive shells don't load `~/.zshrc`, so source SDKMAN and select JDK 21
first (matches CI):

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem
```

| Task | Command |
|------|---------|
| Full build + all tests | `./gradlew build` |
| JVM tests only (fast inner loop) | `./gradlew jvmTest` |
| One module's JVM tests | `./gradlew :kuilt-core:jvmTest` |
| A single test class | `./gradlew :kuilt-core:jvmTest --tests "*SwatchTest"` |
| All platforms' tests | `./gradlew allTests` |
| wasmJs / iOS sim / macOS | `./gradlew wasmJsTest` · `iosSimulatorArm64Test` · `macosArm64Test` |
| mDNS multicast integration (off by default — needs a real network) | `./gradlew :kuilt-mdns:jvmTest -Pmdns.multicast.tests=true` |

The mDNS multicast suite is opt-in because it sends real multicast packets; the
`-P` flag is forwarded to JVM tests as a system property and to K/N simulator
tests as the `MDNS_MULTICAST_TESTS` env var (see `kuilt-mdns/build.gradle.kts`).

## Conventions specific to this repo

- **`explicitApi()` is enforced** (set in the `kuilt.kmp-library` convention
  plugin). Every public declaration needs an explicit visibility modifier or the
  build fails. New public types get `public`.
- **Build logic is centralized** in `build-logic/`: `kuilt.kmp-library` defines
  the standard target set + Android namespace (`us.tractat.kuilt.<module>`);
  `kuilt.publish` wires the GitHub Packages Maven repo. New modules apply
  `id("kuilt.kmp-library")` and almost nothing else.
- **KMP source-set hierarchy is wired by hand in `:kuilt-websocket`** — a manual
  `jvmAndAndroidMain` intermediate (Ktor server is JVM/Android-only) disables the
  plugin's default auto-wiring, so `iosMain`/`macosMain` intermediates are also
  declared explicitly. Edit those `build.gradle.kts` source-set blocks carefully.
- **Test a new fabric by subclassing `SeamConformanceSuite`** and implementing
  `newLoomPair()`. Every fabric must pass the same suite (see
  `InMemoryLoomConformanceTest`). In-process radio fabrics return the same instance
  twice; role-split fabrics return distinct host/joiner Looms wired to each other.
  Real-radio/real-network tests stay separate and `-P`-gated; the conformance suite
  runs against an in-memory or loopback harness.
- Test methods: no `test` prefix (the `@Test` annotation suffices); multi-assert
  tests use `assertAll()`.

## CI & merging

`.github/workflows/ci.yml` uses an aggregator pattern: a cheap `detect` job
classifies the change, the heavy `build` job (`./gradlew build`) runs only when
the change is **not** docs-only, and `ci-required` aggregates them into the one
required status check. **Docs-only PRs** (every changed file is `*.md` or under
`docs/`) **skip the build** — `ci-required` goes green without it — so the many
documentation PRs don't wait on a KMP build. Touch any non-doc file and the full
build runs.

`main` is branch-protected to require only `ci-required` (no required reviews,
no up-to-date-branch requirement, admins not enforced — matching the aggressive
pre-1.0 posture). Auto-merge is enabled and head branches are deleted on merge,
so the normal flow is: open PR → `gh pr merge <n> --auto --squash` → it lands as
soon as `ci-required` is green.

## Versioning & publishing

`build.gradle.kts` sets `group = us.tractat.kuilt`; version is `0.1.0-dev`
locally and is supplied via `-Pversion=` when publishing.

**Publishing is tag-driven and intentional — it does NOT run on every `main`
push.** Per-merge publishing imposed a ~30-min tax (#24: `maven-publish`
serializes ~600 GitHub-Packages uploads behind one lock — see
[gradle/gradle#8950](https://github.com/gradle/gradle/issues/8950) — which no
cache or parallelism fixes). While the API is still settling, cut a release when
you actually want one:

```bash
git tag v0.1.7 && git push origin v0.1.7    # → publishes us.tractat.kuilt:*:0.1.7
```

`.github/workflows/publish.yml` triggers on `v*` tags (version derived from the
tag) or manual `workflow_dispatch` (optional version input, else a
`0.1.<run_number>` snapshot). Day-to-day, `fireworks-compose` consumes kuilt via
the `includeBuild` override (below); cut a tag when you want fireworks CI — which
resolves the *published* artifact — to pick up new kuilt changes.

## Composite-build relationship to fireworks-compose

Per the extraction roadmap, `fireworks-compose` consumes kuilt via published
coordinates, with a **presence-gated local override**: a checkout at `../kuilt`
beside the fireworks repo is wired in via `includeBuild` for zero-latency
iteration, otherwise the published artifact resolves. Promotion to fully
published artifacts (dropping the `includeBuild`) is Phase 5. Keep the public
API and Maven coordinates stable; downstream substitution depends on them.
