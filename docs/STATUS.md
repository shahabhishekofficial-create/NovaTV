# NovaTV status

Newest entries first.

## 2026-10-25 — Phase 0 skeleton

- Gradle/SDK configuration, TV launcher metadata, banner generation, CI, static validation, and debug-signed release packaging implemented.
- Android compilation and APK size must be confirmed by the GitHub Actions run for this commit; this development sandbox has no JDK or Android SDK.

### Needs owner device test

- Install the APK on an Android TV emulator and a physical TV/box.
- Confirm NovaTV appears with the 320×180 banner in both TV and regular launchers.
- Confirm cold start and rendering on the oldest intended Android 5.0-era device.
