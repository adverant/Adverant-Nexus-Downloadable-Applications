// ProseCreatorTTSApp.swift
// Entry point for the ProseCreator TTS companion app.
// Configures the audio session for background playback (keeps the server alive)
// and sets up the main SwiftUI view hierarchy.

import SwiftUI
import AVFoundation

@main
struct ProseCreatorTTSApp: App {
    init() {
        configureAudioSession()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }

    /// Configure the audio session for background capability.
    /// This keeps the app active when the screen is off or the app is backgrounded,
    /// allowing the TTS server to continue serving requests.
    private func configureAudioSession() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .spokenAudio, options: [.mixWithOthers])
            try session.setActive(true)
        } catch {
            print("[ProseCreatorTTS] Failed to configure audio session: \(error)")
        }
    }
}
