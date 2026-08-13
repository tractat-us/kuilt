rootProject.name = "kuilt"

// AGP needs sdk.dir; resolve from local.properties or env on a fresh checkout/CI.
run {
    val localProps = rootDir.resolve("local.properties")
    if (localProps.exists()) return@run
    val sdkDir = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (sdkDir != null) localProps.writeText("sdk.dir=$sdkDir\n")
}

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Build Scan® publishing — free scans.gradle.com tier (NOT paid Develocity).
    // Publishes only on CI (the CI env var) so local builds never upload data.
    // The per-task timeline is how you diagnose where a build/publish spends time.
    id("com.gradle.develocity") version "4.5.0"
    // Remote build-cache backend — S3-compatible (Fly Tigris), shared org-wide.
    // No-op when the S3 creds are absent (see the buildCache block below).
    id("com.github.burrunan.s3-build-cache") version "1.9.8"
}

develocity {
    buildScan {
        termsOfUseUrl = "https://gradle.com/help/legal-terms-of-use"
        termsOfUseAgree = "yes"
        publishing.onlyIf { System.getenv("CI") != null }
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

// Remote build cache: S3-backed (Fly Tigris "buildcache" bucket), shared org-wide
// so CI and local builds hit warm artifacts across branches. Writers are trusted
// CI refs (main); PRs and local builds read-only (opt in with
// S3_BUILD_CACHE_PUSH=true). Absent creds ⇒ disabled. Helps the compile/test
// build; the publish step is dominated by upload time (#24), which caching
// can't touch.
val s3CacheAccessKey = System.getenv("S3_BUILD_CACHE_ACCESS_KEY_ID")
if (!s3CacheAccessKey.isNullOrBlank()) {
    val isCi = System.getenv("CI") != null
    val trustedCiRef = System.getenv("GITHUB_REF") == "refs/heads/main"
    val cachePush = (isCi && trustedCiRef) || System.getenv("S3_BUILD_CACHE_PUSH") == "true"
    buildCache {
        remote<com.github.burrunan.s3cache.AwsS3BuildCache> {
            region = "auto"
            bucket = "buildcache"
            endpoint = "https://fly.storage.tigris.dev"
            // Tigris rejects the REDUCED_REDUNDANCY storage class (400); use STANDARD.
            isReducedRedundancy = false
            isPush = cachePush
        }
    }
}

include(":kuilt-bom")
include(":kuilt-core")
include(":kuilt-liveness")
include(":kuilt-test")
include(":kuilt-conformance")
include(":kuilt-session")
include(":kuilt-session-test")
include(":kuilt-websocket")
include(":kuilt-mdns")
include(":kuilt-webrtc")
include(":kuilt-multipeer")
include(":kuilt-nw")
include(":kuilt-nearby")
include(":kuilt-raft")
include(":kuilt-raft-test")
include(":kuilt-crdt")
include(":kuilt-bolt")
include(":kuilt-heddle")
include(":kuilt-quilter")
include(":kuilt-gossip")
include(":kuilt-gossip-test")
include(":kuilt-deal")
include(":kuilt-deal-test")
include(":kuilt-game")
include(":kuilt-stream")
include(":kuilt-tcp")
include(":kuilt-cluster")
include(":kuilt-scale")
include(":kuilt-otel")
include(":kuilt-otel-tap")
include(":kuilt-otel-tap-test")
include(":kuilt-otel-logging")
include(":kuilt-otel-logback")
include(":kuilt-otel-log4j2")
include(":kuilt-otel-sdk")
include(":kuilt-otel-otlp")
include(":kuilt-warp")
include(":kuilt-warp-ksp")
include(":kuilt-warp-runtime")
include(":kuilt-warp-compiler")
include(":kuilt-warp-test")
include(":kuilt-warp-planning")
include(":kuilt-warp-ml")
include(":kuilt-warp-otel")
include(":kuilt-warp-heddle")
include(":examples")

// Patchwork demo app modules (unpublished; see docs/superpowers/specs/2026-07-07-demo-app-design.md).
include(":demo-shared")
project(":demo-shared").projectDir = file("demo/shared")
include(":demo-relay")
project(":demo-relay").projectDir = file("demo/relay")
include(":demo-cli")
project(":demo-cli").projectDir = file("demo/cli")
include(":demo-web")
project(":demo-web").projectDir = file("demo/web")
include(":demo-tap")
project(":demo-tap").projectDir = file("demo/tap")

// Phase-0 connectivity spike for kuilt-nw (#1403) — opt-in only, kept out of the
// default build graph so a signing-less CI runner never builds it.
// Enable with `-PincludeSpike`.
if (providers.gradleProperty("includeSpike").isPresent) {
    include(":spike")
}

// Mutation-receipt probe module (#2272) — opt-in via `-PguardProbeModule=:kuilt-zzz-probe`.
//
// Several guards in the root build script take the module SET as an input, and the natural way to
// prove one of them notices a new module is to add an `include` here by hand. That receipt is
// invalid, twice over: Gradle refuses to configure a project whose directory does not exist, and
// once you create the directory, `kuilt-bom`'s completeness check fails at CONFIGURATION time —
// before any task runs. Either way the red is about something other than the guard under test.
//
// This block makes the valid shape a command-line flag with NO tracked-file edit: the directory is
// created under `build/` (gitignored, so a probe can never be committed and there is nothing to
// revert), and `kuilt-bom` accounts for the same property so configuration succeeds. A guard that
// needs the probe to carry Kotlin source — `forbidUnlintedModule` — reads `build/guard-probe/src/`.
// The full receipt shape is in the root build script's "Guard plumbing" section.
//
// THE PATH IS A RESERVED LITERAL, NOT A SHAPE THE CALLER CHOOSES, and both reasons were live
// defects in the first version of this block, which took any new `:`-prefixed path:
//
//   - A validating check evaluated WHERE THIS BLOCK SITS cannot be order-independent.
//     `rootProject.children` then holds only the includes ABOVE it, so a module declared further
//     down passed the check and had its project directory silently repointed here — build script
//     never applied, sources invisible to `forbidUnlintedModule`, itself exempted from
//     `kuilt-bom`'s completeness backstop. The exact outcome the check's own message claimed to
//     prevent, decided by a file position nothing pins. A literal cannot collide in any order.
//     (Deferring to `settingsEvaluated` is what makes the residual collision check below sound;
//     see its own comment. Both halves are needed — the literal alone still lost to a real module
//     of the same name, which is the same defect a third time.)
//   - The literal is inside the `:kuilt-` namespace ON PURPOSE. `verifyModuleTable` derives its
//     input as `subprojects.filter { it.startsWith(":kuilt-") }`, so a probe named outside that
//     namespace is included, exempted, and INVISIBLE to the guard: the receipt comes back GREEN,
//     from a cache key identical to the no-probe run, having proved nothing. A free knob drifts to
//     the one setting where the property cannot fail, and a vacuous green is far harder to notice
//     than a misattributed red.
//
// A future guard scoped to some other namespace needs a second reserved literal here, added
// deliberately — not a relaxation of this one back into a shape check.
val guardProbePath = ":kuilt-zzz-probe"

// DEFERRED TO `settingsEvaluated` ON PURPOSE — do not "simplify" the wrapper away. Every `include`
// in this file has been declared by the time it runs, which is the only position from which the
// collision check below can see a module declared BELOW this block. Run inline, that check reads as
// correct and silently is not, which is how the first two versions of this affordance shipped the
// very defect (#2272) they exist to prevent. `include`/`projectDir` from here behave exactly as
// they do inline: projects are not loaded until later.
gradle.settingsEvaluated {
    providers.gradleProperty("guardProbeModule").orNull?.let { path ->
        require(path == guardProbePath) {
            "-PguardProbeModule accepts only the reserved probe path \"$guardProbePath\"; got " +
                "\"$path\" (#2272). It is a fixed name because a free one lands on whichever module " +
                "you name — silently repointing a REAL module at the probe directory — and because " +
                "a name outside the `:kuilt-` namespace is invisible to `verifyModuleTable`, which " +
                "then returns a receipt-shaped GREEN."
        }
        // Checked AFTER the literal, so naming some other real module still gets the more useful
        // "only accepts the reserved path" message. This one is for the last residual: someone
        // creating a real module under the reserved name. Repointing it here would hide its
        // sources and exempt it from the BOM backstop — Critical-1's failure a third time.
        require(rootProject.children.none { ":${it.name}" == path }) {
            "\"$path\" is already a module in this build, so -PguardProbeModule cannot use it as a " +
                "probe: including it would repoint the real module at the probe directory, hiding " +
                "its sources from the guards and exempting it from kuilt-bom's completeness check " +
                "(#2272). Rename that module — this path is reserved for the mutation probe."
        }
        val probeDir = file("build/guard-probe")
        probeDir.mkdirs()
        include(path)
        project(path).projectDir = probeDir
    }
}
