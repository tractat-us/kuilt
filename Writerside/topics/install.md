# Install

Install only what your feature needs, then add modules as your requirements
grow. kuilt is published to Maven Central under the `us.tractat.kuilt` group.

## Add the repository

```kotlin
// settings.gradle.kts
repositories {
    mavenCentral()
}
```

## Depend on the modules you need

The recommended path is the **BOM**: it keeps all kuilt modules on one version,
while you add only the modules you need:

**Why use the BOM:** it prevents version drift between kuilt modules.

```kotlin
// build.gradle.kts
dependencies {
    implementation(platform("us.tractat.kuilt:kuilt-bom:VERSION"))

    implementation("us.tractat.kuilt:kuilt-websocket")  // WebSocket transport
    implementation("us.tractat.kuilt:kuilt-crdt")       // CRDT data types
    implementation("us.tractat.kuilt:kuilt-raft")       // Raft consensus
    implementation("us.tractat.kuilt:kuilt-session")    // Room membership
}
```

Without the BOM, pin each module explicitly (for example, `us.tractat.kuilt:kuilt-crdt:VERSION`). Replace `VERSION` with the [latest release](https://central.sonatype.com/artifact/us.tractat.kuilt/kuilt-core).

Every module re-exports the `kuilt-core` contract (`Loom`/`Seam`/`Swatch`), so you do not need to list `kuilt-core` separately when using those modules. If you only need in-memory message passing, `kuilt-core` alone is enough.

If you're unsure where to start, begin with one transport module
(`kuilt-websocket` is usually the easiest), then add `kuilt-crdt`,
`kuilt-session`, or `kuilt-raft` only when the feature needs those guarantees.

## Local iteration with `includeBuild`

When you are developing kuilt and a consumer side by side, you can use `includeBuild` so Gradle uses your local kuilt source instead of the published artifact:

```kotlin
// consumer's settings.gradle.kts
if (file("../kuilt").exists()) includeBuild("../kuilt")
```

When the `kuilt/` directory is absent (CI, temporary worktrees), Gradle falls back to the published artifact automatically. `includeBuild` is only a local development shortcut.

## Test utilities

Two modules are available for testing code built on kuilt:

```kotlin
// commonTest
testImplementation("us.tractat.kuilt:kuilt-test:VERSION")        // fakes + test utilities
testImplementation("us.tractat.kuilt:kuilt-conformance:VERSION") // SeamConformanceSuite
```

`kuilt-conformance` includes `SeamConformanceSuite`, which verifies that a `Loom` implementation follows the kuilt contract. See [Fabrics](fabrics.md) for usage.
