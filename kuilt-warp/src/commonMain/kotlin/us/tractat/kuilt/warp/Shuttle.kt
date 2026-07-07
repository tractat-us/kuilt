package us.tractat.kuilt.warp

/**
 * Declares a warp op so it reads like an ordinary lambda.
 *
 * `shuttle { }` is the declaration-site counterpart of the loom metaphor: the shuttle
 * carries the weft — your computation — across the warp. The block *is* the op body;
 * pair it with [WarpOp] and the `kuilt-warp-ksp` processor registers it for you:
 *
 * ```kotlin
 * @WarpOp("reverse")
 * val reverse: Op = shuttle { args -> args.reversedArray() }
 * ```
 *
 * At runtime this is the identity — an [Op] is already a `fun interface` over
 * `suspend (ByteArray) -> ByteArray`. The named factory exists so an op declaration
 * is recognizable at a glance (and greppable) rather than a bare SAM conversion.
 *
 * Note the deliberate split with [Warp.shuttle]: *declaring* an op (`shuttle { … }`,
 * here) records what the computation **is**; *throwing* the shuttle
 * (`Warp.shuttle(opId)`) records where a [Draft] pipeline **starts**. Only the name
 * ever crosses the fabric — the lambda never leaves the binary it was compiled into.
 *
 * @param op The op body.
 * @return [op], unchanged.
 * @see WarpOp
 * @see OpRegistrar
 */
public fun shuttle(op: Op): Op = op
