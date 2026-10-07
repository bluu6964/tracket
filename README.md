# Tracket

Tracket is a native Android video-editor prototype built with Kotlin, Jetpack Compose, and Media3.

## Current functionality

- Import videos and photos with Android Photo Picker.
- Preview the main video track with ExoPlayer.
- Split, duplicate, delete, and flip clips; choose a project aspect ratio.
- Export the main video track as MP4 with Media3 Transformer.
- Autosave and restore the current editing session in app-private storage. Picked media is reopened when Android permits the Photo Picker URI grant to be persisted.

Audio mixing, compositing secondary tracks, exporting text/shape/overlay layers, advanced effects, and project management/multiple named projects are not implemented yet. Audio and some editing panels are currently UI prototypes; those clips are not included in the MP4 export.

## Build and run

1. Install Android Studio with JDK 17 and the Android SDK platform required by `app/build.gradle.kts` (API 36).
2. Open this repository in Android Studio and allow Gradle sync to finish.
3. Run the `app` configuration on an emulator or Android device.

No Gemini API key is required by the current app. The CI workflow builds the debug APK and uploads it as a workflow artifact.
