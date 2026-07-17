package us.tractat.kuilt.nw

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/**
 * Host-wide advisory lock that **serialises the real-`libkuilt.dylib` kuilt-nw tests across
 * concurrent JVMs** — e.g. two `:kuilt-nw:jvmTest` runs launched at once from sibling git
 * worktrees on one developer machine.
 *
 * ## Why this exists (issue #1511)
 * The dylib-backed tests ([NwBridgeLoopbackConformanceTest], [NwNativeLibTest]) drive Apple's
 * Network.framework over real GCD sockets through the bundled dylib. Running two such test JVMs
 * at the same time on one host can `SIGABRT` (exit 134) in native teardown of the real-radio
 * bridge path — the recorded suites were all green, the abort was in process-global native state.
 * The `127.0.0.1` loopback port is already OS-assigned (ephemeral; `nw_listener_create` with no
 * fixed port), so the collision is **not** a fixed port — it is shared native/dylib state that we
 * cannot safely reach from a test without touching production teardown. So instead of randomising
 * a resource, we make the *test classes* tolerant of a concurrent sibling by funnelling every
 * real-dylib run through one OS-level lock: two runs **serialise** rather than collide.
 *
 * ## Mechanism
 * The lock file sits at a fixed absolute path under the user home, so every worktree and every
 * test JVM for the same user resolves the identical file. A [FileLock] is held by the OS per
 * *process* for the whole file, so a second run blocks in [acquire] until the first [release]s.
 * Acquisition is a no-op when the dylib is absent (non-macOS / no dylib on the classpath): there
 * is no real native state to protect there, so Linux `ci-required` neither creates the lock file
 * nor serialises.
 *
 * Reference-counted and `@Synchronized` so it stays correct even if in-JVM parallel test
 * execution is ever enabled (nested same-process acquisitions share the one OS lock instead of
 * throwing `OverlappingFileLockException`); under the default sequential test config the count
 * simply toggles 0↔1 per test method.
 */
internal object NwRealDylibHostLock {
    private val lockFile: File = File(System.getProperty("user.home"), ".kuilt-nw-crossprocess.lock")

    private var raf: RandomAccessFile? = null
    private var lock: FileLock? = null
    private var holds: Int = 0

    /** True only on a host where the real dylib actually loads — the only place the lock matters. */
    private fun dylibPresent(): Boolean = NwNativeLib.load() != null

    /**
     * Blocks until no sibling process holds the lock, then takes it. No-op (returns immediately)
     * when the dylib is unavailable. Pair every [acquire] with exactly one [release].
     */
    @Synchronized
    fun acquire() {
        if (!dylibPresent()) return
        if (holds++ == 0) {
            val opened = RandomAccessFile(lockFile, "rw")
            lock = opened.channel.lock() // exclusive, whole-file; blocks on a concurrent sibling JVM
            raf = opened
        }
    }

    /** Releases the lock once the last holder lets go. No-op when the dylib is unavailable. */
    @Synchronized
    fun release() {
        if (!dylibPresent() || holds == 0) return
        if (--holds == 0) {
            runCatching { lock?.release() }
            runCatching { raf?.close() }
            lock = null
            raf = null
        }
    }
}
