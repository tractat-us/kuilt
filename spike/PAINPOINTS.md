# kuilt-nw Phase-0 spike — pain-points log

Running log of friction hit while building the Network.framework connectivity spike.
Seeds the "implementing a new transport" skill. Newest entries at the bottom.

## Environment (2026-07-11)
- Xcode 26.6 (build 17F113), iOS SDK 26.5. Kotlin/Native toolchain 2.3.21.
- `platform.Network` **is** an auto-generated K/N platform klib for `ios_arm64`
  (confirmed under `~/.konan/.../klib/platform/ios_arm64/…platform.Network`). No
  custom `.def` needed — matches the research finding.
- Devices connected (the iOS 26 ↔ iOS 18 baseline pairing):
  - iPhone 17 Pro (iPhone18,1) — "Iain's Phone"
  - iPhone XS (iPhone11,2) — "iPhone"

## Setup decisions
- Spike is a **standalone** KMP module (`alias(libs.plugins.kotlinMultiplatform)`),
  NOT `kuilt.kmp-library` — no Android/wasm/Dokka/explicitApi ceremony for a throwaway.
- Gated out of the root Gradle graph via `-PincludeSpike` (see `settings.gradle.kts`)
  so a signing-less CI runner never builds it.
- Compile the probe with:
  `./gradlew -PincludeSpike :spike:compileKotlinIosArm64`

## Cinterop findings
- ✅ **`:spike:compileKotlinIosArm64` succeeds on the first real cinterop attempt.**
  The whole listener/browser/connection surface + `dispatch_queue_create` compiled.
- **Block handlers bridge as plain Kotlin lambdas** — `nw_listener_set_state_changed_handler(l) { _, _ -> }`,
  `nw_listener_set_new_connection_handler(l) { conn -> … }`, and
  `nw_browser_set_browse_results_changed_handler(b) { _, _, _ -> }` all compile with
  no `staticCFunction`/`StableRef`. Matches the research claim.
- **`NW_PARAMETERS_DISABLE_PROTOCOL` and `NW_PARAMETERS_DEFAULT_CONFIGURATION` are
  exposed** as importable symbols from `platform.Network` (usable directly as the
  configure-block args to `nw_parameters_create_secure_tcp`).
- **BOM completeness backstop trips on a new module:** `kuilt-bom/build.gradle.kts`
  requires every subproject be published or in `deliberatelyUnpublished`, AND has a
  `staleExclusions` guard — so `:spike` must be added there **conditionally** on
  `-PincludeSpike`, else the normal (spike-less) build fails. Done.
- **Hierarchy-template warnings** on `:kuilt-tcp`/`:kuilt-websocket` are pre-existing
  (their manual `jvmAndAndroidMain` wiring), unrelated to the spike.
- TODO next iteration: TLS-PSK via `sec_protocol_options_add_pre_shared_key` (the
  C-API path research flagged as the fiddliest), then the framed ping round-trip.

## TLS-PSK (the fiddly path) — also compiles
- ✅ `platform.Security.sec_protocol_options_add_pre_shared_key` + `platform.Network.nw_tls_copy_sec_protocol_options`
  + `platform.darwin.dispatch_data_create` all resolve. The `configure_tls` block
  (a Kotlin lambda taking `nw_protocol_options_t?`) copies sec options and installs the PSK.
- **`dispatch_data_create` with a null destructor copies the bytes** — so a `usePinned`
  buffer needn't outlive the call (`DISPATCH_DATA_DESTRUCTOR_DEFAULT` is `NULL`, so `null` works).
- `sec_protocol_options_t` lives in **platform.Security**, not platform.Network — import accordingly.
- Two cosmetic "Redundant '?'" warnings on the dispatch_data typealias; harmless.
