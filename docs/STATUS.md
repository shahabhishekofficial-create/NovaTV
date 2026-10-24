# NovaTV status

Newest entries first.

## 2026-10-25 — Phases 1–3 integrated TV experience

- Added the D-pad home/grid/rail, favourites, recents, HD+ filtering, cached loading, custom player overlay, hardware-only playback, retries/fail-over, real stats, voice/search intents, multiple playlists, presets, deep links, and token-protected phone pairing with QR.
- Static validation passes locally. Android compilation, tests, and APK size are pending CI for this integrated checkpoint.

### Needs owner device test

- Install on Android TV/Google TV and verify the launcher banner, D-pad focus on every screen, long-press favourites, restart persistence, and refresh while typing.
- Play HLS, MP4, TS, and FLV sources owned by the user; check HTTP feeds, custom headers, redirects, retries, alternate-source fail-over, pause/resume, channel keys, INFO stats, and 50 consecutive switches.
- Verify BACK restores the previously watched card, MENU picks channels, volume remains system-controlled, and search/voice intents behave on the device's Assistant build.
- Scan the pairing QR with a phone on the same LAN, paste a playlist, and verify the server stops when setup closes. Test a device with no speech recognizer.
- Measure cold cached start, 10,000-channel scrolling/search, release APK memory, and playback on 2015-era hardware; emulator-only checks do not replace these measurements.

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
