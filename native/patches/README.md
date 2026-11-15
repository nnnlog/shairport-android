# Patches

The Dockerfile's `engine-src` stage applies each directory's patches, in file-name order, to the upstream commit it pins (with its SHA-256). Don't rebase them onto another revision silently.

## shairport-sync

- `0001`–`0003`: Bionic library discovery (`AC_SEARCH_LIBS`), cooperative pthread cancellation and shared-memory compatibility, copied from `shairport-echo` commit `395aded` (before that project moved to static musl). The cancellation shim wakes threads with `SIGRTMIN`: ART keeps `SIGUSR1` blocked in an app process.
- `0004`: the metadata socket sender without `bzero`, which Bionic lacks.
- `0005`: the cancellation checks Bionic needs in AirPlay 1 receivers, the activity monitor, the metadata queue and the player's frame wait. Without them a stop, or a sender ending its session, never finishes.
- `0006`: configurable `general.model`.
- `0007`: `aaudio` output backend (`--with-aaudio`): plays through AAudio (`libaaudio.so`, loaded at run time) and reports the real output delay from `AAudioStream_getTimestamp()`, so Shairport keeps sync as with ALSA.
- `0008`: Shairport Sync as the app's JNI library (`android.c`: `main()` becomes `shairport_main()`, stderr goes to logcat, the process ends with `_exit()` after Shairport's own cleanup) and the `android` mDNS backend, which registers the services with the app's `NsdManager`. `get_device_id()` no longer waits 10 s for a MAC address, which an app can never read.
- `0009`: the audio tap. The `aaudio` backend hands every timed buffer, with the time Shairport says it is heard, to `android.c`, which sends it to the socket another app gave the app (`Engine.audioTap`), on `CLOCK_MONOTONIC` (Java's `System.nanoTime()`), with a flush on pause or skip and the volume to play at (`Engine.tapVolume`). `SOCK_SEQPACKET`, one message per send, never blocking the player thread. The AAudio buffer holds the whole desired length, the tap's head start. Silent, AAudio gets zeros instead (same clock), until the other app goes away. Off without a socket.
- `0010`: `--with-remote-control`: Shairport's metadata hub and remote control without D-Bus, MPRIS or MQTT. A hub watcher hands the track, its cover art file, the player's state, duration and position, the AirPlay volume and whether the session is AirPlay 2 to `Engine.nowPlaying`; `Engine.remoteCommand` and `Engine.remoteVolume` drive the sender, AirPlay 2 only (classic AirPlay's DACP needs mDNS browsing). The DACP monitor gets the cancellation check Bionic needs.

- `0011`: AirPlay 2's low-latency stream, the one a Mac uses for its system audio (`general.airplay_2_low_latency_mode`, off by default): AAC-ELD in APAP packets over UDP (APAT), with redundancy and retransmission feedback (RFC 8888 CCFB); the anchor and flush times come as RTSP requests, as for a buffered stream; played through Shairport's player. With the option on, buffered streams (e.g. from the Music app) come in the APAP packet format over TCP, which is received too. Generated from the [shairport-sync fork](https://github.com/nnnlog/shairport-sync) by `import-low-latency.sh <fork>`, which rebases the fork's changes onto 0001–0010; edit the fork, not this file.
- `0012`: the `aaudio` backend opens a low-latency AAudio stream (`PERFORMANCE_MODE_LOW_LATENCY`, a buffer of whole bursts up to the low-latency desired length) while a low-latency stream plays: the default stream moves audio in 250 ms bursts on some devices, more than the low-latency stream's whole lead.

## nqptp

- `0001`–`0002`: Bionic library discovery and shared-memory compatibility, including `NQPTP_SHM_DIRECTORY` for Android app storage.
- `0003`: the default shared-memory interface is created at start, as up to 1.2.8: 1.2.9 waits for Shairport's first message, and the app waits for the file, which NQPTP creates once it holds the PTP ports, before starting Shairport.

Never copy code from `echo-airplay2`: its licence forbids commercial use.
