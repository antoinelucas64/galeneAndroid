# Galène Android

[![MIT licence](https://img.shields.io/badge/licence-MIT-blue.svg)](LICENSE)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white)
![No Google Play Services](https://img.shields.io/badge/Google%20Play%20Services-not%20required-success)
![Status: prototype](https://img.shields.io/badge/status-prototype-orange)

A lightweight, privacy-friendly, open-source native Android client for the
[Galène](https://galene.org) videoconferencing server.

**[⬇ Download the APK (v0.1)](https://antoineassocies.fr/galene-0.1.apk)** — Android 8.0 or later.

---

## Why

On a phone, Galène is normally used through its web client. Mobile browsers cut audio
and video as soon as the screen turns off or you switch apps, and offer no system
integration. This project provides a native Android client in the spirit of Galène:

- **Lightweight**: a single Activity, few dependencies, fast start-up.
- **Privacy-friendly**: no telemetry, no network traffic other than to the Galène server you choose.
- **Google-free**: runs on LineageOS, GrapheneOS and /e/OS, without Google Play Services.
- **Auditable**: documented Kotlin code and a simple architecture (see [ARCHITECTURE.md](ARCHITECTURE.md)).
- **Free software**: MIT licence; F-Droid publication planned.

### Relation to the Android client on galene.org

Juliusz Chroboczek, Galène's author, distributes his own native Android client at
[galene.org/android](https://galene.org/android/). It is audio-only, supports screen
sharing, and its sources are not published. This project is an independent effort:
open source from the start, with video, and a login flow modelled on the web client.
It is not affiliated with the Galène project.

## Features

| | |
|---|---|
| ✅ Public group list; login by password, invitation link or authentication server (YunoHost…) | ✅ Two-way audio with echo cancellation |
| ✅ Two-way video, front / back camera | ✅ Adaptive participant grid, tap for fullscreen |
| ✅ Mute microphone / camera (the camera really turns off) | ✅ Call kept alive in the background (notification) |
| ✅ Camera paused in the background, resumed on return | ✅ Automatic connection restart on ICE failure |
| ✅ Screen kept on during the call | ✅ Call resumes after an incoming phone call |
| ✅ Interface in the phone's language (19 languages) | ⏳ Reconnection after network loss |
| ⏳ Bluetooth headset microphone, wired headset routing | ✅ Group chat: history, clickable links, unread badge, operator notices |
| ✅ Private chat messages (pick a recipient, tap to reply) | ⏳ Raise hand, highlight of who is speaking |
| ✅ Picture-in-Picture with mute and hang-up buttons | ⏳ Participant list; operator actions (mute, allow presenting, operator rights, remove) |
| ⏳ Lower video quality received on mobile data | ⏳ Android call integration (ConnectionService), screen sharing |

⏳ = implemented, not yet tested on a real device.

The interface follows the phone's language (Android 13+ also lets you pick it per app):
English, French, German, Spanish, Italian, Portuguese (PT/BR), Dutch, Polish, Russian,
Ukrainian, Swedish, Turkish, Chinese (simplified/traditional), Japanese, Korean, Hindi and
Arabic. Translations other than French and English are machine-assisted: corrections are
welcome in `app/src/main/res/values-*/strings.xml`.

## Installation

Download [galene-0.1.apk](https://antoineassocies.fr/galene-0.1.apk) on your phone and
open it. Android asks you to allow installing apps from this source (your browser or
file manager) the first time.

You can also build the APK yourself (see [Building](#building)). F-Droid publication is
in progress.

## Usage

**Full user guide: [English](docs/user-guide.md) · [Français](docs/guide-utilisateur.md)** — joining, in-call controls, chat, participants, operator actions, Picture-in-Picture, troubleshooting.

In short, the flow is the same as in Galène's web client.

1. **Server** — enter the server address (`galene.example.org`): the app lists its public
   groups. An unlisted group can be joined by name. The last server used is remembered.
2. **Group** — pick a group, then enter your user name, password, and what you publish on
   joining (*Nothing*, *Microphone*, *Camera + microphone*). The form adapts to the group:
   - plain Galène password;
   - **authentication server** (`authServer`, e.g. YunoHost accounts): your credentials
     are checked by that server, which issues an access token;
   - web portal (`authPortal`): not supported yet, use an invitation link instead.
3. **Room** — if the server refuses you, you are taken back to the form with the reason.

**With an invitation link** — paste it straight into the server field:

```
https://galene.example.org/group/my-room/?token=ABCDEF
```

The user name then becomes optional; it is only asked for if the link requires it.

The app only requests the permissions your choice needs (microphone, camera, and
notifications on Android 13+). Passwords are never stored.

In the room, tap a participant to see them fullscreen; tap again or press back to
return to the grid.

### Server side

Any Galène server speaking version 2 of the protocol works. For phones on mobile data or
behind NAT, a **working TURN server** is essential: without one, direct connections
often fail. See the [Galène documentation](https://galene.org) on the built-in TURN
server or coturn.

## Building

### Requirements

| Tool | Version |
|------|---------|
| JDK | 17 or 21 |
| Android SDK | platform `android-35`, build-tools `35.0.0` |
| Gradle | wrapper included |

Android Studio is not required: everything works from the command line.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android
export PATH=$PATH:$ANDROID_HOME/platform-tools

git clone https://github.com/antoinelucas64/galeneAndroid.git
cd galeneAndroid
./gradlew assembleDebug          # → app/build/outputs/apk/debug/app-<abi>-debug.apk
```

### Installing on a phone

Enable USB debugging on the phone (the emulator handles WebRTC poorly), then:

```bash
adb devices                      # the phone must be listed
./gradlew installDebug
```

### Following the logs

```bash
adb logcat -s GaleneClient WebRTCManager AudioRouter
```

- `GaleneClient`: signaling messages exchanged with the server (`→` sent, `←` received);
- `WebRTCManager`: ICE servers, local candidates, connection state of each stream;
- `AudioRouter`: available audio outputs and the one chosen.

Debug builds also show this signaling trace in a line at the top of the room screen.

After a native crash (libwebrtc), the cause is under the `rtc`, `libc` and `DEBUG` tags:

```bash
adb logcat -b crash -d
```

### Release build

```bash
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-<abi>-release-unsigned.apk

# Signing (create the keystore once)
keytool -genkey -v -keystore galene-release.jks -alias galene -keyalg RSA -keysize 4096 -validity 10000
$ANDROID_HOME/build-tools/35.0.0/apksigner sign --ks galene-release.jks \
  --out galene-arm64-v8a.apk app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk
```

One APK is produced per processor family, since WebRTC's native library is most of
the size (about 7 MB each, against 22 MB for the `universal` one that contains them all):

| APK | Phones | versionCode |
|---|---|---|
| `arm64-v8a` | nearly all phones since 2017 | `versionCode × 10 + 2` |
| `armeabi-v7a` | older 32-bit phones | `× 10 + 1` |
| `x86`, `x86_64` | emulators, some Chromebooks | `× 10 + 3`, `× 10 + 4` |
| `universal` | all | `× 10 + 0` |

For Google Play, build an Android App Bundle instead and sign it with the same keystore
(`jarsigner` comes with the JDK; drop `-sigalg` if the key is not RSA):

```bash
./gradlew bundleRelease
# → app/build/outputs/bundle/release/app-release.aab

keytool -list -keystore galene-release.jks    # shows the key alias
jarsigner -sigalg SHA256withRSA -digestalg SHA-256 -keystore galene-release.jks \
  -signedjar app/build/outputs/bundle/release/app-release-signed.aab \
  app/build/outputs/bundle/release/app-release.aab galene
```

The bundle's versionCode is `versionCode × 10`, like the universal APK; Google Play
then serves each phone the native library for its processor. With Play App Signing,
this keystore is only the upload key: the APKs installed from Play are signed by
Google, so they cannot update an F-Droid or self-built install (and vice versa).

The release build is reproducible: built twice from the same commit in different
directories, the unsigned APKs are identical byte for byte. Another machine needs the
same JDK and build-tools versions to get the same result.

### Tests

```bash
./gradlew :app:testDebugUnitTest
```

Unit tests run anywhere. Integration tests check the signaling protocol against a real
Galène server (on the JVM, no phone needed); they are skipped unless a server is provided:

```bash
export GALENE_TEST_URL=wss://galene.example.org/ws
export GALENE_TEST_TOKEN=<the group's invitation token>
export GALENE_TEST_GROUP=public        # optional
./gradlew :app:testDebugUnitTest
```

## Architecture

```
UI (Jetpack Compose)  ServerScreen · GroupLoginScreen · RoomScreen · VideoGrid
        │
ViewModels            ServerViewModel · GroupLoginViewModel · RoomViewModel
        │
Domain                GaleneDirectory (HTTP) · GaleneClient (WebSocket) · WebRTCManager · AudioRouter
        │
Android services      CallForegroundService (call in the background)
```

| Component | Choice |
|-----------|--------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| WebRTC | [`io.github.webrtc-sdk:android`](https://github.com/webrtc-sdk/android) (libwebrtc without Play Services) |
| Signaling | OkHttp WebSocket |
| JSON | kotlinx.serialization |
| Dependency injection | Koin |

Packages, the implemented subset of the Galène protocol and the data flows are detailed
in [ARCHITECTURE.md](ARCHITECTURE.md).

## Roadmap

- [x] **Phase 1 — Audio MVP**: connection, authentication, two-way audio
- [x] **Phase 2 — Video and UX**: video, permissions, grid, controls, lifecycle, Galène-style login
- [ ] **Phase 3 — Robustness**: ~~background calls~~ ✓, ~~screen kept on~~ ✓, ~~incoming phone calls~~ ✓, network reconnection, audio routing (Bluetooth microphone, wired headset)
- [ ] **Phase 4 — System integration**: ConnectionService, UnifiedPush, screen sharing, ~~PiP~~ ✓
- [ ] **Phase 5 — Distribution**: ~~multilingual UI~~ ✓, F-Droid (submitted), ~~reproducible builds~~ ✓, ~~user documentation~~ ✓

## Contributing

Feedback and contributions are welcome: open an
[issue](https://github.com/antoinelucas64/galeneAndroid/issues) or a pull request.
Please attach `adb logcat -s GaleneClient WebRTCManager` output to connection bug
reports, with tokens and passwords masked.

Discussions about the Galène protocol itself happen on the
[Galène mailing list](https://lists.galene.org).

## Author

**Antoine Lucas** — [Antoine & Associés](https://antoineassocies.fr)

Independent project, not affiliated with the Galène project. Galène is developed by
Juliusz Chroboczek (Université Paris Cité) and its contributors.

## Licence

[MIT](LICENSE) © 2026 Antoine Lucas — Antoine & Associés
