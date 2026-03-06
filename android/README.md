# Nexus Mobile TTS — Android

On-device text-to-speech companion app for the Adverant Nexus platform.

## Download

Download the latest APK from [Releases](https://github.com/adverant/Adverant-Nexus-Downloadable-Applications/releases).

## Installation

1. Download `NexusTTS-v{version}.apk` from the latest release
2. On your Android device, go to **Settings > Security > Install unknown apps**
3. Enable installation for your browser or file manager
4. Open the APK file and tap **Install**
5. Launch **Nexus TTS** from your app drawer

## Setup

1. Open the app and tap **Start TTS Server**
2. The app will download the Kokoro model on first launch (~100MB)
3. Once the server is running, open ProseCreator Writing Studio in your browser
4. The studio will auto-detect your phone's TTS server on the local network
5. Click **Listen** on any content to hear it read aloud

## Supported Models

| Model | Size | Speed | Quality |
|-------|------|-------|---------|
| Kokoro 82M | ~100MB | 11x realtime | Good narration |

## Requirements

- Android 12 or newer
- 4GB RAM minimum
- ~200MB storage for app + model
- Same WiFi network as your computer running Nexus

## Troubleshooting

**App won't connect to Writing Studio:**
- Ensure both devices are on the same WiFi network
- Check that the TTS server is running (green indicator in the app)
- Try refreshing the Writing Studio page

**Audio quality issues:**
- Kokoro works best with English text
- Longer sentences produce more natural prosody
- Speed can be adjusted in the Writing Studio TTS controls
