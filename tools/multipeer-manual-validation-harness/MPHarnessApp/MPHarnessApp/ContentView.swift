import SwiftUI
import Harness

enum TapMode: String, CaseIterable, Identifiable {
    case log = "Log tap"
    case metric = "Metric tap"
    var id: String { rawValue }
}

struct ContentView: View {
    @State private var mode: TapMode = .log
    @State private var status: String = "Idle"
    @State private var code: String = ""
    @State private var selfId: String = ""
    private let controller = TapHostController()

    var body: some View {
        VStack(spacing: 20) {
            Text("kuilt Multipeer validation harness")
                .font(.headline)

            Picker("Mode", selection: $mode) {
                ForEach(TapMode.allCases) { m in
                    Text(m.rawValue).tag(m)
                }
            }
            .pickerStyle(.segmented)

            HStack {
                Button("Start hosting") {
                    status = "Starting \(mode.rawValue)..."
                    code = ""
                    selfId = ""
                    switch mode {
                    case .log:
                        controller.startLogTap(displayName: UIDevice.current.name) { c, id in
                            DispatchQueue.main.async {
                                code = c
                                selfId = id
                                status = "Hosting log tap"
                            }
                        } onError: { message in
                            DispatchQueue.main.async { status = "ERROR: \(message)" }
                        }
                    case .metric:
                        controller.startMetricTap(displayName: UIDevice.current.name) { c, id in
                            DispatchQueue.main.async {
                                code = c
                                selfId = id
                                status = "Hosting metric tap"
                            }
                        } onError: { message in
                            DispatchQueue.main.async { status = "ERROR: \(message)" }
                        }
                    }
                }
                .buttonStyle(.borderedProminent)

                Button("Stop") {
                    controller.stop()
                    status = "Stopped"
                    code = ""
                    selfId = ""
                }
                .buttonStyle(.bordered)
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Status: \(status)")
                if !code.isEmpty {
                    Text("Join code:").font(.caption)
                    Text(code)
                        .font(.system(.largeTitle, design: .monospaced))
                        .textSelection(.enabled)
                }
                if !selfId.isEmpty {
                    Text("Peer id: \(selfId)").font(.caption).foregroundStyle(.secondary)
                }
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.gray.opacity(0.1))
            .cornerRadius(12)

            Spacer()
        }
        .padding()
    }
}
