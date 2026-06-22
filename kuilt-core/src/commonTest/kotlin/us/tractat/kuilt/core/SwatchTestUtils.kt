package us.tractat.kuilt.core

/**
 * Returns true if [swatch] is backed by a larger array than its logical payload,
 * i.e. it is an offset view rather than a freshly-copied minimal array.
 *
 * Used in tests to assert that header stripping in [MuxSeam] and [NamedMux]
 * creates a view, not a copy.
 */
internal fun swatchIsOffsetView(swatch: Swatch): Boolean = swatch.data.size > swatch.length
