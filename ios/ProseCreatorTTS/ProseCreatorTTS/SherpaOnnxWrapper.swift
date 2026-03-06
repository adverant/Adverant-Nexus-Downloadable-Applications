// SherpaOnnxWrapper.swift
// Swift-friendly wrappers around the sherpa-onnx C API for TTS.
// Based on the official sherpa-onnx Swift API examples from k2-fsa/sherpa-onnx.

import Foundation

// MARK: - Generated Audio Wrapper

/// Wraps the C SherpaOnnxGeneratedAudio struct for safe Swift usage.
final class SherpaOnnxGeneratedAudioWrapper {
    let audio: UnsafePointer<SherpaOnnxGeneratedAudio>

    init(audio: UnsafePointer<SherpaOnnxGeneratedAudio>) {
        self.audio = audio
    }

    deinit {
        SherpaOnnxDestroyOfflineTtsGeneratedAudio(audio)
    }

    /// Raw float sample buffer pointer.
    var samples: UnsafePointer<Float> {
        return audio.pointee.samples
    }

    /// Number of samples in the generated audio.
    var count: Int32 {
        return audio.pointee.n
    }

    /// Sample rate of the generated audio (e.g. 24000).
    var sampleRate: Int32 {
        return audio.pointee.sample_rate
    }

    /// Copy samples into a Swift [Float] array.
    func samplesArray() -> [Float] {
        let n = Int(count)
        guard n > 0 else { return [] }
        return Array(UnsafeBufferPointer(start: samples, count: n))
    }
}

// MARK: - Offline TTS Wrapper

/// Main wrapper for the sherpa-onnx offline TTS engine.
final class SherpaOnnxOfflineTtsWrapper {
    private let tts: OpaquePointer

    /// Initialize with a mutable pointer to an SherpaOnnxOfflineTtsConfig.
    init?(config: inout SherpaOnnxOfflineTtsConfig) {
        guard let ptr = SherpaOnnxCreateOfflineTts(&config) else {
            return nil
        }
        self.tts = ptr
    }

    deinit {
        SherpaOnnxDestroyOfflineTts(tts)
    }

    /// The model's native sample rate.
    var sampleRate: Int32 {
        return SherpaOnnxOfflineTtsSampleRate(tts)
    }

    /// Number of speakers supported by the loaded model.
    var numSpeakers: Int32 {
        return SherpaOnnxOfflineTtsNumSpeakers(tts)
    }

    /// Generate audio from text.
    /// - Parameters:
    ///   - text: The input text to synthesize.
    ///   - sid: Speaker ID (0-based). Kokoro v0.19 has speakers 0..10.
    ///   - speed: Speech speed multiplier. 1.0 = normal, <1.0 = faster, >1.0 = slower.
    /// - Returns: A wrapper containing the generated audio samples, or nil on failure.
    func generate(text: String, sid: Int32 = 0, speed: Float = 1.0) -> SherpaOnnxGeneratedAudioWrapper? {
        guard let audioPtr = SherpaOnnxOfflineTtsGenerate(tts, text, sid, speed) else {
            return nil
        }
        return SherpaOnnxGeneratedAudioWrapper(audio: audioPtr)
    }
}

// MARK: - Config Builder Helpers

/// Create a Kokoro model config from file paths.
func sherpaOnnxOfflineTtsKokoroModelConfig(
    model: String = "",
    voices: String = "",
    tokens: String = "",
    dataDir: String = "",
    lengthScale: Float = 1.0,
    dictDir: String = "",
    lexicon: String = "",
    lang: String = ""
) -> SherpaOnnxOfflineTtsKokoroModelConfig {
    return model.withCString { modelPtr in
        voices.withCString { voicesPtr in
            tokens.withCString { tokensPtr in
                dataDir.withCString { dataDirPtr in
                    dictDir.withCString { dictDirPtr in
                        lexicon.withCString { lexiconPtr in
                            lang.withCString { langPtr in
                                var config = SherpaOnnxOfflineTtsKokoroModelConfig()
                                config.model = modelPtr
                                config.voices = voicesPtr
                                config.tokens = tokensPtr
                                config.data_dir = dataDirPtr
                                config.length_scale = lengthScale
                                config.dict_dir = dictDirPtr
                                config.lexicon = lexiconPtr
                                config.lang = langPtr
                                return config
                            }
                        }
                    }
                }
            }
        }
    }
}

/// Create a VITS model config (unused for Kokoro, but included for completeness).
func sherpaOnnxOfflineTtsVitsModelConfig(
    model: String = "",
    lexicon: String = "",
    tokens: String = "",
    dataDir: String = "",
    noiseScale: Float = 0.667,
    noiseScaleW: Float = 0.8,
    lengthScale: Float = 1.0
) -> SherpaOnnxOfflineTtsVitsModelConfig {
    return model.withCString { modelPtr in
        lexicon.withCString { lexiconPtr in
            tokens.withCString { tokensPtr in
                dataDir.withCString { dataDirPtr in
                    var config = SherpaOnnxOfflineTtsVitsModelConfig()
                    config.model = modelPtr
                    config.lexicon = lexiconPtr
                    config.tokens = tokensPtr
                    config.data_dir = dataDirPtr
                    config.noise_scale = noiseScale
                    config.noise_scale_w = noiseScaleW
                    config.length_scale = lengthScale
                    return config
                }
            }
        }
    }
}

/// Create the top-level model config wrapping a Kokoro config.
func sherpaOnnxOfflineTtsModelConfig(
    kokoro: SherpaOnnxOfflineTtsKokoroModelConfig
) -> SherpaOnnxOfflineTtsModelConfig {
    var config = SherpaOnnxOfflineTtsModelConfig()
    config.kokoro = kokoro
    return config
}

/// Create the top-level TTS config.
func sherpaOnnxOfflineTtsConfig(
    model: SherpaOnnxOfflineTtsModelConfig,
    ruleFsts: String = "",
    maxNumSentences: Int32 = 1,
    ruleFars: String = "",
    silenceScale: Float = 1.0
) -> SherpaOnnxOfflineTtsConfig {
    return ruleFsts.withCString { ruleFstsPtr in
        ruleFars.withCString { ruleFarsPtr in
            var config = SherpaOnnxOfflineTtsConfig()
            config.model = model
            config.rule_fsts = ruleFstsPtr
            config.max_num_sentences = maxNumSentences
            config.rule_fars = ruleFarsPtr
            config.silence_scale = silenceScale
            return config
        }
    }
}
