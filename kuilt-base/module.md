# Module kuilt-base

**Which kuilt is running, without a version string you copied by hand.**

Say your app saves a snapshot today and reads it back next month, after an upgrade. It helps to
know which kuilt wrote it. Typing the version into your own code works until the next upgrade,
when the copy silently goes stale. This module carries the real one, filled in by the build that
published it.

## Quick start

<!-- verbatim from kuilt-base/src/commonSamples/kotlin/us/tractat/kuilt/KuiltVersionSamples.kt#sampleKuiltVersion -->
```kotlin
// Save the version beside the bytes it describes.
val snapshotHeader = mapOf("writtenBy" to KuiltVersion.VERSION)

// A later run compares it with the kuilt it is running now.
val writtenByThisKuilt = snapshotHeader["writtenBy"] == KuiltVersion.VERSION
check(writtenByThisKuilt)
```

## You rarely declare it

`:kuilt-core`, `:kuilt-crdt`, `:kuilt-store`, `:kuilt-seal` and `:kuilt-heddle-core` each bring this
module with them, so anything built on one of them can read `us.tractat.kuilt.KuiltVersion`
already. `:kuilt-math` does not. Import the BOM and every kuilt module, this one included, is at
the same version.

## What the value is

`KuiltVersion.VERSION` is the string the build publishes the artifact under: `<line>.0-dev` from
a local build, `<line>.0-dev.<run>` from a snapshot, and the tag's `x.y.z` from a release. The
`kuilt.version-constant` convention plugin generates it from the project version, and a JVM test
checks it against the version the published POM declares.

It is a plain `val`, not a `const`. A `const` is copied into the code that reads it when that code
compiles, so it would name the kuilt you compiled against rather than the one actually running.
It would also put the version in this module's ABI, so every release would recompile everything
built on it.

## Why a module of its own

It depends on nothing: no coroutines, no serialization, no other kuilt module. That is what lets
every root of the module graph take it, including `:kuilt-heddle-core`, which keeps coroutines off
its classpath, and `:kuilt-crdt`, `:kuilt-store` and `:kuilt-seal`, which promise to work with
nothing else attached. Anything added here is inherited by almost every kuilt consumer, so it has
to be worth that.
