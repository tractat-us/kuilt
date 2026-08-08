import SwiftUI
import SpikeKit

@main
struct SpikeApp: App {
    init() {
        // Auto-lock suspends the app, which freezes its coroutines and stops all console output —
        // and a suspended app is still "alive" in the process list, with no crash and no error. On
        // a long unattended run that is indistinguishable from the wedge #1860 is about: during
        // this probe's own bisect, a screen lock truncated a run at n≈7,000 and read exactly like
        // the field failure it was hunting. Holding the idle timer off removes a failure mode that
        // fakes the bug under investigation.
        UIApplication.shared.isIdleTimerDisabled = true
    }

    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

/// One row in the pass/fail matrix, mirrored from the Kotlin `ScenarioResult`.
struct Row: Identifiable {
    let id: Int
    let name: String
    let glyph: String   // PASS / FAIL / SKIP
    let elapsedMs: Int64
    let detail: String

    var color: Color {
        switch glyph {
        case "PASS": return .green
        case "FAIL": return .red
        default: return .secondary
        }
    }
}

final class Model: ObservableObject {
    @Published var rows: [Row] = []
    @Published var log = "idle"
    @Published var report = ""
    @Published var running = false
    @Published var role = ""
    /// The current instruction for the human holding this phone (scenario 6 only). Empty = nothing to do.
    @Published var prompt = ""

    private let suite = ConnectivitySuite()
    private let otelProbe = OtelStallProbe()

    /// #1860 measurement. Unlike the connectivity scenarios this needs no second phone and no
    /// human — it times `Rga.insertAfter` against the whole exporter write turn and prints the
    /// curve. Every line goes to stdout so `devicectl --console` is the whole retrieval story;
    /// the on-screen log is a convenience for a run started by tapping.
    func startOtelProbe(recover: Bool = false) {
        guard !running else { return }
        self.role = recover ? "otel-probe-recover" : "otel-probe"
        self.running = true
        self.rows = []
        self.report = ""
        self.log = "starting otel probe (\(self.role))…"
        let sink: (String) -> Void = { [weak self] line in
            print("[probe] " + line)
            DispatchQueue.main.async {
                guard let self else { return }
                self.log = line + "\n" + self.log
                if line.hasPrefix("===PROBE-END===") { self.running = false }
            }
        }
        if recover { otelProbe.startRecover(onLine: sink) } else { otelProbe.start(onLine: sink) }
    }

    func start(role: String) {
        guard !running else { return }
        self.role = role
        self.running = true
        self.rows = []
        self.report = ""
        self.prompt = ""
        self.log = "starting \(role)…"
        suite.start(
            role: role,
            onLog: { [weak self] line in
                print("[suite] " + line) // stdout for `devicectl --console` (Mac-tethered harness)
                DispatchQueue.main.async {
                    guard let self else { return }
                    self.log = line + "\n" + self.log
                }
            },
            onScenario: { [weak self] r in
                DispatchQueue.main.async {
                    guard let self else { return }
                    self.rows.append(Row(id: Int(r.id), name: r.name, glyph: r.glyph, elapsedMs: r.elapsedMs, detail: r.detail))
                }
            },
            onPrompt: { [weak self] text in
                print("[suite] SAY | " + text)
                DispatchQueue.main.async {
                    guard let self else { return }
                    self.prompt = text
                }
            },
            onComplete: { [weak self] text in
                // Emit the full report to stdout, fenced, so the harness can extract it verbatim.
                print("[suite] ===REPORT-BEGIN===")
                print(text)
                print("[suite] ===REPORT-END===")
                DispatchQueue.main.async {
                    guard let self else { return }
                    self.report = text
                    self.running = false
                    self.prompt = ""
                }
            }
        )
    }
}

struct ContentView: View {
    @StateObject private var model = Model()

    var body: some View {
        VStack(spacing: 12) {
            Text("kuilt-nw connectivity suite").font(.headline)
            Text("two phones · one Host · one Join · texts the report back")
                .font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center)

            HStack(spacing: 16) {
                Button("Host") { model.start(role: "host") }
                    .buttonStyle(.borderedProminent).disabled(model.running)
                Button("Join") { model.start(role: "join") }
                    .buttonStyle(.borderedProminent).disabled(model.running)
            }
            // #1467 diagnostic: run ONLY scenario 4, with no earlier scenario having left a
            // listener/browser alive — isolates accumulated state from the service type itself.
            HStack(spacing: 16) {
                Button("Host · S4 only") { model.start(role: "host-s4") }
                    .buttonStyle(.bordered).disabled(model.running)
                Button("Join · S4 only") { model.start(role: "join-s4") }
                    .buttonStyle(.bordered).disabled(model.running)
            }
            // #1712 gate: run ONLY scenario 6 — the operator-driven airplane-mode outage. Unlike the
            // battery, the button here PICKS THE ROLE: Join is the phone that goes offline, Host is the
            // phone that stays up. Whoever taps Join does the toggling.
            HStack(spacing: 16) {
                Button("Host · S6 stay up") { model.start(role: "host-s6") }
                    .buttonStyle(.bordered).disabled(model.running)
                Button("Join · S6 go offline") { model.start(role: "join-s6") }
                    .buttonStyle(.bordered).disabled(model.running)
            }
            // #1637 repro: run ONLY scenario 7 — one airplane-mode blip held 10-30s, the band where a
            // joiner's link dies but the host never notices. Same role convention as S6: Join is the
            // phone that goes offline. EXPECTED TO FAIL until #1637 is fixed — that FAIL is the point.
            HStack(spacing: 16) {
                Button("Host · S7 stay up") { model.start(role: "host-s7") }
                    .buttonStyle(.bordered).disabled(model.running)
                Button("Join · S7 go offline") { model.start(role: "join-s7") }
                    .buttonStyle(.bordered).disabled(model.running)
            }
            // #1860 measurement — one phone, no partner, no human at a toggle.
            HStack(spacing: 16) {
                Button("otel probe · grow") { model.startOtelProbe() }
                    .buttonStyle(.bordered).disabled(model.running)
                Button("otel probe · recover") { model.startOtelProbe(recover: true) }
                    .buttonStyle(.bordered).disabled(model.running)
            }
            if model.running {
                HStack(spacing: 8) { ProgressView(); Text("running \(model.role)…").font(.caption) }
            }

            // The operator banner. Deliberately the loudest thing on screen while it is non-empty: the
            // person is holding two phones in a lift, and a missed instruction is a wasted run.
            if !model.prompt.isEmpty {
                Text(model.prompt)
                    .font(.body.bold())
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(12)
                    .background(Color.orange.opacity(0.25))
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color.orange, lineWidth: 2))
                    .accessibilityAddTraits(.isHeader)
            }

            // Pass/fail matrix
            VStack(spacing: 4) {
                ForEach(model.rows) { r in
                    HStack(alignment: .top, spacing: 8) {
                        Text(r.glyph).font(.caption.bold().monospaced())
                            .foregroundStyle(r.color).frame(width: 44, alignment: .leading)
                        VStack(alignment: .leading, spacing: 1) {
                            Text("\(r.id). \(r.name)").font(.footnote.bold())
                            Text(r.detail).font(.caption2).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text(fmt(r.elapsedMs)).font(.caption2.monospaced()).foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 2)
                    Divider()
                }
            }

            // Shareable report
            if !model.report.isEmpty {
                HStack(spacing: 12) {
                    ShareLink(item: model.report) { Label("Share report", systemImage: "square.and.arrow.up") }
                    Button {
                        UIPasteboard.general.string = model.report
                    } label: { Label("Copy", systemImage: "doc.on.doc") }
                }
                .font(.footnote)
            }

            Divider()
            ScrollView {
                Text(model.log)
                    .font(.system(.caption2, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
            }
        }
        .padding()
        .onAppear {
            // Harness drive: launch args `host`/`join` auto-start the battery headlessly.
            // The `-s4` variants run scenario 4 alone (#1467 diagnostic), `-s6` runs the
            // operator-driven local-fabric gate (#1712) and `-s7` the sub-timeout-blip repro
            // (#1637); check the suffixed forms FIRST, since `contains` is exact-match and
            // "host-s4" must not fall through to the full battery. `-s6`/`-s7` still need a human
            // at the Airplane Mode toggle — the launch arg only starts them.
            let args = ProcessInfo.processInfo.arguments
            // `otel-probe-recover` must be tested BEFORE `otel-probe`: `contains` is exact-match
            // per element, but the recover launch passes both-looking args nowhere near each
            // other only by convention, and ordering the specific case first is the same
            // discipline the `-s4`/`-s6` variants above already needed.
            if args.contains("otel-probe-recover") { model.startOtelProbe(recover: true) }
            else if args.contains("otel-probe") { model.startOtelProbe() }
            else if args.contains("host-s4") { model.start(role: "host-s4") }
            else if args.contains("join-s4") { model.start(role: "join-s4") }
            else if args.contains("host-s6") { model.start(role: "host-s6") }
            else if args.contains("join-s6") { model.start(role: "join-s6") }
            else if args.contains("host-s7") { model.start(role: "host-s7") }
            else if args.contains("join-s7") { model.start(role: "join-s7") }
            else if args.contains("host") { model.start(role: "host") }
            else if args.contains("join") { model.start(role: "join") }
        }
    }

    private func fmt(_ ms: Int64) -> String {
        ms >= 1000 ? String(format: "%.1fs", Double(ms) / 1000.0) : "\(ms)ms"
    }
}
