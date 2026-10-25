# NovaTV

NovaTV is a lightweight, remote-first IPTV player for Android TV and Google TV. It plays **only M3U/M3U8 playlists supplied by the user**. It ships no channels, credentials, accounts, ads, analytics, or background service.

- Android 5.0/API 21 onward; target API 34; 320×180 TV banner
- `LAUNCHER` and `LEANBACK_LAUNCHER`, with no touchscreen requirement
- HLS and progressive HTTP streams, cleartext HTTP, per-playlist `User-Agent`/`Referer`, redirects, retries, and same-name source fail-over
- Local favourites, recents, resume positions, HD+ filter, normalized asynchronous search, and on-disk catalogue cache
- Multiple playlists, optional iptv-org presets, phone-assisted URL entry, voice typing, and Assistant search intents

## Download and install

### Downloader app on TV

1. Install a free downloader/file-browser app on the TV and allow it to install unknown apps in Android's settings.
2. Enter this stable release-asset URL exactly:

   ```text
   https://github.com/shahabhishekofficial-create/NovaTV/releases/download/continuous/novatv-release.apk
   ```

3. Open the downloaded APK and choose **Install**. The continuous APK is release-optimized and signed with the repository's public debug certificate for consistent sideload updates. It is not a Play Store production signature.

The same APK is also attached to successful Actions runs as `novatv-apk`. See [docs/STATUS.md](docs/STATUS.md) for verified builds and tests that still require a real device.

### ADB over Wi-Fi

Enable Developer options and **ADB debugging / network (wireless) debugging** on the TV. On a computer with the free Android SDK Platform Tools:

```bash
adb connect TV_IP_ADDRESS:5555
adb devices
adb install -r novatv-release.apk
```

Some Android versions use a different wireless-debugging port shown on the TV; use that port and pair first if requested. Disable ADB when finished if it is not needed.

### USB

Copy the APK to a USB drive, open it with a TV file manager, and allow installation from that app. Or connect a computer with USB debugging enabled:

```bash
adb devices
adb install -r novatv-release.apk
```

## Add a playlist

Open **Playlists** and choose one of these options:

- **Add URL:** enter an `http://` or `https://` M3U/M3U8 playlist and an optional name, User-Agent, and Referer.
- **Built-in presets:** optionally add India, Sports, or Everything from the external iptv-org project. These are playlist links only; NovaTV does not bundle their channels and is not affiliated with them.
- **Phone pairing:** while setup is open, scan the QR code or open `http://TV_IP_ADDRESS:8765` with the shown token on a phone on the same LAN. Paste the URL there. The temporary token expires and the embedded server stops when setup closes; there is no idle server or cloud relay.
- **Deep link:** push a URL-encoded link from a browser or ADB, for example:

  ```bash
  adb shell am start -a android.intent.action.VIEW -d 'novatv://playlist?url=https%3A%2F%2Fexample.com%2Flist.m3u' tv.nova.player
  ```

The `url|User-Agent=…&Referer=…` convention is supported directly in playlist entries:

```text
#EXTINF:-1 group-title="Example",Example Channel HD
http://streams.example/channel.m3u8|User-Agent=My TV&Referer=http://streams.example/
```

Playlist-level headers are defaults; entry-level `#EXTVLCOPT` values take precedence. Use **Reload** to refresh manually. The last successful load time is shown, and the cached catalogue remains available offline.

## Remote controls

| Remote key | Action |
|---|---|
| D-pad Up / Down / Left / Right | Move between search, category rail, buttons, and channel cards; focus is always outlined |
| OK / Enter | Play/select; in the player show or hide the overlay; on an error retry |
| Long-press OK on a card | Add or remove a favourite (stored by stream URL) |
| Back | Clear search/return to grid; in the player stop playback and return to the previously watched card; Back again can leave the app |
| Menu | Open the channel picker |
| Info | Show/hide real playback state, resolution, bitrate, video/audio codec details, dropped-frame, buffer, and live/VOD stats |
| Channel + / Channel − | Next / previous channel |
| Media Next / Previous | Next / previous channel |
| Media Play/Pause | Pause/resume; a live stream resumes from its pause buffer |
| Volume Up / Down / Mute | Left to the TV/system; NovaTV does not consume them |
| Search key | Open the channel search field |
| On-screen Voice | Launch the device's speech recognizer and pre-filter the grid |

**Voice limitation:** Android TV's Assistant owns the hardware microphone/Assistant key. NovaTV does **not** claim to intercept that key or support “Hey Google, play X.” The on-screen Voice button uses `ACTION_RECOGNIZE_SPEECH`; if no recognizer exists, NovaTV says so and returns to typing. `ACTION_SEARCH` and `<searchable>` support Assistant-originated in-app search queries where the device provides them.

## Search and favourites

Search is case-insensitive and token-AND matched across channel name, group, and quality. `&` becomes `and`, `+` becomes `plus`, and `. , ' -` are ignored. Compact spelling is also indexed, so `Star Plus HD`, `StarPlus`, and `STAR PLUS` can coexist in results for `star plus`.

Examples: `star`, `plus`, `star plus`, `sports`, `ten 2`, and `hd`. Results rank exact names first, then prefixes, other name matches, and finally group/quality-only matches. Search is debounced by 200 ms and runs away from the UI thread. If nothing matches, try fewer tokens or turn off **HD+**.

Favourites, recents, settings, and resume positions remain on the device. Favourites use stream URLs, not list indices, so reordering or refreshing a playlist does not move a favourite to another channel.

## Why a channel may fail

A playlist link does not guarantee that every stream is online or authorized. Servers can expire, refuse hotlinking, require exact headers, block networks or regions, redirect incorrectly, or use a codec the TV cannot decode in hardware. NovaTV shows a readable error instead of a blank screen and tries bounded retries plus another source with the same normalized channel name when one exists. It deliberately does not loop through slow software video decoders.

Check the playlist in its official app/source, confirm the User-Agent/Referer, refresh the list, and try another authorized source. Cleartext HTTP is enabled because many user-supplied feeds require it; HTTPS is preferred when available. User-installed CA certificates are trusted in addition to system CAs.

## Performance and privacy

The release CI fails if the APK exceeds **3,000,000 bytes**. Runtime dependencies are limited to AndroidX Core, RecyclerView, coroutines, and Media3 ExoPlayer/HLS. There is no Media3 UI, image library, Firebase, DI, reflection framework, advertising, or analytics. JUnit is test-only and is not packaged.

Playlists are downloaded and parsed line-by-line with bounded lines. Logos use `HttpURLConnection`, downsampling, a 2 MiB memory cache, and a 4 MiB disk cache; scroll recycling cancels work and offline devices do not make logo network requests. The pairing socket exists only during setup. Playlist URLs and any credentials embedded in them stay in app-private local storage; do not share playlists you are not authorized to use.

Target budgets are ≤1.5 s cached cold start, ≤5 s initial 10,000-channel network load at 10 Mbps, ≤96 MiB steady-state memory, and smooth 10,000-channel navigation. These remain device-measurement items until confirmed on the oldest target hardware; see [docs/STATUS.md](docs/STATUS.md).

## Build and verify

Requirements: free JDK 17, Android SDK 35, and Gradle wrapper 8.11.1 (AGP 8.7.3, Kotlin 2.0.21). FFmpeg is also needed to generate the optional synthetic instrumentation streams.

```bash
python3 tools/validate.py
./gradlew testDebugUnitTest assembleRelease assembleDebug
```

Release output: `app/build/outputs/apk/release/app-release.apk`. CI uploads it as `novatv-release.apk` and updates the `continuous` pre-release on `main`.

For on-device interaction tests, start an Android TV emulator, generate the synthetic CI-only HLS/MP4/TS/FLV files, and serve them on the host. The emulator reaches the host as `10.0.2.2`:

```bash
tools/generate-test-media.sh /tmp/novatv-media
python3 -m http.server 8766 --directory /tmp/novatv-media &
./gradlew connectedDebugAndroidTest
kill $!
```

The **Android TV verification** workflow runs this at `tv_720p` and `tv_1080p`, then uploads screenshots, logcat, and instrumentation reports. Media files are synthetic test patterns/tone, not shipped content. Instrumentation alone can opt into software video decoding because cloud emulators have no TV hardware codec; normal app execution remains hardware-only. Audio-format checks do not prove physical speaker audibility.

Regenerate the icon and exact 320×180 banner with standard-library Python only (no Pillow):

```bash
python3 tools/make_assets.py
```

See [docs/CHANGELOG.md](docs/CHANGELOG.md), [docs/DECISIONS.md](docs/DECISIONS.md), and [docs/STATUS.md](docs/STATUS.md) for implementation notes and owner device tests.

## Legal

NovaTV contains no media or channel catalogue. **The user is solely responsible for obtaining playlists and for having the rights to access and watch their content.** Only use streams and sources you are authorized to use.
