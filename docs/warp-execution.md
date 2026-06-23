# `:kuilt-warp` — the execution engine

> Part of the [`:kuilt-warp`](warp-vision.md) dream. Read the [walk](warp-vision.md)
> first; this is one leaf of the [deeper-waters index](warp-deeper.md). The internal
> engine: how work and code actually move and run. Speculative (#665, spike #680);
> no commitment to build.

## How the method actually travels

A fair question kills most compute-grid dreams: *how does `score` even get to the
other peers and run there?*

**You never ship the function. You ship a name and its arguments.** kuilt moves
opaque bytes between peers that might be a JVM server, an iPhone (Kotlin/Native),
and a browser (wasmJs). A Kotlin lambda compiled to JVM bytecode cannot execute on
Native or wasm — there is no portable, runtime-shippable representation of Kotlin
code across those targets. So what crosses the fabric is a tiny **task
descriptor** — `{ op: "score", arg: ⟦query⟧, item: docId }` — dropped into the
**`TaskQueue`** (a thin wrapper over an `ORSet`). A peer claims it, looks `"score"`
up in a **local operation registry** that every node populated at startup from the
same compiled binary, and runs *its own copy* on local data. The result merges back
as a CRDT. The function never moved; only its name did.

That single descriptor turns out to be a workhorse — one envelope doing three jobs:
it **routes the work**, its `op` doubles as the **bobbin's content hash** (see *Lazy
bobbins* below) once you ship real code, and it can carry a W3C `traceparent` so a
trace [follows the work](warp-observability.md) across peers. Design the envelope
once; it pays off three ways.

![How a method crosses the fabric, in five steps: shuttle splits the work into descriptors; a CRDT work-queue replicates them; the equalizer places them; each peer runs its own registered copy; results merge back. On the wire: names and data, never the function.](images/warp/on-the-wire.svg)

That implies one honest constraint, stated up front: **a homogeneous binary with
symbolic dispatch.** Every peer runs the same build; the grid distributes
*decisions about where data is processed*, not the processing code. The
`shuttle { … }` block is therefore sugar for "reference a registered operation" —
a compiler plugin (KSP) could auto-register the ops it sees so it still *reads*
like an ordinary lambda.

## Shipping real code: WASM kernels (the fantasy)

Named ops can only run code already in the deployed binary. If we ever wanted to
ship *new* computations at runtime — true code mobility — there is exactly one
substrate that works across kuilt's targets, and the surprise is that it isn't
native code:

- **① Named ops — the default.** Ship `(opId, args)`; the code is already
  everywhere. Works on every target. Limit: no new code at runtime.
- **③ WASM kernels — the fantasy.** Ship a compiled `.wasm` blob *as* the method.
  It's the same bytes on every platform; it's a capability **sandbox** (decisive —
  you'd be running code other peers sent you); and the browser runs it natively.
  Under the hood the desktop/server runtimes (wasmtime/wasmer) JIT it to native via
  Cranelift/LLVM, so near-native speed comes for free. The one hard constraint:
  **iOS forbids runtime JIT**, so on iPhone you *interpret* the wasm (wasm3) —
  slower, but real and portable.
- **Not LLVM IR on the wire.** The tempting "ship LLVM bitcode for native speed"
  path is a trap: bitcode isn't portable (Google tried it — PNaCl — and retired it
  in favour of WebAssembly), it needs the JIT iOS bans, and it's unsandboxed. LLVM
  keeps its rightful job — *inside* the wasm runtime, the thing that makes wasm
  fast — not as a distribution format.

The lovely inversion: the part that *sounds* like science fiction (live code
crossing a phone, a server, and a browser at once) rides on the most ordinary
substrate we already half-target — while the path that *sounds* like the
performance win (native/LLVM) is the dead end. Code mobility is a tier, not a
switch: names by default, sandboxed wasm kernels when you genuinely must ship code,
never raw native.

![Code mobility as a ladder: named ops are the default; WASM kernels are the fantasy — same bytes everywhere, sandboxed, browser-native, JIT'd to native via Cranelift where allowed and interpreted on iOS where JIT is banned; shipping LLVM IR on the wire is the trap PNaCl already proved a dead end.](images/warp/code-mobility.svg)

## Lazy bobbins: gossiping the code

Shipping a kernel raises its own question — push every kernel to every peer up
front? No. You let it spread the way everything else here spreads: **eventually**.
A kernel is a **bobbin**, the wound thread the shuttle draws from; a peer can't run
an op until the right bobbin is loaded, so bobbins gossip across the mesh lazily,
on demand. The rack of loaded bobbins is the **creel**.

The structure is exactly the CRDT-cache shape — a **content-addressed store with a
CRDT manifest over the keys**, *keys known, values lazy*:

- **Keys** are content hashes. The op-id in a descriptor *is* `hash(kernel)`. The
  set of known bobbin-hashes is a grow-only `GSet<Hash>` (the zoo's `EphemeralMap`
  gives the cache-with-eviction variant) — cheap to gossip, always converges.
  Every peer agrees which bobbins exist.
- **Values** are the immutable kernel bytes, fetched on first use. Content-
  addressing makes the value-merge *trivially* conflict-free: because the key is
  `hash(value)`, any two peers holding the same key hold byte-identical bytes —
  "merge" is just "same bytes," and each value sits in a one-step lattice
  `Absent ⊏ Present`. You gossip availability and pull on demand.

A peer assigned an op whose bobbin it lacks asks a neighbour, pulls the bytes,
re-hashes to verify, caches, and runs; new peers warm their creel lazily.

One honest line: the key-set converging is **safety** (the CRDT guarantees it); the
bytes being *fetchable* is **liveness** (it does not). Pin each bobbin on ≥ k peers,
like any peer-to-peer content store. The shape has a name — a **Merkle-CRDT**:
CRDT state addressed by hash, advertised eagerly and fetched lazily.

![Lazy bobbins: the op-id is the content-hash of a kernel; the creel is a CRDT GSet of hashes that every peer converges on (keys known), while the kernel bytes are a content-addressed cache each peer holds a subset of and fetches on demand (values lazy). Content-addressing makes every fetch conflict-free; the one caveat is that availability is a liveness property, so pin each bobbin on several peers.](images/warp/bobbins.svg)

## Compiler nodes & distributed tiered compilation

Compilation is just **another warp op**: `compile(wasmHash, target) → compiledHash`.
A *compiler node* is simply a peer that registered the `compile` op and carries the
heavy toolchain (wasmtime/Cranelift); weaker peers embed only an interpreter and
fetch a *precompiled* bobbin from the creel. It slots straight into the bobbin
model — a bobbin gains **variants**: raw `.wasm` plus per-`(target, opt-level)`
compiled forms, each content-addressed by `hash(wasm) + target`. The grid bootstraps
its own code distribution.

This buys **distributed tiered compilation**: a peer starts *interpreting*
immediately, a compiler node produces the optimized native build in the background,
and the peer tiers up when that bobbin gossips in — a JIT smeared across the mesh.

**Reuse mature toolchains, don't invent one.** Two bootstraps already exist:
**Kotlin/Wasm** lets a kernel be authored in the *same language as the app* and
compiled to a portable `.wasm` bobbin (no separate Rust/C), and **GraalVM**
(GraalWasm to execute wasm on JVM nodes; the Graal compiler / native-image as the
AOT engine) is a ready compiler-node toolchain. The caveat is the familiar one:
Kotlin/Native and Graal native-image both lower through LLVM/AOT and so don't
escape the iOS runtime-native-code ban — they bootstrap *authoring* and the
*non-iOS* tiers, not the iOS ceiling.

**The honest iOS asterisk — do not oversell it.** A compiler node cannot rescue
iOS. The iOS wall isn't "iOS lacks a compiler"; Apple forbids **executing
externally-delivered machine code at all** (not just JIT — downloaded native dylibs
are banned too). So no peer can compile arm64 and ship it to an iPhone to run
natively; iOS's ceiling stays *interpret*. What a compiler node *can* still do for
iOS is ship an **optimized wasm** (wasm→wasm via Binaryen `wasm-opt`) — a leaner
module the interpreter runs faster, but never native. The constraint is policy, not
capability, and that is the one thing the mesh cannot optimize away.
