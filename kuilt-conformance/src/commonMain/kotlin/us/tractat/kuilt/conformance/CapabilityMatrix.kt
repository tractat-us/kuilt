package us.tractat.kuilt.conformance

/**
 * One fabric's row in the [renderMatrix] capability matrix.
 *
 * A fabric declares its [SeamCapabilities], and — for every flag it flips to
 * `false` — a tracking URL in [gaps] keyed by the capability's canonical name
 * (see [SeamCapabilities.falseFlags]). This mirrors the fabric's own
 * `capabilityGaps()`, so the same map that satisfies
 * `SeamConformanceSuite.everyFalseCapabilityDeclaresAGap` renders here.
 *
 * @property fabric the fabric's display name (the row label).
 * @property capabilities the declared behaviour against the `Seam` contract.
 * @property gaps capability name → issue/doc URL, for every `false` flag.
 * @property meshEvidence for a `meshDelivery = true` fabric, WHY the flag is
 *   trusted: a `MeshConformanceSuite` subclass name that exercises N-peer
 *   coverage, or an explicit 2-peer vacuity note (`SeamConformanceSuite` is
 *   fixed at two Looms by ADR-001, so 3-peer mesh coverage cannot live there).
 *   Required (non-blank) when `meshDelivery = true`; `null` otherwise.
 */
public data class MatrixEntry(
    val fabric: String,
    val capabilities: SeamCapabilities,
    val gaps: Map<String, String>,
    val meshEvidence: String? = null,
)

/**
 * The eight capability columns, in the fixed order the matrix always renders.
 *
 * Held here (not derived from reflection, which KMP `commonMain` lacks) so the
 * column order is deterministic and matches [SeamCapabilities.falseFlags].
 */
private val CAPABILITY_COLUMNS: List<Pair<String, (SeamCapabilities) -> Boolean>> = listOf(
    "ordersDelivery" to SeamCapabilities::ordersDelivery,
    "reportsPeerLoss" to SeamCapabilities::reportsPeerLoss,
    "terminatesIncomingOnClose" to SeamCapabilities::terminatesIncomingOnClose,
    "staysTornAfterClose" to SeamCapabilities::staysTornAfterClose,
    "throwsOnSendToTorn" to SeamCapabilities::throwsOnSendToTorn,
    "supportsSendTo" to SeamCapabilities::supportsSendTo,
    "securesTransport" to SeamCapabilities::securesTransport,
    "meshDelivery" to SeamCapabilities::meshDelivery,
)

/**
 * Render the fabric capability matrix as a stable markdown table — the visibility
 * artifact that makes every declared gap loud.
 *
 * Rows are the [entries] in input order; columns are the eight capabilities in the
 * fixed [CAPABILITY_COLUMNS] order. A `true` cell renders `✓`; a `false` cell
 * renders an issue-linked en-dash `[–](url)` pointing at the gap's tracking URL. A
 * trailing "mesh evidence" column records, for each `meshDelivery = true` row, why
 * the flag is trusted.
 *
 * The output is deterministic: same [entries] → same string, byte-for-byte.
 *
 * Two conditions are **render errors** (an [IllegalArgumentException], never a
 * silent render):
 *  1. A `false` flag with no non-blank URL in [MatrixEntry.gaps] for that
 *     capability name — mirrors `SeamConformanceSuite.everyFalseCapabilityDeclaresAGap`.
 *  2. `meshDelivery == true` with a `null`/blank [MatrixEntry.meshEvidence] — a
 *     mesh-claiming fabric must cite its evidence.
 *
 * (`meshDelivery == false` is governed by condition 1 — it needs a gap URL, not
 * evidence; `meshDelivery == true` is governed by condition 2 — it needs evidence,
 * not a gap.)
 */
public fun renderMatrix(entries: List<MatrixEntry>): String {
    // Fail fast: validate every entry BEFORE emitting anything, so a bad entry
    // never yields a partially-rendered table.
    for (entry in entries) {
        for ((name, flag) in CAPABILITY_COLUMNS) {
            if (!flag(entry.capabilities)) {
                require(!entry.gaps[name].isNullOrBlank()) {
                    "fabric '${entry.fabric}': capability '$name' is false but declares no " +
                        "non-blank gap URL in gaps"
                }
            }
        }
        if (entry.capabilities.meshDelivery) {
            require(!entry.meshEvidence.isNullOrBlank()) {
                "fabric '${entry.fabric}': meshDelivery = true must cite mesh evidence " +
                    "(a MeshConformanceSuite subclass name or a 2-peer vacuity note) in meshEvidence"
            }
        }
    }

    val header = "| Fabric | " + CAPABILITY_COLUMNS.joinToString(" | ") { it.first } + " | mesh evidence |"
    val divider = "|---".repeat(CAPABILITY_COLUMNS.size + 2) + "|"
    val rows = entries.map { entry ->
        val cells = CAPABILITY_COLUMNS.joinToString(" | ") { (name, flag) ->
            if (flag(entry.capabilities)) "✓" else "[–](${entry.gaps.getValue(name)})"
        }
        "| ${entry.fabric} | $cells | ${entry.meshEvidence ?: ""} |"
    }
    return (listOf(header, divider) + rows).joinToString("\n")
}
