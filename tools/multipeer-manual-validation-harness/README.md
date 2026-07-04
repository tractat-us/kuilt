# Multipeer manual validation harness

Throwaway rig used to run the manual hardware checklist in
`../../docs/otel-tap-multipeer-validation.md` (issue #1042 / PR #1074). Not part of the
kuilt build — it's a standalone composite build (`includeBuild("../..")`) that depends
on kuilt's published module coordinates and resolves them against the local checkout,
so it always tracks whatever commit this directory is checked out at.

**Not wired into CI, not meant to be kept green forever** — it's a snapshot of what was
used to validate the Multipeer reach path on real hardware once. Kept for the next time
someone needs to re-run (or extend) that checklist rather than rebuilding it from scratch.

## Layout

- `harness/` — a small Kotlin Multiplatform module (`jvm` + `iosArm64`).
  - `src/commonMain` — shared setup: issues a join token, seeds a few log records / one
    metric counter, wraps `installLogTap`/`installMetricTap` behind a
    `TapHostController` with a Swift-friendly callback API (`startLogTap`/
    `startMetricTap`/`stop`).
  - `src/jvmMain` (`MacMain.kt`) — the Mac-side puller. `MultipeerPeerLinkFactory`'s JVM
    target bridges to the same Kotlin/Native `libkuilt.dylib` `:kuilt-multipeer` builds
    for macOS via JNA, so this runs as a **plain JVM program — no Xcode needed** for the
    Mac side. Run it with `./gradlew :harness:runMac -PharnessArgs="<log|metric> <code>
    [discoveryTimeoutSeconds]"`.
  - `src/iosArm64Main` — built as an embeddable framework
    (`./gradlew :harness:linkDebugFrameworkIosArm64`) for the iOS app below.
- `MPHarnessApp/` — a minimal SwiftUI iOS app (project generated via
  [XcodeGen](https://github.com/yonaskolb/XcodeGen) from `project.yml`) that links the
  framework and exposes a mode picker + start/stop button showing the join code. This is
  the iPhone side — Multipeer doesn't work reliably in iOS Simulators, so it has to run
  on a real device.

## Re-running the checklist

1. `./gradlew :harness:linkDebugFrameworkIosArm64` (builds `Harness.framework` for a real
   device).
2. `cd MPHarnessApp && xcodegen generate` (regenerates `MPHarnessApp.xcodeproj` — not
   committed as a build artifact, see `.gitignore`... actually it *is* committed here for
   convenience; regenerate if `project.yml` changes).
3. `xcodebuild -project MPHarnessApp.xcodeproj -scheme MPHarnessApp -destination
   'id=<device-id>' -allowProvisioningUpdates build`, then `xcrun devicectl device
   install app` + `... process launch`.
4. On the phone: pick a mode, tap **Start hosting**, read off the join code.
5. On the Mac: `./gradlew :harness:runMac -PharnessArgs="log <code> 20"` (or `metric`).

`project.yml`'s `DEVELOPMENT_TEAM` (`F4S2NUR9VL`) and the signing identity are tied to
the machine this was built on — change them if reusing this on a different Mac/Apple
Developer account.

## Gotcha worth keeping

`MultipeerServiceBrowser.discoveries()` must stay **collected** across the `join()` call
— cancelling it first (e.g. via `Flow.first()`) tears down the native browser before
`join()` can use it (`mc_runtime session open failed`). `MacMain.kt` keeps a background
collector alive into a `CompletableDeferred` and only cancels it after the pull
completes — the same pattern `:kuilt-multipeer`'s own `MultipeerCrossProcessProbe.
runJoinFirst` uses.
