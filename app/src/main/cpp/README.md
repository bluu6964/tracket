# whisper.cpp (speech to text for Auto Captions)

The app transcribes speech with [whisper.cpp](https://github.com/ggml-org/whisper.cpp)
(model: `ggml-small-q5_1.bin`, ~181 MB, downloaded by the app the first time Auto Captions is used,
see `engine/WhisperModelDownloader.kt`).

The whisper.cpp source is NOT inside this zip.

**GitHub Actions:** nothing to do - `.github/workflows/build.yml` downloads it before every build.

**Building on your own computer:** do this once before building:

1. Download a whisper.cpp release (v1.7.x or newer): https://github.com/ggml-org/whisper.cpp/releases
   (or `git clone --depth 1 --branch v1.7.6 https://github.com/ggml-org/whisper.cpp`).
2. Put its contents here, so that this file exists:

       app/src/main/cpp/whisper.cpp/CMakeLists.txt

   (you can delete `whisper.cpp/examples/`, `bindings/` and `models/` to keep the project small).
3. Build as usual. Gradle runs CMake (`app/build.gradle.kts` -> `externalNativeBuild`) and builds
   `libwhisper_jni.so` for arm64-v8a only. NDK r28 or newer is recommended (16 KB page size).

Files:

- `CMakeLists.txt` - builds whisper.cpp + the bridge, always as Release, with portable arm64-v8a baseline flags (ggml may use safe per-target optimizations).
- `whisper_jni.cpp` - JNI bridge used by `engine/WhisperNative.kt`.

Phones without dotprod / fp16 (older than ~2018) are detected in `WhisperEngine.isSupported()`
and the library is never loaded there. Phones with a 32-bit-only CPU show the same message.
