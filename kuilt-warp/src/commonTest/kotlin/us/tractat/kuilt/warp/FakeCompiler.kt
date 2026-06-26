package us.tractat.kuilt.warp

/**
 * The 8-byte header of an empty-but-valid WebAssembly module: magic `\0asm` + version 1.
 * A conforming runtime loads it without error; it exports nothing. Sufficient as a spike
 * fixture — the go/no-go injects a [FakeWasmRuntime], so no real export is executed.
 */
internal val MINIMAL_WASM: ByteArray = byteArrayOf(
    0x00, 0x61, 0x73, 0x6D, // "\0asm"
    0x01, 0x00, 0x00, 0x00, // version 1
)

/**
 * The spike's **fake compiler**: appends a WebAssembly *custom section* tagging [bytes] as
 * "compiled-for:<target>". A custom section is valid wasm that runtimes ignore, so the
 * module still loads and runs identically — but the bytes (and therefore the content hash)
 * differ per [target], making the result a genuinely distinct, fetchable bobbin.
 *
 * Deterministic and pure: same input ⇒ same output ⇒ same hash. This proves *distribution
 * and swap*, NOT *speedup* — the transform is a no-op optimization. Real optimization is the
 * D4 toolchain epic.
 *
 * Custom-section encoding: section id `0x00`, then a LEB128 length, then a name (LEB128
 * length-prefixed UTF-8) followed by the (empty) payload.
 */
internal fun fakeCompile(bytes: ByteArray, target: Target): ByteArray {
    val name = "compiled-for:${target.name}".encodeToByteArray()
    val nameField = leb128(name.size) + name
    return bytes + byteArrayOf(0x00) + leb128(nameField.size) + nameField
}

/** Minimal unsigned LEB128 encoder (sufficient for small section lengths). */
private fun leb128(value: Int): ByteArray {
    var v = value
    val out = ArrayList<Byte>()
    do {
        var b = (v and 0x7F)
        v = v ushr 7
        if (v != 0) b = b or 0x80
        out.add(b.toByte())
    } while (v != 0)
    return out.toByteArray()
}
