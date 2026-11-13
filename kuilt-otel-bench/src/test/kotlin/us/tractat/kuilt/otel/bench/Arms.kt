package us.tractat.kuilt.otel.bench

import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.FileChannelDurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import java.io.File
import kotlin.io.path.createTempDirectory

/** The two stores the recording arm runs against: the cost floor, and a real fsync'd write. */
enum class BenchStore {
    IN_MEMORY,
    FILE,
}

/**
 * Runs [body] with a store factory of [kind]. A FILE store gets one fresh temporary directory per
 * call, deleted afterwards.
 */
suspend fun <T> withStore(kind: BenchStore, body: suspend (newStore: () -> DurableStore) -> T): T = when (kind) {
    BenchStore.IN_MEMORY -> body { InMemoryDurableStore() }
    BenchStore.FILE -> {
        val dir: File = createTempDirectory("otel-bench").toFile()
        try {
            body { FileChannelDurableStore(File(dir, "store")) }
        } finally {
            dir.deleteRecursively()
        }
    }
}

fun recordingTable(arm: String, rows: List<RecordingRow>) = report(
    arm,
    listOf(
        "op", "store", "series", "µs/op", "store µs/op", "encode µs", "rest µs",
        "encode/call (paired)", "KiB/write", "alloc KiB/op", "timed ops",
    ),
    rows.map { r ->
        val total = r.sample.nanosPerOp
        val rest = total - r.storeNanosPerOp - r.encodeNanos
        listOf(
            r.op.name, r.store.lowercase(), r.series.toString(), micros(total), micros(r.storeNanosPerOp),
            micros(r.encodeNanos), micros(rest), percent(r.encodeOverCall, 1.0),
            kib(r.bytesPerWrite.toDouble()), kib(r.sample.allocatedBytesPerOp), r.sample.executed.toString(),
        )
    },
)

fun bridgeTable(rows: List<BridgeRow>) = report(
    "bridge-export",
    listOf(
        "series", "points/export", "writes/export", "ms/export", "µs/point", "store ms/export",
        "alloc MiB/export", "timed exports",
    ),
    rows.map { r ->
        listOf(
            r.series.toString(), r.series.toString(), r.writesPerExport.toString(), millis(r.sample.nanosPerOp),
            micros(r.sample.nanosPerOp / r.series), millis(r.storeNanosPerExport),
            kib(r.sample.allocatedBytesPerOp / 1024.0), r.sample.executed.toString(),
        )
    },
)

fun drainTable(rows: List<DrainRow>) = report(
    "edge-drain",
    listOf(
        "wire", "series", "changed", "ms/drain", "edge store ms", "edge KiB read", "edge KiB written",
        "request KiB", "alloc MiB/drain", "timed drains",
    ),
    rows.map { r ->
        listOf(
            r.wire.name.lowercase(), r.series.toString(), r.changed.toString(), millis(r.sample.nanosPerOp),
            millis(r.edgeStoreNanosPerDrain), kib(r.edgeBytesReadPerDrain.toDouble()),
            kib(r.edgeBytesWrittenPerDrain.toDouble()), kib(r.requestBytesPerDrain.toDouble()),
            kib(r.sample.allocatedBytesPerOp / 1024.0), r.sample.executed.toString(),
        )
    },
)
