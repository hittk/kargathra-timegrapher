# KARGATHRA Timegrapher

Native Android timegrapher app for mechanical watches. Target device: Pixel 8 Pro.

See `KARGATHRA_Timegrapher_Requirements.md` for the full requirements specification.

## Building

### CI / GitHub Actions
Push to `main` → debug APK builds automatically. Tag with `v*` → signed release APK.

### Local development
The Gradle wrapper (`gradlew`, `gradle-wrapper.jar`) is **not committed** to the repo. Before the first build, bootstrap it using a local Gradle installation:

```bash
gradle wrapper --gradle-version 8.7 --distribution-type bin
```

Then open the project in Android Studio (Hedgehog or later). The NDK and CMake will be installed automatically via the SDK Manager on first sync.

### Requirements
- Android Studio Hedgehog+
- JDK 17
- NDK 25.2.9519653 (r25c) — exact version required for Oboe 1.9.0 prefab
- CMake 3.22.1
