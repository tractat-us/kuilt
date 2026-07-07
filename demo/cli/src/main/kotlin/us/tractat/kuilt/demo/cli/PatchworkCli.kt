package us.tractat.kuilt.demo.cli

import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.demo.Cell
import us.tractat.kuilt.demo.Colour
import us.tractat.kuilt.demo.PatchworkSession

/**
 * The Patchwork terminal commands — a deliberately thin, testable wrapper over
 * [PatchworkSession]. Each [execute] call parses one command line, drives the
 * session, and returns the text to print; all interactive I/O stays in `main`.
 *
 * Commands:
 * - `stitch <x> <y> <colour>` — set a cell (`#rrggbb` or a palette name).
 * - `tunnel` — go offline; stitches keep landing on the local board.
 * - `reconnect` — come back online; offline stitches merge into every peer.
 * - `show` — render the merged quilt.
 * - `status` — connection state and patch count.
 * - `help` — this list.
 */
class PatchworkCli(
    private val session: PatchworkSession,
    private val relayTag: Tag,
) {
    suspend fun execute(line: String): String {
        val words = line.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return ""
        return when (words.first().lowercase()) {
            "stitch" -> stitch(words.drop(1))
            "tunnel" -> tunnel()
            "reconnect" -> reconnect()
            "show" -> renderQuilt(session.quilt.value)
            "status" -> status()
            "help" -> HELP
            else -> "unknown command '${words.first()}' — try 'help'"
        }
    }

    private fun stitch(args: List<String>): String {
        if (args.size != 3) return STITCH_USAGE
        val x = args[0].toIntOrNull() ?: return STITCH_USAGE
        val y = args[1].toIntOrNull() ?: return STITCH_USAGE
        val colour = parseColour(args[2]) ?: return STITCH_USAGE
        if (x < 0 || y < 0) return STITCH_USAGE
        session.stitch(Cell(x, y), colour)
        val where = if (session.connected.value) "broadcast to the quilt" else "kept local until you reconnect"
        return "stitched ($x, $y) ${colour.hex} — $where"
    }

    private suspend fun tunnel(): String {
        if (!session.connected.value) return "already offline"
        session.disconnect()
        return "entered the tunnel — offline; stitches stay local until 'reconnect'"
    }

    private suspend fun reconnect(): String {
        if (session.connected.value) return "already online"
        session.join(relayTag)
        return "back online — offline patches are merging into the shared quilt"
    }

    private fun status(): String {
        val state = if (session.connected.value) "online" else "offline (tunnel mode)"
        return "$state — ${session.quilt.value.size} patches"
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        private val STITCH_USAGE =
            "usage: stitch <x> <y> <colour>   (x, y >= 0; colour = #rrggbb or one of $PALETTE_NAMES)"

        val HELP: String = """
            |stitch <x> <y> <colour>  set a cell ('#rrggbb' or: $PALETTE_NAMES)
            |tunnel                   go offline — stitches keep landing locally
            |reconnect                come back online — offline stitches merge in
            |show                     render the merged quilt
            |status                   connection state and patch count
            |quit                     leave
        """.trimMargin()
    }
}

/** Small named palette so demo commands read naturally. */
internal val PALETTE: Map<String, Colour> = mapOf(
    "red" to Colour("#e94f37"),
    "green" to Colour("#57a773"),
    "blue" to Colour("#4062bb"),
    "gold" to Colour("#f2c14e"),
    "purple" to Colour("#7768ae"),
    "teal" to Colour("#17bebb"),
    "white" to Colour("#f5f5f5"),
    "black" to Colour("#222222"),
)

private val PALETTE_NAMES = PALETTE.keys.joinToString(", ")

private val HEX_COLOUR = Regex("#[0-9a-fA-F]{6}")

/** `#rrggbb` (case-insensitive) or a [PALETTE] name; null when neither. */
internal fun parseColour(word: String): Colour? = when {
    HEX_COLOUR.matches(word) -> Colour(word.lowercase())
    else -> PALETTE[word.lowercase()]
}

/**
 * Renders the quilt as an ANSI-coloured grid over the stitched cells' bounding
 * box — two terminal columns per cell (background-coloured for a patch, a
 * middle dot for an empty cell), plus a patch tally.
 */
fun renderQuilt(quilt: Map<Cell, Colour>): String {
    if (quilt.isEmpty()) return "(the quilt is empty — 'stitch <x> <y> <colour>' adds the first patch)"
    val maxX = quilt.keys.maxOf { it.x }
    val maxY = quilt.keys.maxOf { it.y }
    return buildString {
        for (y in 0..maxY) {
            for (x in 0..maxX) {
                append(quilt[Cell(x, y)]?.let(::ansiPatch) ?: "· ")
            }
            append('\n')
        }
        append("${quilt.size} ${if (quilt.size == 1) "patch" else "patches"}")
    }
}

/** Two spaces on a 24-bit ANSI background; the raw hex when it is malformed. */
private fun ansiPatch(colour: Colour): String {
    val hex = colour.hex
    if (!HEX_COLOUR.matches(hex)) return "??"
    val r = hex.substring(1, 3).toInt(16)
    val g = hex.substring(3, 5).toInt(16)
    val b = hex.substring(5, 7).toInt(16)
    return "\u001B[48;2;$r;$g;${b}m  \u001B[0m"
}
