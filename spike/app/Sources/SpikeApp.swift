import SwiftUI
import SpikeKit

@main
struct SpikeApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

final class Model: ObservableObject {
    @Published var log = "idle"
    private let nw = SpikeNw()

    init() {
        nw.setOnLog { [weak self] line in
            DispatchQueue.main.async {
                guard let self else { return }
                self.log = line + "\n" + self.log
            }
        }
    }

    func start(role: String, runId: String) { nw.start(role: role, runId: runId) }
}

struct ContentView: View {
    @StateObject private var model = Model()

    var body: some View {
        VStack(spacing: 16) {
            Text("kuilt-nw spike").font(.title2).bold()
            Text("Network.framework P2P · TLS-PSK")
                .font(.caption).foregroundStyle(.secondary)
            HStack(spacing: 16) {
                Button("Host") { model.start(role: "host", runId: "manual") }
                    .buttonStyle(.borderedProminent)
                Button("Join") { model.start(role: "join", runId: "manual") }
                    .buttonStyle(.borderedProminent)
            }
            Divider()
            ScrollView {
                Text(model.log)
                    .font(.system(.footnote, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
            }
        }
        .padding()
        .onAppear {
            // Auto-pick role + run-id from launch arguments so the harness can drive
            // headlessly AND validate that THIS launch actually started.
            let args = ProcessInfo.processInfo.arguments
            let runId = args.first { $0.hasPrefix("run=") }.map { String($0.dropFirst(4)) } ?? "none"
            if args.contains("host") { model.start(role: "host", runId: runId) }
            else if args.contains("join") { model.start(role: "join", runId: runId) }
        }
    }
}
