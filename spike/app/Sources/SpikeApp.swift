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

    func host() { nw.startHost() }
    func join() { nw.startJoin() }
}

struct ContentView: View {
    @StateObject private var model = Model()

    var body: some View {
        VStack(spacing: 16) {
            Text("kuilt-nw spike").font(.title2).bold()
            Text("Network.framework P2P · TLS-PSK")
                .font(.caption).foregroundStyle(.secondary)
            HStack(spacing: 16) {
                Button("Host") { model.host() }
                    .buttonStyle(.borderedProminent)
                Button("Join") { model.join() }
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
            // Auto-pick role from a launch argument so the whole test can be
            // driven headlessly via `devicectl process launch … host|join`.
            let args = ProcessInfo.processInfo.arguments
            if args.contains("host") { model.host() }
            else if args.contains("join") { model.join() }
        }
    }
}
