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
    id("com.gradle.develocity") version "4.4.1"
    // Remote build-cache backend — S3-compatible (Fly Tigris), shared org-wide.
    // No-op when the S3 creds are absent (see the buildCache block below).
    id("com.github.burrunan.s3-build-cache") version "1.9.5"
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

include(":kuilt-core")
include(":kuilt-test")
include(":kuilt-conformance")
include(":kuilt-session")
include(":kuilt-session-test")
include(":kuilt-websocket")
include(":kuilt-mdns")
include(":kuilt-webrtc")
include(":kuilt-multipeer")
include(":kuilt-nearby")
include(":kuilt-raft")
include(":kuilt-raft-test")
include(":kuilt-crdt")
