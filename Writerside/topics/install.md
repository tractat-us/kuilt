# Install

kuilt publishes to Maven Central under the `us.tractat.kuilt` group.

## Add the repository

```kotlin
// settings.gradle.kts
repositories {
    mavenCentral()
}
```

## Depend on the modules you need

```kotlin
// build.gradle.kts
dependencies {
    implementation("us.tractat.kuilt:kuilt-core:VERSION")       // always — the contract
    implementation("us.tractat.kuilt:kuilt-websocket:VERSION")  // WebSocket fabric
    implementation("us.tractat.kuilt:kuilt-crdt:VERSION")       // CRDT zoo
    implementation("us.tractat.kuilt:kuilt-raft:VERSION")       // Raft consensus
    implementation("us.tractat.kuilt:kuilt-session:VERSION")    // membership / room
}
```

Replace `VERSION` with the [latest release](https://central.sonatype.com/artifact/us.tractat.kuilt/kuilt-core).

Pick only the modules you actually use — each depends on `kuilt-core` and nothing unnecessary. A project that only needs in-memory message passing depends only on `kuilt-core`.

## Local iteration with `includeBuild`

When you are developing kuilt and a consumer side by side, a presence-gated `includeBuild` substitutes the local sources for the published artifact with zero latency:

```kotlin
// consumer's settings.gradle.kts
if (file("../kuilt").exists()) includeBuild("../kuilt")
```

When the `kuilt/` directory is absent (CI, ephemeral worktrees), Gradle resolves the published coordinates as normal. The public API and Maven coordinates are the compatibility surface — the `includeBuild` shortcut is for local iteration only.

## Test utilities

Two modules exist purely to support testing code built on kuilt:

```kotlin
// commonTest
testImplementation("us.tractat.kuilt:kuilt-test:VERSION")        // fakes + test utilities
testImplementation("us.tractat.kuilt:kuilt-conformance:VERSION") // SeamConformanceSuite
```

`kuilt-conformance` ships `SeamConformanceSuite`, which proves any `Loom` implementation correct. See [Fabrics](fabrics.md) for how to use it.
