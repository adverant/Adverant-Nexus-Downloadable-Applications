// TTSServer.swift
// Manages the HTTP server that serves the OpenAI-compatible TTS API.
// Routes:
//   GET  /health              — Health check
//   GET  /v1/models           — List loaded models
//   GET  /v1/audio/voices     — List available Kokoro voices
//   POST /v1/audio/speech     — Generate speech (returns WAV audio)
//
// The API is designed to be compatible with the ProseCreator web app's
// TTSProxyService, which expects an OpenAI-like TTS endpoint.

import Foundation

/// Server state observable by the UI.
@MainActor
final class TTSServer: ObservableObject {
    /// Whether the server is currently running.
    @Published var isRunning: Bool = false
    /// The URL clients should connect to.
    @Published var serverURL: String = ""
    /// Status message for the UI.
    @Published var statusMessage: String = "Stopped"
    /// Number of requests served since server start.
    @Published var requestCount: Int = 0
    /// Last request timestamp.
    @Published var lastRequestTime: Date?

    /// The port the server listens on.
    let port: UInt16 = 8881

    private var httpServer: HTTPServer?
    private let engine: TTSEngine

    /// Total requests served (thread-safe via actor isolation on update).
    private var totalRequests: Int = 0

    init(engine: TTSEngine) {
        self.engine = engine
    }

    /// Start the TTS HTTP server.
    func start() throws {
        guard !isRunning else { return }
        guard engine.isLoaded else {
            statusMessage = "Model not loaded"
            throw TTSEngineError.modelNotLoaded
        }

        let server = HTTPServer()
        registerRoutes(on: server)

        try server.start(port: port)

        self.httpServer = server
        self.isRunning = true
        self.serverURL = NetworkUtils.serverURL(port: port)
        self.statusMessage = "Running on \(serverURL)"
        self.requestCount = 0
        self.totalRequests = 0
    }

    /// Stop the TTS HTTP server.
    func stop() {
        httpServer?.stop()
        httpServer = nil
        isRunning = false
        serverURL = ""
        statusMessage = "Stopped"
    }

    // MARK: - Route Registration

    private func registerRoutes(on server: HTTPServer) {
        // Capture self weakly to avoid retain cycles
        let engine = self.engine

        // GET /health
        server.get("/health") { [weak self] _ in
            await self?.incrementRequestCount()
            let isReady = await engine.isLoaded
            let sampleRate = await engine.sampleRate
            return HTTPResponse.json([
                "status": isReady ? "ok" : "loading",
                "model": "kokoro-en-v0_19",
                "sample_rate": sampleRate,
                "device": "ios",
                "engine": "sherpa-onnx",
            ])
        }

        // GET /v1/models
        server.get("/v1/models") { [weak self] _ in
            await self?.incrementRequestCount()
            let isReady = await engine.isLoaded
            let numSpeakers = await engine.numSpeakers
            return HTTPResponse.json([
                "object": "list",
                "data": [
                    [
                        "id": "kokoro-en-v0_19",
                        "object": "model",
                        "created": Int(Date().timeIntervalSince1970),
                        "owned_by": "k2-fsa",
                        "ready": isReady,
                        "num_speakers": numSpeakers,
                    ] as [String: Any]
                ] as [[String: Any]],
            ] as [String: Any])
        }

        // GET /v1/audio/voices
        server.get("/v1/audio/voices") { [weak self] _ in
            await self?.incrementRequestCount()
            let voiceList: [[String: Any]] = kokoroVoices.map { voice in
                [
                    "voice_id": voice.id,
                    "name": voice.name,
                    "gender": voice.gender,
                    "accent": voice.accent,
                    "speaker_id": voice.speakerId,
                    "preview_url": nil as Any?,
                ] as [String: Any]
            }
            return HTTPResponse.json([
                "voices": voiceList,
                "model": "kokoro-en-v0_19",
            ] as [String: Any])
        }

        // POST /v1/audio/speech
        server.post("/v1/audio/speech") { [weak self] request in
            await self?.incrementRequestCount()

            // Parse JSON body
            guard !request.body.isEmpty else {
                return HTTPResponse.error("Request body is required", statusCode: 400)
            }

            guard let json = try? JSONSerialization.jsonObject(with: request.body) as? [String: Any] else {
                return HTTPResponse.error("Invalid JSON body", statusCode: 400)
            }

            // Extract parameters (OpenAI-compatible format)
            guard let input = json["input"] as? String, !input.isEmpty else {
                return HTTPResponse.error("'input' field is required and must be non-empty", statusCode: 400)
            }

            let voice = json["voice"] as? String ?? "af_sky"
            let speed = (json["speed"] as? NSNumber)?.floatValue ?? 1.0
            let responseFormat = json["response_format"] as? String ?? "wav"

            // Only WAV is supported
            guard responseFormat == "wav" || responseFormat == "pcm" else {
                return HTTPResponse.error(
                    "Unsupported response_format '\(responseFormat)'. Only 'wav' and 'pcm' are supported.",
                    statusCode: 400
                )
            }

            // Generate speech
            do {
                let startTime = CFAbsoluteTimeGetCurrent()
                let wavData = try await engine.generateSpeech(text: input, voice: voice, speed: speed)
                let elapsed = CFAbsoluteTimeGetCurrent() - startTime
                let sampleRate = await engine.sampleRate

                // Calculate audio duration from WAV data
                // WAV header is 44 bytes, 16-bit mono = 2 bytes per sample
                let numSamples = (wavData.count - 44) / 2
                let duration = sampleRate > 0 ? Double(numSamples) / Double(sampleRate) : 0

                var headers: [String: String] = [
                    "Content-Type": "audio/wav",
                    "X-TTS-Duration": String(format: "%.3f", duration),
                    "X-TTS-Processing-Time": String(format: "%.3f", elapsed),
                    "X-TTS-Voice": voice,
                    "X-TTS-Sample-Rate": "\(sampleRate)",
                ]

                if responseFormat == "pcm" {
                    // Strip WAV header, return raw PCM
                    let pcmData = wavData.count > 44 ? wavData.dropFirst(44) : Data()
                    headers["Content-Type"] = "audio/pcm"
                    return HTTPResponse(
                        statusCode: 200,
                        statusMessage: "OK",
                        headers: headers,
                        body: Data(pcmData)
                    )
                }

                return HTTPResponse(
                    statusCode: 200,
                    statusMessage: "OK",
                    headers: headers,
                    body: wavData
                )
            } catch {
                return HTTPResponse.error(
                    "TTS generation failed: \(error.localizedDescription)",
                    statusCode: 500
                )
            }
        }

        // Catch-all for unknown routes
        server.get("/*") { _ in
            return HTTPResponse.json([
                "error": [
                    "message": "Unknown endpoint. Available: GET /health, GET /v1/models, GET /v1/audio/voices, POST /v1/audio/speech",
                    "type": "not_found",
                ] as [String: Any],
            ] as [String: Any], statusCode: 404)
        }
    }

    private func incrementRequestCount() {
        totalRequests += 1
        requestCount = totalRequests
        lastRequestTime = Date()
    }
}
