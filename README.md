# Soul Sleep 🎵

An Android application that fetches synchronized lyrics from free, open APIs and embeds them directly into your audio files' metadata.

![Platform](https://img.shields.io/badge/Platform-Android-green)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-blue)
![License](https://img.shields.io/badge/License-MIT-orange)

## Features

- **Direct Metadata Tagging**: Embeds synchronized lyrics directly into audio file tags (ID3 `USLT` for MP3, `©lyr` for M4A, `LYRICS` Vorbis comment for FLAC/OGG) without creating loose `.lrc` sidecar files.
- **Dual API Synchronization**:
  - **lrc.red (Priority 1)**: Syllable/word-level synchronized lyrics (Apple-style TTML converted to enhanced LRC format).
  - **LRCLIB (Fallback)**: Fast and reliable line-synchronized lyrics database.
- **Smart Categorization & Filtering**:
  - List and filter songs into status categories: `NONE`, `WORD`, `RETRY`, `LINE`, and `ADD`.
  - Automatic detection of existing embedded lyrics on scan (displays `WORD` or `LINE` sync immediately).
  - Grouped sections with live track counts and batch action buttons (`ADD ALL`, `RETRY ALL`, etc.).
- **Privacy-Friendly Folder Access**: Uses Android Storage Access Framework (SAF) so you only need to grant write permissions to your specific music folder rather than full device storage.
- **Rate-Limit Safe**: Automatic cooldown management and single-request queuing to respect public community API rate limits.
- **Minimalist Dark UI**: Fluid, rotating harmonic visualizer that reacts to tagging status and cooldown periods.

## Tech Stack

- **UI**: Android Jetpack Compose (Material 3)
- **Networking**: OkHttp
- **Audio Tagging**: JAudioTagger (Android-optimized fork via JitPack)
- **Architecture**: MVVM with Kotlin Coroutines & StateFlow

## Building

Prerequisites: Android SDK 34 and JDK 17+.

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

