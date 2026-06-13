# Publishing kuilt

## Two-tier versioning

| Tier | Version pattern | Where | Who uses it |
|------|----------------|-------|-------------|
| **Public** | `0.x.0` | Maven Central (`mavenCentral()`) | External consumers, downstream OSS |
| **Internal** | `0.x.y` (y > 0) | Tigris build cache | other in-house projects |

Patch releases (`0.x.1`, `0.x.2`, …) are continuous snapshots published on every push to `main` — fast iteration for in-house consumers, no guarantees of stability or compatibility. Minor releases (`0.x.0`) are the deliberate, signed, permanently-available Central artifacts.

## Consuming from Maven Central

Add the repository and dependency coordinates:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
```

### Recommended: import the BOM

`kuilt-bom` is the recommended entry point for consumers using more than one module. Import it once and omit version numbers from all subsequent kuilt dependencies:

```kotlin
// build.gradle.kts
dependencies {
    implementation(platform("us.tractat.kuilt:kuilt-bom:0.4.0"))
    implementation("us.tractat.kuilt:kuilt-core")
    // kuilt-crdt, kuilt-raft, kuilt-session, kuilt-websocket, …
}
```

### Without the BOM

```kotlin
// build.gradle.kts
dependencies {
    implementation("us.tractat.kuilt:kuilt-core:0.4.0")
    // pick the modules you need:
    // kuilt-crdt, kuilt-raft, kuilt-session, kuilt-websocket, …
}
```

No credentials required — Central artifacts are publicly readable.

## Consuming internal builds (sibling projects)

Add the Tigris Maven repo and use a patch version:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("https://fly.storage.tigris.dev/buildcache/maven/")
            credentials {
                username = providers.gradleProperty("tigrisUsername").get()
                password = providers.gradleProperty("tigrisPassword").get()
            }
        }
    }
}
```

Tigris builds require credentials. Pin to an explicit `0.x.y` version (don't use `+` or `SNAPSHOT`).

## Cutting a Central release

1. Ensure the four repo secrets are present (`MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY`, `SIGNING_PASSWORD`). See issue [#302](https://github.com/tractat-us/kuilt/issues/302) and [#300](https://github.com/tractat-us/kuilt/issues/300) for setup steps.
2. Push a version tag:
   ```bash
   git tag v0.5.0
   git push origin v0.5.0
   ```
   The `maven-central` workflow job fires, signs the artifacts, and uploads a **pending** deployment to the [Central Portal](https://central.sonatype.com).
3. Log in to the Portal and release the pending deployment.

Alternatively, trigger the `publish` workflow manually with `release_to_central = true` and an explicit `version` field — useful for release candidates or re-releases without a tag.

`automaticRelease = false` is enforced in the publish convention, so uploads always land as *pending* — there is no path that releases to Central automatically.

## Bumping the minor version

When a release introduces a breaking API change, bump `kuiltVersionLine` in `gradle.properties` before tagging:

```properties
kuiltVersionLine=0.5
```

Both the Tigris continuous build and the next Central tag read from this single property. Bump it in a PR, merge to `main`, then tag `v0.5.0`.
