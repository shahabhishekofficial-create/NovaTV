# Engineering decisions

Newest entries first.

## 2026-10-25

- **Debug certificate for the continuous APK:** the repository contains a deliberately public debug-only PKCS#12 key with the standard `android` password. It provides a stable update signature for sideloads; it is not a production signing identity or a secret.
- **JUnit is test-only:** the five allowed runtime libraries are the only packaged dependencies. JUnit 4 is used solely on the unit-test classpath and is not included in an APK.
- **No bundled media:** presets are optional playlist URLs only. NovaTV includes no channel catalogue, stream, credentials, advertising, or analytics.
