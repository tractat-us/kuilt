;; bloated-kernel.wat — the deliberately-bloated warp benchmark kernel (D4-4).
;;
;; Reproduce the embedded bytes in BloatedKernelBenchmark.kt with:
;;   wat2wasm --no-canonicalize-leb128 kuilt-warp-compiler/wat/bloated-kernel.wat -o /tmp/bloated.wasm
;;   xxd -i /tmp/bloated.wasm     # → the BLOATED_KERNEL byte literal
;;
;; wat2wasm emits exactly what is written (no optimization), so the raw module
;; carries every redundant op below. `wasm-opt -O3` strips the identity ops, the
;; redundant local shuffles, the dead computations, and hoists the loop-invariant
;; — leaving the same result computed with far fewer instructions PER ITERATION,
;; which is directly faster on an interpreter (Chicory). raw and -O3 compute the
;; identical accumulator, so the benchmark asserts equality and only *reports* the
;; wall-clock ratio.
;;
;; ABI (the warp contract — same exports the D4-2 REVERSE fixture uses):
;;   warp_alloc(len i32) -> ptr i32          hands back a fixed scratch pointer
;;   warp_run(ptr i32, len i32) -> i64        (resPtr << 32) | (resLen & 0xffffffff)
;;
;; Input: 4 little-endian bytes = the loop trip count N (chosen by the harness, so
;; the workload is tuned without recompiling the kernel). Output: 4 little-endian
;; bytes = the final accumulator.
(module
  (memory (export "memory") 1 16)

  ;; warp_alloc: a fixed scratch pointer; the host writes the 4-byte trip count here.
  (func (export "warp_alloc") (param $len i32) (result i32)
    i32.const 1024)

  ;; warp_run: read N, run the bloated hot loop N times, write + return the accumulator.
  (func (export "warp_run") (param $ptr i32) (param $len i32) (result i64)
    (local $n i32)     ;; trip count
    (local $i i32)     ;; loop counter
    (local $acc i32)   ;; the real accumulator
    (local $t1 i32)    ;; redundant copy target
    (local $t2 i32)    ;; redundant copy target
    (local $dead i32)  ;; dead-computation sink
    (local $inv i32)   ;; loop-invariant recomputed every iteration (hoistable)

    (local.set $n (i32.load (local.get $ptr)))
    (local.set $acc (i32.const 305419896))   ;; seed 0x12345678
    (local.set $i (i32.const 0))

    (block $done
      (loop $loop
        (br_if $done (i32.ge_u (local.get $i) (local.get $n)))

        ;; BLOAT — loop-invariant, recomputed every iteration (depends only on $len):
        (local.set $inv
          (i32.add (i32.mul (local.get $len) (i32.const 2654435761)) (i32.const 40503)))

        ;; REAL WORK — an LCG step: acc = acc*1664525 + 1013904223
        (local.set $acc
          (i32.add (i32.mul (local.get $acc) (i32.const 1664525)) (i32.const 1013904223)))
        ;; mix in the invariant and the counter so both stay live
        (local.set $acc (i32.xor (local.get $acc) (local.get $inv)))
        (local.set $acc (i32.add (local.get $acc) (local.get $i)))

        ;; BLOAT — identity ops that -O3 folds away (x+0, x*1, x^0, x|0, x-0, x<<0):
        (local.set $acc (i32.add (local.get $acc) (i32.const 0)))
        (local.set $acc (i32.mul (local.get $acc) (i32.const 1)))
        (local.set $acc (i32.xor (local.get $acc) (i32.const 0)))
        (local.set $acc (i32.or  (local.get $acc) (i32.const 0)))
        (local.set $acc (i32.sub (local.get $acc) (i32.const 0)))
        (local.set $acc (i32.shl (local.get $acc) (i32.const 0)))

        ;; BLOAT — redundant local shuffle that copy-propagation removes:
        (local.set $t1 (local.get $acc))
        (local.set $t2 (local.get $t1))
        (local.set $acc (local.get $t2))

        ;; BLOAT — dead computation that DCE removes ($dead never read after the loop):
        (local.set $dead (i32.add (i32.mul (local.get $i) (i32.const 7)) (i32.const 3)))
        (local.set $dead (i32.mul (local.get $dead) (i32.const 2)))

        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br $loop)))

    (i32.store (i32.const 2048) (local.get $acc))

    (i64.or
      (i64.shl (i64.extend_i32_u (i32.const 2048)) (i64.const 32))
      (i64.extend_i32_u (i32.const 4))))
)
