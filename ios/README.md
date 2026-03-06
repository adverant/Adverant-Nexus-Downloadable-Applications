# Nexus Mobile TTS — iOS

On-device text-to-speech companion app for iPhone and iPad. Runs Kokoro TTS locally via SherpaOnnx — no cloud needed.

## Build from Source

TestFlight distribution is coming soon. In the meantime, you can build and run on your device with Xcode.

### Requirements

- macOS with Xcode 15+
- Free or paid Apple Developer account
- iPhone 12 or newer (A14 Bionic or later) running iOS 17+
- ~200MB storage for app + model

### Steps

1. **Build SherpaOnnx framework** (one-time):
   ```bash
   cd ios/ProseCreatorTTS
   bash scripts/build-sherpa-onnx.sh
   ```
   This downloads and builds the SherpaOnnx xcframework into `Frameworks/`.

2. **Open in Xcode**:
   ```bash
   open ProseCreatorTTS.xcodeproj
   ```

3. **Configure signing**:
   - Select `ProseCreatorTTS` target
   - Under Signing & Capabilities, select your team (free Apple ID works)
   - Change the bundle identifier to something unique (e.g., `com.yourname.prosecreator-tts`)

4. **Build and run** on your connected iPhone (Cmd+R)

5. **Trust the developer** on your iPhone:
   - Settings > General > VPN & Device Management > Your Developer App > Trust

6. **Start the TTS server** in the app — ProseCreator Writing Studio will auto-discover it on your local network.

## Features

- Kokoro TTS engine via SherpaOnnx (optimized for Apple Neural Engine)
- Built-in HTTP server (port 8880) — OpenAI-compatible `/v1/audio/speech` API
- Auto-downloads Kokoro model on first launch (~100MB)
- Background audio playback
- Auto-discovery by ProseCreator Writing Studio on the same WiFi network

## Architecture

```
ProseCreatorTTS/
├── ProseCreatorTTSApp.swift    # SwiftUI app entry point
├── ContentView.swift           # Main UI (Start/Stop server, status)
├── TTSEngine.swift             # Kokoro inference via SherpaOnnx
├── TTSServer.swift             # HTTP server coordinator
├── HTTPServer.swift            # Lightweight HTTP server (NIO-free)
├── ModelDownloader.swift       # Downloads Kokoro model from HuggingFace
├── NetworkUtils.swift          # WiFi IP detection, mDNS
├── SherpaOnnxBridge.h          # C bridge header
└── SherpaOnnxWrapper.swift     # Swift wrapper for SherpaOnnx C API
```

## Sign Up for TestFlight Beta

Visit [adverant.ai](https://adverant.ai) to be notified when the TestFlight beta is available.
