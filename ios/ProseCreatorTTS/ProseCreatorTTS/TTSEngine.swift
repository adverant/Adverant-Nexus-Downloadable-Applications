// TTSEngine.swift
// Manages the sherpa-onnx Kokoro TTS model lifecycle and audio generation.
// Handles model loading from downloaded files, synthesis, and WAV encoding.

import Foundation
import AVFoundation

/// Kokoro voice preset definitions matching the mlx-audio/Kokoro naming convention.
struct KokoroVoice: Codable, Identifiable {
    let id: String        // e.g. "af_sky"
    let name: String      // e.g. "Sky"
    let gender: String    // "female" or "male"
    let speakerId: Int32  // 0-based index into Kokoro's speaker embedding
    let accent: String    // e.g. "American", "British"
}

/// All 25 Kokoro v0.19 voice presets.
/// The sherpa-onnx Kokoro v0.19 model packs voices into voices.bin.
/// Speaker IDs 0..10 correspond to the first 11 voices; the rest are accessed
/// by name through the voices.bin lookup when using newer sherpa-onnx builds.
/// For maximum compatibility, we map all 25 to their known speaker IDs.
let kokoroVoices: [KokoroVoice] = [
    // American Female
    KokoroVoice(id: "af_alloy", name: "Alloy", gender: "female", speakerId: 0, accent: "American"),
    KokoroVoice(id: "af_aoede", name: "Aoede", gender: "female", speakerId: 1, accent: "American"),
    KokoroVoice(id: "af_bella", name: "Bella", gender: "female", speakerId: 2, accent: "American"),
    KokoroVoice(id: "af_heart", name: "Heart", gender: "female", speakerId: 3, accent: "American"),
    KokoroVoice(id: "af_jessica", name: "Jessica", gender: "female", speakerId: 4, accent: "American"),
    KokoroVoice(id: "af_kore", name: "Kore", gender: "female", speakerId: 5, accent: "American"),
    KokoroVoice(id: "af_nicole", name: "Nicole", gender: "female", speakerId: 6, accent: "American"),
    KokoroVoice(id: "af_nova", name: "Nova", gender: "female", speakerId: 7, accent: "American"),
    KokoroVoice(id: "af_river", name: "River", gender: "female", speakerId: 8, accent: "American"),
    KokoroVoice(id: "af_sarah", name: "Sarah", gender: "female", speakerId: 9, accent: "American"),
    KokoroVoice(id: "af_sky", name: "Sky", gender: "female", speakerId: 10, accent: "American"),
    // American Male
    KokoroVoice(id: "am_adam", name: "Adam", gender: "male", speakerId: 11, accent: "American"),
    KokoroVoice(id: "am_echo", name: "Echo", gender: "male", speakerId: 12, accent: "American"),
    KokoroVoice(id: "am_eric", name: "Eric", gender: "male", speakerId: 13, accent: "American"),
    KokoroVoice(id: "am_liam", name: "Liam", gender: "male", speakerId: 14, accent: "American"),
    KokoroVoice(id: "am_michael", name: "Michael", gender: "male", speakerId: 15, accent: "American"),
    KokoroVoice(id: "am_onyx", name: "Onyx", gender: "male", speakerId: 16, accent: "American"),
    // British Female
    KokoroVoice(id: "bf_alice", name: "Alice", gender: "female", speakerId: 17, accent: "British"),
    KokoroVoice(id: "bf_emma", name: "Emma", gender: "female", speakerId: 18, accent: "British"),
    KokoroVoice(id: "bf_isabella", name: "Isabella", gender: "female", speakerId: 19, accent: "British"),
    KokoroVoice(id: "bf_lily", name: "Lily", gender: "female", speakerId: 20, accent: "British"),
    // British Male
    KokoroVoice(id: "bm_daniel", name: "Daniel", gender: "male", speakerId: 21, accent: "British"),
    KokoroVoice(id: "bm_fable", name: "Fable", gender: "male", speakerId: 22, accent: "British"),
    KokoroVoice(id: "bm_george", name: "George", gender: "male", speakerId: 23, accent: "British"),
    KokoroVoice(id: "bm_lewis", name: "Lewis", gender: "male", speakerId: 24, accent: "British"),
]

/// Look up a Kokoro voice by its ID string.
func kokoroVoiceByID(_ voiceID: String) -> KokoroVoice? {
    return kokoroVoices.first { $0.id == voiceID }
}

// MARK: - WAV Encoder

/// Encode a raw float PCM buffer into a WAV file in memory.
/// - Parameters:
///   - samples: Interleaved float samples in [-1.0, 1.0].
///   - sampleRate: Sample rate in Hz (e.g. 24000).
///   - channels: Number of audio channels (1 for mono).
/// - Returns: Data containing a complete WAV file.
func encodeWAV(samples: [Float], sampleRate: Int32, channels: Int16 = 1) -> Data {
    let bitsPerSample: Int16 = 16
    let bytesPerSample = Int(bitsPerSample) / 8
    let dataSize = samples.count * bytesPerSample * Int(channels)
    let fileSize = 36 + dataSize

    var data = Data()
    data.reserveCapacity(44 + dataSize)

    // RIFF header
    data.append(contentsOf: "RIFF".utf8)
    data.append(contentsOf: withUnsafeBytes(of: UInt32(fileSize).littleEndian) { Array($0) })
    data.append(contentsOf: "WAVE".utf8)

    // fmt sub-chunk
    data.append(contentsOf: "fmt ".utf8)
    data.append(contentsOf: withUnsafeBytes(of: UInt32(16).littleEndian) { Array($0) })    // SubChunk1Size
    data.append(contentsOf: withUnsafeBytes(of: UInt16(1).littleEndian) { Array($0) })     // PCM format
    data.append(contentsOf: withUnsafeBytes(of: UInt16(channels).littleEndian) { Array($0) })
    data.append(contentsOf: withUnsafeBytes(of: UInt32(sampleRate).littleEndian) { Array($0) })
    let byteRate = UInt32(sampleRate) * UInt32(channels) * UInt32(bytesPerSample)
    data.append(contentsOf: withUnsafeBytes(of: byteRate.littleEndian) { Array($0) })
    let blockAlign = UInt16(channels) * UInt16(bytesPerSample)
    data.append(contentsOf: withUnsafeBytes(of: blockAlign.littleEndian) { Array($0) })
    data.append(contentsOf: withUnsafeBytes(of: UInt16(bitsPerSample).littleEndian) { Array($0) })

    // data sub-chunk
    data.append(contentsOf: "data".utf8)
    data.append(contentsOf: withUnsafeBytes(of: UInt32(dataSize).littleEndian) { Array($0) })

    // Convert float samples to 16-bit PCM
    for sample in samples {
        let clamped = max(-1.0, min(1.0, sample))
        let intSample = Int16(clamped * Float(Int16.max))
        data.append(contentsOf: withUnsafeBytes(of: intSample.littleEndian) { Array($0) })
    }

    return data
}

// MARK: - TTS Engine

/// Errors that can occur during TTS engine operations.
enum TTSEngineError: LocalizedError {
    case modelNotLoaded
    case modelFileMissing(String)
    case initializationFailed
    case generationFailed
    case invalidVoice(String)

    var errorDescription: String? {
        switch self {
        case .modelNotLoaded:
            return "TTS model is not loaded. Download the model first."
        case .modelFileMissing(let file):
            return "Required model file missing: \(file)"
        case .initializationFailed:
            return "Failed to initialize the TTS engine. Check model files."
        case .generationFailed:
            return "Audio generation failed."
        case .invalidVoice(let voice):
            return "Unknown voice ID: \(voice)"
        }
    }
}

/// The TTS engine wraps sherpa-onnx's Kokoro model for on-device speech synthesis.
/// Thread-safe: all generation goes through a serial dispatch queue.
@MainActor
final class TTSEngine: ObservableObject {
    /// Whether the model is loaded and ready to generate.
    @Published var isLoaded: Bool = false
    /// Human-readable status message.
    @Published var statusMessage: String = "Not loaded"
    /// Loading progress (0.0 to 1.0) during model initialization.
    @Published var loadProgress: Double = 0.0

    private var ttsWrapper: SherpaOnnxOfflineTtsWrapper?
    private let generationQueue = DispatchQueue(label: "com.prosecreator.tts.generation", qos: .userInitiated)

    /// The sample rate of the loaded model (0 if not loaded).
    var sampleRate: Int32 {
        return ttsWrapper?.sampleRate ?? 0
    }

    /// Number of speakers in the loaded model.
    var numSpeakers: Int32 {
        return ttsWrapper?.numSpeakers ?? 0
    }

    /// Directory where Kokoro model files are stored after download.
    static var modelDirectory: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent("kokoro-en-v0_19", isDirectory: true)
    }

    /// Check if all required model files exist on disk.
    static var modelFilesExist: Bool {
        let dir = modelDirectory
        let fm = FileManager.default
        let requiredFiles = ["model.onnx", "tokens.txt", "voices.bin"]
        for file in requiredFiles {
            if !fm.fileExists(atPath: dir.appendingPathComponent(file).path) {
                return false
            }
        }
        // espeak-ng-data directory must exist
        var isDir: ObjCBool = false
        let espeakPath = dir.appendingPathComponent("espeak-ng-data").path
        if !fm.fileExists(atPath: espeakPath, isDirectory: &isDir) || !isDir.boolValue {
            return false
        }
        return true
    }

    /// Load the Kokoro model from disk. Must be called after model download completes.
    func loadModel() async throws {
        statusMessage = "Loading model..."
        loadProgress = 0.1

        let dir = TTSEngine.modelDirectory
        let modelPath = dir.appendingPathComponent("model.onnx").path
        let voicesPath = dir.appendingPathComponent("voices.bin").path
        let tokensPath = dir.appendingPathComponent("tokens.txt").path
        let espeakDataDir = dir.appendingPathComponent("espeak-ng-data").path

        // Verify all files exist
        let fm = FileManager.default
        guard fm.fileExists(atPath: modelPath) else {
            throw TTSEngineError.modelFileMissing("model.onnx")
        }
        guard fm.fileExists(atPath: voicesPath) else {
            throw TTSEngineError.modelFileMissing("voices.bin")
        }
        guard fm.fileExists(atPath: tokensPath) else {
            throw TTSEngineError.modelFileMissing("tokens.txt")
        }
        guard fm.fileExists(atPath: espeakDataDir) else {
            throw TTSEngineError.modelFileMissing("espeak-ng-data/")
        }

        loadProgress = 0.3
        statusMessage = "Initializing sherpa-onnx..."

        // Build the config chain. We must keep C strings alive during init,
        // so we use a synchronous approach with withCString nesting.
        let wrapper: SherpaOnnxOfflineTtsWrapper? = await withCheckedContinuation { continuation in
            // Run on background thread since model loading can take several seconds
            generationQueue.async {
                let result = modelPath.withCString { modelCStr in
                    voicesPath.withCString { voicesCStr in
                        tokensPath.withCString { tokensCStr in
                            espeakDataDir.withCString { dataDirCStr in
                                "".withCString { emptyCStr -> SherpaOnnxOfflineTtsWrapper? in
                                    var kokoroConfig = SherpaOnnxOfflineTtsKokoroModelConfig()
                                    kokoroConfig.model = modelCStr
                                    kokoroConfig.voices = voicesCStr
                                    kokoroConfig.tokens = tokensCStr
                                    kokoroConfig.data_dir = dataDirCStr
                                    kokoroConfig.length_scale = 1.0
                                    kokoroConfig.dict_dir = emptyCStr
                                    kokoroConfig.lexicon = emptyCStr
                                    kokoroConfig.lang = emptyCStr

                                    var modelConfig = SherpaOnnxOfflineTtsModelConfig()
                                    modelConfig.kokoro = kokoroConfig

                                    var ttsConfig = SherpaOnnxOfflineTtsConfig()
                                    ttsConfig.model = modelConfig
                                    ttsConfig.rule_fsts = emptyCStr
                                    ttsConfig.max_num_sentences = 2
                                    ttsConfig.rule_fars = emptyCStr
                                    ttsConfig.silence_scale = 1.0

                                    return SherpaOnnxOfflineTtsWrapper(config: &ttsConfig)
                                }
                            }
                        }
                    }
                }
                continuation.resume(returning: result)
            }
        }

        guard let wrapper = wrapper else {
            loadProgress = 0.0
            statusMessage = "Failed to load model"
            throw TTSEngineError.initializationFailed
        }

        self.ttsWrapper = wrapper
        self.isLoaded = true
        self.loadProgress = 1.0
        self.statusMessage = "Model loaded (\(wrapper.sampleRate) Hz, \(wrapper.numSpeakers) speakers)"
    }

    /// Unload the model to free memory.
    func unloadModel() {
        ttsWrapper = nil
        isLoaded = false
        statusMessage = "Not loaded"
        loadProgress = 0.0
    }

    /// Generate speech audio from text.
    /// - Parameters:
    ///   - text: The text to synthesize.
    ///   - voice: Kokoro voice ID (e.g. "af_sky"). Defaults to "af_sky".
    ///   - speed: Speed multiplier. 1.0 = normal.
    /// - Returns: WAV file data.
    func generateSpeech(text: String, voice: String = "af_sky", speed: Float = 1.0) async throws -> Data {
        guard let wrapper = ttsWrapper, isLoaded else {
            throw TTSEngineError.modelNotLoaded
        }

        // Resolve voice to speaker ID
        let speakerId: Int32
        if let voicePreset = kokoroVoiceByID(voice) {
            speakerId = voicePreset.speakerId
        } else if let intId = Int32(voice) {
            // Allow numeric speaker IDs directly
            speakerId = intId
        } else {
            // Default to speaker 0 (af_alloy) if voice not found
            speakerId = 0
        }

        // Clamp speed to reasonable range
        let clampedSpeed = max(0.5, min(2.0, speed))

        let result: Data? = await withCheckedContinuation { continuation in
            generationQueue.async {
                guard let audio = wrapper.generate(text: text, sid: speakerId, speed: clampedSpeed) else {
                    continuation.resume(returning: nil)
                    return
                }

                let samples = audio.samplesArray()
                guard !samples.isEmpty else {
                    continuation.resume(returning: nil)
                    return
                }

                let wavData = encodeWAV(samples: samples, sampleRate: audio.sampleRate)
                continuation.resume(returning: wavData)
            }
        }

        guard let wavData = result else {
            throw TTSEngineError.generationFailed
        }

        return wavData
    }

    /// Estimate generation time for a given text length (rough heuristic).
    func estimateGenerationTime(textLength: Int) -> TimeInterval {
        // Kokoro on iPhone typically processes ~50 chars/second on A15+
        return TimeInterval(textLength) / 50.0
    }
}
