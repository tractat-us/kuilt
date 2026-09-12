# Warp

Warp spreads a pile of work across whoever is connected, with no central boss and no risk
of two devices doing the same job twice. Some jobs can only run on certain devices — one
needs a GPU, another has to stay in a given region — so this is also where a job gets
matched to a device that is actually allowed to run it. And when the device that should
run a job doesn't have the code for it yet, this is how that code gets shipped over and
run safely once it arrives.

## Where a task may run (location eligibility)

This builds on the lanes and the ledger described under [Fair share & placement](heddle.md#fair-share--placement).

**Intent:** *where* a task is allowed to run — "needs a GPU", "must stay in us-east", "sit
where the data is". This is orthogonal to the *how much* a lane answers: eligibility
introduces no budget and never touches the ledger.
**Primitive:** `TaskDescriptor.where(affinity)` with an `Affinity` predicate over the `CapSet`
tokens a peer advertises (`:kuilt-warp`). The predicate is a serializable value (`has`/`attr`
combined with `and`/`or`/`not`), not a lambda — it rides the wire, and placement hashes over
only the eligible peers. `Affinity.Anywhere` (the default) requires nothing. Composes with
`inLane(...)`: a task may carry both.

<!-- verbatim from kuilt-warp/src/commonSamples/kotlin/us/tractat/kuilt/warp/WarpSamples.kt#sampleAffinity -->
```kotlin
    // "must run on a GPU node in us-east" — a composable predicate, not a lambda (it rides the wire).
    val where = Affinity.has("GPU") and Affinity.attr("region", "us-east")

    val gpuUsEast = CapSet(tokens = setOf("GPU"), attributes = mapOf("region" to "us-east"))
    val cpuUsWest = CapSet(tokens = setOf("CPU"), attributes = mapOf("region" to "us-west"))
    check(where.matches(gpuUsEast))       // eligible
    check(!where.matches(cpuUsWest))      // not eligible
    check(Affinity.Anywhere.matches(cpuUsWest)) // the default requires nothing

    // Tag a task with the requirement; placement then hashes over only the eligible peers.
    val task = TaskDescriptor(OpId("train"), byteArrayOf(1, 2, 3)).where(where)
    check(task.affinity == where)
```

## Code mobility

**Intent:** the peer that should do the work doesn't have the code. You want to ship it there,
cache it, and run it — without shipping a whole new build, and without trusting whatever arrives.

**Primitive:** `Creel` + `BobbinExchange` for the bytes, `WasmRuntime` + `WarpLazyFetch` for
running them (`:kuilt-warp`; the engines are in `:kuilt-warp-runtime`). Don't hand-roll a blob
cache, a "who has these bytes" protocol, or a sandbox.

- **`Creel`** is the local cache, keyed by the SHA-256 of the bytes. Content addressing means
  merge is free (same key ⇒ same bytes, no conflict possible) and `putVerified` re-hashes
  anything that came off the wire before trusting it. `get` returning `null` is the ordinary
  "not fetched yet" state, not an error.
- **`BobbinExchange`** gossips a manifest of *which* kernels exist eagerly and fetches the
  *bytes* on demand — concurrent callers of the same hash share one in-flight request, and a
  re-request loop reaches a peer that only later acquires the bytes.
- **`WarpLazyFetch`** is the capability bundle you hand a `WarpNode` so an unknown `OpId`
  resolves at execution time: fetch, load under the sandbox, run, cache. A fetch that times out
  is **transient** — the task stands by and is retried; only a kernel that is broken or hostile
  fails terminally.
- **`WasmRuntime`** is the sandbox contract, and it is strict on purpose because kernels come
  from peers you don't control: a module declaring any import is rejected, it must declare a
  bounded memory maximum within `WasmSandboxConfig.maxMemoryPages`, and every invocation is cut
  off at `WasmSandboxConfig.executionTimeout`. `ChicoryWasmRuntime` (JVM), `Wasm3WasmRuntime`
  (iOS/macOS) and `BrowserWasmRuntime` (wasmJs) all pass the same `WasmRuntimeConformanceSuite`.

<!-- verbatim from kuilt-warp/src/commonSamples/kotlin/us/tractat/kuilt/warp/WarpSamples.kt#sampleLazyFetch -->
```kotlin
    val creel = Creel()

    // Storing bytes yields their content address; storing them again is a no-op.
    val kernel = byteArrayOf(0x00, 0x61, 0x73, 0x6d)
    val hash: BobbinHash = creel.put(kernel)
    check(creel.put(kernel) == hash)

    // Bytes that arrived from a neighbour are re-hashed before being cached — a mismatch throws.
    creel.putVerified(hash, kernel)
    check(creel.contains(hash))
    check(hash in creel.loaded)          // the fragment this peer can serve to neighbours

    // A miss is the legitimate "not fetched yet" state, not an error.
    check(creel.get(BobbinHash("deadbeef")) == null)

    // The capability bundle a WarpNode needs to run an op it has never seen.
    val lazyFetch = WarpLazyFetch(
        creel = creel,
        runtime = runtime,
        opToBobbin = { op -> if (op == OpId("reverse")) hash else null },
    )
    check(lazyFetch.opToBobbin(OpId("reverse")) == hash)
    check(lazyFetch.opToBobbin(OpId("unknown")) == null) // nothing to fetch — the task stands by
```

The training step this ships is `FedAvg` from `:kuilt-warp-ml` — its cookbook entry lives
with replication rather than here: [Replicated data](replication.md#replicated-data).
