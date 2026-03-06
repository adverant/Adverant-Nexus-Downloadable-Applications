// ContentView.swift
// Main SwiftUI interface for the ProseCreator TTS companion app.
//
// Design: Warm, literary aesthetic for writers.
// Light cream backgrounds, warm terra cotta accents, soft sage/blue secondaries.
// Inspired by Claude's calm, approachable UI — suitable for novel writers.

import SwiftUI

// MARK: - Design Tokens

private enum Theme {
    // Backgrounds
    static let background = Color(red: 0.984, green: 0.973, blue: 0.957)   // #FBF8F4 warm cream
    static let cardSurface = Color.white
    static let cardSurfaceAlt = Color(red: 0.941, green: 0.922, blue: 0.894) // #F0EBE4 warm gray

    // Primary — Warm Terra Cotta
    static let primary = Color(red: 0.769, green: 0.439, blue: 0.294)       // #C4704B
    static let primaryContainer = Color(red: 1.0, green: 0.859, blue: 0.788) // #FFDBC9

    // Secondary — Warm Sage
    static let secondary = Color(red: 0.420, green: 0.498, blue: 0.420)     // #6B7F6B
    static let secondaryContainer = Color(red: 0.886, green: 0.941, blue: 0.878) // #E2F0E0

    // Tertiary — Dusty Blue
    static let tertiary = Color(red: 0.357, green: 0.478, blue: 0.541)      // #5B7A8A
    static let tertiaryContainer = Color(red: 0.835, green: 0.918, blue: 0.961) // #D5EAF5

    // Text
    static let textPrimary = Color(red: 0.176, green: 0.169, blue: 0.157)   // #2D2B28
    static let textSecondary = Color(red: 0.322, green: 0.306, blue: 0.282) // #524E48
    static let textMuted = Color(red: 0.541, green: 0.522, blue: 0.502)     // #8A8580

    // Status
    static let statusRunning = Color(red: 0.361, green: 0.620, blue: 0.420) // #5C9E6B
    static let statusStopped = Color(red: 0.722, green: 0.314, blue: 0.314) // #B85050

    // Borders
    static let border = Color(red: 0.835, green: 0.816, blue: 0.792)        // #D5D0CA

    static let cardCorner: CGFloat = 16
    static let cardPadding: CGFloat = 20
}

struct ContentView: View {
    @StateObject private var engine = TTSEngine()
    @StateObject private var downloader = ModelDownloader()
    @State private var server: TTSServer?
    @State private var errorMessage: String?
    @State private var showTestResult: Bool = false
    @State private var testResultText: String = ""
    @State private var isGeneratingTest: Bool = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    headerView
                    serverStatusCard
                    modelStatusCard
                    if engine.isLoaded {
                        serverControlCard
                    }
                    if server?.isRunning == true {
                        connectionInfoCard
                        statsCard
                        testCard
                    }
                    voiceListCard
                    footerView
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 24)
            }
            .background(Theme.background.ignoresSafeArea())
            .navigationBarTitleDisplayMode(.inline)
            .onAppear {
                initializeServer()
                checkModelAndAutoLoad()
            }
        }
    }

    // MARK: - Header

    private var headerView: some View {
        VStack(spacing: 8) {
            Image(systemName: "text.book.closed.fill")
                .font(.system(size: 36))
                .foregroundStyle(Theme.primary)

            Text("ProseCreator TTS")
                .font(.system(.title2, design: .serif))
                .fontWeight(.semibold)
                .foregroundStyle(Theme.textPrimary)

            Text("Your personal voice for every character")
                .font(.subheadline)
                .foregroundStyle(Theme.textSecondary)
        }
        .padding(.bottom, 8)
    }

    // MARK: - Server Status Card

    private var serverStatusCard: some View {
        VStack(spacing: 14) {
            HStack(spacing: 12) {
                Circle()
                    .fill(server?.isRunning == true ? Theme.statusRunning : Theme.statusStopped)
                    .frame(width: 10, height: 10)

                Text(server?.isRunning == true ? "Server Running" : "Server Stopped")
                    .font(.headline)
                    .foregroundStyle(Theme.textPrimary)

                Spacer()

                if server?.isRunning == true {
                    Image(systemName: "antenna.radiowaves.left.and.right")
                        .foregroundStyle(Theme.statusRunning)
                        .symbolEffect(.pulse, isActive: true)
                }
            }

            if let url = server?.serverURL, !url.isEmpty, server?.isRunning == true {
                Text(url)
                    .font(.system(.footnote, design: .monospaced))
                    .foregroundStyle(Theme.textSecondary)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Theme.cardSurfaceAlt)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .textSelection(.enabled)
            }

            if let errorMessage {
                HStack(spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                        .font(.caption)
                    Text(errorMessage)
                        .font(.caption)
                        .foregroundStyle(Theme.statusStopped)
                }
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Theme.statusStopped.opacity(0.08))
                .clipShape(RoundedRectangle(cornerRadius: 8))
            }
        }
        .modifier(CardModifier())
    }

    // MARK: - Model Status Card

    private var modelStatusCard: some View {
        VStack(spacing: 14) {
            HStack(spacing: 12) {
                Image(systemName: "waveform")
                    .font(.title3)
                    .foregroundStyle(Theme.primary)

                VStack(alignment: .leading, spacing: 2) {
                    Text("Kokoro v0.19")
                        .font(.headline)
                        .foregroundStyle(Theme.textPrimary)
                    Text(engine.statusMessage)
                        .font(.caption)
                        .foregroundStyle(Theme.textMuted)
                }

                Spacer()

                if engine.isLoaded {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(Theme.statusRunning)
                } else if downloader.isModelReady {
                    Image(systemName: "arrow.down.circle")
                        .foregroundStyle(Theme.tertiary)
                }
            }

            if !engine.isLoaded && !downloader.isModelReady {
                downloadSection
            } else if !engine.isLoaded && downloader.isModelReady {
                Button {
                    Task { await loadModel() }
                } label: {
                    Label("Load Model", systemImage: "play.circle.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.primary)
            } else {
                HStack(spacing: 12) {
                    StatBadge(label: "Sample Rate", value: "\(engine.sampleRate) Hz")
                    StatBadge(label: "Speakers", value: "\(engine.numSpeakers)")
                    StatBadge(label: "Engine", value: "sherpa-onnx")
                }
            }

            if engine.loadProgress > 0 && engine.loadProgress < 1.0 {
                ProgressView(value: engine.loadProgress)
                    .tint(Theme.primary)
            }
        }
        .modifier(CardModifier())
    }

    private var downloadSection: some View {
        VStack(spacing: 10) {
            switch downloader.state {
            case .idle, .failed:
                VStack(spacing: 10) {
                    Text("Download the voice engine to get started (~85 MB)")
                        .font(.subheadline)
                        .foregroundStyle(Theme.textSecondary)
                        .multilineTextAlignment(.center)

                    Button {
                        downloader.startDownload()
                    } label: {
                        Label("Download Model", systemImage: "arrow.down.circle.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Theme.primary)

                    if case .failed(let msg) = downloader.state {
                        Text(msg)
                            .font(.caption)
                            .foregroundStyle(Theme.statusStopped)
                            .multilineTextAlignment(.center)
                    }
                }

            case .downloading:
                VStack(spacing: 6) {
                    ProgressView(value: downloader.progress)
                        .tint(Theme.primary)
                    HStack {
                        Text(downloader.statusText)
                            .font(.caption)
                            .foregroundStyle(Theme.textMuted)
                        Spacer()
                        Text("\(Int(downloader.progress * 100))%")
                            .font(.system(.caption, design: .monospaced))
                            .foregroundStyle(Theme.textSecondary)
                    }
                    Button("Cancel") {
                        downloader.cancelDownload()
                    }
                    .font(.caption)
                    .tint(Theme.statusStopped)
                }

            case .extracting:
                VStack(spacing: 6) {
                    ProgressView()
                        .tint(Theme.primary)
                    Text("Preparing voice engine...")
                        .font(.caption)
                        .foregroundStyle(Theme.textMuted)
                }

            case .completed:
                Label("Download Complete", systemImage: "checkmark.circle.fill")
                    .foregroundStyle(Theme.statusRunning)
                    .onAppear {
                        Task { await loadModel() }
                    }
            }
        }
    }

    // MARK: - Server Control Card

    private var serverControlCard: some View {
        VStack(spacing: 14) {
            HStack(spacing: 12) {
                Image(systemName: "network")
                    .font(.title3)
                    .foregroundStyle(Theme.tertiary)

                VStack(alignment: .leading, spacing: 2) {
                    Text("TTS Server")
                        .font(.headline)
                        .foregroundStyle(Theme.textPrimary)
                    Text("Port \(server?.port ?? 8881)")
                        .font(.caption)
                        .foregroundStyle(Theme.textMuted)
                }

                Spacer()
            }

            if server?.isRunning == true {
                Button {
                    stopServer()
                } label: {
                    Label("Stop Server", systemImage: "stop.circle.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.statusStopped)
            } else {
                Button {
                    startServer()
                } label: {
                    Label("Start Server", systemImage: "play.circle.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.statusRunning)
            }
        }
        .modifier(CardModifier())
    }

    // MARK: - Connection Info Card

    private var connectionInfoCard: some View {
        VStack(spacing: 14) {
            HStack(spacing: 12) {
                Image(systemName: "wifi")
                    .font(.title3)
                    .foregroundStyle(Theme.tertiary)

                Text("Connection")
                    .font(.headline)
                    .foregroundStyle(Theme.textPrimary)

                Spacer()
            }

            VStack(alignment: .leading, spacing: 8) {
                if let ip = NetworkUtils.getWiFiIPAddress() {
                    ConnectionRow(label: "Wi-Fi IP", value: ip)
                }
                ConnectionRow(label: "Port", value: "\(server?.port ?? 8881)")
                ConnectionRow(label: "Health", value: "\(server?.serverURL ?? "")/health")

                Divider()
                    .overlay(Theme.border)

                Text("ProseCreator will find this device automatically. Or set the TTS server URL to:")
                    .font(.caption)
                    .foregroundStyle(Theme.textMuted)

                Text(server?.serverURL ?? "")
                    .font(.system(.footnote, design: .monospaced))
                    .foregroundStyle(Theme.textPrimary)
                    .textSelection(.enabled)
                    .padding(10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Theme.cardSurfaceAlt)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
            }

            let allAddresses = NetworkUtils.getAllIPAddresses()
            if allAddresses.count > 1 {
                DisclosureGroup("All Network Interfaces") {
                    ForEach(allAddresses, id: \.address) { addr in
                        ConnectionRow(label: addr.interface, value: addr.address)
                    }
                }
                .font(.caption)
                .foregroundStyle(Theme.textSecondary)
            }
        }
        .modifier(CardModifier())
    }

    // MARK: - Stats Card

    private var statsCard: some View {
        VStack(spacing: 14) {
            HStack(spacing: 12) {
                Image(systemName: "chart.bar.fill")
                    .font(.title3)
                    .foregroundStyle(Theme.secondary)

                Text("Session")
                    .font(.headline)
                    .foregroundStyle(Theme.textPrimary)

                Spacer()
            }

            HStack(spacing: 12) {
                StatBadge(label: "Requests", value: "\(server?.requestCount ?? 0)")
                StatBadge(
                    label: "Last Request",
                    value: server?.lastRequestTime.map { formatTime($0) } ?? "Never"
                )
            }
        }
        .modifier(CardModifier())
    }

    // MARK: - Test Card

    private var testCard: some View {
        VStack(spacing: 14) {
            HStack(spacing: 12) {
                Image(systemName: "speaker.wave.2.fill")
                    .font(.title3)
                    .foregroundStyle(Theme.primary)

                Text("Test Voice")
                    .font(.headline)
                    .foregroundStyle(Theme.textPrimary)

                Spacer()
            }

            Button {
                Task { await runTestGeneration() }
            } label: {
                if isGeneratingTest {
                    ProgressView()
                        .tint(.white)
                        .frame(maxWidth: .infinity)
                } else {
                    Label("Generate Test Audio", systemImage: "waveform")
                        .frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.borderedProminent)
            .tint(Theme.primary)
            .disabled(isGeneratingTest)

            if showTestResult {
                Text(testResultText)
                    .font(.caption)
                    .foregroundStyle(testResultText.hasPrefix("OK") ? Theme.statusRunning : Theme.statusStopped)
                    .multilineTextAlignment(.center)
            }
        }
        .modifier(CardModifier())
    }

    // MARK: - Voice List Card

    private var voiceListCard: some View {
        VStack(spacing: 14) {
            HStack(spacing: 12) {
                Image(systemName: "person.2.fill")
                    .font(.title3)
                    .foregroundStyle(Theme.secondary)

                Text("Voices (\(kokoroVoices.count))")
                    .font(.headline)
                    .foregroundStyle(Theme.textPrimary)

                Spacer()
            }

            LazyVGrid(columns: [
                GridItem(.flexible()),
                GridItem(.flexible()),
            ], spacing: 8) {
                ForEach(kokoroVoices) { voice in
                    HStack(spacing: 6) {
                        Circle()
                            .fill(voice.gender == "female"
                                  ? Theme.primary.opacity(0.6)
                                  : Theme.tertiary.opacity(0.6))
                            .frame(width: 6, height: 6)

                        VStack(alignment: .leading, spacing: 1) {
                            Text(voice.name)
                                .font(.caption)
                                .fontWeight(.medium)
                                .foregroundStyle(Theme.textPrimary)
                            Text(voice.id)
                                .font(.caption2)
                                .foregroundStyle(Theme.textMuted)
                        }

                        Spacer()
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
                    .background(Theme.cardSurfaceAlt)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                }
            }
        }
        .modifier(CardModifier())
    }

    // MARK: - Footer

    private var footerView: some View {
        VStack(spacing: 4) {
            Text("ProseCreator TTS v1.0")
                .font(.caption)
                .foregroundStyle(Theme.textMuted)
            Text("On-device Kokoro voice engine · Adverant AI")
                .font(.caption2)
                .foregroundStyle(Theme.textMuted.opacity(0.7))
        }
        .padding(.top, 8)
    }

    // MARK: - Actions

    private func initializeServer() {
        server = TTSServer(engine: engine)
    }

    private func checkModelAndAutoLoad() {
        if downloader.isModelReady && !engine.isLoaded {
            Task { await loadModel() }
        }
    }

    private func loadModel() async {
        errorMessage = nil
        do {
            try await engine.loadModel()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func startServer() {
        errorMessage = nil
        do {
            try server?.start()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func stopServer() {
        server?.stop()
    }

    private func runTestGeneration() async {
        isGeneratingTest = true
        showTestResult = false

        do {
            let startTime = CFAbsoluteTimeGetCurrent()
            let testText = "The night was dark and full of whispers. She turned the page carefully, each word pulling her deeper into the story."
            let wavData = try await engine.generateSpeech(text: testText, voice: "af_sky", speed: 1.0)
            let elapsed = CFAbsoluteTimeGetCurrent() - startTime

            let numSamples = (wavData.count - 44) / 2
            let duration = Double(numSamples) / Double(engine.sampleRate)

            testResultText = String(format: "OK: %.1fs audio generated in %.2fs (%.1fx realtime), %d bytes",
                                    duration, elapsed, duration / elapsed, wavData.count)
            showTestResult = true
        } catch {
            testResultText = "Error: \(error.localizedDescription)"
            showTestResult = true
        }

        isGeneratingTest = false
    }

    private func formatTime(_ date: Date) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: date, relativeTo: Date())
    }
}

// MARK: - Card Modifier

private struct CardModifier: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(Theme.cardPadding)
            .background(Theme.cardSurface)
            .clipShape(RoundedRectangle(cornerRadius: Theme.cardCorner))
            .overlay(
                RoundedRectangle(cornerRadius: Theme.cardCorner)
                    .stroke(Theme.border, lineWidth: 0.5)
            )
    }
}

// MARK: - Supporting Views

struct StatBadge: View {
    let label: String
    let value: String

    var body: some View {
        VStack(spacing: 4) {
            Text(value)
                .font(.system(.body, design: .monospaced))
                .fontWeight(.medium)
                .foregroundStyle(Theme.textPrimary)
            Text(label)
                .font(.caption2)
                .foregroundStyle(Theme.textMuted)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .background(Theme.cardSurfaceAlt)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

struct ConnectionRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label)
                .font(.caption)
                .foregroundStyle(Theme.textMuted)
                .frame(width: 70, alignment: .leading)
            Text(value)
                .font(.system(.caption, design: .monospaced))
                .foregroundStyle(Theme.textSecondary)
                .textSelection(.enabled)
        }
    }
}

#Preview {
    ContentView()
}
