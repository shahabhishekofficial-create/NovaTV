# NovaTV status

Newest entries first.

## 2026-10-25 — Phase 1 catalogue logic

- Added streaming M3U parsing with bounded lines, alternate sources, local stores, on-disk cache, playlist fetch/error mapping, and ranked normalized search.
- Added pure unit tests for mandatory search terms, parser edge cases, 10,000 channels, quality labels, and header parsing. CI verification is pending for this checkpoint.

## 2026-10-25 — Phase 0 skeleton

- GitHub Actions run `40135962291` passed on commit `3b3bec8`, including both APK builds and the 3 MB release-size check.

- Gradle/SDK configuration, TV launcher metadata, banner generation, CI, static validation, and debug-signed release packaging implemented.

### Needs owner device test

- Install the APK on an Android TV emulator and a physical TV/box.
- Confirm NovaTV appears with the 320×180 banner in both TV and regular launchers.
- Confirm cold start and rendering on the oldest intended Android 5.0-era device.
