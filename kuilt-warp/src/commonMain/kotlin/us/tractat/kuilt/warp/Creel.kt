package us.tractat.kuilt.warp

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import org.kotlincrypto.hash.sha2.SHA256

/**
 * The local rack of loaded bobbins — a content-addressed store keyed by [BobbinHash].
 *
 * A **bobbin** is an immutable kernel: opaque bytes (later a `.wasm` blob) that a peer
 * fetches once and caches. The **creel** is the peer's local collection of those bytes,
 * indexed by their content hash so that any fetch can be verified before use.
 *
 * **Content-addressing makes merge trivial.** Because `key = hash(bytes)`, any two peers
 * that hold the same [BobbinHash] hold byte-identical bytes. The value lattice is the
 * one-step `Absent ⊏ Present`: two stores are merged by taking the union of their keys —
 * no conflict is possible. The gossip layer (warp slice C5) advertises a
 * `GSet<BobbinHash>` manifest eagerly and fetches the bytes lazily; this class is the
 * local byte-cache that sits beneath it.
 *
 * **A `null` result from [get] is legitimate.** It means the bobbin has not been fetched
 * yet — the ordinary "bobbin not loaded yet" state on the lazy-fetch path. It is not an
 * invariant violation; callers must handle it as a real case (mirrors the
 * `OpRegistry.resolve → null` contract).
 *
 * **Thread-safety.** The backing map is guarded by an explicit
 * [kotlinx.atomicfu.locks.ReentrantLock]; no `suspend` calls are made inside the lock.
 * This type is correct under a multi-threaded dispatcher.
 *
 * @see BobbinHash
 */
public class Creel {

    private val lock = reentrantLock()
    private val store = mutableMapOf<BobbinHash, ByteArray>()

    /**
     * Hashes [bytes] with SHA-256, stores them under the resulting [BobbinHash], and
     * returns the hash. Idempotent: if the same bytes are put again the store is unchanged
     * and the same hash is returned.
     *
     * The caller's [bytes] array is copied on entry; external mutation of the original
     * does not affect stored content.
     */
    public fun put(bytes: ByteArray): BobbinHash {
        val hash = hash(bytes)
        lock.withLock {
            if (!store.containsKey(hash)) {
                store[hash] = bytes.copyOf()
            }
        }
        return hash
    }

    /**
     * Re-hashes [bytes] and verifies that the result matches [expected], then stores
     * the bytes. Throws [IllegalArgumentException] if the hash does not match — a
     * content-addressing invariant violation indicating tampered or corrupt bytes.
     *
     * This is the **verify-on-insert** step described in the design: a peer that received
     * bytes from a neighbour calls this to confirm what it fetched before caching it.
     *
     * @throws IllegalArgumentException if `hash(bytes) != expected`.
     */
    public fun putVerified(expected: BobbinHash, bytes: ByteArray) {
        val actual = hash(bytes)
        require(actual == expected) {
            "Bobbin hash mismatch: expected ${expected.value} but computed ${actual.value}. " +
                "The bytes are tampered or corrupt."
        }
        lock.withLock {
            if (!store.containsKey(expected)) {
                store[expected] = bytes.copyOf()
            }
        }
    }

    /**
     * Returns a copy of the bytes stored under [hash], or `null` if this creel does not
     * yet hold that bobbin.
     *
     * A `null` return is the legitimate **"bobbin not loaded yet"** state — routine on
     * the lazy-fetch path. It is not an error; the caller should request the bytes from
     * a neighbour (warp slice C5) and call [put] or [putVerified] once they arrive.
     *
     * The returned array is a defensive copy; callers may mutate it freely without
     * affecting stored content.
     */
    public fun get(hash: BobbinHash): ByteArray? = lock.withLock { store[hash]?.copyOf() }

    /**
     * Returns `true` if this creel holds bytes for [hash].
     */
    public fun contains(hash: BobbinHash): Boolean = lock.withLock { store.containsKey(hash) }

    /**
     * The set of [BobbinHash]es currently held by this creel — the keys this peer can
     * serve to neighbours. This is the local fragment of the `GSet<BobbinHash>` manifest
     * that slice C5 will gossip across the mesh.
     */
    public val loaded: Set<BobbinHash>
        get() = lock.withLock { store.keys.toSet() }

    // ── Hash ─────────────────────────────────────────────────────────────────

    private fun hash(bytes: ByteArray): BobbinHash = BobbinHash(SHA256().digest(bytes).toHex())

    private fun ByteArray.toHex(): String = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
}
