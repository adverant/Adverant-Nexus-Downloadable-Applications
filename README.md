# Adverant Nexus — Downloadable Applications

Official companion applications for the [Adverant Nexus](https://adverant.ai) platform. These apps extend Nexus capabilities to your local devices — run AI models on-device, manage TTS playback, and connect to your Nexus workspace without relying on cloud compute.

---

## Available Applications

### 1. Android — Nexus Mobile TTS

Run text-to-speech models directly on your Android device. Listen to audiobooks, review generated prose, and preview AI-written content — all processed locally on your phone.

**Features:**
- On-device Kokoro TTS (82M parameter model)
- Audiobook playback from ProseCreator Writing Studio
- Auto-connects to your Nexus workspace via local network
- No cloud API costs — all inference runs on-device
- Background playback with notification controls

**Requirements:** Android 12+ with 4GB+ RAM

**Download:** See [Releases](https://github.com/adverant/Adverant-Nexus-Downloadable-Applications/releases) for the latest APK.

**Install:**
1. Download the `.apk` file from Releases
2. On your Android device, enable "Install from unknown sources" in Settings > Security
3. Open the downloaded APK and tap Install
4. Launch "Nexus TTS" and follow the setup wizard

---

### 2. iOS — Nexus Mobile TTS

Run text-to-speech on your iPhone or iPad. Same capabilities as the Android app, optimized for Apple's Neural Engine.

**Features:**
- On-device Kokoro TTS via CoreML
- Background audio playback
- Handoff support between iOS and macOS
- AirPlay streaming to speakers

**Requirements:** iOS 17+ on iPhone 12 or newer

**Status:** Coming soon via TestFlight. Sign up for early access at [adverant.ai/mobile](https://adverant.ai).

---

### 3. macOS — Nexus Local Compute

Menu bar application for running AI models locally on Apple Silicon Macs. Powers the TTS engine for ProseCreator Writing Studio with zero cloud dependency.

**Features:**
- Runs Kokoro 82M, Qwen3-TTS 1.7B, Chatterbox, and more via MLX
- Menu bar status indicator with one-click start/stop
- Automatic model management (download, load, unload)
- OpenAI-compatible TTS API on localhost:8880
- Minimal resource usage when idle (~50MB RAM)

**Requirements:** macOS 14+ on Apple Silicon (M1/M2/M3/M4)

**Download:** See [Releases](https://github.com/adverant/Adverant-Nexus-Downloadable-Applications/releases) for the latest `.dmg`.

**Install:**
1. Download the `.dmg` file from Releases
2. Open the DMG and drag "Nexus Local Compute" to Applications
3. Launch from Applications — it appears in your menu bar
4. Click the menu bar icon and select "Start TTS Server"

---

### 4. Windows — Nexus Local Compute

Run local AI models on Windows with NVIDIA GPU acceleration. Provides the same localhost TTS API as the macOS version.

**Features:**
- CUDA-accelerated TTS inference
- System tray application with quick controls
- Compatible with NVIDIA GPUs (RTX 2060+)
- OpenAI-compatible API on localhost:8880

**Requirements:** Windows 10/11 with NVIDIA GPU (CUDA 12+), 8GB+ VRAM recommended

**Status:** Coming soon. Development in progress.

---

### 5. Server — Nexus TTS Server (Self-Hosted)

Deploy the TTS engine on your own Linux server or Kubernetes cluster. Ideal for teams that want shared TTS infrastructure without cloud API costs.

**Features:**
- Docker container with all models pre-packaged
- GPU support via NVIDIA Container Toolkit
- Horizontal scaling behind a load balancer
- REST API compatible with OpenAI `/v1/audio/speech`
- Health checks and Prometheus metrics

**Requirements:** Linux with Docker, NVIDIA GPU recommended (CPU fallback available)

**Quick Start:**
```bash
# Pull and run
docker run -d \
  --gpus all \
  -p 8880:8880 \
  ghcr.io/adverant/nexus-tts-server:latest

# Verify
curl http://localhost:8880/health
```

**Status:** Docker image coming soon. See [server/](server/) for build instructions.

---

## Release Downloads

All application binaries are distributed via [GitHub Releases](https://github.com/adverant/Adverant-Nexus-Downloadable-Applications/releases).

| Platform | File | Status |
|----------|------|--------|
| Android | `NexusTTS-v{version}.apk` | Available |
| iOS | TestFlight | Coming Soon |
| macOS | `NexusLocalCompute-v{version}.dmg` | Available |
| Windows | `NexusLocalCompute-v{version}-setup.exe` | Coming Soon |
| Server | `docker pull ghcr.io/adverant/nexus-tts-server` | Coming Soon |

---

## How It Works

```
Your Device (Phone/Mac/PC)          Adverant Nexus Cloud
+--------------------------+        +----------------------+
|  Nexus TTS App           |  <-->  |  Writing Studio      |
|  - Kokoro 82M model      |  WiFi  |  - ProseCreator      |
|  - Local inference        |        |  - Audio player      |
|  - localhost:8880 API     |        |  - Voice casting     |
+--------------------------+        +----------------------+
```

The companion apps run AI models **entirely on your device**. The Writing Studio in Nexus connects to your local app over your WiFi network — no audio data leaves your network.

---

## Security

- All apps are code-signed (macOS notarized, Android signed)
- No telemetry or analytics collected
- No cloud API keys required
- All inference happens on-device
- Network communication is local-only (localhost or LAN)

---

## Support

- **Issues:** [GitHub Issues](https://github.com/adverant/Adverant-Nexus-Downloadable-Applications/issues)
- **Documentation:** [docs.adverant.ai](https://docs.adverant.ai)
- **Email:** support@adverant.ai

---

## License

Copyright 2024-2026 Adverant Inc. All rights reserved.

The applications in this repository are distributed as pre-built binaries under the [Adverant Software License](LICENSE). Source code for individual applications is maintained in their respective private repositories.
