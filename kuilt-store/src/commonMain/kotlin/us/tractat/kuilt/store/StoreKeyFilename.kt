package us.tractat.kuilt.store

/**
 * The only characters an encoded filename carries verbatim: lowercase ASCII
 * letters, digits and `-`. See [encodeStoreKeyName] for why it is this narrow.
 */
private fun isSafe(char: Char): Boolean = char in 'a'..'z' || char in '0'..'9' || char == '-'

/** Uppercase, so a decoder can reject a lowercase escape and stay strict about its own image. */
private const val HEX_DIGITS = "0123456789ABCDEF"

/** One hex digit carries four bits. */
private const val NIBBLE_BITS = 4

/** Mask selecting the low nibble of a byte. */
private const val LOW_NIBBLE = 0x0F

/** Mask turning a signed [Byte] back into its unsigned 0..255 value. */
private const val BYTE_MASK = 0xFF

/** `%` plus two hex digits. */
private const val ESCAPE_LENGTH = 3

/** The filename this [StoreKey] is stored under, on every file-backed [DurableStore]. */
internal val StoreKey.filename: String
    get() = encodeStoreKeyName(name)

/**
 * Encode a [StoreKey.name] as a filename — losslessly, and safely on any filesystem.
 *
 * Every byte of the name's UTF-8 encoding that is not in `[a-z0-9-]` becomes `%XX`
 * with uppercase hex. `%` itself becomes `%25`, which is what makes the mapping
 * **injective**: distinct keys always produce distinct filenames, so a write under
 * one key can never destroy the value under another (#2506). [decodeStoreKeyName]
 * is the inverse, and exists so that "lossless" is checkable rather than asserted.
 *
 * ## Why the safe set is narrower than it looks like it needs to be
 *
 * `_`, `.` and every uppercase letter are escaped even though all three are legal
 * in a filename. Do not widen the set — each exclusion is load-bearing:
 *
 * - **The legacy namespace has to stay disjoint.** The fix that introduced this
 *   encoding ships **no migration**, so files written under the old lossy scheme
 *   (`[^a-zA-Z0-9_-]` → `_`) stay on disk in the same directory. If a new-scheme
 *   filename ever equalled an *orphaned* legacy filename belonging to a
 *   **different** key, that key's first read would return another key's abandoned
 *   bytes — silent wrong-key data, strictly worse than the loss orphaning already
 *   accepts. Concretely: legacy `otel.logs` was stored as `otel_logs`, so with `_`
 *   safe, a future key literally named `otel_logs` would adopt it. Because an
 *   encoded name never contains `_`, and a legacy name contains no `_` only when
 *   the key was already inside `[A-Za-z0-9-]` (where the legacy map was the
 *   identity), the two namespaces overlap **only** where both schemes are the
 *   identity on the same key — and there the "orphan" is that key's own file, so
 *   reading it is correct. `spans` and `span-state` carry over for free.
 * - **The `.tmp` sidecar namespace is disjoint for free.** Both file backends write
 *   `<name>.tmp` beside `<name>`. Since an encoded name never contains `.`, no
 *   entry's filename can equal another entry's temp filename.
 * - **Case-insensitive filesystems stop being a hole.** APFS is case-insensitive by
 *   default, as are exFAT and NTFS, so `StoreKey("a")` and `StoreKey("A")` shared
 *   one file under the old scheme — the same defect, on the same backends, that
 *   nobody had measured. Escaping uppercase closes it, and the escapes themselves
 *   cannot reintroduce it: after a `%` the next two characters are always uppercase
 *   hex, and a literal `%` is never emitted, so no two encoded names differ only in
 *   case either.
 *
 * ## What is not promised
 *
 * **Length.** Percent-encoding inflates a name by up to 3× per UTF-8 byte, and a
 * non-ASCII character is up to 4 UTF-8 bytes — so a 128-character key can encode to
 * well over a 255-byte filename limit and the write will fail with whatever the
 * platform reports. The shipped keyspace is short ASCII; a green round-trip test on
 * a long *ASCII* key should not be read as a promise about a long non-ASCII one.
 * If a caller needs unbounded key names, it owes the store a digest, not a name.
 *
 * **An empty name.** `StoreKey("")` encodes to an empty filename, which addresses
 * the store's own directory rather than an entry. That was true of the old scheme
 * too; an empty key has never been usable and this does not make it so.
 *
 * @throws IllegalArgumentException if [name] is not well-formed text — an unpaired
 *   surrogate has no UTF-8 encoding, and silently substituting `U+FFFD` for it
 *   would reintroduce exactly the many-to-one fold this function exists to remove.
 */
internal fun encodeStoreKeyName(name: String): String =
    buildString(name.length) {
        for (byte in name.encodeToByteArray(throwOnInvalidSequence = true)) {
            val value = byte.toInt() and BYTE_MASK
            val char = value.toChar()
            if (isSafe(char)) {
                append(char)
            } else {
                append('%')
                append(HEX_DIGITS[value shr NIBBLE_BITS])
                append(HEX_DIGITS[value and LOW_NIBBLE])
            }
        }
    }

/**
 * The inverse of [encodeStoreKeyName].
 *
 * Nothing in the store calls this on a hot path — the backends only ever encode.
 * It exists so that losslessness is a property a test can *check*: `decode(encode(k))
 * == k` over a corpus is a much stronger statement than "the encoder looks injective".
 *
 * Deliberately strict about its own image rather than forgiving like a URL decoder:
 * a lowercase escape, a truncated escape, or a bare character outside the safe set
 * is rejected. A forgiving decoder would map two different filenames onto one key
 * name, which is the same many-to-one fold in the other direction.
 *
 * @throws IllegalArgumentException if [filename] is not something
 *   [encodeStoreKeyName] could have produced.
 */
internal fun decodeStoreKeyName(filename: String): String {
    val bytes = ByteArray(filename.length)
    var length = 0
    var index = 0
    while (index < filename.length) {
        val char = filename[index]
        if (char == '%') {
            require(index + ESCAPE_LENGTH <= filename.length) {
                "\"$filename\" ends in a truncated escape at index $index"
            }
            val high = hexValue(filename[index + 1], filename)
            val low = hexValue(filename[index + 2], filename)
            bytes[length++] = ((high shl NIBBLE_BITS) or low).toByte()
            index += ESCAPE_LENGTH
        } else {
            require(isSafe(char)) { "\"$filename\" contains '$char' at index $index, which is outside the safe set" }
            bytes[length++] = char.code.toByte()
            index++
        }
    }
    return bytes.decodeToString(0, length, throwOnInvalidSequence = true)
}

private fun hexValue(char: Char, filename: String): Int {
    val value = HEX_DIGITS.indexOf(char)
    require(value >= 0) { "\"$filename\" contains '$char' where an uppercase hex digit was expected" }
    return value
}
