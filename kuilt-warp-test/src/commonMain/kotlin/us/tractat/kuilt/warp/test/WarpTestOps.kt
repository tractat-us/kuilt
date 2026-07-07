package us.tractat.kuilt.warp.test

import us.tractat.kuilt.warp.Op
import us.tractat.kuilt.warp.WarpOp
import us.tractat.kuilt.warp.shuttle

/**
 * A pass-through op — echoes its argument bytes unchanged.
 *
 * The canonical trivial op for warp tests that need *an* op but don't care what it
 * computes. Unlike [MultiNodeWarpSim.trackedEchoRegistry]'s per-sim tracked echo
 * (which closes over the sim's execution log), this one is stateless and
 * statically declared, so it is `@WarpOp`-auto-registered: install it with
 * `opRegistryOf(WarpOps)` — the generated registrar for this package — instead of
 * a hand-written `registry.register(OpId("echo"), ...)` line.
 */
@WarpOp("echo")
public val echo: Op = shuttle { args -> args }
