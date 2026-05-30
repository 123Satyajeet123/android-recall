# Private Recall for Android

On-device screen memory. Remembers everything you see, answers questions about it. Nothing leaves your device.

```
you:  "where did I see calculator?"
recall: "You saw 'calculator' in launcher, 1 hour ago. Found in 5 captures."
        (350ms, fully offline)
```

## What it does

- Reads all screen text via Android's accessibility tree (no screenshots, no OCR)
- Deduplicates with SHA-256 (1000+ events/hour → ~25 meaningful captures)
- Stores in SQLite with FTS4 full-text search
- Answers natural language queries using Gemma 1B on-device
- Runs 24/7 with zero noticeable battery impact

## Hardware

Built and tested on:
- **Brave Ark tablet** (Snapdragon 8 Gen 3 / SM8650)
- 12GB RAM, Android 16 (userdebug)
- Gemma 1B: 557MB model, ~600MB runtime memory
- Should work on any Android 9+ device with 4GB+ RAM (without LLM query)

## Performance

| Metric | Value |
|--------|-------|
| Query latency (warm) | 350ms |
| Query latency (cold) | ~4s |
| Capture overhead | ~10ms per event |
| Storage | ~300KB/day |
| Battery impact | unmeasurable |
| Event reduction (dedup) | 77x |

## Setup

```bash
# Build
./gradlew assembleDebug

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Enable accessibility service
adb shell settings put secure enabled_accessibility_services \
  com.brave.veloxcore/.RecallAccessibilityService

# (Optional) Push Gemma model for NL queries
adb push gemma3-1b-generic.litertlm /data/local/tmp/
```

## Usage

```bash
# Query (natural language)
adb shell "am broadcast -a com.brave.veloxcore.QUERY --es q 'where did I see chrome'"

# Check answer
adb logcat -s VeloxRecall,VeloxQuery --format=brief -d | tail -5

# Pause capture
adb shell am broadcast -a com.brave.veloxcore.PAUSE

# Resume capture
adb shell am broadcast -a com.brave.veloxcore.RESUME

# Raw database access
adb shell sqlite3 /data/data/com.brave.veloxcore/databases/recall_db \
  "SELECT COUNT(*) FROM captures;"
```

## Architecture

```
Screen changes → AccessibilityService → SHA-256 dedup → SQLite + FTS4
                                                              ↑
Natural language query → Gemma 1B (keyword extraction) → FTS4 search → answer
```

**Key insight:** Android's accessibility tree gives structured text for free. No screenshots, no OCR, no ML cost for capture. The only ML inference is at query time (keyword extraction from your question).

## Files

```
RecallAccessibilityService.kt  — capture, dedup, throttle, query handler
data/CaptureEntity.kt          — Room entity (table schema)
data/CaptureDao.kt             — database queries + FTS4 search
data/RecallDatabase.kt         — database singleton + migration
query/QueryEngine.kt           — LLM keyword extraction + search + answer
query/Prompts.kt               — prompt templates for Gemma
```

## Privacy

- All data stays on device. No network calls. No cloud.
- Sensitive app blocklist (banking, auth apps excluded from capture)
- Pause/resume anytime
- Model runs locally (Gemma 1B via LiteRT-LM)

## Roadmap

- [ ] Retention policy (auto-delete after N days)
- [ ] Voice interface (STT → query → TTS)
- [ ] Semantic search (embeddings)
- [ ] AOSP system service (init.rc, no app install needed)
- [ ] Open-vocabulary detection (visual memory)

## Status

Work in progress. Built in one night as a learning project. More coming.

## License

MIT
