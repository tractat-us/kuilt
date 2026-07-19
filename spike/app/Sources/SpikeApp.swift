import SwiftUI
import SpikeKit

@main
struct SpikeApp: App {
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

    private let suite = ConnectivitySuite()

    func start(role: String) {
        guard !running else { return }
        self.role = role
        self.running = true
        self.rows = []
        self.report = ""
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
            onComplete: { [weak self] text in
                // Emit the full report to stdout, fenced, so the harness can extract it verbatim.
                print("[suite] ===REPORT-BEGIN===")
                print(text)
                print("[suite] ===REPORT-END===")
                DispatchQueue.main.async {
                    guard let self else { return }
                    self.report = text
                    self.running = false
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
            if model.running {
                HStack(spacing: 8) { ProgressView(); Text("running \(model.role)…").font(.caption) }
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
            let args = ProcessInfo.processInfo.arguments
            if args.contains("host") { model.start(role: "host") }
            else if args.contains("join") { model.start(role: "join") }
        }
    }

    private func fmt(_ ms: Int64) -> String {
        ms >= 1000 ? String(format: "%.1fs", Double(ms) / 1000.0) : "\(ms)ms"
    }
}
