# Architecture — Galène Android

This document describes how the code is organised, what each package is responsible for,
and how data flows between components.

---

## Overview

```
┌──────────────────────────────────────────────────────────────┐
│                    UI  (Jetpack Compose)                     │
│  ServerScreen → GroupLoginScreen → RoomScreen + VideoGrid    │
│  (server, groups)    (login)         (video grid, controls)  │
└───────────────────────────┬──────────────────────────────────┘
                            │ StateFlow<RoomUiState>
┌───────────────────────────▼──────────────────────────────────┐
│                      RoomViewModel                           │
│  Drives signaling and WebRTC · exposes UI state              │
└──────────┬────────────────────────────────────┬──────────────┘
           │                                    │
┌──────────▼──────────┐            ┌────────────▼─────────────┐
│    GaleneClient     │            │      WebRTCManager       │
│  OkHttp WebSocket   │            │ libwebrtc PeerConnections│
│  signaling protocol │            │  uplink / downlinks      │
└──────────┬──────────┘            └────────────┬─────────────┘
           │ WS over TLS                        │ DTLS/SRTP over UDP
┌──────────▼──────────┐            ┌────────────▼─────────────┐
│   Galène server     │◄──────────►│   Galène server (SFU)    │
│   (signaling /ws)   │            │   (media relay)          │
└─────────────────────┘            └──────────────────────────┘
```

Galène is an SFU (*Selective Forwarding Unit*): each client opens **one** signaling
WebSocket and **one or more** WebRTC connections with the server (one for upload, one per
received stream).

---

## Package layout

```
app/src/main/java/fr/antoineassocies/galene/
│
├── GaleneApp.kt                   # Application entry point, initialises WebRTC and Koin
├── MainActivity.kt                # Single Activity, hosts GaleneNavHost
│
├── di/
│   └── AppModule.kt               # Koin dependency graph
│                                  # (OkHttpClient, Json, EglBase, PeerConnectionFactory,
│                                  #  GaleneDirectory, UserPreferences, GaleneClient,
│                                  #  WebRTCManager, AudioRouter, ViewModels)
│
├── data/
│   └── UserPreferences.kt         # Last server and last user name (never a secret)
│
├── domain/                        # Business layer (no UI)
│   ├── AudioRouter.kt             # Communication mode + loudspeaker during the call
│   ├── ConnectionState.kt         # Enum: Connecting | Connected | Disconnected
│   ├── GaleneDirectory.kt         # HTTP: public groups, group status, authServer
│   ├── GaleneClient.kt            # WebSocket + protocol message dispatch
│   ├── SignalingEvent.kt          # Sealed class: internal signaling events
│   ├── WebRTCManager.kt           # PeerConnections (up/downlinks), microphone, camera,
│   │                              # video tracks exposed to the UI
│   │
│   ├── model/
│   │   ├── GaleneLink.kt          # Free-form input → server or group (+ token); GroupTarget
│   │   ├── GroupStatus.kt         # Public status of a group (.status / public-groups.json)
│   │   ├── JoinRequest.kt         # Room join, authentication resolved
│   │   ├── Participant.kt         # A peer in the room
│   │   └── PresentMode.kt         # Nothing / Microphone / Camera + microphone
│   │
│   └── protocol/
│       └── Messages.kt            # @Serializable data classes for the Galène protocol
│                                  # (HandshakeMsg, JoinMsg, OfferMsg, AnswerMsg, …)
│
├── service/
│   └── CallForegroundService.kt   # "microphone" foreground service:
│                                  # keeps the microphone alive in the background
│
└── ui/
    ├── GaleneNavHost.kt           # Navigation: server → group → room
    │
    ├── components/
    │   └── VideoGrid.kt           # Tile grid, fullscreen tile, VideoRenderer (SurfaceViewRenderer)
    │
    ├── screens/
    │   ├── ServerScreen.kt        # Home: server or link, public groups
    │   ├── GroupLoginScreen.kt    # Group page: name, password, what to publish
    │   └── RoomScreen.kt          # Room: grid, controls, permissions,
    │                              # foreground / background, fullscreen
    │
    ├── theme/
    │   ├── Color.kt
    │   ├── Theme.kt               # Material 3 GaleneTheme
    │   └── Type.kt
    │
    └── viewmodel/
        ├── ServerViewModel.kt     # Server input, public group list
        ├── GroupLoginViewModel.kt # Group status, authentication resolution
        └── RoomViewModel.kt       # UI state + signaling/WebRTC orchestration
```

---

## Interactions between packages

### 1. App start-up

```
GaleneApp.onCreate()
    ├─ PeerConnectionFactory.initialize()
    └─ Koin.start(appModule)
            │
MainActivity.onCreate()
    └─ setContent { GaleneNavHost() }
            │
GaleneNavHost
    └─ NavHost(startDestination = "server")
            └─ ServerScreen
```

### 2. Joining a room (same flow as Galène's web client)

```
ServerScreen / ServerViewModel
    input "galene.example.org"  → GET /public-groups.json → group list
    input of a group link       → straight to the next step (with its token, if any)
        │ GroupTarget(location, token?)
GroupLoginScreen / GroupLoginViewModel
    GET <location>.status  → display name, description, endpoint, authServer, authPortal
    form: name · password · publish (Nothing / Microphone / Camera + microphone)
    authentication resolution:
        invitation token  → join { token }
        authServer        → POST {location, username, password} → JWT → join { token }
                            (HTTP 204: join { password }, as the web client does)
        otherwise         → join { password }
        authPortal        → not supported (message)
        │ JoinRequest(endpoint, group, username, password|token, present)
        ├─ holder.request = request
        └─ navController.navigate("room")
                │
        RoomScreen(request)
                │
                └─ koinViewModel<RoomViewModel>(params)
                        │
                RoomViewModel.init
                        ├─ galeneClient.connect(endpoint)     # opens the WebSocket
                        ├─ observeSignaling()                 # collects galeneClient.events
                        └─ observeConnectionState()           # collects galeneClient.connectionState
```

### 3. Signaling flow (GaleneClient → RoomViewModel)

```
GaleneClient                         RoomViewModel
    │                                    │
    │  WS onOpen                         │
    ├──► Connected event ────────────────► sendJoin(group, username, password | token)
    │                                    │
    │  "joined" kind=join                │
    ├──► Joined event ───────────────────► applyRtcConfiguration() + audioRouter.start()
    │                                    ├─ sendRequest({"" : ["audio", "video"]})
    │                                    └─ maybeStartUplink()  ────────────┐
    │                                       (waits for joined AND permissions)
    │                                    WebRTCManager                      │
    │                                    ├─ microphone track (if allowed)   │
    │                                    ├─ camera track (if allowed)       │
    │                                    ├─ startUpstream(streamId, label)  │
    │                                    │    └─ PeerConnection.createOffer()│
    │                                    │                                   │
    │                                    └───────────────────────────────────┘
    │                                         sdpOffer
    │                                    galeneClient.sendOffer(streamId, label, sdpOffer)
    │                                         │
    │  "answer" (SFU reply)                   │
    ├──► RemoteAnswer ───────────────────► webRTCManager.handleUpstreamAnswer()
    │                                         └─ pc.setRemoteDescription()
    │
    │  "ice" (server ICE candidates)
    ├──► RemoteIce ──────────────────────► webRTCManager.addRemoteIceCandidate()
    │
    │  "offer" (another participant's stream)
    ├──► RemoteOffer ────────────────────► webRTCManager.handleRemoteOffer()
    │                                         ├─ pc.setRemoteDescription()
    │                                         ├─ pc.createAnswer()
    │                                         └─ galeneClient.sendAnswer()
    │
    │  "user" kind=add/change/delete
    ├──► UserUpdate ─────────────────────► handleUserUpdate() → participant list
    │
    │  "renegotiate" (ICE failure seen by the server)
    └──► Renegotiate ────────────────────► restartUplinkIce() → offer kind=renegotiate
```

### 3a. ICE failure recovery

Modelled on the web client (`static/protocol.js`): as soon as a PeerConnection reaches
`IceConnectionState.FAILED`, `WebRTCManager.onIceFailed` notifies the ViewModel, which

- for the **up** stream, sends a new offer with `IceRestart` (same `id`, `kind=renegotiate`);
- for a **down** stream, sends `{"type":"renegotiate","id":…}`; the server replies with
  a new offer on the same PeerConnection.

The server also sends `renegotiate` for the up stream after its own delay; a second
restart is harmless.

### 3b. Background and lifecycle

| Event | Effect |
|-------|--------|
| Microphone allowed (app visible) | `CallForegroundService.start()` — "microphone" service + notification |
| `ON_STOP` (app hidden) | `onAppBackground()`: camera capture stops, sound goes on |
| `ON_START` (back) | `onAppForeground()`: camera restarted if the background had stopped it |
| Screen rotation | Activity not recreated (`configChanges`), nothing to do |
| `leave()` / ViewModel cleared | WebRTC released, audio restored, service stopped |
| App swiped from recent tasks | `onTaskRemoved` → service stopped, call ended |

Android 14 only allows starting a "microphone" service while the app is visible and
RECORD_AUDIO is granted: that is why it is started in `onPermissionsResult`, never from
the background.

### 4. WebRTCManager — uplink vs downlinks

```
WebRTCManager
│
├── upStream: UpStream?                    # our stream to the SFU (label "camera")
│   └── PeerConnection (sendonly, single BUNDLE)
│       ├─ localAudioTrack (microphone)
│       ├─ localVideoTrack (camera 640×480@30, Camera2)
│       └─ ICE ──► (TURN) ──► SFU
│
└── downStreams: Map<streamId, DownStream> # one per stream published by the others
    └── PeerConnection (recvonly)
        └─ ICE ──► (TURN) ──► SFU ──► received audio + video tracks

To the UI (StateFlow, fed from libwebrtc's signaling thread):
├── localVideoTrack: VideoTrack?             # local preview
└── remoteVideoTracks: Map<streamId, VideoTrack>
```

Received audio is played directly by libwebrtc (Java audio module); `AudioRouter` puts the
phone in communication mode on the loudspeaker during the call.

Video is rendered by `SurfaceViewRenderer`s (in `VideoGrid`) sharing the factory's EGL
context, which enables hardware encoding and decoding. Only the local preview is mirrored
(front camera); the stream sent to others is not, as in every videoconferencing client.

ICE candidates emitted by the `PeerConnection`s are intercepted through
`onLocalIceCandidate` and forwarded immediately to `GaleneClient.sendIce()`.

Server candidates that arrive before the `remoteDescription` is set, **or while it is
being applied** (`settingRemote`), are buffered in `pendingCandidates` and drained right
after. Without this, candidates closely following an answer or an ICE restart were lost
or attached to the previous ICE generation.

### 5. Dependency injection (Koin)

```
appModule
│
├── single<OkHttpClient>          # shared by GaleneClient and GaleneDirectory
├── single<Json>                  # encodeDefaults=true, ignoreUnknownKeys=true
├── single<EglBase>               # EGL context shared by capture / codecs / rendering
├── single<PeerConnectionFactory> # hardware video codecs; initialised once
├── single<GaleneDirectory>, single<UserPreferences>
│
├── factory<GaleneClient>         # new instance per session
│                                 # (no state leaking between calls)
├── factory<WebRTCManager>
├── factory<AudioRouter>
│
├── viewModel<ServerViewModel>
├── viewModel<GroupLoginViewModel>(params)   # parameter: GroupTarget
└── viewModel<RoomViewModel>(params)         # parameter: JoinRequest
```

`factory` (rather than `single`) for `GaleneClient` and `WebRTCManager`: each call in a
new `RoomScreen` gets a fresh instance with no leftover state.

---

## Galène protocol — implemented messages

Defined in `domain/protocol/Messages.kt` and dispatched in `GaleneClient.handleMessage()`.

### Client → Server

| Type | Class | When it is sent |
|------|-------|-----------------|
| `handshake` | `HandshakeMsg` | When the WebSocket opens |
| `join` kind=join | `JoinMsg` | After the handshake is confirmed |
| `join` kind=leave | `LeaveMsg` | On disconnection |
| `request` | `RequestMsg` | After joined, to request streams |
| `offer` | `OfferMsg` | When the uplink SDP is ready; `kind=renegotiate` for an ICE restart |
| `answer` | `AnswerMsg` | In reply to a server offer |
| `ice` | `IceMsg` | For each local ICE candidate |
| `renegotiate` | `RenegotiateMsg` | ICE failure on a down stream |
| `pong` | `PongMsg` | In reply to a server `ping` |

### Server → Client

| Type | Class | Action in RoomViewModel |
|------|-------|-------------------------|
| `handshake` | `ServerHandshakeMsg` | Checks version=["2"], emits `Connected` |
| `joined` kind=join | `JoinedMsg` | Emits `Joined`, starts the uplink |
| `joined` kind=fail | `JoinedMsg` | Emits `JoinFailed` (code + message), back to the group form |
| `joined` kind=leave | `JoinedMsg` | Emits `JoinLeft`, clears participants |
| `user` | `UserMsg` | Emits `UserUpdate`, updates the list |
| `offer` | `ServerOfferMsg` | Emits `RemoteOffer`, creates (or renegotiates) a downlink |
| `answer` | `ServerAnswerMsg` | Emits `RemoteAnswer`, completes the uplink |
| `ice` | `ServerIceMsg` | Emits `RemoteIce`, adds an ICE candidate |
| `close` | `CloseMsg` | Emits `StreamClose`, closes a downlink |
| `abort` | `AbortMsg` | Emits `StreamAbort`, closes the uplink |
| `renegotiate` | `RenegotiateMsg` | Emits `Renegotiate`, ICE restart of the uplink |
| `ping` | — | Answered with `pong` directly in GaleneClient |

### HTTP endpoints (GaleneDirectory)

| Request | Purpose |
|---------|---------|
| `GET /public-groups.json` | Public groups listed on the home screen |
| `GET /group/<name>/.status` (fallback `.status.json`) | Group status: endpoint, authentication mode |
| `POST <authServer>` `{location, username, password}` | Token (`application/jwt`) or 204 (use the password) |

---

## UI navigation

```
GaleneNavHost                          NavigationHolder (Activity-scoped ViewModel)
│                                          target: GroupTarget?
├── "server" → ServerScreen ─────────────► request: JoinRequest?
├── "group"  → GroupLoginScreen(target) ─► joinFailure: JoinFailed?
└── "room"   → RoomScreen(request)
                 │ joined kind=fail
                 └─ back to "group" with the reason; `need-username` → the name
                    becomes required, otherwise the token is dropped (as on the web)
```

Parameters **do not go through routes**: tokens and passwords have no business in the
back stack's saved state, and an empty argument does not match a route.

`NavigationHolder` survives configuration changes but not process death. In that case,
the `group` and `room` destinations fall back to `server`.

Inside the room, tapping a tile shows it fullscreen (`FullscreenTile`, system bars
hidden); a tap or the back button returns to the grid, and the view falls back to the
grid by itself if that stream disappears.

---

## Tests

```
app/src/test/java/fr/antoineassocies/galene/
├── domain/
│   └── GaleneLinkTest.kt        # Interpretation of server / group / invitation input
└── integration/
    ├── GaleneTestClient.kt      # Pure JVM signaling client (no android.util.Log)
    │                            # Reuses the types from domain/protocol/
    └── GaleneIntegrationTest.kt # 8 tests against a real server (env vars, skipped otherwise)
```

JVM tests (`src/test/`) can reuse `domain/protocol/Messages.kt` and
`domain/SignalingEvent.kt` because they have no Android dependency. `GaleneClient` itself
cannot be reused (`android.util.Log`) — hence `GaleneTestClient`, its pure JVM equivalent.

Instrumented tests (real WebRTC layer, audio/video streams) need a physical device and
will live in `src/androidTest/` — not implemented yet.

---

## Known pitfalls (and why the code looks the way it does)

| Symptom | Cause | Fix |
|---------|-------|-----|
| `SIGABRT` on `network_thread` when creating a PeerConnection | libwebrtc's NetworkMonitor throws a `SecurityException` without `ACCESS_NETWORK_STATE` | Permission declared in the manifest |
| Remote audio inaudible | Android plays call audio through the earpiece | `AudioRouter`: communication mode + loudspeaker |
| Microphone silent in the background | Android 11+ cuts the microphone of apps that are not visible | `microphone`-type `CallForegroundService` |
| Connection never established after an ICE restart | Candidates applied to the old remote description | `settingRemote` buffer in `WebRTCManager` |
| Crash on `removeSink` of a video tile | The PeerConnection already released the track | `runCatching` in `VideoRenderer`; UI notified before `dispose` |
| Taps on a video tile ignored | The native `SurfaceView` receives the touches | Transparent clickable Compose layer above the renderer |
