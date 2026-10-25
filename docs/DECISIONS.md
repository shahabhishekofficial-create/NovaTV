# Engineering decisions

Newest entries first.

## 2026-10-25 — TV verification

- **Built-in instrumentation only:** Android's JUnit 3 runner and accessibility APIs exercise real views/dialogs without adding Espresso, UI Automator, or any other test/runtime dependency.
- **Test-only software video:** `DecoderPolicy.allowSoftwareVideoForTests` is set by instrumentation before launch so software-only CI emulators can decode generated test media. It defaults to false; the production hardware-only policy is unchanged.
- **Observable playback diagnostics:** INFO includes playback state and decoded audio MIME type, sample rate, and channel count. This supports automated stream/audio-path assertions, but emulator audio is not evidence of audible sound on a physical TV.
- **Synthetic networked fixtures:** CI creates a test pattern and sine tone with FFmpeg, serves HLS/MP4/TS/FLV from the runner, and uses only the emulator's host loopback alias. No fixture is packaged or presented as a channel service.

## 2026-10-25

- **One activity for home and playback:** `HomeActivity` owns both screens and `PlayerController` owns Media3. This follows the non-negotiable single-activity constraint rather than adding the phase note's separate `PlayerActivity`; there are no fragments or fragment dependencies.
- **Assistant/mic limitation:** the app provides an on-screen recognizer and `ACTION_SEARCH`, but does not claim to intercept the hardware Assistant/mic key or support “Hey Google, play X.” Android TV's Assistant owns that path.
- **Hardware video only:** known software video decoders and decoder fallback are excluded. Unsupported codecs produce an error card instead of expensive software fallback loops.
- **Dependency-free QR:** the optional pairing URL fits a fixed version 4-L QR code, implemented directly with Reed–Solomon coding. It avoids a barcode library and its APK cost.
- **Debug certificate for the continuous APK:** the repository contains a deliberately public debug-only PKCS#12 key with the standard `android` password. It provides a stable update signature for sideloads; it is not a production signing identity or a secret.
- **JUnit is test-only:** the five allowed runtime libraries are the only packaged dependencies. JUnit 4 is used solely on the unit-test classpath and is not included in an APK.
- **No bundled media:** presets are optional playlist URLs only. NovaTV includes no channel catalogue, stream, credentials, advertising, or analytics.
