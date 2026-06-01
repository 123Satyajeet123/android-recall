# Private Recall for Android

On-device screen memory with voice-gated access. Remembers everything you see. Answers in 370ms. Only your voice unlocks it.

```
you:   "where did I see calculator?"
recall: "You saw 'calculator' in launcher, May 31, 2:03 AM"
        speaker verified: 55ms | STT: 266ms | search: 5ms | total: 326ms
        
friend: "where did I see calculator?"
recall: REJECTED (not owner voice, 70ms)
```

## How it works

1. **Capture** — AccessibilityService reads all screen text (no screenshots, no OCR, no battery drain)
2. **Deduplicate** — SHA-256 hash, only stores new content (~25 captures/hour from 1000+ events)
3. **Listen** — always-on mic with Silero VAD, only activates on sustained speech
4. **Verify** — CAM++ speaker embeddings confirm it's the owner in 55-99ms
5. **Transcribe** — Whisper base.en converts speech to text in 250-315ms
6. **Search** — FTS4 full-text search across all captured screen content in 2-5ms

Everything runs on-device. No cloud. No internet required.

## Performance

| Component | Model | Size | Latency |
|-----------|-------|------|---------|
| VAD | Silero v5 int8 | 630KB | <1ms/frame |
| Speaker verify | CAM++ (3DSpeaker) | 29MB | 55-99ms |
| STT | Whisper base.en int8 | 160MB | 250-315ms |
| Recall search | SQLite FTS4 | — | 2-5ms |
| **End-to-end** | **speech → answer** | | **320-420ms** |

| System metric | Value |
|---------------|-------|
| Capture overhead | ~10ms/event |
| Storage | ~300KB/day |
| Battery impact | unmeasurable |
| Dedup reduction | 77x |
| Always-on CPU | <1% |

## Hardware

Built and tested on:
- **Brave Ark tablet** — Snapdragon 8 Gen 3 (SM8650), 12GB RAM
- **Android 16** (userdebug, AOSP)
- Total model memory: ~747MB (fits easily in 12GB)

## Setup

```bash
# Build
./gradlew assembleDebug

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Enable services
adb shell settings put secure enabled_accessibility_services \
  com.brave.veloxcore/.RecallAccessibilityService
adb shell pm grant com.brave.veloxcore android.permission.RECORD_AUDIO

# Push models to device
adb shell mkdir -p /data/local/tmp/sherpa-models/whisper-base.en
# Download from: github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models
adb push silero_vad.onnx /data/local/tmp/sherpa-models/
adb push campplus_en.onnx /data/local/tmp/sherpa-models/
adb push base.en-encoder.int8.onnx /data/local/tmp/sherpa-models/whisper-base.en/
adb push base.en-decoder.int8.onnx /data/local/tmp/sherpa-models/whisper-base.en/
adb push base.en-tokens.txt /data/local/tmp/sherpa-models/whisper-base.en/

# Enroll your voice (speak 3 phrases when prompted)
adb shell am broadcast -a com.brave.veloxcore.ENROLL

# Launch
adb shell am start -n com.brave.veloxcore/.DemoActivity
```

## Architecture

```
Always-on (< 1% CPU):
  AudioRecord 16kHz → Silero VAD (0.7 threshold, 0.8s min speech)

On speech detected:
  → Speaker Verify (CAM++ 512-dim embedding, cosine similarity)
  → If owner: Whisper base.en transcription
  → Pattern-match keyword extraction → FTS4 search → answer
  → If not owner: rejected, ignored

Parallel (event-driven):
  Screen changes → AccessibilityService → SHA-256 dedup → SQLite + FTS4
```

## Files

```
VoiceService.kt                — always-on voice: VAD + verify + STT + query
RecallAccessibilityService.kt  — screen capture, dedup, storage
DemoActivity.kt                — real-time voice status UI
data/                          — Room entities, DAO, database
query/                         — keyword extraction, FTS4 search, answer formatting
```

## Privacy

- All data on-device. Zero network calls.
- Speaker verification: only enrolled voice can query
- Sensitive apps blocked (banking, auth, payments)
- Pause/resume via broadcast

## Roadmap

- [x] Screen recall (accessibility + FTS4)
- [x] Voice query (Whisper STT)
- [x] Speaker verification (CAM++ voice gate)
- [ ] Wake word ("Hey Ark")
- [ ] TTS response (Kokoro, <500ms TTFA)
- [ ] Device awareness (battery, notifications, app state)
- [ ] AOSP system service (init.rc, boot-with-OS)

## License

MIT
