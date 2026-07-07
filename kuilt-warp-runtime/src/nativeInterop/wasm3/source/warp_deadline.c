//
//  warp_deadline.c
//
//  kuilt-warp addition — NOT part of upstream wasm3 v0.5.0.
//
//  Cooperative wall-clock execution deadline for the warp capability sandbox
//  (the CPU-bomb defense). This file provides the STRONG m3_Yield() definition
//  the interpreter polls; the upstream weak default in m3_core.c is removed by
//  the companion "WARP PATCH (kuilt)" so this is the only definition and the
//  static-link resolution is unambiguous.
//
//  The interpreter polls m3_Yield() at the two sites an unbounded computation
//  must pass through (straight-line code over a finite code section always
//  terminates):
//    - every function-call entry (upstream op_Call in m3_exec.h), and
//    - every loop backward branch (the "WARP PATCH (kuilt)" in op_Loop).
//
//  The deadline is THREAD-LOCAL: a wasm3 runtime is single-threaded per
//  invocation (the Kotlin host serializes each Op with a lock and arms/clears
//  the deadline around the synchronous guest call on the calling thread), so
//  concurrent runtimes on different threads never see each other's budgets.
//
//  The clock is CLOCK_UPTIME_RAW via clock_gettime_nsec_np (Apple targets
//  only, available since macOS 10.12 / iOS 10 — below every deployment
//  target here). Reads are amortized: the deadline is only checked every
//  WARP_DEADLINE_STRIDE polls, bounding overshoot to a few hundred branches
//  (~microseconds) while keeping the hot interpreter loop cheap.
//

#include "wasm3.h"

#include <stdint.h>
#include <time.h>

// Declared for cinterop in wasm3.def; kept in sync by hand.
void warp_set_execution_deadline_ns(uint64_t timeout_ns);
void warp_clear_execution_deadline(void);

// The distinguished M3Result for a deadline trap. The Kotlin host matches this
// exact text to translate the trap into its execution-timeout error.
static const char* const warp_err_deadline_exceeded = "warp execution deadline exceeded";

#define WARP_DEADLINE_STRIDE 256u

// 0 = disarmed. Absolute CLOCK_UPTIME_RAW nanosecond deadline otherwise.
static _Thread_local uint64_t warp_deadline_ns = 0;
static _Thread_local uint32_t warp_deadline_countdown = 0;

void warp_set_execution_deadline_ns(uint64_t timeout_ns) {
    warp_deadline_ns = clock_gettime_nsec_np(CLOCK_UPTIME_RAW) + timeout_ns;
    warp_deadline_countdown = WARP_DEADLINE_STRIDE;
}

void warp_clear_execution_deadline(void) {
    warp_deadline_ns = 0;
}

M3Result m3_Yield(void) {
    if (warp_deadline_ns == 0) return m3Err_none;
    if (--warp_deadline_countdown != 0) return m3Err_none;
    warp_deadline_countdown = WARP_DEADLINE_STRIDE;
    if (clock_gettime_nsec_np(CLOCK_UPTIME_RAW) >= warp_deadline_ns) {
        return warp_err_deadline_exceeded;
    }
    return m3Err_none;
}
