# The compiler node

> **Playground.** Like the rest of warp, this is real and shipping but pre-1.0 — outside
> kuilt's stability promise, and its API can change. Treat it as a preview, not a foundation.

Some devices in a room are faster than others, and some jobs are the same job done over
and over. So instead of every device grinding through the slow version of a program, one
capable device can do a little extra work up front — take the program, tidy it up so it
runs faster, and hand the leaner copy back to everyone else. Now the whole room runs the
better version, and only one device paid to make it.

That device is a **compiler node**. Think of it like one strong friend who reads the recipe,
notices half the steps are unnecessary, rewrites it shorter, and photocopies it for the
table. Everyone else just cooks from the shorter recipe.

## What you get out of it

A concrete example: warp jobs can carry a small program — a *kernel* — that each device
runs to produce its answer. A freshly-written kernel is usually full of slack: steps that
compute the same thing twice, arithmetic that does nothing (`x + 0`, `x × 1`), values worked
out inside a loop that never change. A phone running that kernel a few million times feels
every one of those wasted steps.

A compiler node runs the program through a professional optimizer, which strips all of that
out, and shares the tightened version back over the same room the devices already use to
talk. The next device to pick up that kind of job gets the fast copy for free — it never has
to notice the program was ever slow, and it never has to run the optimizer itself.

Only a device that *volunteers* to be a compiler node carries the optimizer. Everyone else
stays a pure consumer of the result.

## How much faster

On a deliberately-bloated test kernel — one padded with redundant, no-op work precisely so
there is something to strip — the optimized version ran roughly **5× faster** and about a
third smaller than the raw version:

| version | size | speed vs. raw |
|---------|-----:|--------------:|
| raw (unoptimized) | 263 bytes | — |
| optimized for size (`-Oz`) | 174 bytes | **5.42×** |
| optimized for speed (`-O3`) | 174 bytes | **5.01×** |

That is an 82% cut in wall-clock time for the best case, with every version computing the
*identical* answer (correctness is asserted, not hoped for).

> **Read these numbers honestly.** They were measured running the kernel through kuilt's
> **JVM WebAssembly interpreter (Chicory)** — so the multiples are *interpreter-relative*.
> They say "the interpreter has far fewer instructions to walk through," not "this is 5×
> native speed." An interpreter feels every removed instruction directly, which is exactly
> why the win is so visible here; a native or JIT tier would show a smaller multiple. The
> kernel is also intentionally wasteful to make the effect legible — a already-tight program
> has little for the optimizer to remove. Take the result as *the mechanism works and pays
> off on every interpreter tier*, not as a headline speed figure.

## Under the hood

The compiler node is the `:kuilt-warp-compiler` module. It implements the `WasmOptimizer`
seam from `:kuilt-warp` with `BinaryenWasmOptimizer`, which hands a raw WebAssembly kernel
to the industry-standard **Binaryen `wasm-opt`** tool and returns a smaller, still-runnable
module. The warp calling convention (`warp_alloc` / `warp_run`) is preserved end to end, so
the optimized module drops straight into any peer's runtime in place of the original.

You choose how `wasm-opt` optimizes with an `OptLevel`:

- **`O3`** — optimize hard for speed.
- **`Oz`** — optimize hard for size (often the fastest here too, because a smaller module is
  fewer instructions for an interpreter to walk).
- **`O2` / `O0`** — a lighter pass, and no optimization, respectively.

The `wasm-opt` binary is not committed to the repository. It is downloaded from Binaryen's
official release at build time — **version-pinned and SHA-256-verified** — and bundled as a
resource, so there is no toolchain for an operator to install and no unverified binary in
git. Because running a native optimizer is a server-side concern, this is a **JVM/server-only
module**: iOS, browser, and other native peers consume the optimized variant but never run
`wasm-opt` themselves.

> **Packaging caveat.** The published `:kuilt-warp-compiler` artifact today bundles a
> **build-host-only** `wasm-opt` binary — i.e. the one for whichever OS/architecture built
> the release. Running a compiler node on a *different* host OS is not yet covered by the
> published artifact. Bundling `wasm-opt` for every target OS is tracked separately in
> [#1335](https://github.com/tractat-us/kuilt/issues/1335).

## Where this sits in warp

The compiler node is a second, orthogonal shipping piece alongside the [work
scheduler](warp.md): the scheduler spreads *which device does which job*; the compiler node
makes *the job itself* leaner for whoever runs it. Neither depends on the speculative
"warp dream" — both are built from parts kuilt already had, and both are measured, not
promised.
