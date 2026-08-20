package us.tractat.kuilt.store

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Properties of the shared key→filename encoding (#2506).
 *
 * These run on **every** target, which is the point: the defect they close was two
 * independently written sanitisers that each read as correct and disagreed on
 * non-ASCII. One encoder with one property suite is what stops that recurring.
 */
class StoreKeyFilenameTest {

    /**
     * Keys the encoder has to keep apart. Three groups, each earning its place:
     *
     * - **the shipped keyspace** — every `StoreKey` `:kuilt-otel` actually
     *   constructs, so a regression is measured against real names, not invented ones;
     * - **collision witnesses** — pairs the legacy scheme folded together
     *   (punctuation onto `_`, non-ASCII onto `_`, `a` onto `A`);
     * - **hazard witnesses** — a legacy filename used as a key (`otel_logs`), a
     *   path escape, a bare `%`, an entry shaped like a `.tmp` sidecar.
     */
    private val corpus = listOf(
        // The shipped keyspace.
        "otel.causal.clock", "otel.logs", "otel.logs.idx", "otel.logs.seg.0", "otel.logs.seg.17",
        "otel.metrics", "otel.metrics.sums", "otel.metrics.sums.double", "otel.metrics.gauges",
        "otel.metrics.histograms", "otel.metrics.cardinalities", "otel.spans", "otel/spans.v1",
        "otlp.sent.logs@-1274839", "otlp.sent.metrics@0", "otlp.sent.spans@42",
        "span-state", "spans",
        // Collision witnesses: the legacy scheme folded these five onto "a_b".
        "a.b", "a/b", "a b", "a:b", "a_b", "a-b",
        // Case: one file on APFS under the legacy scheme.
        "a", "A", "Otel.Logs", "OTEL.LOGS",
        // Non-ASCII: folded by the JVM sanitiser, passed through by the Apple one.
        "мир", "миг", "日本語", "café", "café",
        // Hazard witnesses.
        "otel_logs", "otel_spans", "otel_causal_clock",
        "%", "%25", "a%b", "%2E", "..", "../evil", "./x", "/", "-", ".",
        "x", "x.tmp", "spans.tmp",
        // Whitespace and the C-string terminator: a NUL truncates a path in any
        // POSIX call, and NSFileManagerDurableStore renames through rename(2).
        "", " ", "\n", "\t", "\u0000", "a\u0000b",
    )

    // ---- lossless ----

    @Test
    fun everyKeyRoundTripsThroughItsFilename() {
        assertAll(
            *corpus.map { name ->
                { assertEquals(name, decodeStoreKeyName(encodeStoreKeyName(name)), "round trip of \"$name\"") }
            }.toTypedArray(),
        )
    }

    @Test
    fun distinctKeysEncodeToDistinctFilenames() {
        val encoded = corpus.associateWith { encodeStoreKeyName(it) }
        val collisions = encoded.entries.groupBy { it.value }.filterValues { it.size > 1 }
        assertTrue(
            collisions.isEmpty(),
            "distinct keys must not share a filename, but these do: " +
                collisions.map { (file, keys) -> "$file <- ${keys.map { it.key }}" },
        )
    }

    /**
     * The property that makes a case-insensitive filesystem safe.
     *
     * Injectivity alone is not enough on APFS/exFAT/NTFS: two filenames that differ
     * only in case are one file there. It holds because a literal `%` is never
     * emitted, so every `%` in the output starts an escape and the two characters
     * after it are always *uppercase* hex — no encoded name can be the case-variant
     * of another.
     */
    @Test
    fun distinctKeysEncodeToFilenamesThatDifferEvenIgnoringCase() {
        val folded = corpus.associateWith { encodeStoreKeyName(it).lowercase() }
        val collisions = folded.entries.groupBy { it.value }.filterValues { it.size > 1 }
        assertTrue(
            collisions.isEmpty(),
            "distinct keys must not share a filename on a case-insensitive filesystem, but these do: " +
                collisions.map { (file, keys) -> "$file <- ${keys.map { it.key }}" },
        )
    }

    // ---- disjointness from the namespaces that share the directory ----

    /** The filename `FileChannelDurableStore` produced before #2506. */
    private fun legacyJvmName(name: String): String = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")

    /** The filename `NSFileManagerDurableStore` produced before #2506. */
    private fun legacyAppleName(name: String): String =
        buildString {
            for (char in name) append(if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_')
        }

    /**
     * The corpus really does contain collisions under both legacy schemes.
     *
     * A positive control for the two tests below: if it did not, "no cross-key name
     * equals another" would be satisfied by a corpus that never exercised the fold,
     * and the disjointness result would be vacuous.
     */
    @Test
    fun theCorpusExercisesBothLegacyFolds() {
        val jvmFolded = corpus.groupBy { legacyJvmName(it) }.filterValues { it.size > 1 }
        val appleFolded = corpus.groupBy { legacyAppleName(it) }.filterValues { it.size > 1 }
        assertAll(
            { assertTrue(jvmFolded.isNotEmpty(), "corpus contains keys the JVM sanitiser folded together") },
            { assertTrue(appleFolded.isNotEmpty(), "corpus contains keys the Apple sanitiser folded together") },
            // The two schemes must be shown to *differ*, or "one shared encoder" is
            // solving a problem the corpus cannot see. `мир` is the witness: folded
            // by the JVM regex, passed through by isLetterOrDigit().
            {
                assertTrue(
                    corpus.any { legacyJvmName(it) != legacyAppleName(it) },
                    "corpus contains a key on which the two legacy sanitisers disagreed",
                )
            },
        )
    }

    /**
     * No key's encoded filename may equal a **different** key's orphaned legacy
     * filename.
     *
     * There is no migration, so the legacy files sit in the same directory forever.
     * An overlap here would mean a key's very first read returned bytes written
     * under some other key — silent wrong-key data, which is strictly worse than the
     * loss that orphaning already accepts.
     */
    @Test
    fun noKeyAdoptsADifferentKeysLegacyFilename() {
        val overlaps = buildList {
            for (legacyKey in corpus) {
                for (scheme in listOf(::legacyJvmName, ::legacyAppleName)) {
                    val legacy = scheme(legacyKey)
                    for (newKey in corpus) {
                        if (newKey != legacyKey && encodeStoreKeyName(newKey) == legacy) {
                            add("\"$newKey\" encodes to \"$legacy\", the legacy file of \"$legacyKey\"")
                        }
                    }
                }
            }
        }
        assertTrue(overlaps.isEmpty(), "new and legacy namespaces must be disjoint across keys, but: $overlaps")
    }

    @Test
    fun noKeyAdoptsADifferentKeysLegacyFilenameEvenOnACaseFoldingFilesystem() {
        val overlaps = buildList {
            for (legacyKey in corpus) {
                for (scheme in listOf(::legacyJvmName, ::legacyAppleName)) {
                    val legacy = scheme(legacyKey)
                    for (newKey in corpus) {
                        if (newKey != legacyKey && encodeStoreKeyName(newKey).equals(legacy, ignoreCase = true)) {
                            add("\"$newKey\" encodes to a case-variant of \"$legacy\", the legacy file of \"$legacyKey\"")
                        }
                    }
                }
            }
        }
        assertTrue(overlaps.isEmpty(), "new and legacy namespaces must be disjoint across keys, but: $overlaps")
    }

    /**
     * Where the two namespaces *do* overlap, they overlap on the same key — so the
     * "orphan" is that key's own file and reading it is correct rather than wrong.
     * `spans` and `span-state` are the shipped keys this carries over for free.
     */
    @Test
    fun aKeyInsideTheSafeSetKeepsItsOwnLegacyFile() {
        val carriedOver = corpus.filter { encodeStoreKeyName(it) == legacyJvmName(it) }
        assertAll(
            { assertTrue("spans" in carriedOver, "\"spans\" carries over") },
            { assertTrue("span-state" in carriedOver, "\"span-state\" carries over") },
            // Every carry-over must be the identity on both sides — that is the only
            // shape in which sharing a filename with the legacy scheme is safe.
            {
                assertEquals(
                    emptyList(),
                    carriedOver.filter { encodeStoreKeyName(it) != it },
                    "a carried-over key is one both schemes leave alone",
                )
            },
        )
    }

    /**
     * An entry's filename can never equal another entry's `.tmp` sidecar, because no
     * encoded name contains a `.` at all. Both file backends write `<name>.tmp`
     * beside `<name>`, so without this an in-flight write could land on a live entry.
     */
    @Test
    fun anEncodedNameNeverCollidesWithATempSidecar() {
        val encoded = corpus.map { encodeStoreKeyName(it) }
        val sidecars = encoded.map { "$it.tmp" }.toSet()
        assertAll(
            { assertEquals(emptyList(), encoded.filter { '.' in it }, "no encoded name contains a dot") },
            { assertEquals(emptyList(), encoded.filter { it in sidecars }, "no entry lands on a sidecar") },
        )
    }

    @Test
    fun anEncodedNameContainsNothingThatCouldEscapeTheDirectory() {
        val encoded = corpus.map { encodeStoreKeyName(it) }
        assertAll(
            { assertEquals(emptyList(), encoded.filter { '/' in it }, "no path separator") },
            { assertEquals(emptyList(), encoded.filter { '\\' in it }, "no Windows path separator") },
            { assertEquals(emptyList(), encoded.filter { '_' in it }, "no underscore — the legacy fold target") },
            {
                assertEquals(
                    emptyList(),
                    encoded.filter { it == "." || it == ".." },
                    "no relative-directory name",
                )
            },
        )
    }

    // ---- the escapes themselves ----

    @Test
    fun theCharactersTheOldSchemeFoldedAreEscapedOverTheirUtf8Bytes() {
        assertAll(
            { assertEquals("a%2Eb", encodeStoreKeyName("a.b")) },
            { assertEquals("a%2Fb", encodeStoreKeyName("a/b")) },
            { assertEquals("a%20b", encodeStoreKeyName("a b")) },
            { assertEquals("a%3Ab", encodeStoreKeyName("a:b")) },
            { assertEquals("a%5Fb", encodeStoreKeyName("a_b")) },
            { assertEquals("a-b", encodeStoreKeyName("a-b")) },
            { assertEquals("a", encodeStoreKeyName("a")) },
            { assertEquals("%41", encodeStoreKeyName("A")) },
            // `%` escaping itself is what makes the whole mapping injective.
            { assertEquals("%25", encodeStoreKeyName("%")) },
            { assertEquals("%2525", encodeStoreKeyName("%25")) },
            // Two UTF-8 bytes per Cyrillic letter; the JVM sanitiser folded all three to "_".
            { assertEquals("%D0%BC%D0%B8%D1%80", encodeStoreKeyName("мир")) },
            { assertEquals("%D0%BC%D0%B8%D0%B3", encodeStoreKeyName("миг")) },
        )
    }

    /**
     * `otel/spans.v1` is a real shipped key with a path separator in it. Pinned
     * literally because it is the one key whose *old* behaviour a reader is most
     * likely to assume is unchanged.
     */
    @Test
    fun theShippedKeyWithAPathSeparatorEncodesWithoutOne() {
        assertEquals("otel%2Fspans%2Ev1", encodeStoreKeyName("otel/spans.v1"))
    }

    // ---- both directions refuse what they cannot represent ----

    /**
     * An unpaired surrogate has no UTF-8 encoding. The stdlib default would
     * substitute `U+FFFD`, which folds every such key onto one filename — precisely
     * the many-to-one mapping this encoder exists to remove — so it fails instead.
     */
    @Test
    fun encodingRefusesTextThatHasNoUtf8Form() {
        val loneHighSurrogate = "a\uD800b"
        val loneLowSurrogate = "a\uDC00b"
        assertAll(
            { assertFailsWith<IllegalArgumentException> { encodeStoreKeyName(loneHighSurrogate) } },
            { assertFailsWith<IllegalArgumentException> { encodeStoreKeyName(loneLowSurrogate) } },
            // A *paired* surrogate is ordinary text and must still encode.
            { assertEquals("%F0%9F%A7%B5", encodeStoreKeyName("🧵")) },
        )
    }

    /**
     * Decoding is strict about the encoder's own image rather than forgiving like a
     * URL decoder. A forgiving decoder would map several filenames onto one key
     * name — the same many-to-one fold, running the other way.
     */
    @Test
    fun decodingRefusesAnythingTheEncoderCouldNotHaveProduced() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { decodeStoreKeyName("otel_logs") } },
            { assertFailsWith<IllegalArgumentException> { decodeStoreKeyName("a.b") } },
            { assertFailsWith<IllegalArgumentException> { decodeStoreKeyName("A") } },
            // Lowercase hex: a second spelling of an escape the encoder emits uppercase.
            { assertFailsWith<IllegalArgumentException> { decodeStoreKeyName("%2e") } },
            // Over-escaped: the encoder emits a safe byte verbatim, never as an escape,
            // so accepting these would map two filenames onto one key name.
            { assertFailsWith<IllegalArgumentException> { decodeStoreKeyName("%61") } },
            { assertFailsWith<IllegalArgumentException> { decodeStoreKeyName("%2D") } },
            { assertFailsWith<IllegalArgumentException> { decodeStoreKeyName("%30") } },
            { assertFailsWith<IllegalArgumentException> { decodeStoreKeyName("%zz") } },
            { assertFailsWith<IllegalArgumentException> { decodeStoreKeyName("%2") } },
            { assertFailsWith<IllegalArgumentException> { decodeStoreKeyName("%") } },
            // Well-formed escapes that are not well-formed UTF-8.
            { assertFailsWith<IllegalArgumentException> { decodeStoreKeyName("%FF") } },
            { assertFailsWith<IllegalArgumentException> { decodeStoreKeyName("%D0") } },
        )
    }

    // ---- the length non-promise, stated as a measurement rather than a hope ----

    /**
     * Length is **not** promised, and this test says so in numbers rather than
     * leaving a green round-trip to imply otherwise.
     *
     * A 128-character key is well inside what a conformance suite exercises, and an
     * all-ASCII one encodes to 128 bytes. The same length of Cyrillic encodes to
     * 768 — three times a common 255-byte filename limit. The encoder is correct in
     * both cases; the *filesystem* is what refuses the second, and a caller that
     * needs unbounded names owes the store a digest, not a name.
     */
    @Test
    fun encodingInflatesUpToThreeTimesPerUtf8Byte() {
        val asciiKey = "a".repeat(128)
        val cyrillicKey = "м".repeat(128)
        assertAll(
            { assertEquals(128, encodeStoreKeyName(asciiKey).length, "short ASCII key stays short") },
            { assertEquals(768, encodeStoreKeyName(cyrillicKey).length, "2 UTF-8 bytes × 3 chars per escape") },
            { assertTrue(encodeStoreKeyName(cyrillicKey).length > 255, "past a common filename limit") },
            { assertFalse(encodeStoreKeyName(asciiKey).length > 255, "the shipped shape is nowhere near it") },
        )
    }
}
