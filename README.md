# LeanType CJK Companion Plugin

Lightweight, high-performance CJK companion plugin for [LeanType](https://github.com/LeanBitLab/LeanType), with Chinese (Pinyin) and Japanese (romaji to kana to kanji) input.

## Overview

LeanType keeps its core APK bloat-free by modularizing large language dictionaries and specialized native engines into standalone companion plugins. This repository houses the Pinyin input engine powered by the battle-tested AOSP Google Pinyin native C++ engine (`libjni_pinyin.so`) with the full 65,000+ word dictionary (`dict_pinyin.dat`).

### Key Highlights
- **Chinese and Japanese**: Chinese Pinyin through the Google Pinyin engine, and Japanese romaji input with kana-to-kanji candidates from an offline SKK-based dictionary.
- **Korean needs no plugin**: Korean typing uses LeanType's built-in Hangul input, so this plugin only covers Chinese and Japanese.
- **In-process Modular Loading**: Loaded dynamically into LeanType via `DexClassLoader` with strict ABI matching (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`).
- **Small Footprint**: Split APKs are about 3.5 MB each. The bundled dictionaries are about 1 MB (Pinyin) and 10 MB (Japanese) uncompressed.
- **Zero Latency**: Real-time N-gram candidate generation, matrix search, and syllable splitting running fully offline with zero telemetry or network permissions.
- **Android 14+ W^X Compliant**: Native libraries and dictionary files are unpacked to private, read-only storage before loading.

## Architecture

```
LeanType (Main IME)
  └── CjkInputManager / CjkPluginLoader
        └── DexClassLoader -> CjkEngineImpl (implements ICjkEngine)
                                └── PinyinDecoderJni
                                      └── libjni_pinyin.so (AOSP C++ MatrixSearch)
                                            └── dict_pinyin.dat (~65,000 words)
```

## Building

Prerequisites:
- Android SDK (API 34 / compileSdk 34)
- Android NDK (r25c or newer)
- CMake 3.22.1+

Build split APKs:
```bash
./gradlew assembleRelease
```

Generated APKs (`app/build/outputs/apk/release/`), about 3.5 MB each:
- `cjk_plugin-arm64-v8a.apk`
- `cjk_plugin-armeabi-v7a.apk`
- `cjk_plugin-x86.apk`
- `cjk_plugin-x86_64.apk`

## Installation

The plugin is loaded into LeanType from a file; it does not need to be installed as an app.

1. Open **Settings → Plugins → CJK Input** in LeanType.
2. In the Standard flavor you can download the plugin there. In the Offline flavor, download the APK for your device from the [releases page](https://github.com/LeanBitLab/LeanType-CJK-Plugin/releases/latest) and load it on that screen.
3. Add Chinese or Japanese under **Settings → Languages & Layouts**.

For a local build, copy the APK to the device and load it the same way.
