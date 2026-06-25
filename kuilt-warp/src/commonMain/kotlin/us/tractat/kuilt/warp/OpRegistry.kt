package us.tractat.kuilt.warp

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

/**
 * A local, startup-time registry that maps symbolic [OpId]s to their [Op] implementations.
 *
 * Every peer populates the registry at startup from the same compiled binary so that a
 * later task descriptor `{ op, args }` (warp slice C2) can look an op up by name and run
 * *its own registered copy*. The function never moves across the fabric; only its name does.
 *
 * **Duplicate registration fails loud.** A homogeneous binary registers each op exactly
 * once at startup; registering the same [OpId] twice indicates a programming error and
 * throws [IllegalStateException].
 *
 * **`resolve` returning `null` is legitimate.** An unknown [OpId] is a **"bobbin not
 * loaded yet"** state — routine on the lazy-fetch path (warp slices C4/C5). Callers must
 * handle `null` as a real case, not an invariant violation.
 *
 * **Thread-safety.** The backing map is guarded by an explicit
 * [kotlinx.atomicfu.locks.ReentrantLock]. This type is safe under a multi-threaded
 * dispatcher. [Op.invoke] is *not* called inside the lock — the registry only stores and
 * looks up; invocation is the caller's concern.
 */
public class OpRegistry {

    private val lock = reentrantLock()
    private val ops = mutableMapOf<OpId, Op>()

    /**
     * Registers [op] under [id].
     *
     * @throws IllegalStateException if [id] is already registered. A homogeneous binary
     *   registers each op exactly once at startup; a duplicate is a programming error.
     */
    public fun register(id: OpId, op: Op) {
        lock.withLock {
            check(!ops.containsKey(id)) {
                "Op '${id.value}' is already registered. Each op must be registered exactly once at startup."
            }
            ops[id] = op
        }
    }

    /**
     * Returns the [Op] registered under [id], or `null` if no op has been registered
     * for [id].
     *
     * A `null` result is a legitimate **"bobbin not loaded yet"** state — the lazy-fetch
     * path (warp slices C4/C5) will surface the op on demand. It is not an invariant
     * violation; callers must handle it as a real case.
     */
    public fun resolve(id: OpId): Op? = lock.withLock { ops[id] }

    /**
     * The set of [OpId]s currently registered in this registry.
     */
    public val registered: Set<OpId>
        get() = lock.withLock { ops.keys.toSet() }
}
