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

## Declare it yourself

No other kuilt module brings this one with it, so add it next to the modules you already use.
Under the BOM it needs no version, and it stays at the same version as every other kuilt module:

```kotlin
implementation(platform("us.tractat.kuilt:kuilt-bom:<version>"))
implementation("us.tractat.kuilt:kuilt-base")
```

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

It depends on nothing: no coroutines, no serialization, no other kuilt module. So adding it never
brings anything else along, even to an app that keeps coroutines off its classpath.

And nothing in kuilt depends on it, which is deliberate. Every snapshot build stamps a new version
into this module, so on Kotlin/Native and wasm everything compiled against it has to be compiled
again on every snapshot. Keeping it a leaf means only this module pays (#3464).
