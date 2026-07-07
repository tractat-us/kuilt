@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package us.tractat.kuilt.demo.web

import kotlin.JsFun

// ── Browser DOM bindings ───────────────────────────────────────────────────────
// The page structure and styling live in resources/index.html; these @JsFun
// wrappers are the only DOM surface the Kotlin side touches (same interop
// pattern as :kuilt-webrtc's wasmJs WebSocket bindings — in Kotlin/Wasm we
// declare the externals ourselves). Everything with behaviour stays in Kotlin,
// on :demo-shared's tested PatchworkSession; this file is deliberately thin,
// untested glue.

/** The named query parameter, or `""` when absent. */
@JsFun("(name) => new URLSearchParams(window.location.search).get(name) ?? ''")
internal external fun queryParam(name: String): String

/** `ws://<page host>:9190/patchwork` — the relay's default address, LAN-friendly. */
@JsFun("() => 'ws://' + (window.location.hostname || 'localhost') + ':9190/patchwork'")
internal external fun defaultRelayUrl(): String

/** Builds the `w`×`h` quilt grid inside `#quilt`, one `.cell` div per cell. */
@JsFun(
    """(w, h) => {
        const grid = document.getElementById('quilt');
        grid.style.gridTemplateColumns = 'repeat(' + w + ', 1fr)';
        for (let y = 0; y < h; y++) {
            for (let x = 0; x < w; x++) {
                const cell = document.createElement('div');
                cell.className = 'cell';
                cell.id = 'cell-' + x + '-' + y;
                cell.dataset.x = x;
                cell.dataset.y = y;
                grid.appendChild(cell);
            }
        }
    }""",
)
internal external fun buildGrid(w: Int, h: Int)

/** One delegated click listener on the grid; `handler(x, y)` per stitched cell. */
@JsFun(
    """(handler) => {
        document.getElementById('quilt').addEventListener('click', (e) => {
            const cell = e.target.closest('.cell');
            if (cell) handler(Number(cell.dataset.x), Number(cell.dataset.y));
        });
    }""",
)
internal external fun onCellClick(handler: (Int, Int) -> Unit)

/** Paints a cell and replays its stitch-in flash so merges visibly animate. */
@JsFun(
    """(x, y, hex) => {
        const cell = document.getElementById('cell-' + x + '-' + y);
        if (!cell) return;
        cell.style.background = hex;
        cell.classList.add('stitched');
        cell.classList.remove('flash');
        void cell.offsetWidth; // restart the CSS animation
        cell.classList.add('flash');
    }""",
)
internal external fun paintCell(x: Int, y: Int, hex: String)

/** Appends a colour swatch button to `#palette`; `handler` fires on click. */
@JsFun(
    """(hex, handler) => {
        const swatch = document.createElement('button');
        swatch.className = 'swatch';
        swatch.style.background = hex;
        swatch.dataset.hex = hex;
        swatch.title = hex;
        swatch.addEventListener('click', () => handler());
        document.getElementById('palette').appendChild(swatch);
    }""",
)
internal external fun addSwatch(hex: String, handler: () -> Unit)

/** Marks exactly the swatch for `hex` as the selected colour. */
@JsFun(
    """(hex) => {
        document.querySelectorAll('.swatch').forEach((swatch) => {
            swatch.classList.toggle('selected', swatch.dataset.hex === hex);
        });
    }""",
)
internal external fun markSelectedSwatch(hex: String)

/** Sets the status line's text and CSS class (`connecting`/`online`/`tunnel`/`error`). */
@JsFun(
    """(text, cssClass) => {
        const status = document.getElementById('status');
        status.textContent = text;
        status.className = cssClass;
    }""",
)
internal external fun setStatus(text: String, cssClass: String)

/** Relabels the tunnel toggle button. */
@JsFun("(label) => { document.getElementById('tunnel').textContent = label; }")
internal external fun setTunnelButton(label: String)

/** Wires the tunnel toggle button's click. */
@JsFun("(handler) => { document.getElementById('tunnel').addEventListener('click', () => handler()); }")
internal external fun onTunnelClick(handler: () -> Unit)

/** Updates the patch tally. */
@JsFun(
    """(count) => {
        document.getElementById('tally').textContent =
            count === 0 ? '' : count + (count === 1 ? ' patch' : ' patches');
    }""",
)
internal external fun setPatchTally(count: Int)
