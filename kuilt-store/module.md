# Module kuilt-store

**Put some bytes somewhere they will still be after the app restarts.**

Give it a name and a blob of bytes; ask for that name later — after a reload, a
force-quit, or a crash — and the bytes are still there. That is the whole idea.
There is one small interface, and one implementation of it per platform that knows
how to make a write really stick on that platform.

## The promise

The point of the interface is a single, load-bearing promise:

> When the write call returns, the bytes are safe.

Not "queued", not "probably written soon" — safe. That is what lets a caller treat
its own job as done at that moment, rather than holding everything open until some
slower thing further downstream confirms it. Storage is not the interesting part of
most programs, but *when* a write became permanent usually is.

## Quick start

```kotlin
@sample us.tractat.kuilt.store.sampleDurableStore
```

## What's here

| Type | Targets | What it does |
|---|---|---|
| [DurableStore] | all | The interface: `read`, `write`, `delete`, keyed by [StoreKey]. |
| [StoreKey] | all | A named key. A `value class` over `String`, so two keys cannot be swapped by accident. |
| [InMemoryDurableStore] | all | Keeps everything in a map. **Not** crash-safe — for tests and anywhere a restart doesn't matter. |
| `FileChannelDurableStore` | JVM, Android | Temp file → `FileChannel.force(true)` → atomic rename. |
| `NSFileManagerDurableStore` | iOS, macOS | `NSData.writeToFile` → POSIX `rename(2)`. |
| `IndexedDbDurableStore` | wasmJs | An IndexedDB transaction, awaited to its `complete` event. |

Each implementation's KDoc names the exact instant it treats as the commit, and where
its guarantee stops. They are not all equally strong: the JVM/Android store forces the
bytes to the device before it renames, the Apple one does not, so power loss (as opposed
to process death) can commit the new *name* over unwritten *extents* — see #2141. The
in-memory store, of course, keeps nothing at all across a process exit.

## What it deliberately is not

No iteration, no querying, no transaction spanning two keys, and no opinion about what
the bytes mean. The expected shape is a handful of fixed, hand-written key names holding
serialized state a caller already knows how to encode. Anything richer — an op log with
history you can replay and forget, a replicated structure that merges across peers — is a
different module's job (`kuilt-bolt` and `kuilt-crdt` respectively).

## Dependencies

Coroutines and atomicfu. That is the entire bill: no CRDT lattice, no serialization
format, no logging facade. A consumer that wants a durable key→bytes store on one
platform should not inherit a telemetry stack along with it — which is exactly why this
module was split out of `kuilt-otel` (#2497).
