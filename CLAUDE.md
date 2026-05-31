# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**kuilt** is a peer-symmetric, multiplatform networking library. It moves
opaque byte frames between peers over interchangeable *fabrics* (WebSocket,
mDNS-discovered LAN, Apple Multipeer, WebRTC, Android Nearby) behind one
contract. It knows nothing about the application semantics that ride on top —
that's the consumer's job.

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
  `kuilt.publish` wires the in-tree `TigrisStaging` (file://) Maven repo that
  `publish.yml` stages publications into. New modules apply
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
- **Coroutine test determinism:** types that own a `CoroutineScope` take an
  injectable dispatcher (production default), or inherit `currentCoroutineContext()`;
  tests inject `UnconfinedTestDispatcher(testScheduler)` rather than letting a real
  production dispatcher run under `runTest` (the cause of a past Kotlin/Native flake).
  See `docs/testing-coroutine-determinism.md`.

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

The version is parameterized by `kuiltVersionLine` in `gradle.properties`
(currently `0.3`). Both `build.gradle.kts` (which sets the local default to
`${kuiltVersionLine}.0-dev`) and `publish.yml` (which publishes
`${kuiltVersionLine}.<run_number>` on every main push) read it, so a
breaking-API release is a **one-line PR bumping the line**. Group is
`us.tractat.kuilt`.

**Publishing runs on every push to `main`** (plus manual `workflow_dispatch`).
No tag is required. The publish flow is:

1. Gradle stages publications into `build/staged-maven-repo/` via the
   `TigrisStaging` Maven repo (file:// URL).
2. `aws s3 sync build/staged-maven-repo/ s3://buildcache/maven/` uploads the
   whole tree to **Tigris** (Fly's S3-compatible storage) in one parallel
   pass.

Wall time is ~5 min total, ~12–20 s for the upload itself. This is ~40× faster
than the prior GitHub Packages path (#22), which is why per-merge publishing
is back on instead of tag-only.

Bypassing Gradle's native `s3://` transport is deliberate: it silently sets
a request header (ACL or storage-class) Tigris rejects with HTTP 400. The
stage-then-`aws s3 sync` pattern sidesteps it entirely. Consumers reading
from Tigris hit the same Gradle s3:// transport for GETs, but GETs don't
set those headers so the read path works.

GitHub Packages still hosts the historical 0.1.x and 0.3.1/0.3.2/0.3.4
artifacts (read-only — consumers can still resolve them from
`https://maven.pkg.github.com/tractat-us/kuilt` if needed). New versions go
to Tigris only.

## Composite-build consumption

Consumers should depend on kuilt via published coordinates
(`us.tractat.kuilt:kuilt-*:<version>`). For zero-latency iteration when a
consumer is developed alongside kuilt, the standard pattern is a
**presence-gated `includeBuild`** in the consumer's `settings.gradle.kts`:

```kotlin
if (file("../kuilt").exists()) includeBuild("../kuilt")
```

Absent the side-by-side checkout (CI, ephemeral worktrees), the published
artifact resolves. The public API and Maven coordinates are the compatibility
surface — keep them stable across patch versions.

## References policy

When documenting kuilt (KDoc, README, design docs, commit messages, PR bodies,
this file), follow two rules:

- **Don't reference external projects / issues / PRs without explicit approval.**
  Citations to third-party trackers date quickly and mislead future readers —
  what's "the bug" today may be "the fix" tomorrow (see #24's history: it cited
  gradle/gradle#8950 as the *cause* of serial publishes when that issue is
  actually where the fix landed in 2019). If a citation is genuinely necessary,
  ask first.
- **Avoid references to other `tractat-us/*` repos where possible.** kuilt
  ships as a standalone library; cross-repo references leak organisational
  context that doesn't belong in this codebase and become dangling if those
  repos move or restructure. Describe kuilt's own behaviour and contracts in
  terms that stand alone. (Wire identifiers shared with consumers — service
  types, dylib names, cdecl symbols — are the unavoidable exception and stay.)
