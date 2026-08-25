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
     * - **the shipped keyspace** — real names rather than invented ones, so a
     *   regression is measured against what kuilt stores. Two of the three groups are
     *   families rather than lists, and both are bounded the same way — a fixed prefix
     *   plus a variable part that lies entirely inside the safe set, so the entries
     *   here differ from every other member only in safe characters and stand in for
     *   all of them. `:kuilt-otel`'s are fixed literals apart from
     *   `otel.logs.seg.<n>`, whose variable part is decimal digits, represented here
     *   by `.seg.0` and `.seg.17`. `:kuilt-otel-otlp`'s are one per collector URL:
     *   three fixed prefixes each followed by a 128-bit SHA-256 tag in lowercase hex.
     *   Note every entry contains a `.`: nothing kuilt ships is inside the safe set;
     * - **collision witnesses** — pairs the legacy scheme folded together
     *   (punctuation onto `_`, non-ASCII onto `_`, `a` onto `A`);
     * - **hazard witnesses** — a legacy filename used as a key (`otel_logs`), a
     *   path escape, a bare `%`, an entry shaped like a `.tmp` sidecar.
     */
    private val corpus = listOf(
        // The shipped keyspace: every StoreKey :kuilt-otel and :kuilt-otel-otlp
        // construct in a *Main source. Every one contains a '.', so every one moves.
        "otel.causal.clock", "otel.logs", "otel.logs.idx", "otel.logs.seg.0", "otel.logs.seg.17",
        "otel.metrics", "otel.metrics.sums", "otel.metrics.sums.double", "otel.metrics.gauges",
        "otel.metrics.histograms", "otel.metrics.cardinalities", "otel.spans",
        // The OTLP sent-set keys, `otlp.sent.<signal>@<128-bit SHA-256 of the collector
        // base URL, hex>` (#2513). Real tags, not invented ones — respectively the
        // trimmed bases `https://collector.example.com:4318`,
        // `http://otel-collector.observability.svc.cluster.local:4318` and
        // `https://[2001:0db8:85a3:0000:0000:8a2e:0370:7334]:4318/otlp`. The tag is
        // inside the safe set, so only the `.` and `@` of the prefix move.
        "otlp.sent.logs@75a77f9c45c56344fc6b18201684d108",
        "otlp.sent.metrics@8c1c246af2a88ef0282fdd87c7d11770",
        "otlp.sent.spans@e1c0b85b82d62daec00d66cda94d0e10",
        // Safe-set fixtures — NOT shipped keys. They exist to exercise the carry-over
        // arm, which no key kuilt itself stores can reach.
        "span-state", "spans",
        // A key shaped like a path. Invented, not shipped — kuilt stores nothing
        // with a '/' in it.
        "otel/spans.v1",
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

    /**
     * The same disjointness, re-asked under the equality a **case-insensitive
     * filesystem** actually uses — which is the one that matters, since escaping
     * uppercase is justified above precisely by APFS folding case.
     *
     * It does **not** hold absolutely here, and this test says so in the only
     * honest way: by measuring the boundary instead of asserting an empty set.
     * The overlap is exactly the case-variant pairs — an old key inside
     * `[A-Za-z0-9-]` and a new key that is its lowercase form. `StoreKey("config")`
     * really can read `StoreKey("Config")`'s orphan on APFS.
     *
     * Written as an equality against the enumerated set, not a filter that merely
     * tolerates such pairs: if a change introduced an overlap of any *other* shape
     * — the dangerous kind — this reds, whereas `overlaps.all { isCaseVariant(it) }`
     * would also pass on an empty set and so could not tell a fix from a regression.
     */
    @Test
    fun noKeyAdoptsADifferentKeysLegacyFilenameEvenOnACaseFoldingFilesystem() {
        val overlaps = mutableSetOf<String>()
        for (legacyKey in corpus) {
            for (scheme in listOf(::legacyJvmName, ::legacyAppleName)) {
                val legacy = scheme(legacyKey)
                for (newKey in corpus) {
                    if (newKey != legacyKey && encodeStoreKeyName(newKey).equals(legacy, ignoreCase = true)) {
                        overlaps.add("\"$newKey\" -> \"$legacy\" (legacy file of \"$legacyKey\")")
                    }
                }
            }
        }
        assertEquals(
            listOf("\"a\" -> \"A\" (legacy file of \"A\")"),
            overlaps.sorted(),
            "the only cross-key overlap a case-folding filesystem may introduce is a case-variant pair",
        )
    }

    /**
     * Where the two namespaces *do* overlap under `==`, they overlap on the same
     * key — so the "orphan" is that key's own file and reading it is correct.
     *
     * This benefits **consumers only**. No key kuilt itself stores is inside the
     * safe set: every one of them contains a `.`, so every one of them moves and
     * loses its data. `spans` and `span-state` below are fixtures chosen to sit in
     * the safe set, not shipped keys — an earlier version of this file called them
     * shipped and was wrong.
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
     * A key containing a path separator stays one filename rather than becoming a
     * directory. Pinned literally because it is the shape whose *old* behaviour a
     * reader is most likely to assume is unchanged.
     *
     * `otel/spans.v1` is invented, not shipped — kuilt stores no key with a `/`.
     */
    @Test
    fun aKeyWithAPathSeparatorEncodesWithoutOne() {
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
