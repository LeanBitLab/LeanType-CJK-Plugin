# LeanType CJK Companion Plugin

Lightweight, high-performance CJK companion plugin for [LeanType](https://github.com/LeanBitLab/LeanType), with Chinese (Pinyin) and Japanese (romaji to kana to kanji) input.

## Overview

LeanType keeps its core APK bloat-free by modularizing large language dictionaries and specialized native engines into standalone companion plugins. This repository houses the Pinyin input engine powered by the battle-tested AOSP Google Pinyin native C++ engine (`libjni_pinyin.so`) with the full 65,000+ word dictionary (`dict_pinyin.dat`).

### Key Highlights
- **Chinese and Japanese**: Chinese Pinyin through the Google Pinyin engine, and Japanese romaji input with kana-to-kanji candidates from an offline SKK-based dictionary.
- **In-process Modular Loading**: Loaded dynamically into LeanType via `DexClassLoader` with strict ABI matching (`arm64-v8a`, `armeabi-v7a`, `x86_64`).
- **Tiny Footprint**: Native shared library is under 400 KB per ABI; embedded dictionary is ~1 MB. Split APKs are ~1.7 to 1.9 MB each.
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

Generated APKs (`app/build/outputs/apk/release/`):
- `cjk_plugin-arm64-v8a.apk` (~1.9 MB)
- `cjk_plugin-armeabi-v7a.apk` (~1.7 MB)
- `cjk_plugin-x86_64.apk` (~1.9 MB)
- `cjk_plugin-universal.apk` (~2.5 MB)

## Installation

Install the companion plugin alongside LeanType:
```bash
adb install -r app/build/outputs/apk/release/cjk_plugin-arm64-v8a.apk
```

LeanType automatically detects the installed package `com.leanbitlab.leantype.cjk.plugin` on startup and enables full-vocabulary Chinese Pinyin input.
