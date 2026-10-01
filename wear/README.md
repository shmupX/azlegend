# AZ Legend for Wear OS

A standalone Wear OS music player for the AZ Legend catalog. It downloads albums from
[azlegend.easierbycode.deno.net](https://azlegend.easierbycode.deno.net) to the watch and plays them
offline, with the system media controls, Bluetooth headphones, and the crown for volume. Built for a
Google Pixel Watch 5 (Wear OS 7) and installed by sideloading; no Play Store, no phone app.

## What it does

- **Library** lists every album from `/public/music/albums.json` (a copy is bundled, "Sync catalog"
  refreshes it) plus any folders of audio files you copy onto the watch yourself.
- **Album** screen plays the album, downloads it (one album at a time, over Wi-Fi when the watch can
  get it, otherwise through the phone link), or deletes the downloaded files.
- **Player** shows the track, progress, previous/play/next, volume (crown or buttons), and the
  current audio output. Playback runs in a Media3 `MediaSessionService`, so it keeps going when the
  screen is off and shows up in the watch's media controls and the watch-face chip.
- Tracks that are not downloaded can still be streamed when the watch is online.
- **Added** holds songs added remotely from the site's `/add` page. They arrive
  through the same catalog sync as everything else, so no new app build is needed
  to add a song. The library refreshes itself on resume (at most every 5 minutes,
  silently), so a song added on a phone shows up on its own; "Sync catalog"
  fetches it immediately.

- **Desktop music** (at the bottom of the library) is a remote for a paired desktop launcher: it
  lists the albums of the AZ Legend player docked there and switches the song playing on the
  desktop, with previous / pause / next. See [Desktop music remote](#desktop-music-remote).

## Build

Requires JDK 17 and the Android SDK (platform 36, build-tools 36). `local.properties` with
`sdk.dir=…` is git-ignored, so create it or export `ANDROID_HOME`.

```sh
cd wear
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # shrunken APK, signed with the debug key for sideloading
./gradlew :app:testDebugUnitTest
```

## Install on the watch (Pixel Watch 5)

Wear OS 3+ only supports ADB over Wi-Fi, so the watch and your computer must be on the same Wi-Fi
network. The watch only joins 2.4 GHz networks that are saved on the paired phone, and it often stays
on the phone's Bluetooth link instead; if "Wireless debugging" never shows an address, turn the phone's
Bluetooth off for a minute so the watch falls back to Wi-Fi.

1. On the watch: **Settings > System > About > Versions**, tap **Build number** seven times.
2. **Settings > Developer options**: turn on **ADB debugging** and **Wireless debugging**.
3. **Wireless debugging > Pair new device** shows an address, a port and a six-digit code. On the
   computer (`adb` from `platform-tools` 30+):

   ```sh
   adb pair 192.168.1.42:37123      # pairing address:port, then type the code
   adb connect 192.168.1.42:42451   # the connection address:port shown on the main screen
   adb devices
   adb install -r -g wear/app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n com.azlegend.wear/.MainActivity
   ```

   `-g` grants the notification permission up front; without it the app asks on first launch
   (Wear OS 4+ starts new apps with notifications off, so download progress would be invisible).
   Pairing is one-time; `adb connect` is needed again after each reboot, and the port changes.

## Putting your own files on the watch

Any audio files (mp3, m4a, aac, ogg, opus, flac, wav) copied into the app's Music folder appear in the
library under **On this watch**, one album per sub-folder. Launch the app once first so the folder exists.

```sh
adb push ~/Music/Midas\ II/ /sdcard/Android/data/com.azlegend.wear/files/Music/
```

If Wear OS refuses to write there (`Permission denied`), use the app's private folder instead:

```sh
adb push track.mp3 /data/local/tmp/track.mp3
adb shell run-as com.azlegend.wear sh -c 'mkdir -p files/Music/Midas_II && cp /data/local/tmp/track.mp3 files/Music/Midas_II/'
```

## Desktop music remote

Separate from playback on the watch: **Library > Desktop music** drives the AZ Legend player docked
in a [cmg launcher](https://github.com/easierbycode/cmg) on another machine.

1. In the launcher, open **Settings > WATCH REMOTE** and switch it on. It shows an eight-character
   code such as `ABCD-EFGH`.
2. On the watch, open **Desktop music**, type the code and confirm. It is remembered; **Unpair** is at
   the bottom of the list.

```
watch --(internet)--> Firebase Realtime Database <--(internet)-- launcher --> AZ Legend player
```

The watch and the desktop never connect to each other. Both talk to the database over plain REST
under the pairing code, so they need not share a network and the desktop needs no open port:

```
watch   --> /builders/<code>/music/launch    one slot, latest press wins
watch   --> /builders/<code>/music/control   pause, resume, next, prev, sync
desktop --> /builders/<code>/music/playing   what its player is doing
desktop --> /builders/<code>/music/library   the albums it can play
```

The desktop half is `static/watch-music.js` in the cmg repo, and the same protocol is spoken by the
[watchAmp](https://github.com/shmupX/watchAmp) app, so either watch app can drive the same launcher.
The code is in `remote/` (pairing code, wire format, stream client) and `ui/RemoteScreen.kt`.

- A song is not shown as switched until the desktop names the press; a press nobody answers says
  "No reply from desktop" after 12 seconds rather than pretending.
- The stream's first frame is whatever the last session left in the database, so "desktop is there"
  waits for a write that arrives after it.
- The pairing code is the only secret: the database is open to anyone who knows it. Everything read
  from it is treated as untrusted (bounded, de-duplicated), and the launcher only plays tracks its
  own player reported.
- The stream is held only while the app is in the foreground.
- No remote volume: the desktop player's protocol has no command for it.

Testing it over adb has two traps: `adb shell input text` shows up in the watch keyboard's preview
but never reaches the text field (tap the on-screen keys by coordinate instead), and the keyboard
can take several seconds to appear (poll `dumpsys input_method | grep mInputShown=true`).

## Audio output

Following Google's Wear guidance, the player does not start music on the watch speaker by accident.
With no Bluetooth headphones connected, pressing play opens the system output switcher, where you can
connect headphones or explicitly pick the watch speaker; playback resumes once an output is chosen.
The output button on the player screen opens the same switcher (or Bluetooth settings).

## Storage layout

| Path | Contents |
| --- | --- |
| `files/music/<albumId>/` | downloaded tracks (private, deleted on uninstall) |
| `files/catalog/` | cached copy of the site's album JSON |
| `Android/data/com.azlegend.wear/files/Music/` | your own files, pushed with `adb push` |
| `files/Music/` | your own files, pushed with `adb shell run-as` |

## Stack

Kotlin 2.3, Compose for Wear OS Material 3 1.6, Media3 1.11 (ExoPlayer + MediaSession),
kotlinx.serialization, a dataSync foreground service for downloads, AGP 8.13 / Gradle 8.14, minSdk 30,
target/compile SDK 36.
