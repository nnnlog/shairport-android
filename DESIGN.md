# Design

How Shairport works inside. The rule: whatever Android can do, Android does. No root.

## How it works

- Engine: `ReceiverService` writes `shairport-sync.conf` and binds `EngineService`, which runs Shairport Sync as a JNI library (`libshairport_sync.so`, patch [`0008`](native/patches/README.md)) in a process of its own (`:engine`). Shairport assumes a fresh process, so it runs once per process: stopping asks it to exit, its own cleanup runs (mDNS goodbyes, AAudio closed) and the process ends; the next start waits until it is gone. An engine that dies after 30 s of uptime is restarted (not sooner, so a broken setup can't loop). Only the JNI entry points are exported, so the static OpenSSL and FFmpeg inside can't clash with the app's own libraries. The log goes to logcat, tag `Shairport`.
- `ReceiverService` is a `connectedDevice` foreground service: Android 15 doesn't let `mediaPlayback` ones start from `BOOT_COMPLETED`. The type doesn't affect audio.
- Discovery: Shairport's `android` mDNS backend hands its services and TXT records to `NsdManager`, so Android's responder advertises them and follows address changes. NsdManager can't update a TXT record, so an AirPlay 2 group change re-registers the service. The app holds a Wi-Fi `MulticastLock`.
- Audio: AAudio (patch `0007`) reports the real output delay, which AirPlay 2 timing and multi-room need. It's why Android 8.1 is the minimum.
- AirPlay 2: NQPTP binds UDP ports 319 and 320 (PTP). Linux keeps ports below 1024 for root, but Android opens a dozen well-known ones to apps with an eBPF allowlist, PTP's among them ([heise](https://www.heise.de/en/news/Android-opens-a-few-well-known-ports-11314477.html)): Android 17 does. With the switch on, `ReceiverService` runs NQPTP — `libnqptp.so`, an executable among the native libraries, extracted on install so it can run — as a child process and stops it with the engine; Android kills it with the app process. Its shared memory lives in the app's internal storage, where the engine maps it. NQPTP creates it once it holds the ports (patch `nqptp/0003`), and the engine starts only then. Shairport maps it once, at start, so an NQPTP that ends by itself restarts the engine. Where apps can't bind the ports (`Nqptp.portsOpen`), or NQPTP ends within 30 s of its start, the AirPlay 2 switch turns off and Shairport starts in classic mode. NQPTP asks for realtime scheduling, which takes root: it runs without.
- Device ID: apps can't read the MAC address, so the AirPlay device ID comes from `ANDROID_ID`.
- Volume: with Link Android music volume on, Shairport ignores volume control and sends metadata to the app over loopback UDP; the app applies the sender's volume to Android's music stream. The engine passes every change of that volume on to the audio tap, and with an AirPlay 2 sender to the sender too, unless the change came from it. With it off, Shairport applies AirPlay volume itself.
- Now playing: patch `0010` builds Shairport's metadata hub and remote control without D-Bus or MQTT. A hub watcher hands the track, cover art file, state, duration and position to `ReceiverService` over the engine's status `Messenger`: the status (and Home Assistant) and `NowPlaying.kt`, a `MediaSession` with a `MediaStyle` notification, read it. Its controls go back over the engine's binder to Shairport's remote control. Since the 5.6.2 pin, upstream extracts the track from AirPlay 2's plist-packaged metadata into the hub — under the 5.6 pin the hub ignored it, and the notification got no title, artist or artwork with AirPlay 2 senders.
- Wi-Fi: a high-performance Wi-Fi lock keeps the receiver reachable with the screen off up to Android 13 (see the README's [Limitations](README.md#limitations)).

## Audio tap

Another app can take what the receiver plays, as it plays it, and do what it likes with it: only apps signed with the same key can (the tap's permission is `signature`); the receiver doesn't know or care what the app does, and its status shows which app taps it.

The app binds the service `com.hkfuertes.shairport.AUDIO_TAP` (in `com.hkfuertes.shairport`, with `<queries>` for it) and sends its `Messenger` a message (`what` 1) with:

- `socket`: one end of a `SOCK_SEQPACKET` socket pair (`Os.socketpair(AF_UNIX, SOCK_SEQPACKET, ...)`; `ParcelFileDescriptor`'s pairs are streams, which the engine refuses); the app reads the other end, a message per read.
- `lead` (float, 0.5 to 1 s): how early the audio leaves before it's heard, the AAudio buffer's length; 0.5 s is the receiver's own.
- `silent` (boolean): this device plays zeros meanwhile. Its AAudio output keeps running, so its clock still paces Shairport, and the app's audio with it.
- `only_44100` (boolean): AirPlay 2 at 44.1 kHz only: no lossless (48 kHz ALAC), 48 kHz AAC or surround. Buffered audio stays (44.1 kHz AAC and ALAC).

A change of `lead` or `only_44100` restarts the engine (a first tap usually does); the socket and `silent` apply at once. A new message replaces the tap. Unbinding, or the app dying, ends it, and this device plays again.

The engine then sends a message per buffer AAudio plays, in native byte order (little-endian): `int32 type`, `int32 value`, `int64 time`, then, for audio, its frames.

| `type` | `value` | `time` | then |
|---|---|---|---|
| 0: audio | the rate (Hz) | when its first frame is heard: `System.nanoTime()` (`CLOCK_MONOTONIC`, the same clock in every process), on the sender's timeline (AirPlay 2's PTP included) | 16-bit stereo frames |
| 1: flush (pause, skip, stop) | 0 | 0 | |
| 2: volume | percent | 0 | |

The volume is 100 while AirPlay volume goes into the audio. With **Link Android music volume** the audio is at full scale and the volume is Android's music volume, however it is set. Sending never blocks Shairport: a socket the app doesn't read in time drops messages.
