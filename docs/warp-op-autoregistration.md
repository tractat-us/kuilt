# Warp ops that register themselves

Warp spreads work across connected peers by sending each job's *name* — every peer
already holds the code and runs its own copy. That only works if every peer's
registry actually contains every op, which used to mean a hand-maintained list of
`registry.register(...)` lines: forget one and the job just sits there, unclaimed,
on every peer. This page is about deleting that list.

## Authoring: before and after

Before — every op needs a declaration *and* a matching registration line somewhere
else, kept in sync by hand:

```kotlin
val reverse = Op { args -> args.reversedArray() }

// ...elsewhere, at startup — forget this line and the op silently never runs:
val registry = OpRegistry().also {
    it.register(OpId("reverse"), reverse)
}
```

After — the declaration *is* the registration:

```kotlin
@WarpOp("reverse")
val reverse: Op = shuttle { args -> args.reversedArray() }

// at startup — one line per module, however many ops it declares:
val registry = opRegistryOf(WarpOps)
```

`shuttle { }` declares the op so it reads like an ordinary lambda; `@WarpOp` names
it on the wire. At build time the `kuilt-warp-ksp` symbol processor collects every
annotated op in a package into a generated `WarpOps` object (an `OpRegistrar`), and
`opRegistryOf(...)` installs registrars from as many packages/modules as the app
composes. No runtime reflection, no service loading, nothing added to the binary —
the annotation is `SOURCE`-retained and only the generated ordinary Kotlin remains.

The op id is the one name that must stay stable across deployments (it is what
travels in task descriptors). `@WarpOp("reverse")` pins it explicitly — recommended;
a bare `@WarpOp` derives it from the property name, which is convenient but makes a
rename a silent wire-protocol change.

## Why an annotation (the design fork)

Two shapes were on the table:

1. **Annotation + KSP** (chosen): `@WarpOp` on a top-level `val` of type `Op`; a KSP
   processor generates the registrar.
2. **Convention discovery**: no annotation — discover every top-level `val`
   initialized with `shuttle("id") { ... }`.

Option 2 dies on a hard KSP boundary: **KSP is declaration-level**. It sees
declarations, types, and annotations — never expression bodies — so the processor
cannot see that a property's initializer calls `shuttle(...)`, and cannot read an id
string passed to it. The id must live where KSP can read it: an annotation argument
or the symbol name. (Discovery purely by *type* — "every top-level `Op` val" — would
work, but means scanning every file instead of an indexed annotation lookup, no
explicit ids, and accidental registration of helper vals.)

The same boundary scopes the roadmap's full dream — `warp.shuttle(corpus) { doc ->
score(query, doc) }` auto-registering a *call-site* lambda — as a **compiler-plugin
(IR) project, not a KSP one**. KSP generates new files; it cannot rewrite call
sites. Declaration-site `shuttle { }` + `@WarpOp` is the KSP-honest fixed point, and
everything it introduces (stable ids, registrars, the registry) is exactly what a
future compiler plugin would lower onto.

## One generation, every target (the KMP wiring)

A KSP processor always executes on the JVM at build time, regardless of the target
it generates for. Ops live in commonMain, so the processor runs **once**, on the
common-metadata compilation, and its output is ordinary commonMain Kotlin that
every target — JVM, Android, iOS, macOS, wasmJs — compiles like hand-written code.
No per-target processing, no per-target divergence.

In-repo modules get the whole wiring from one convention plugin:

```kotlin
plugins {
    id("kuilt.kmp-library")
    id("kuilt.warp-ops")
}
```

External consumers reproduce it with the published processor:

```kotlin
plugins { id("com.google.devtools.ksp") }

dependencies { add("kspCommonMainMetadata", "us.tractat.kuilt:kuilt-warp-ksp:<version>") }

kotlin.sourceSets.commonMain {
    kotlin.srcDir(layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin"))
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    if (name != "kspCommonMainKotlinMetadata") dependsOn("kspCommonMainKotlinMetadata")
}
```

(KGP does not auto-wire generated metadata sources into the other compilations —
the last two blocks are that standard plumbing; `kuilt.warp-ops` also covers the
sources-jar/Dokka/detekt task-graph edges.)

Known limits, by construction:

- **Platform-specific ops** (declared in `jvmMain`, `appleMain`, ...) are not
  covered by the metadata run. Fallback: the manual `registry.register(...)` path,
  which remains fully supported — auto-registration is sugar over it, not a
  replacement.
- **Test source sets** have no common-metadata compilation; annotated ops belong in
  a main source set (as `:kuilt-warp-test`'s `echo` does — a published test utility).

## Fail-loud, at the earliest possible moment

The processor enforces at **build time** what the registry enforces at startup:
the target must be a top-level immutable `val` of type `Op`, visible to its package
(`public`/`internal` — a `private` val is invisible to the generated file), and op
ids must be unique within the module. A violation fails compilation at the
offending declaration. Cross-module duplicates can't be seen at build time; they
still fail loud at startup via `OpRegistry.register`'s existing duplicate check —
which is also how generated registrars stay coherent with the **lazy-bobbin path**:
compiled-in ops install eagerly through registrars, lazily-fetched wasm kernels
register dynamically into the same registry when their bytes arrive, and the same
registry contract arbitrates both.
