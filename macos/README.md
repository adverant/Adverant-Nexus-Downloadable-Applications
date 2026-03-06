# Nexus Local Compute — macOS

Menu bar application for running AI models locally on Apple Silicon Macs. Powers the TTS engine for ProseCreator Writing Studio.

## Download

Download the latest DMG from [Releases](https://github.com/adverant/Adverant-Nexus-Downloadable-Applications/releases).

## Installation

1. Download `NexusLocalCompute-v{version}.dmg` from the latest release
2. Open the DMG file
3. Drag **Nexus Local Compute** to your Applications folder
4. Launch from Applications — it appears in your menu bar
5. Click the menu bar icon and select **Start TTS Server**

## Features

- **Multiple TTS Models**: Kokoro 82M (fast), Qwen3-TTS 1.7B (voice design), Chatterbox (multilingual), and more
- **MLX Optimized**: Runs natively on Apple Silicon using MLX framework
- **Menu Bar Control**: One-click start/stop, model loading, status indicator
- **Auto-Discovery**: Writing Studio automatically finds the local server
- **Minimal Footprint**: ~50MB RAM when idle, models loaded on demand

## Supported Models

| Model | Parameters | Speed | Use Case |
|-------|-----------|-------|----------|
| Kokoro 82M | 82M | 11x RT | Fast preview, 25 voice presets |
| Qwen3-TTS | 1.7B | 2.4x RT | Voice design via text description |
| Chatterbox | 350M | 5.5x RT | General narration |
| CSM | 1B | 1.2x RT | Context-aware conversation |
| Dia | 1.6B | 0.8x RT | Multi-speaker dialogue |

## Requirements

- macOS 14 (Sonoma) or newer
- Apple Silicon (M1, M2, M3, or M4)
- 8GB RAM minimum (16GB recommended for larger models)
- ~2GB storage for app + models

## API

The app runs an OpenAI-compatible TTS API on `http://localhost:8880`:

```bash
# Check health
curl http://localhost:8880/health

# List loaded models
curl http://localhost:8880/v1/models

# Generate speech
curl -X POST http://localhost:8880/v1/audio/speech \
  -H "Content-Type: application/json" \
  -d '{"model":"kokoro","input":"Hello world","voice":"af_sky"}' \
  -o output.wav
```
