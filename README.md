# Shairport

An AirPlay receiver for Android 8.1 or later (arm64 and 32-bit ARM), no root needed. [Shairport Sync](https://github.com/mikebrady/shairport-sync) 5.6.3 runs inside the app as a JNI library and advertises itself through Android's own mDNS. AirPlay 2 (multi-room) also runs [NQPTP](https://github.com/mikebrady/nqptp) 1.2.9, its timing service, which needs UDP ports 319 and 320: Android lets apps use them from Android 17 ([heise](https://www.heise.de/en/news/Android-opens-a-few-well-known-ports-11314477.html)); elsewhere the receiver is classic AirPlay.

A [Kiosk Satellite plugin](#kiosk-satellite-plugin) manages the app from a kiosk and Home Assistant. Other apps signed with the same key can take what the receiver plays, as it plays it: the [audio tap](#audio-tap).

## Use

Build and install `app-release.apk` (see [Build](#build)) and open Shairport:

- **AirPlay receiver** starts or stops it, as do the Quick Settings tile and the notification's *Stop*.
- **AirPlay 2** runs NQPTP, which binds UDP ports 319 and 320 (PTP). Where Android doesn't let apps use them, the switch stays off, says why, and the receiver stays classic AirPlay.
- **Start at boot**. With a secure lock screen it starts after the first unlock.
- **Name** (default: the device name), **Model** (the icon senders show, AirPlay 2 only) and **Playback mode** (stereo or mono).
- **Low latency** (off by default, AirPlay 2 only): offers AirPlay 2's low-latency stream, which a Mac uses for its system audio — about 70–300 ms of delay instead of about 2 s, lossy (AAC-ELD), played through a low-latency AAudio stream. Senders that don't use it, and the Music app's buffered audio, play as before. Off while an app taps the audio.
- **Link Android music volume** (default on): AirPlay moves Android's music volume; with an AirPlay 2 sender it works both ways — Android's volume keys move the sender's slider. Off: that volume stays fixed and AirPlay volume applies only to this receiver's audio.
- **Now playing notification** (off by default): the track, artwork and progress in a media notification (lock screen and media controls included); with AirPlay 2 also previous, play/pause and next, reached by media buttons too.
- **Status** shows each part: engine and mode, NQPTP, the mDNS advertisement, playback, and which app taps the audio.

## Configure from ADB

One key/value pair per call, stored where the settings screen stores them. Protected by `android.permission.DUMP`; unknown keys, wrong types and out-of-list values are rejected. A running receiver applies changes at once (restarting the engine when the configuration changed), otherwise they apply the next time it starts. `airplay_2` turns itself back off on a device that keeps UDP ports 319 and 320 from apps.

```sh
# Keys: receiver_enabled, airplay_2, server_name, model, low_latency, playback_mode,
# link_stream_volume, now_playing, start_at_boot. Booleans: --ez value false. List settings take their value or its
# label ("HomePod mini"). A value with spaces needs a second level of quotes.
adb shell am broadcast --include-stopped-packages -n com.hkfuertes.shairport/.SettingsReceiver \
  -a com.hkfuertes.shairport.CONFIGURE_SETTINGS --es key server_name --es value "'Kitchen speaker'"
```

Same receiver, other actions: `LIST_SETTINGS` (every key, type, current value, default and choices, as JSON) and `GET_STATUS` (the state as one URL-encoded line: `state`, `mode`, `source`, `title`, `artist`, `album`, `address`, `volume`, `low_latency_active` and every setting's value). Turning the receiver on over adb takes effect the next time it starts: from the app, or [headless](#headless-setup).

## Headless setup

The whole setup works over adb, no screen interaction.

1. `adb install app-release.apk`.
2. Configure as in [Configure from ADB](#configure-from-adb).
3. Start it without opening the app (exported only to holders of `android.permission.DUMP`):

   ```sh
   adb shell am start-foreground-service -n com.hkfuertes.shairport/.ReceiverService
   ```

4. Start at every boot: `--es key start_at_boot --ez value true`.

Hardware media keys require `now_playing=true`: this setting creates the MediaSession used to forward play/pause to the AirPlay 2 sender.

## Kiosk Satellite plugin

[`kiosk-plugin/`](kiosk-plugin) is a [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) plugin that manages the app from the kiosk and its Remote Admin page (the app's own settings, with its current values, state refreshed every 5 s), and publishes to Home Assistant a switch *AirPlay receiver* and State, Source, Title, Artist, Album and Volume entities. The app must be installed and keeps running the receiver; the plugin drives the adb interface above through Kiosk Satellite's Shizuku access (the `shell` backend is enough). Install the `shairport-*.zip` from `build/kiosk-plugin/` with **Plugin Manager > Developer Tools > Install from ZIP**, then grant Shizuku access and enable the plugin.

## Audio tap

Apps signed with the same key can take what the receiver plays, as it plays it, and do what they like with it — the receiver doesn't know or care, and its status shows who taps it. The wire protocol and mechanics: [DESIGN.md](DESIGN.md#audio-tap).

## Limitations

- Control back to the sender (now playing controls, Android's volume) works with AirPlay 2 only: classic AirPlay's (DACP) needs the sender's `_dacp._tcp` service, which the app doesn't browse.
- Android's volume going back to the sender is untested in AirPlay 2 multi-room groups.
- The notification's progress can run up to the AirPlay latency (about 2 s) ahead of what is heard.
- Home app: only the Generic model can be added, and the home hub removes an accessory that switches models. HomeKit pairings live in memory only.
- AirPlay 2 only where Android lets apps bind UDP ports 319 and 320 (PTP): Android 17 does; heise reports Google Play system updates bringing it to Android 13+ devices with a 5.15 or newer kernel. Other devices stay classic AirPlay.
- Screen off: from Android 14 a Wi-Fi lock only works with the screen on and the app in the foreground, so with the screen off the device may stop answering (the POCO does).
- Ports are fixed: 7000 (AirPlay 2) and 5000 (classic); upstream ignores `general.port`.
- No realtime priority for the player thread or NQPTP (it asks for `SCHED_FIFO`, which takes root): watch for underruns under load.
- armeabi-v7a verified only on the POCO in 32-bit mode, not on a 32-bit-only device.

## Build

Everything builds in Docker from a clean clone; the host needs nothing else:

```sh
make           # build/app-release.apk and build/kiosk-plugin/
make install   # and adb install -r
```

The [Dockerfile](Dockerfile) downloads the NDK and the pinned, checksum-verified dependencies (static libraries, Shairport Sync, NQPTP), applies [`native/patches`](native/patches/README.md), builds both ABIs, then the APK and the plugin; Docker's layer cache stands in for incremental builds. Release builds are signed with the debug key: `~/.android/debug.keystore`, when it exists, is passed as a build secret, so installs keep the AirPlay device ID (it comes from `ANDROID_ID`, which depends on the signing key).

- `make plugin`: only the plugin, in `build/kiosk-plugin`.
- `make jnilibs`: only the engine, in `build/jniLibs` (for Gradle or Android Studio outside Docker).
- `make src`: the patched upstream sources, in `build/src`.

Tests, all in every build: `NowPlayingTest`; on a device also `tests/aaudio/run.sh [serial]` and `python3 tests/airplay2-ui/check.py open` (or `closed`, on a device that keeps UDP ports 319 and 320 from apps). Without an Apple device, AirConnect's `cliraop -a` (ALAC) plays to classic AirPlay.

## Layout

- `app/`: the Kotlin app — `MainActivity` (settings and status), `ReceiverService`, `Engine.kt`, `Nqptp.kt`, `AudioTapService`, `NowPlaying`, `VolumeSync`, `SettingsReceiver` (the adb interface), `BootReceiver`, `ReceiverTileService`.
- `native/`: the engine's build scripts, run by the Dockerfile, and its [patches](native/patches/README.md).
- `kiosk-plugin/`: the Kiosk Satellite plugin (Java) and its test.
- `tests/`: the on-device checks (AAudio, the AirPlay 2 switch).
- `satellites/`: companion apps, **not built** — out of the Gradle build; [their README](satellites/README.md).
- `Dockerfile`, `Makefile`: the whole build. `DESIGN.md`: how it works inside, and the audio tap protocol.

## Made with AI

Most of the code, patches and documentation were written by AI coding agents (Claude and GPT); the author set the goals, made the design decisions and tested on real devices. Review it as you would any unaudited code before relying on it.

## Credits

- [Shairport Sync](https://github.com/mikebrady/shairport-sync) and [NQPTP](https://github.com/mikebrady/nqptp), by Mike Brady and contributors: the AirPlay receiver and its timing service — this app is an Android shell around them. The engine also bundles OpenSSL, FFmpeg, libsodium, libgcrypt and libgpg-error, libplist, libconfig, popt and libuuid; each keeps its own licence, versions pinned in the [Dockerfile](Dockerfile).
- [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) and its [plugin template](https://github.com/jxlarrea/kiosk-satellite-plugins), by jxlarrea: the plugin SDK and build tool.
