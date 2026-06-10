package us.tractat.kuilt.deal

// Kotlin/Wasm JS interop only accepts external, primitive, string, and function
// types across the boundary — not ByteArray. We fill a JS Uint8Array via the Web
// Crypto CSPRNG, then read it back byte-by-byte through an Int8Array view over the
// same buffer (matching the bit pattern Kotlin's signed Byte expects).

/** Allocate a Uint8Array of [length] bytes filled by `crypto.getRandomValues`, return its buffer. */
@JsFun("(length) => { const a = new Uint8Array(length); crypto.getRandomValues(a); return a.buffer; }")
private external fun secureRandomBuffer(length: Int): JsAny

/** Read a single byte from an ArrayBuffer [buffer] at [index]. */
@JsFun("(buffer, index) => new Int8Array(buffer)[index]")
private external fun bufferGetByte(
    buffer: JsAny,
    index: Int,
): Byte

internal actual fun secureRandomBytes(n: Int): ByteArray {
    require(n >= 0) { "secureRandomBytes length must be non-negative, was $n" }
    if (n == 0) return ByteArray(0)
    val buffer = secureRandomBuffer(n)
    return ByteArray(n) { bufferGetByte(buffer, it) }
}
